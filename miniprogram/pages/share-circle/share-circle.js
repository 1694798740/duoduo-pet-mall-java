// 分享圈（第四十三轮 · 参考拼多多的「拼小圈」；第四十四轮升为一级 tab 页）
//
// 信息流展示用户的商品分享：每条 = 分享者（昵称/头像）+ 自撰评价 + 商品卡（快照）
// + 互动条（❤️点赞 / ⭐收藏 / 🔁转发）。数据是公开接口（GET /api/shares/feed），
// 未登录也能逛 —— 分享是口碑内容，强制登录等于把传播链掐断。
//
// tab 页注意（第四十四轮）：
//  * 必须自绘导航栏（R41 教训：tab 切换原生导航栏标题行被重建，白条闪现）；
//  * 下拉刷新用自绘指示器（原生三点会被自绘导航栏遮挡，index 页同款）；
//  * 跳进本页只能 wx.switchTab，navigateTo 对 tab 页无效。
//
// 转发（第四十六轮修复）：旧版点「转发」只调起微信原生面板，**从不写后端** ——
// 于是①列表里看不到转发动态、②卡片落点带的是原帖 ID（详情页显示原发帖人）。
// 现在点「转发」先开弹层，两条出路各司其职：
//  * 「转发到分享圈」→ POST /api/shares/{id}/repost，真的落一条以**我**为作者的转发行，
//    刷新后出现在信息流最前；
//  * 「分享给好友」→ 走微信原生转发，落点 = 弹层里那条（转发过后它就是我的转发单，
//    好友点开看到的是我的转发页）。
const api = require('../../utils/api');
const unread = require('../../utils/unread');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

/** 信息流两种范围（第五十六轮）：推荐 = 全站，关注 = 只看我关注的人 */
const SCOPE_RECOMMEND = 'RECOMMEND';
const SCOPE_FOLLOWING = 'FOLLOWING';

/** 举报理由：预置项而不是自由输入 —— 运营拿到的理由才可归类 */
const REPORT_REASONS = ['垃圾广告', '色情低俗', '违法违规', '不实信息', '引战辱骂', '其他'];

/** 列表项装饰：图片映射 + 金额 + 时间文本 + 转发标记（isRepost 由 parentId 派生） */
function decorate(it) {
  return Object.assign({}, it, {
    image: images.productImage(it.spuId, it.productImage),
    priceText: api.fen2yuan(it.minPrice),
    timeText: api.shortTime(it.createdAt),
    isRepost: !!it.parentId,
    topics: it.topics || [],
  });
}

Page({
  data: {
    items: [],
    total: 0,
    page: 1,
    loading: false,
    refreshing: false,
    noMore: false,
    error: '',
    // 转发弹层（第四十六轮）
    sharePanel: false,
    repostTarget: null,
    repostContent: '',
    reposting: false,
    // 关注流 / 举报（第五十六轮）
    scope: SCOPE_RECOMMEND,
    reportPanel: false,
    reportTarget: null,
    reportReasons: REPORT_REASONS,
    reporting: false,
  },

  onLoad() {
    this.fetch(true);
  },

  /**
   * 角标刷新（第五十五轮）。
   *
   * 本页的 feed 仍只在 onLoad 与下拉时拉取（不在这里重复请求列表 ——
   * 从详情页返回就重拉整页会让用户丢失滚动位置与正在编辑的转发内容）；
   * onShow 只做与世界状态无关、成本极低的角标刷新。
   */
  onShow() {
    unread.refresh();
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    this.fetch(true, () => {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom() {
    if (this.data.loading || this.data.noMore) return;
    this.fetch(false);
  },

  /** append=false 时重置回第一页 */
  fetch(reset, done) {
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: '' });
    api.share
      .feed(page, PAGE_SIZE, this.data.scope)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        this.setData({
          items: reset ? rows : this.data.items.concat(rows),
          total: (data && data.total) || 0,
          page: page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
          refreshing: false,
        });
        if (done) done();
      })
      .catch((err) => {
        this.setData({ loading: false, refreshing: false, error: (err && err.message) || '加载失败' });
        if (done) done();
      });
  },

  retry() {
    this.fetch(true);
  },

  // ---------------- 推荐 / 关注双 tab（第五十六轮） ----------------

  /**
   * 切换信息流范围。
   *
   * <p>切到「关注」用 requireLogin 而不是 ensureLogin：关注流要按「我关注了谁」过滤，
   * 未登录根本无从谈起 —— 静默 wx.login 会无感登进一个微信绑定账号，
   * 用户莫名其妙看到一堆「关注的人的动态」（第四十四轮同源教训）。
   *
   * <p>切换时清空列表再拉：不清空的话，上一段范围的内容会短暂留在屏上，
   * 用户以为关注流里混进了陌生人。
   */
  switchScope(e) {
    const scope = e.currentTarget.dataset.scope;
    if (!scope || scope === this.data.scope) return;
    const go = () => {
      this.setData({ scope: scope, items: [], page: 1, noMore: false, error: '' });
      this.fetch(true);
    };
    if (scope === SCOPE_FOLLOWING) {
      getApp()
        .requireLogin()
        .then(go)
        .catch((err) => {
          if (err && err.needLogin) return; // 已引导登录页
          wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
        });
      return;
    }
    go();
  },

  /** 我的分享（第五十六轮）：删除 / 编辑自己的分享都在那一页 */
  goMine() {
    getApp()
      .requireLogin()
      .then(() => wx.navigateTo({ url: '/pages/share-mine/share-mine' }))
      .catch((err) => {
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  /** 话题标签 → 话题聚合页（第五十六轮） */
  goTopic(e) {
    const name = e.currentTarget.dataset.name;
    if (!name) return;
    wx.navigateTo({ url: '/pages/share-topic/share-topic?name=' + encodeURIComponent(name) });
  },

  // ---------------- 举报（第五十六轮） ----------------

  openReport(e) {
    const id = e.currentTarget.dataset.id;
    const target = this.data.items.find((it) => String(it.id) === String(id)) || null;
    if (!target) return;
    this.setData({ reportPanel: true, reportTarget: target });
  },

  closeReport() {
    this.setData({ reportPanel: false, reportTarget: null });
  },

  /** 选一个理由即提交（理由是预置项，不需要再让用户确认一次） */
  pickReport(e) {
    const reason = this.data.reportReasons[e.currentTarget.dataset.index];
    const target = this.data.reportTarget;
    if (!reason || !target || this.data.reporting) return;
    this.setData({ reporting: true });
    getApp()
      .requireLogin()
      .then(() => api.share.report(target.id, reason))
      .then(() => {
        this.setData({ reporting: false, reportPanel: false, reportTarget: null });
        wx.showToast({ title: '已收到举报，平台会尽快处理', icon: 'none' });
      })
      .catch((err) => {
        this.setData({ reporting: false, reportPanel: false, reportTarget: null });
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '举报失败', icon: 'none' });
      });
  },

  /** 阻止互动条内点击冒泡到整条卡片 */
  noop() {},

  /** 点商品卡 → 商品详情 */
  goProduct(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.spu });
  },

  /** 点整条卡片 → 分享落地页（含评价语境） */
  goDetail(e) {
    wx.navigateTo({ url: '/pages/share-detail/share-detail?id=' + e.currentTarget.dataset.id });
  },

  /**
   * 点作者 → 他人主页（第四十七轮）：关注 / 私信的社交入口。
   *
   * <p>requireLogin：主页里的关注/私信都要登录态，进门前先统一收口，
   * 避免进去之后每个动作各自弹登录。
   */
  goAuthor(e) {
    const userId = e.currentTarget.dataset.user;
    if (!userId) return;
    getApp()
      .requireLogin()
      .then(() => wx.navigateTo({ url: '/pages/user-profile/user-profile?id=' + userId }))
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  /**
   * 点赞 / 取消点赞（toggle）。
   *
   * <p>登录用 requireLogin 而不是 ensureLogin：点赞是「以用户身份对外发声」，
   * 未登录必须**主动**登录 —— 静默 wx.login 会无感登进一个微信绑定账号，
   * 用户不知道自己是谁就点了赞（第四十四轮 Bug 同源）。
   */
  toggleLike(e) {
    this.toggle(e, 'like', 'liked', 'likeCount', '已赞', '已取消点赞');
  },

  toggleFavorite(e) {
    this.toggle(e, 'favorite', 'favorited', 'favoriteCount', '已收藏', '已取消收藏');
  },

  toggle(e, action, flagKey, countKey, okText, offText) {
    const id = e.currentTarget.dataset.id;
    const index = this.data.items.findIndex((it) => String(it.id) === String(id));
    if (index < 0) return;
    getApp()
      .requireLogin()
      .then(() => api.share[action](id))
      .then((res) => {
        const patch = {};
        patch['items[' + index + '].' + flagKey] = !!res.active;
        patch['items[' + index + '].' + countKey] = res.count;
        this.setData(patch);
        wx.showToast({ title: res.active ? okText : offText, icon: 'none' });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' });
      });
  },

  /**
   * 点「转发」→ 开转发弹层（第四十六轮）。
   *
   * <p>不再直接把按钮做成 open-type=share：转发有两条出路（进分享圈 / 发给好友），
   * 默认只弹微信面板会丢掉「进分享圈」这条 —— 那正是旧版 bug①的来源。
   */
  openForward(e) {
    const id = e.currentTarget.dataset.id;
    const target = this.data.items.find((it) => String(it.id) === String(id)) || null;
    if (!target) return;
    this._pendingItem = null; // 开新弹层 = 新一次转发，不让上一条的落点残留
    this.setData({ sharePanel: true, repostTarget: target, repostContent: '' });
  },

  closeForward() {
    this.setData({ sharePanel: false, repostTarget: null, repostContent: '' });
  },

  onRepostInput(e) {
    this.setData({ repostContent: e.detail.value });
  },

  /**
   * 「转发到分享圈」：写接口落一条以我为作者的转发行。
   *
   * <p>登录用 requireLogin（不是 ensureLogin）：转发是「以我的身份对外发声」，
   * 未登录必须主动登录 —— 静默 wx.login 会无感登进微信绑定账号（第四十四轮同源）。
   *
   * <p>成功后刷新列表：我的转发行由服务端以「最新发布时间」返回，出现在信息流最前
   * —— 修复 bug①「转发后刷新列表里看不到」。
   */
  repostToCircle() {
    const target = this.data.repostTarget;
    if (!target || this.data.reposting) return;
    this.setData({ reposting: true });
    getApp()
      .requireLogin()
      .then(() => api.share.repost(target.id, this.data.repostContent))
      .then(() => {
        this.setData({ reposting: false, sharePanel: false, repostTarget: null, repostContent: '' });
        wx.showToast({ title: '已转发到分享圈', icon: 'success' });
        this.fetch(true);
      })
      .catch((err) => {
        this.setData({ reposting: false });
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '转发失败', icon: 'none' });
      });
  },

  /**
   * 「分享给好友」前的落点准备：open-type=share 的点击**紧随其后**触发
   * onShareAppMessage，这里先把「要转的是哪条」定下来（弹层里的那条）。
   */
  prepFriendShare() {
    this._pendingItem = this.data.repostTarget || null;
  },

  /** 转发卡片：弹层里选定过一次就转那条（落点 share-detail?id=），否则转分享圈本身 */
  onShareAppMessage() {
    const item = this._pendingItem;
    this._pendingItem = null; // 落点一次性消费，避免之后从右上角菜单转发时残留旧条目
    if (item) {
      const title = item.content
        ? item.nickname + '：' + item.content
        : item.nickname + ' 分享了好物「' + item.productTitle + '」';
      return {
        title: title.slice(0, 40),
        path: '/pages/share-detail/share-detail?id=' + item.id,
        imageUrl: item.image,
      };
    }
    return {
      title: '多多分享圈｜大家都晒了什么好物',
      path: '/pages/share-circle/share-circle',
      imageUrl: '/images/share/cover.jpg',
    };
  },
});
