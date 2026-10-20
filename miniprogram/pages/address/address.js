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
   * 失败路径必须都能继续填表：
   *  · 用户拒绝授权 → 引导去设置页开权限，或直接用下拉；
   *  · 服务端未配置地图 Key → 明确告知「尚未配置」，不要说成定位坏了；
   *  · 省市区没匹配上 → 只回填详细地址，其余仍由用户选。
   */
  locate() {
    if (this.data.locating) return;
    this.setData({ locating: true, locateHint: '' });
    wx.getLocation({
      type: 'gcj02',
      success: (pos) => {
        api.address
          .regeo(pos.longitude, pos.latitude)
          .then((geo) => {
            this.setData({ locating: false });
            if (!geo || geo.configured === false) {
              this.setData({ locateHint: '地图服务尚未配置，请手动选择省市区' });
              return;
            }
            const region = [geo.province || '', geo.city || geo.province || '', geo.district || ''];
            this.setData({
              region,
              'form.province': region[0],
              'form.city': region[1],
              'form.district': region[2],
              'form.detail': geo.detail || this.data.form.detail,
              locateHint: region[0]
                ? '已按定位填入「' + region.filter(Boolean).join(' / ') + '」，请核对门牌号'
                : '已定位，但没有匹配到可选的省市区，请手动选择',
            });
          })
          .catch((err) => {
            this.setData({
              locating: false,
              locateHint: (err && err.message) || '定位服务调用失败，请手动选择省市区',
            });
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
