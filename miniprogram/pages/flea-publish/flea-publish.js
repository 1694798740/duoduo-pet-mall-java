// 发布闲置（第五十二轮 · C2C 发布链路的写侧）
//
// 三条设计取舍：
//  1. **原生导航栏**（非 tab 页）：自绘栏没有返回键，推进式打开的页面会被困住（R41 推论）。
//  2. **价格以「元」输入、以「分」提交**：后端金额一律整数分。在界面上让用户填「分」
//     既反直觉又易错，因此在这里做一次显式换算 —— 换算是本页唯一的金额逻辑，
//     并做了「最多两位小数 / 不接受非法字符」的校验，不用 parseFloat 静默容忍脏输入。
//  3. **发布后不停留在本页**：直接 redirect 到「我的闲置」并提示「已提交，等待审核」。
//     停在表单上会让用户以为没成功（新发布是 PENDING，公开列表里查不到），
//     而「我的闲置」里能看到状态，语义自洽。
const api = require('../../utils/api');

const CONDITIONS = [
  { code: 'NEW', label: '全新' },
  { code: 'LIKE_NEW', label: '九成新' },
  { code: 'GOOD', label: '良好' },
  { code: 'FAIR', label: '可用' },
];

const SPECIES = [
  { code: 'cat', label: '猫' },
  { code: 'dog', label: '狗' },
  { code: '', label: '其他' },
];

/** 元 → 分。只接受最多两位小数的正数；非法输入返回 null（由调用方给出明确提示） */
function yuanToFen(text) {
  const s = String(text === null || text === undefined ? '' : text).trim();
  if (!s) return null;
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  const parts = s.split('.');
  const yuan = Number(parts[0]);
  const cents = parts.length > 1 ? Number((parts[1] + '00').slice(0, 2)) : 0;
  const fen = yuan * 100 + cents;
  if (!Number.isFinite(fen) || fen <= 0) return null;
  return fen;
}

Page({
  data: {
    title: '',
    description: '',
    priceYuan: '',
    originalYuan: '',
    conditionLevel: 'GOOD',
    city: '',
    species: 'cat',
    breed: '',

    conditions: CONDITIONS,
    speciesList: SPECIES,

    submitting: false,
    error: '',

    // 实名预检状态：loading（查询中）/ ok（可发布）/ needed（需先实名）。
    // 查询失败时也按 needed 处理并显示重试 —— 宁可多拦一道，不让用户白填表单。
    realNameState: 'loading',
  },

  onLoad() {
    // 发布需登录 + 需实名，两道都提前拦：填完一长串表单再被 403 是最差的体验
    if (!api.getToken()) {
      api.openLoginPage();
      wx.navigateBack();
      return;
    }
    this.checkRealName();
  },

  onShow() {
    // 从实名页返回时重新检查（实名成功会静默换新令牌，这里复查一次即解锁表单）
    if (api.getToken() && this.data.realNameState !== 'ok') {
      this.checkRealName();
    }
  },

  /** 查询实名状态：GET /api/users/me 的 realNameVerified（与后端 publish 的判定同源） */
  checkRealName() {
    this.setData({ realNameState: 'loading' });
    api
      .ensureSession()
      .then((user) => {
        this.setData({ realNameState: user && user.realNameVerified ? 'ok' : 'needed' });
      })
      .catch((e) => {
        if (e && e.needLogin) {
          api.openLoginPage();
          wx.navigateBack();
          return;
        }
        this.setData({ realNameState: 'needed', error: (e && e.message) || '实名状态获取失败' });
      });
  },

  goRealname() {
    wx.navigateTo({ url: '/pages/realname/realname' });
  },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value, error: '' });
  },

  onPickCondition(e) {
    this.setData({ conditionLevel: e.currentTarget.dataset.code });
  },

  onPickSpecies(e) {
    this.setData({ species: e.currentTarget.dataset.code });
  },

  validate() {
    const title = this.data.title.trim();
    if (!title) return '请填写标题';
    if (title.length > 200) return '标题不能超过 200 字';

    const price = yuanToFen(this.data.priceYuan);
    if (price === null) return '请填写正确的价格（最多两位小数）';

    let originalPrice = 0;
    if (this.data.originalYuan.trim()) {
      const op = yuanToFen(this.data.originalYuan);
      if (op === null) return '原价格式不正确（最多两位小数）';
      originalPrice = op;
    }

    if (this.data.description.length > 2000) return '描述不能超过 2000 字';
    if (this.data.city.length > 50) return '城市不能超过 50 字';
    if (this.data.breed.length > 50) return '品种不能超过 50 字';

    return null;
  },

  onSubmit() {
    if (this.data.submitting) return;
    // 兜底闸：预检未通过不允许提交（正常情况下表单已被 wx:if 隐藏，到不了这里）
    if (this.data.realNameState !== 'ok') {
      this.checkRealName();
      return;
    }

    const err = this.validate();
    if (err) {
      this.setData({ error: err });
      return;
    }

    const price = yuanToFen(this.data.priceYuan);
    const originalPrice = this.data.originalYuan.trim()
      ? yuanToFen(this.data.originalYuan)
      : 0;

    this.setData({ submitting: true, error: '' });
    api.listing
      .publish({
        title: this.data.title.trim(),
        description: this.data.description.trim(),
        price,
        originalPrice,
        conditionLevel: this.data.conditionLevel,
        city: this.data.city.trim(),
        species: this.data.species,
        breed: this.data.breed.trim(),
        images: [],
      })
      .then(() => {
        this.setData({ submitting: false });
        // 新发布是 PENDING：公开列表看不到，停在本页会让用户以为失败。
        // 因此直接送去「我的闲置」——那里能看到状态与审核进度。
        wx.showToast({ title: '已提交，等待审核', icon: 'none', duration: 1800 });
        setTimeout(() => {
          wx.redirectTo({ url: '/pages/flea-mine/flea-mine' });
        }, 900);
      })
      .catch((e) => {
        this.setData({
          submitting: false,
          error: (e && e.message) || '发布失败，请稍后重试',
        });
      });
  },

  onCancel() {
    wx.navigateBack();
  },
});
