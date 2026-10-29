// 我的多多币（R-P1 第三轮 · 小程序对齐 Web）
// 与 Web 同一组接口：/api/coins/me、/signin/month、/tasks、/campaign/active、/signin。
const api = require('../../utils/api');

const fmt = (iso) => (iso ? String(iso).slice(0, 16).replace('T', ' ') : '');

Page({
  data: {
    loading: true,
    error: '',
    account: null,
    signinMonth: null,
    tasks: [],
    dailyTasks: [],
    newcomerTasks: [],
    campaign: null,
    signedToday: false,
    signinBusy: false,
  },

  onShow() {
    this.load();
  },

  load() {
    this.setData({ loading: true, error: '' });
    api
      .ensureSession()
      .then(() =>
        Promise.all([
          api.get('/api/coins/me'),
          api.get('/api/coins/signin/month'),
          api.get('/api/coins/tasks'),
          api.get('/api/coins/campaign/active').catch(() => null),
        ]),
      )
      .then(([account, signinMonth, tasks, campaign]) => {
        const list = tasks || [];
        this.setData({
          loading: false,
          account,
          signinMonth,
          tasks: list.map((task) => ({
            ...task,
            percent:
              task.rewarded || task.targetCount <= 0
                ? 100
                : Math.min(100, Math.round((task.progress / task.targetCount) * 100)),
          })),
          dailyTasks: list.filter((task) => task.taskType === 'DAILY'),
          newcomerTasks: list.filter((task) => task.taskType === 'NEWCOMER'),
          campaign: campaign
            ? {
                ...campaign,
                startText: fmt(campaign.startAt),
                endText: fmt(campaign.endAt),
              }
            : null,
          signedToday: !!(signinMonth && signinMonth.signedToday),
        });
      })
      .catch((err) => {
        if (err && err.needLogin) return;
        this.setData({ loading: false, error: (err && err.message) || '加载失败，请稍后重试' });
      });
  },

  signin() {
    if (this.data.signinBusy || this.data.signedToday) return;
    this.setData({ signinBusy: true });
    api
      .post('/api/coins/signin')
      .then((result) => {
        wx.showToast({ title: '签到 +' + result.coins + ' 币', icon: 'success' });
        this.load();
      })
      .catch((err) => {
        if (err && err.needLogin) return;
        wx.showModal({
          title: '签到失败',
          content: (err && err.message) || '请稍后重试',
          showCancel: false,
        });
      })
      .finally(() => this.setData({ signinBusy: false }));
  },

  goOrders() {
    wx.navigateTo({ url: '/pages/orders/orders' });
  },

  goProduct() {
    wx.switchTab({ url: '/pages/index/index' });
  },
});
