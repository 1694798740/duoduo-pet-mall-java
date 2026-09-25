// 地址簿：列表 / 新增 / 编辑 / 设为默认 / 删除
//
// 为什么要有这一页：此前小程序内没有地址管理，结算时只能提示"请先去 Web 端添加地址"——
// 对只用小程序的新用户来说，等于**无法完成下单**。后端地址接口（A8）早已齐备，
// 这里补上 UI 即可闭环。
//
// 技术选型与约束（重要）：
//  * **不做省/市/区级联选择器**：省市区三级数据包约 900KB+，主包当前已含 352KB 图片，
//    2MB 上限下再塞数据包会挤爆包体 → 改为「省/市/区 三个文本输入 + 常用标签」，
//    长度与格式在本地做**与后端一致的校验**（@Size(30) / 手机号正则 / 详细地址 ≤200）。
//  * 字段与后端 AddressUpsertRequest 一一对应，不新增任何端点。
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
    list: [],
    loading: false,
    error: '',
    // mode: list（列表）| form（新增/编辑表单）
    mode: 'list',
    editingId: '',
    form: emptyForm(),
    saving: false,
    labels: LABELS,
  },

  onLoad() {
    this.fetchList();
  },

  fetchList() {
    this.setData({ loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.address.list())
      .then((list) => this.setData({ list: list || [], loading: false }))
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ loading: false, error: '登录后管理收货地址' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  // ── 表单 ─────────────────────────────────────────────────
  openCreate() {
    this.setData({ mode: 'form', editingId: '', form: emptyForm() });
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
    });
  },

  closeForm() {
    this.setData({ mode: 'list', editingId: '', form: emptyForm() });
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
    if (!f.province.trim()) return '请填写省份';
    if (!f.city.trim()) return '请填写城市';
    if (!f.district.trim()) return '请填写区 / 县';
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
