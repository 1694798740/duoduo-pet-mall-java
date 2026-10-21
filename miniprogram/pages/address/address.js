// 地址簿：列表 / 新增 / 编辑 / 设为默认 / 删除
//
// 为什么要有这一页：此前小程序内没有地址管理，结算时只能提示"请先去 Web 端添加地址"——
// 对只用小程序的新用户来说，等于**无法完成下单**。后端地址接口（A8）早已齐备，
// 这里补上 UI 即可闭环。
//
// 技术选型与约束（重要）：
//  * **省/市/区改用微信原生 `picker mode="region"`**（A1，2026-10-20）：
//    此前担心"三级数据包 900KB 会挤爆 2MB 主包"而改成了三个文本输入框 ——
//    原生 region 选择器**由微信客户端自带数据**，一个字节都不用打进包体，
//    还顺带解决了"广东 / 广东省"这类写法不一致、以及直辖市重复的问题。
//    （同一轮里网页端用的是内置 110KB 数据 + 三个联动下拉，两端选出来的名称一致。）
//  * **定位回填走后端代理**：`wx.getLocation` 只拿经纬度，省市区由 `/api/geo/regeo`
//    解析（地图 Key 在服务端，小程序不必为地图服务商单独申请 request 合法域名）。
//    定位失败/被拒绝时**不阻断**，用户仍可用下拉手选。
//  * 字段与后端 AddressUpsertRequest 一一对应，不新增任何地址端点。
const api = require('../../utils/api');
const amapConfig = require('../../config/amap');
const {
  classifyLocationError,
  describeLocationError,
  canOpenSetting,
  ensureLocationAuth,
  openLocationSetting,
} = require('../../utils/location');

/**
 * 高德小程序 SDK 懒初始化。
 *
 * ★ 只在 Key 有值时才 require SDK 并建实例：
 *   · 没配 Key（例如别人 clone 后没跑生成脚本）→ 直接走后端代理，不报错；
 *   · SDK 加载失败（文件缺失/被裁剪）→ 同样回退后端，不让定位整条挂掉。
 */
let amapClient = null;
let amapBroken = false;
function getAmapClient() {
  if (amapBroken) return null;
  const key = (amapConfig && amapConfig.key) || '';
  if (!key) return null;
  if (amapClient) return amapClient;
  try {
    const AMapWX = require('../../libs/amap-wx').AMapWX;
    amapClient = new AMapWX({ key });
    return amapClient;
  } catch (e) {
    amapBroken = true;
    return null;
  }
}

const PHONE_REGEX = /^1[3-9]\d{9}$/;
const LABELS = ['家', '公司', '学校', '父母家'];

function emptyForm() {
  return {
    receiverName: '',
    receiverPhone: '',
    province: '',
    city: '',
    district: '',
    detail: '',
    label: '',
  };
}

Page({
  data: {
    // 选择模式：从结算页（购物车）以 ?pick=1 进入，选中地址后回传并返回
    pickMode: false,
    list: [],
    loading: false,
    error: '',
    // mode: list（列表）| form（新增/编辑表单）
    mode: 'list',
    editingId: '',
    form: emptyForm(),
    saving: false,
    labels: LABELS,
    /** 原生省市区选择器的值：[省, 市, 区]；与 form 的三个字段保持同步 */
    region: [],
    locating: false,
    locateHint: '',
    /** 周边候选位置（逆地理 extensions=all 带回，按距离升序） */
    candidates: [],
  },

  onLoad(query) {
    this.setData({ pickMode: !!(query && query.pick === '1') });
    this.fetchList();
  },

  /**
   * 登录页成功后 navigateBack 回到本页：onShow 触发但 onLoad 不会。
   * 只在「上次因未登录失败」时重拉，避免每次 onShow 都多发一次请求。
   */
  onShow() {
    if (this.data.needLogin && api.getToken()) {
      this.fetchList();
    }
  },

  goLogin() {
    api.openLoginPage();
  },

  /**
   * 选择模式：把地址回传给上一页（购物车），用于「本次结算临时换地址」。
   *
   * <p>用 navigateTo 的 eventChannel 回传而不是全局变量：生命周期清清楚楚，
   * 返回时不会留下"下一次结算还沿用上一次选的地址"这种幽灵状态。
   */
  pickAddress(e) {
    if (!this.data.pickMode) return;
    const item = this.data.list[Number(e.currentTarget.dataset.index)];
    if (!item) return;
    const channel = this.getOpenerEventChannel && this.getOpenerEventChannel();
    if (channel && channel.emit) {
      channel.emit('pickAddress', item);
    }
    wx.navigateBack();
  },

  fetchList() {
    this.setData({ loading: true, error: '', needLogin: false });
    return api
      .ensureSession()
      .then(() => api.address.list())
      .then((list) => this.setData({ list: list || [], loading: false }))
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ loading: false, error: '登录后管理收货地址', needLogin: true });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败', needLogin: false });
      });
  },

  // ── 表单 ─────────────────────────────────────────────────
  openCreate() {
    this.setData({ mode: 'form', editingId: '', form: emptyForm(), region: [], locateHint: '' });
  },

  openEdit(e) {
    const item = this.data.list[Number(e.currentTarget.dataset.index)];
    if (!item) return;
    this.setData({
      mode: 'form',
      editingId: item.id,
      form: {
        receiverName: item.receiverName || '',
        receiverPhone: item.receiverPhone || '',
        province: item.province || '',
        city: item.city || '',
        district: item.district || '',
        detail: item.detail || '',
        label: item.label || '',
      },
      // 历史地址可能只有省市区文本（没有被选择器规范化过），照样回显到选择器上
      region: [item.province || '', item.city || '', item.district || ''],
      locateHint: '',
    });
  },

  closeForm() {
    this.setData({ mode: 'list', editingId: '', form: emptyForm(), region: [], locateHint: '' });
  },

  /** 省市区选择器（A1）：一次选完三级，同时清掉可能残留的定位提示 */
  onRegionChange(e) {
    const value = e.detail.value || [];
    this.setData({
      region: value,
      'form.province': value[0] || '',
      'form.city': value[1] || '',
      'form.district': value[2] || '',
      locateHint: '',
    });
  },

  /**
   * 定位并回填（A1）。
   *
   * 两条通道，各用各的 Key：
   *   ① **小程序 SDK + 小程序 Key**（优先）：SDK 会带平台标识参数，才能通过
   *      高德对「微信小程序」类型 Key 的平台校验；同时也顺带拿到街道门牌。
   *   ② **后端代理 + Web 服务 Key**（兜底）：SDK 不可用、或小程序后台没配
   *      `restapi.amap.com` 合法域名时走这条 —— 功能不因此中断。
   *
   * 失败路径一律给"可继续填表"的出口：拒绝授权、定位超时、Key 未配置、解析失败。
   */
  /**
   * 定位当前地址。
   *
   * 先做**授权预检**再调接口：未授权时 wx.getLocation 只会返回一句模糊的 errMsg，
   * 用户根本不知道该点哪里；预检能把"从没问过 / 曾拒绝 / 系统关掉"分开处理。
   */
  locate() {
    if (this.data.locating) return;
    this.setData({ locating: true, locateHint: '' });
    ensureLocationAuth()
      .then(() => this.doLocate())
      .catch((err) => {
        const code = (err && err.code) || classifyLocationError(err && err.errMsg);
        this.onLocateBlocked(code, '定位', () => this.locate());
      });
  },

  /** 真正调用 wx.getLocation（此时授权已就绪） */
  doLocate() {
    wx.getLocation({
      type: 'gcj02',
      success: (pos) => {
        const lng = pos.longitude;
        const lat = pos.latitude;
        const client = getAmapClient();
        if (!client) {
          this.regeoViaBackend(lng, lat);
          return;
        }
        client.getRegeo({
          location: lng + ',' + lat,
          success: (data) => {
            // ★ SDK 1.2.0 的返回结构是 [{ name, regeocodeData }]：
            //   name 是拼好的整段地址，结构化字段在 regeocodeData 里（不是 regeocode）。
            //   另注：SDK 内部把 province 写成了 provice（官方历史拼写错误），
            //   所以我们自己从 addressComponent.province 取，不依赖它的拼串。
            const item = (data && data[0]) || {};
            const ac = (item.regeocodeData && item.regeocodeData.addressComponent) || {};
            const sn = ac.streetNumber || {};
            const roads = (item.regeocodeData && item.regeocodeData.roads) || [];
            const detail =
              ((sn.street || '') + (sn.number || '')).trim()
              || (roads[0] && roads[0].name ? roads[0].name + '附近' : '')
              || '';
            this.applyGeo({
              configured: true,
              province: ac.province || '',
              city: ac.city || '',
              district: ac.district || '',
              detail,
            });
          },
          // SDK 失败（域名未加白名单 / Key 类型不对 / 额度用尽）→ 静默回落后端代理
          fail: () => this.regeoViaBackend(lng, lat),
        });
      },
      fail: (err) => {
        // 这里是"接口调得动但失败"（如系统定位关闭、超时）；
        // 声明缺失/未授权在 ensureLocationAuth 阶段就被拦下了。
        this.onLocateBlocked(classifyLocationError(err && err.errMsg), '定位', () => this.locate());
      },
    });
  },

  /**
   * 定位/地图选点失败的**统一出口**。
   *
   * 三件事一起做：
   *   ① 给出**准确**原因（区分"接口没声明"与"没授权"）——
   *      这正是此前"明明是配置问题却提示去开手机定位"的修复点；
   *   ② 只有用户自己能解决的原因（拒绝授权 / 系统关闭）才弹「去设置」，
   *      其余不弹无意义弹窗；
   *   ③ 无论哪种失败，都把页面滚到下方省市区选择 —— 那是永远可用的兜底，
   *      并且文案明确写"可直接用下方选择，不影响下单"。
   */
  onLocateBlocked(code, action, retry) {
    this.setData({ locating: false, locateHint: describeLocationError(code, action) });
    try {
      // 把用户视线带到兜底控件（picker 无法用代码聚焦，滚动是可行且不打扰的方式）
      wx.pageScrollTo({ selector: '.addr-region-field', offsetTop: -60, duration: 200 });
    } catch (e) {
      /* 低版本基础库不支持 selector，忽略即可：文案已经指明要去哪选 */
    }
    if (!canOpenSetting(code)) return;
    wx.showModal({
      title: `${action}不可用`,
      content: describeLocationError(code, action),
      confirmText: '去设置',
      cancelText: '手动选择',
      success: (res) => {
        if (!res.confirm) return;
        openLocationSetting().then((granted) => {
          if (granted && typeof retry === 'function') {
            retry(); // 用户回来已授权：自动重试一次，不用再点一遍
          } else if (!granted) {
            this.setData({ locateHint: '仍未获得定位权限。可直接用下方「省 / 市 / 区」选择，不影响下单。' });
          }
        });
      },
    });
  },


  /**
   * 在地图上选点（A1 增强，2026-10-20）。
   *
   * 这就是「外卖式」体验：`wx.chooseLocation` 打开微信原生地图，用户可以
   * **拖动地标**、搜索、缩放，确认后返回精确的经纬度与地点名。
   * ★ 它是微信原生能力，**不需要高德 Key**，也不占高德配额。
   */
  chooseOnMap() {
    ensureLocationAuth()
      .then(() => {
        wx.chooseLocation({
          success: (res) => {
            if (!res || !res.latitude) {
              this.setData({ locateHint: '没有取到位置，请重试或直接用下方「省 / 市 / 区」选择。' });
              return;
            }
            this.setData({ locating: true, locateHint: '' });
            // 选点返回的是 GCJ-02（微信地图与高德同坐标系），并带上用户看到的地点名
            const picked = (res.name || '').trim();
            this.regeoViaBackend(res.longitude, res.latitude, picked || null);
          },
          fail: (err) => {
            const code = classifyLocationError(err && err.errMsg);
            // 用户主动取消不算错误，静默即可
            if (code === 'CANCELLED') return;
            this.onLocateBlocked(code, '地图选点', () => this.chooseOnMap());
          },
        });
      })
      .catch((err) => {
        const code = (err && err.code) || classifyLocationError(err && err.errMsg);
        this.onLocateBlocked(code, '地图选点', () => this.chooseOnMap());
      });
  },


  /**
   * 附近位置快选（wx.choosePoi，2026-10-20 新增的第三个入口）。
   *
   * 为什么加它：自动定位有偏差、地图选点要拖地标，而很多场景用户只是想
   * "选旁边那个小区/写字楼/商圈"。wx.choosePoi 打开微信原生的「所在位置」
   * 列表（首项为所在城市，其余为附近 POI），点一下就带入名称与详细地址，
   * 是三个入口里最快的路径。
   *
   * ★ 它是**并列入口，不替代**地图选点：POI 列表只能选到"已存在的地标"，
   *   精确到门牌号仍然要 chooseLocation 拖地标。所以三个入口并存：
   *   定位当前地址 / 地图选点 / 附近位置。
   * ★ 回调两种形态，必须分开处理：
   *   type=2 选了精确位置 → 经纬度有效（gcj02），走与地图选点同一条逆地理通道；
   *   type=1 只选了"城市"（模糊定位）→ **经纬度不可用**，只填市，
   *   省与区交给用户手动选，否则会把表单写成空省空区。
   */
  chooseNearbyPoi() {
    ensureLocationAuth()
      .then(() => {
        wx.choosePoi({
          success: (res) => {
            const lng = res && res.longitude;
            const lat = res && res.latitude;
            if (res && res.type === 2 && lng && lat) {
              this.setData({ locating: true, locateHint: '' });
              this.regeoViaBackend(lng, lat, (res.address || res.name || '').trim() || null);
              return;
            }
            // type=1（或坐标缺失）：只选到城市，不能拿坐标反查省市区
            const city = (res && res.city) || '';
            this.setData({
              locating: false,
              region: ['', city, ''],
              'form.province': '',
              'form.city': city,
              'form.district': '',
              'form.detail': (res && res.address) || this.data.form.detail,
              locateHint: city
                ? '已选到「' + city + '」，省与区请手动选择；需要更精确请用「地图选点」'
                : '没有取到位置，请重试或用下方「省 / 市 / 区」选择',
            });
          },
          fail: (err) => {
            const code = classifyLocationError(err && err.errMsg);
            // 用户主动取消不算错误，静默即可
            if (code === 'CANCELLED') return;
            this.onLocateBlocked(code, '附近位置', () => this.chooseNearbyPoi());
          },
        });
      })
      .catch((err) => {
        const code = (err && err.code) || classifyLocationError(err && err.errMsg);
        this.onLocateBlocked(code, '附近位置', () => this.chooseNearbyPoi());
      });
  },


  /** 兜底通道：后端用「Web 服务」Key 调高德（小程序 Key 不能用于这条） */
  regeoViaBackend(lng, lat, overrideDetail) {
    api.address
      .regeo(lng, lat, 'gcj02')
      .then((geo) => {
        if (!geo || geo.configured === false) {
          this.setData({ locating: false, locateHint: '地图服务尚未配置，请手动选择省市区' });
          return;
        }
        this.applyGeo(geo, overrideDetail);
      })
      .catch((err) => {
        this.setData({
          locating: false,
          locateHint: (err && err.message) || '定位服务调用失败，请手动选择省市区',
        });
      });
  },

  /** 把归一化后的省市区写回表单（三条通道共用，保证行为一致） */
  applyGeo(geo, overrideDetail) {
    const region = [geo.province || '', geo.city || geo.province || '', geo.district || ''];
    // 地图选点时，用户看到并确认过的地点名优先于逆地理推出来的街道门牌
    const detail = (overrideDetail || '').trim() || geo.detail || this.data.form.detail;
    this.setData({
      locating: false,
      region,
      candidates: (geo.candidates || []).slice(0, 8),
      'form.province': region[0],
      'form.city': region[1],
      'form.district': region[2],
      'form.detail': detail,
      locateHint: region[0]
        ? (overrideDetail
            ? '已按地图选点填入「' + region.filter(Boolean).join(' / ') + '」，请核对门牌号'
            : '已按定位填入「' + region.filter(Boolean).join(' / ') + '」，请核对门牌号')
        : '已定位，但没有匹配到可选的省市区，请手动选择',
    });
  },

  /** 选中周边候选（与网页端同一套逻辑，避免两端行为不一致） */
  pickCandidate(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const cd = (this.data.candidates || [])[idx];
    if (!cd) return;
    this.setData({
      'form.detail': cd.name,
      locateHint: '已选「' + cd.name + '」' + (cd.distance != null ? '（距定位点 ' + cd.distance + ' 米）' : '') + '，可再补充门牌号',
    });
  },

  // ── 表单输入 ─────────────────────────────────────────────
  //
  // ★ 2026-10-21 补回：这三个方法（含 validate）在做「坐标系修复」那次改动时被
  //   整段吞掉了 —— 替换函数时花括号配对把文件尾部一起吃进匹配范围。
  //   症状不是报错，而是「点保存没反应、输入框打不进字」，静态自检才抓出来。
  //   教训：改 JS 用括号配对替换后，必须回头确认「文件末尾的最后一个方法还在」。
  onInput(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    this.setData({ ['form.' + field]: e.detail.value });
  },

  pickLabel(e) {
    const label = e.currentTarget.dataset.label;
    // 再点一次可取消标签
    this.setData({ 'form.label': this.data.form.label === label ? '' : label });
  },

  /** 本地校验与后端约束保持一致，避免"本地过了、后端 400" */
  validate() {
    const f = this.data.form;
    if (!f.receiverName.trim()) return '请填写收货人';
    if (f.receiverName.trim().length > 50) return '收货人最多 50 个字符';
    if (!PHONE_REGEX.test(f.receiverPhone.trim())) return '请填写正确的 11 位手机号';
    if (!f.province.trim()) return '请选择省份';
    if (!f.city.trim()) return '请选择城市';
    if (!f.district.trim()) return '请选择区 / 县';
    if (f.detail.trim().length > 200) return '详细地址最多 200 个字符';
    if (f.label && f.label.length > 20) return '标签最多 20 个字符';
    return '';
  },

  save() {
    if (this.data.saving) return;
    const msg = this.validate();
    if (msg) {
      wx.showToast({ title: msg, icon: 'none' });
      return;
    }
    const f = this.data.form;
    const payload = {
      receiverName: f.receiverName.trim(),
      receiverPhone: f.receiverPhone.trim(),
      province: f.province.trim(),
      city: f.city.trim(),
      district: f.district.trim(),
      detail: f.detail.trim(),
      label: f.label || undefined,
    };
    this.setData({ saving: true });
    const action = this.data.editingId
      ? api.address.update(this.data.editingId, payload)
      : api.address.create(payload);
    action
      .then(() => {
        this.setData({ saving: false });
        wx.showToast({ title: this.data.editingId ? '已保存' : '已新增', icon: 'success' });
        this.closeForm();
        this.fetchList();
      })
      .catch((err) => {
        this.setData({ saving: false });
        wx.showToast({ title: (err && err.message) || '保存失败', icon: 'none' });
      });
  },

  // ── 列表操作 ─────────────────────────────────────────────
  setDefault(e) {
    const id = e.currentTarget.dataset.id;
    api.address
      .setDefault(id)
      .then(() => {
        wx.showToast({ title: '已设为默认', icon: 'success' });
        this.fetchList();
      })
      .catch(api.toastError);
  },

  remove(e) {
    const id = e.currentTarget.dataset.id;
    wx.showModal({
      title: '删除地址',
      content: '确定删除这个收货地址吗？',
      confirmColor: '#e5484d',
      success: (res) => {
        if (!res.confirm) return;
        api.address
          .remove(id)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' });
            this.fetchList();
          })
          .catch(api.toastError);
      },
    });
  },
});
