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
  locate() {
    if (this.data.locating) return;
    this.setData({ locating: true, locateHint: '' });
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
        this.setData({ locating: false });
        const denied = err && /auth|deny|denied/i.test(err.errMsg || '');
        this.setData({
          locateHint: denied
            ? '定位权限被拒绝，可在右上角「…」→ 设置里开启，或手动选择省市区'
            : '定位失败（可能未开启定位服务），请手动选择省市区',
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
    wx.chooseLocation({
      success: (res) => {
        if (!res || !res.latitude) {
          this.setData({ locateHint: '没有取到位置，请重试或手动选择省市区' });
          return;
        }
        this.setData({ locating: true, locateHint: '' });
        // 选点返回的是 GCJ-02（微信地图与高德同坐标系），并带上用户看到的地点名
        const picked = (res.name || '').trim();
        this.regeoViaBackend(res.longitude, res.latitude, picked || null);
      },
      fail: (err) => {
        // 用户主动取消不算错误
        if (err && /cancel/i.test(err.errMsg || '')) return;
        this.setData({ locateHint: '地图选点打不开，请检查定位权限，或手动选择省市区' });
      },
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
