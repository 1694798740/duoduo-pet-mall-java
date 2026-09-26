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
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

Page({
  data: {
    items: [],
    total: 0,
    page: 1,
    loading: false,
    refreshing: false,
    noMore: false,
    error: '',
  },

  onLoad() {
    this.fetch(true);
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
      .feed(page, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map((it) =>
          Object.assign({}, it, {
            image: images.productImage(it.spuId, it.productImage),
            priceText: api.fen2yuan(it.minPrice),
            timeText: api.shortTime(it.createdAt),
          })
        );
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
   * 转发按钮（open-type=share）的伴随点击：先记下要转的是哪条分享，
   * 随后触发的 onShareAppMessage 按 pendingItem 组卡 —— 卡片落点带评价语境。
   */
  markForward(e) {
    const index = this.data.items.findIndex(
      (it) => String(it.id) === String(e.currentTarget.dataset.id)
    );
    this._pendingItem = index >= 0 ? this.data.items[index] : null;
  },

  /** 转发卡片：有条目转该条（落点 share-detail?id=），没有则转分享圈本身 */
  onShareAppMessage() {
    const item = this._pendingItem;
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
