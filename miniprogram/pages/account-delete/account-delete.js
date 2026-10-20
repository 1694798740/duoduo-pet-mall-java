// 账号注销（C7）
//
// 为什么单独一个页面而不是弹窗：注销要展示的信息太多（阻塞项清单、冷静期含义、
// 数据保留说明、密码确认、撤销方式），塞进弹窗会变成没人读的一坨文字。
//
// 三条铁律（与后端 AccountDeletionService 的状态机严格对应）：
//   ① 申请前**必须先预检**，把阻塞项摆出来 —— 用户不该输完密码才被告知不行；
//   ② 冷静期内登录被停用，所以撤销用「手机号 + 密码」，不依赖登录态；
//   ③ 提交成功后**立刻清本地会话**并明确告知"所有端已登出"，避免用户以为还能用。
const api = require('../../utils/api.js');

const TYPE_LABEL = {
  ORDER: '未完成的订单',
  AFTER_SALE: '进行中的售后',
  MERCHANT_OWNER: '店铺主体',
  MERCHANT_MEMBER: '店铺成员身份',
  LISTING: '在售的闲置',
};

Page({
  data: {
    loading: true,
    blocked: false,
    items: [],
    // 冷静期状态（若账号已在冷静期，页面自动切成"撤销"形态）
    cancelling: false,
    scheduledAtText: '',
    graceDays: 7,
    password: '',
    cancelPassword: '',
    phone: '',
    reason: '',
    submitting: false,
    // 二次确认：注销不可逆，不能一次点击就提交
    confirmed: false,
  },

  onLoad() {
    this.refresh();
  },

  /** 拉取预检结果 + 当前注销状态（同一个端点，后端按 account_status 返回） */
  refresh() {
    this.setData({ loading: true });
    api.accountDeletion
      .precheck()
      .then((view) => {
        const v = view || {};
        const items = (v.items || v.blockedItems || []).map((it) => ({
          type: it.type,
          label: TYPE_LABEL[it.type] || '待处理事项',
          ref: it.ref || '',
          detail: it.detail || '',
        }));
        const cancelling = v.accountStatus === 'CANCELLING';
        this.setData({
          loading: false,
          blocked: !!v.blocked,
          items,
          cancelling,
          scheduledAtText: v.scheduledAt ? formatTime(v.scheduledAt) : '',
          graceDays: v.graceDays || 7,
        });
        if (cancelling) {
          wx.setNavigationBarTitle({ title: '撤销账号注销' });
        }
      })
      .catch((err) => {
        this.setData({ loading: false });
        api.toastError(err);
      });
  },

  onPasswordInput(e) {
    this.setData({ password: e.detail.value });
  },
  onCancelPasswordInput(e) {
    this.setData({ cancelPassword: e.detail.value });
  },
  onPhoneInput(e) {
    this.setData({ phone: e.detail.value });
  },
  onReasonInput(e) {
    this.setData({ reason: e.detail.value });
  },
  toggleConfirm() {
    this.setData({ confirmed: !this.data.confirmed });
  },

  /** 提交注销申请 */
  submit() {
    const { password, reason, confirmed, submitting, blocked } = this.data;
    if (submitting) return;
    if (blocked) {
      wx.showToast({ title: '请先处理上面的待办事项', icon: 'none' });
      return;
    }
    if (!password) {
      wx.showToast({ title: '请输入登录密码', icon: 'none' });
      return;
    }
    if (!confirmed) {
      wx.showToast({ title: '请先勾选「我已了解后果」', icon: 'none' });
      return;
    }
    const doSubmit = () => {
      this.setData({ submitting: true });
      api.accountDeletion
        .request(password, reason)
        .then((view) => {
          this.setData({ submitting: false });
          // 后端已吊销全部 refresh 令牌，本地会话此刻起也无效 —— 直接清掉，
          // 否则用户还以为自己登录着，点任何需要登录的功能都会报"未登录"。
          api.clearSession();
          const at = (view && view.scheduledAt) ? formatTime(view.scheduledAt) : '';
          wx.showModal({
            title: '注销申请已提交',
            content: '账号已进入 ' + (view && view.graceDays ? view.graceDays : 7)
              + ' 天冷静期，期间无法登录。' + (at ? '将于 ' + at + ' 执行。' : '')
              + '想反悔的话，在本页用「手机号 + 密码」撤销即可。',
            showCancel: false,
            confirmText: '我知道了',
            success: () => this.refresh(),
          });
        })
        .catch((err) => {
          this.setData({ submitting: false });
          // 409 ACCOUNT_DELETE_BLOCKED：带上阻塞清单刷新页面，让用户看到具体卡在哪
          api.toastError(err);
          this.refresh();
        });
    };

    wx.showModal({
      title: '确认注销账号？',
      content: '注销后手机号、昵称、头像、地址、宠物档案将被清除且无法恢复；'
        + '订单等交易记录按法规留存。冷静期内可以撤销。',
      confirmText: '确认注销',
      confirmColor: '#d93025',
      cancelText: '再想想',
      success: (res) => {
        if (res.confirm) doSubmit();
      },
    });
  },

  /** 撤销注销（无需登录态：手机号 + 密码） */
  cancel() {
    const { phone, cancelPassword: password, submitting } = this.data;
    if (submitting) return;
    if (!/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '请输入 11 位手机号', icon: 'none' });
      return;
    }
    if (!password) {
      wx.showToast({ title: '请输入登录密码', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    api.accountDeletion
      .cancelByPhone(phone, password)
      .then(() => {
        this.setData({ submitting: false, cancelPassword: '' });
        wx.showModal({
          title: '已撤销',
          content: '账号已恢复正常，可以重新登录了。',
          showCancel: false,
          success: () => this.refresh(),
        });
      })
      .catch((err) => {
        this.setData({ submitting: false });
        api.toastError(err);
      });
  },

  goBack() {
    wx.navigateBack();
  },
});

/** 后端返回 ISO-8601 字符串，小程序里 new Date(str) 在 iOS 上要替换成斜杠才稳 */
function formatTime(iso) {
  if (!iso) return '';
  const d = new Date(String(iso).replace(/-/g, '/').replace('T', ' ').slice(0, 19));
  if (isNaN(d.getTime())) return String(iso);
  const pad = (n) => (n < 10 ? '0' + n : '' + n);
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}
