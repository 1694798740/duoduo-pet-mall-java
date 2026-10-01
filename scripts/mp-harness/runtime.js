/**
 * 小程序「无浏览器」运行时（Node 侧）
 *
 * 为什么需要它：小程序根本没有 DOM —— 它跑在微信的逻辑层，界面由 WebView 渲染，
 * 逻辑层只能通过 setData 把数据推过去。所以「给小程序做自动化测试」既不该、
 * 也不需要真实浏览器：真正要驱动的是 **Page 实例的生命周期与 setData 结果**。
 *
 * 以前这一层只能靠静态检查（`check-miniprogram.js`）和把页面代码复制进单测，
 * 前者看不到运行时行为，后者会和真实页面代码逐渐分叉（改了页面、测试还绿）。
 * 这个运行时直接**加载仓库里真实的页面 js**，因此测的就是要上线的那份代码。
 *
 * 能力：
 *   - 注入 wx.* 桩（request 走真实网关，其余记录调用）
 *   - 实现 require：解析相对路径、缓存模块，与小程序 CommonJS 一致
 *   - Page()/App()/Component() 注册，可触发 onLoad/onShow/onReady/onUnload
 *   - 捕获每次 setData，供断言「界面最终长什么样」
 *   - 事件方法可直接调用（wxml 里 bindtap="send" → page.send()）
 *
 * 用法见 scripts/mp-harness/ai-page.test.js。
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const DEFAULT_GATEWAY = process.env.GATEWAY ?? 'http://127.0.0.1:8080';

/** 把小程序路径（'../../utils/api'）解析到仓库内的 js 文件（含「目录→index.js」，Node 的规则） */
function resolveModule(baseFile, request) {
  const dir = path.dirname(baseFile);
  const candidates = [
    path.resolve(dir, request),
    path.resolve(dir, request + '.js'),
    path.resolve(dir, request, 'index.js'),
    // 绝对路径（'/utils/api'）按小程序根目录解析
    path.resolve(this.root, request.replace(/^\//, '')),
    path.resolve(this.root, request.replace(/^\//, '') + '.js'),
    path.resolve(this.root, request.replace(/^\//, ''), 'index.js'),
  ];
  for (const c of candidates) {
    if (fs.existsSync(c) && fs.statSync(c).isFile()) return c;
  }
  throw new Error(`模块解析失败：${request}（from ${baseFile}）`);
}

export function createMiniProgramRuntime(options = {}) {
  const root = path.resolve(options.root ?? 'miniprogram');
  const gateway = options.gateway ?? DEFAULT_GATEWAY;

  const state = {
    pages: new Map(),   // path -> Page config
    app: null,          // App config
    components: new Map(),
    requests: [],       // 记录所有 wx.request
    responses: [],      // 记录所有响应（排查「页面为什么当失败处理」时看这里）
    unknownWxApis: new Set(), // 页面用到了但运行时没显式实现的 wx API（供审计，不静默吞）
    stubs: [],       // 请求桩：命中即不发真实网络请求（写接口绝不能真打到库）
    toasts: [],
    navigations: [],
    storage: new Map(),
  };

  /** 让被加载的模块里也能用 require —— 关键是 baseFile 要跟着变 */
  function makeRequire(baseFile) {
    const cache = new Map();
    return (request) => {
      const file = resolveModule.call({ root }, baseFile, request);
      if (cache.has(file)) return cache.get(file);
      const moduleObj = { exports: {} };
      cache.set(file, moduleObj.exports);
      loadFile(file, moduleObj);
      cache.set(file, moduleObj.exports);
      return moduleObj.exports;
    };
  }

  function loadFile(file, moduleObj) {
    const code = fs.readFileSync(file, 'utf8');
    const sandbox = {
      module: moduleObj,
      exports: moduleObj.exports,
      require: makeRequire(file),
      console,
      setTimeout,
      clearTimeout,
      setInterval,
      clearInterval,
      Date,
      JSON,
      Math,
      Promise,
      Object,
      Array,
      String,
      Number,
      Boolean,
      Error,
      encodeURIComponent,
      decodeURIComponent,
      wx: wrapWx(makeWx()),
      getApp: () => appInstance(),
      Page: (cfg) => {
        // 用文件名反推页面路径（pages/ai/ai.js → pages/ai/ai）
        const rel = path.relative(root, file).replace(/\\/g, '/').replace(/\.js$/, '');
        state.pages.set(rel, cfg);
        moduleObj.exports = cfg;
      },
      Component: (cfg) => {
        const rel = path.relative(root, file).replace(/\\/g, '/').replace(/\.js$/, '');
        state.components.set(rel, cfg);
        moduleObj.exports = cfg;
      },
      App: (cfg) => {
        state.app = cfg;
        moduleObj.exports = cfg;
      },
      getCurrentPages: () => state.mounted.map((m) => m.instance),
    };
    state.mounted = state.mounted ?? [];
    vm.createContext(sandbox);
    vm.runInContext(code, sandbox, { filename: file });
    return moduleObj.exports;
  }

  /**
   * 注册一个请求桩：命中的请求**不发真实网络请求**。
   *
   * 为什么必须有它：行为断言要验「加购 / 下单」这类写操作，但让测试真去下单会
   * 在库里留下一堆脏订单 —— 上一次跑留下的脏数据会污染下一次的断言，而且报错的
   * 是**下一个脚本**，最难归因。所以写接口一律用桩拦住：请求照样被记录下来
   * （可以断言「请求体到底发了什么」），只是不落到库里。
   *
   * @param {string|RegExp} matcher URL 包含该子串 / 匹配该正则即命中
   * @param {object|Function} handler 直接给响应体，或 (opts) => 响应体
   */
  function stub(matcher, handler) {
    state.stubs.push({ matcher, handler });
    return () => {
      const i = state.stubs.findIndex((s) => s.matcher === matcher && s.handler === handler);
      if (i >= 0) state.stubs.splice(i, 1);
    };
  }

  function matchStub(url, method, opts) {
    for (const s of state.stubs) {
      const hit = typeof s.matcher === 'string'
        ? url.includes(s.matcher)
        : s.matcher.test(url);
      if (!hit) continue;
      // 把请求体一并交给桩：断言要看的就是「请求体到底发了什么」
      const body = typeof s.handler === 'function'
        ? s.handler({ url, method, data: opts?.data })
        : s.handler;
      return { statusCode: 200, data: { code: 'SUCCESS', message: 'OK', data: body } };
    }
    return null;
  }

  function makeWx() {
    return {
      request(opts) {
        const url = opts.url ?? '';
        const absolute = url.startsWith('http') ? url : gateway + url;
        const method = (opts.method ?? 'GET').toUpperCase();
        // GET/HEAD 不能被带 body：小程序侧 data 默认传 {}，若原样透传，
        // 标准 fetch 实现会直接抛「Request with GET/HEAD method cannot have body」，
        // 结果是所有 GET 都进 fail 分支 —— 页面里的 .catch 会被误触发，
        // 表现为「接口明明 200 且返回了数据，页面却当失败处理」。
        const hasBody = method !== 'GET' && method !== 'HEAD' && opts.data != null;
        state.requests.push({ url: absolute, method, data: opts.data });

        // 桩优先：写接口不落库，但仍然完整记录请求（行为断言要看的就是请求体）
        const stubbed = matchStub(absolute, method, opts);
        if (stubbed) {
          state.responses.push({ url: absolute, method, status: 200, data: stubbed.data, stubbed: true });
          const wrapped = { statusCode: 200, data: stubbed.data, errMsg: 'request:ok' };
          opts.success?.(wrapped);
          opts.complete?.(wrapped);
          return Promise.resolve(wrapped);
        }

        const promise = (async () => {
          try {
            const res = await fetch(absolute, {
              method,
              headers: { 'Content-Type': 'application/json', ...(opts.header ?? {}) },
              body: hasBody ? JSON.stringify(opts.data) : undefined,
            });
            const text = await res.text();
            let data = text;
            try { data = JSON.parse(text); } catch { /* 非 JSON 就用原文 */ }
            const wrapped = { statusCode: res.status, data, errMsg: 'request:ok' };
            state.responses.push({ url: absolute, method, status: res.status, data });
            if (res.ok && opts.success) opts.success(wrapped);
            if (!res.ok && opts.fail) opts.fail(wrapped);
            if (opts.complete) opts.complete(wrapped);
            return wrapped;
          } catch (e) {
            const wrapped = { statusCode: 0, data: null, errMsg: String(e) };
            if (opts.fail) opts.fail(wrapped);
            if (opts.complete) opts.complete(wrapped);
            return wrapped;
          }
        })();
        return promise;
      },
      getStorageSync(key) {
        return state.storage.has(key) ? state.storage.get(key) : '';
      },
      setStorageSync(key, value) {
        state.storage.set(key, value);
      },
      removeStorageSync(key) {
        state.storage.delete(key);
      },
      showToast(opts) {
        state.toasts.push(opts);
        if (opts?.complete) opts.complete();
      },
      hideToast() {},
      showLoading() {},
      hideLoading() {},
      navigateTo(opts) { state.navigations.push({ type: 'navigateTo', ...opts }); if (opts?.success) opts.success(); },
      redirectTo(opts) { state.navigations.push({ type: 'redirectTo', ...opts }); if (opts?.success) opts.success(); },
      switchTab(opts) { state.navigations.push({ type: 'switchTab', ...opts }); if (opts?.success) opts.success(); },
      navigateBack(opts) { state.navigations.push({ type: 'navigateBack', ...opts }); },
      login(opts) { if (opts?.fail) opts.fail({ errMsg: 'login:fail harness' }); },
      getUserProfile() {},
      getSystemInfoSync: () => ({ platform: 'devtools', statusBarHeight: 20, windowWidth: 375, pixelRatio: 2 }),
      getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375, pixelRatio: 2 }),
      getMenuButtonBoundingClientRect: () => ({ top: 24, height: 32, width: 87, right: 368, bottom: 56, left: 281 }),
      createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec: () => {} }) }), exec: () => {} }),
      setNavigationBarTitle() {},
      stopPullDownRefresh() {},
      onKeyboardHeightChange() {},
      offKeyboardHeightChange() {},
      createIntersectionObserver: () => ({ relativeToViewport: () => ({ observe: () => {} }), disconnect: () => {} }),
      setTabBarBadge() {},
      removeTabBarBadge() {},
      showTabBarRedDot() {},
      hideTabBarRedDot() {},
      getTabBar: () => null,
      pageScrollTo() {},
      startPullDownRefresh() {},
      nextTick(cb) { if (typeof cb === 'function') cb(); },
      vibrateShort() {},
      showModal(opts) { if (opts?.fail) opts.fail({ errMsg: 'cancel' }); else if (opts?.success) opts.success({ confirm: false, cancel: true }); },
      showActionSheet() {},
      previewImage() {},
      setClipboardData(opts) { if (opts?.success) opts.success(); },
      getNetworkType: () => ({ networkType: 'wifi' }),
      onNetworkStatusChange() {},
      getStorage: (opts) => { if (opts?.success) opts.success({ data: state.storage.get(opts.key) ?? '' }); },
    };
  }

  /**
   * wx.* 的兜底桩。
   *
   * <p>小程序 API 有几百个，逐个手写既写不完也会随基础库版本漂移。但**直接返回空函数**
   * 会把「页面调了个不存在的 API（如拼错 wx.showToat）」也一起吞掉 —— 那这个 harness
   * 就成了另一个假绿来源。所以这里：调用照常不报错，但**把用过的未知 API 记进
   * `state.unknownWxApis`**，跑完可审计（见 pages-smoke 的末尾输出）。
   *
   * @returns 一个「怎么点都不炸」的链桩；注意 `then` 必须是 undefined，
   *          否则它被当成 thenable，页面里 `await wx.xxx()` 会永久挂住。
   */
  function unknownStub(name) {
    const fn = function () { return fn; };
    return new Proxy(fn, {
      apply: () => fn,
      get(_t, prop) {
        if (prop === 'then') return undefined;
        if (prop === Symbol.toPrimitive) return () => '';
        if (prop === 'toString' || prop === 'valueOf') return () => '';
        return unknownStub(`${name}.${String(prop)}`);
      },
    });
  }

  /** 给 wx 对象套一层兜底：显式实现的走实现，没有的记一笔再给链桩 */
  function wrapWx(base) {
    return new Proxy(base, {
      get(target, prop) {
        if (prop in target) return target[prop];
        if (typeof prop === 'symbol') return undefined;
        state.unknownWxApis.add(String(prop));
        return unknownStub(`wx.${String(prop)}`);
      },
      has: () => true,
    });
  }

  function appInstance() {
    if (!state.app) return {};
    if (!state._appInstance) {
      const inst = {
        globalData: state.app.globalData ?? {},
        ...state.app,
      };
      state._appInstance = inst;
      if (typeof state.app.onLaunch === 'function') {
        // 真实小程序会带 options；测试里给空对象即可
        state.app.onLaunch.call(inst, {});
      }
    }
    return state._appInstance;
  }

  /** 加载一个页面文件（如 'pages/ai/ai'）并返回它的 Page 配置 */
  function loadPage(pagePath) {
    const rel = String(pagePath).replace(/\.js$/, '');
    if (state.pages.has(rel)) return state.pages.get(rel);
    const file = path.join(root, rel + '.js');
    if (!fs.existsSync(file)) throw new Error(`页面不存在：${file}`);
    loadFile(file, { exports: {} });
    return state.pages.get(rel);
  }

  /**
   * 挂载页面：返回一个「实例 + 断言用的 data 快照」。
   *
   * setData 会被完整记录 —— 断言要看的是「界面最终是什么」，
   * 而不是去猜页面内部有没有调 setData。
   */
  function mountPage(pagePath, options = {}) {
    const cfg = loadPage(pagePath);
    const setDataCalls = [];
    const instance = {
      data: JSON.parse(JSON.stringify(cfg.data ?? {})),
      setData(patch, cb) {
        const normalized = typeof patch === 'string' ? { [patch]: arguments[1] } : patch;
        setDataCalls.push(normalized);
        for (const [k, v] of Object.entries(normalized)) {
          // 支持 'a.b' 路径（小程序支持，且页面里确实用）
          const parts = k.split('.');
          let target = instance.data;
          for (let i = 0; i < parts.length - 1; i++) {
            target[parts[i]] = target[parts[i]] ?? {};
            target = target[parts[i]];
          }
          target[parts[parts.length - 1]] = v;
        }
        if (typeof cb === 'function') cb();
      },
      ...cfg,
    };
    // 方法里的 this 指向实例（与小程序一致）
    for (const key of Object.keys(cfg)) {
      if (typeof cfg[key] === 'function') {
        instance[key] = cfg[key].bind(instance);
      }
    }
    state.mounted = state.mounted ?? [];
    state.mounted.push({ path: pagePath, instance });
    if (typeof cfg.onLoad === 'function') instance.onLoad(options);
    if (typeof cfg.onShow === 'function') instance.onShow();
    if (typeof cfg.onReady === 'function') instance.onReady();
    return { instance, data: instance.data, setDataCalls, unload: () => { if (typeof cfg.onUnload === 'function') cfg.onUnload.call(instance); } };
  }

  state.mounted = state.mounted ?? [];

  // 预热入口：真实小程序启动时先执行 app.js，页面里的 getApp() 才拿得到 globalData。
  // 不预热的话 getApp() 返回 {}，页面一读 globalData.user 就 TypeError ——
  // 看上去像「页面有 bug」，其实是**运行时没照小程序的启动顺序来**。
  const appFile = path.join(root, 'app.js');
  if (fs.existsSync(appFile)) {
    try {
      loadFile(appFile, { exports: {} });
    } catch (e) {
      // app.js 自身启动失败不该连带把所有页面测试判成失败：实例化时再报
      state.appLoadError = e;
    }
  }

  return {
    root,
    gateway,
    state,
    loadPage,
    mountPage,
    stub,
    app: appInstance,
    /** 断言辅助：最近一次请求的 url */
    lastRequest: () => state.requests[state.requests.length - 1],
  };
}
