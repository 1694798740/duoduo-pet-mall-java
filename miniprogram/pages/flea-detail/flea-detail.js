// 闲置详情（第五十二轮，第五十三轮改版）
//
// 数据源 GET /api/listings/{id}（免登）。服务端的可见性硬规则是
// 「只暴露 status=ON_SALE 且 auditStatus=APPROVED」，其余一律 404
// （不是 403 —— 避免用状态码探测「这条 id 存在但未过审」）。
// 因此本页对 404 的处理是「该闲置已下架或不存在」，不做任何存在性推断。
//
// 第五十三轮新增：
//  * 卖家信息卡：card.sellerId → GET /api/socials/users/{id}/profile（公开端点，
//    复用他人主页同一数据源；前端聚合而非后端聚合——零新后端契约）。
//    取不到时降级为「用户{sellerId}」占位，不挡详情。
//  * 「我想要」：进与卖家的 LISTING 咨询会话（bizType=LISTING，服务端按闲置归属
//    定收件人），整段对话留在该闲置的独立会话里 —— 与商品页「问一问」同款链路。
//  * 「立即购买」：过渡态按钮。完整 C2C 担保交易属设计稿 Batch-3（下单/锁定/托管），
//    且上线前须核实微信类目资质（设计稿 §12-6），此处如实提示，不做假下单。
const api = require('../../utils/api');
const images = require('../../utils/images');

Page({
  data: {
    id: null,
    card: null,
    description: '',
    gallery: [],
    sameCategoryNew: [],
    seller: null, // 卖家公开资料（他人主页同源）：{userId, nickname, avatar}
    isOwner: false, // 自己的闲置：不显示「我想要」，引导去「我的闲置」管理
    loading: true,
    error: '',
  },

  onLoad(options) {
    const id = options && options.id;
    if (!id) {
      this.setData({ loading: false, error: '缺少闲置 id' });
      return;
    }
    this.setData({ id });
    this.load(id);
  },

  onShow() {
    // 从登录页回来时 globalData.user 才就绪（onLoad 时可能为空）——复核归属判定
    const me = getApp().globalData.user;
    const card = this.data.card;
    if (me && card && card.sellerId) {
      const isOwner = String(me.id) === String(card.sellerId);
      if (isOwner !== this.data.isOwner) this.setData({ isOwner });
    }
  },

  /**
   * 拉取详情。
   *
   * @param {string} id 闲置 id
   * @param {{silent?: boolean, done?: Function}} [opts]
   *   silent：下拉刷新专用 —— 不清空已渲染内容、不显示「正在加载」。
   *           用户下拉只是想让计数更新，此刻整页闪成加载态、内容消失，反而像出错了。
   *   done：  交给调用方收尾（例如 wx.stopPullDownRefresh）。
   */
  load(id, opts) {
    const opt = opts || {};
    // 非静默（首次进入）才切加载态；静默刷新保留现有内容，只清错误
    if (opt.silent) {
      this.setData({ error: '' });
    } else {
      this.setData({ loading: true, error: '' });
    }
    api.listing
      .detail(id)
      .then((res) => {
        // 请求层成功时已返回包络里的 data（{card, description, images, sameCategoryNew}），
        // 勿再剥一层 res.data（R52 热修实录）
        const data = res || {};
        const card = data.card || {};
        const gallery = images.listingGallery(card.id, (data.images || [])[0]);
        const me = getApp().globalData.user;
        this.setData({
          card: Object.assign({}, card, {
            priceText: api.fen2yuan(card.price),
            originalText: card.originalPrice ? api.fen2yuan(card.originalPrice) : '',
          }),
          description: data.description || '',
          gallery,
          isOwner: !!(me && card.sellerId && String(me.id) === String(card.sellerId)),
          sameCategoryNew: (data.sameCategoryNew || []).map((p) =>
            Object.assign({}, p, {
              image: images.productImage(p.id, p.mainImage || p.cover),
              priceText: api.fen2yuan(p.minPrice || p.price),
            })
          ),
          loading: false,
        });
        if (card.sellerId) this.loadSeller(card.sellerId);
        if (opt.done) opt.done();
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '该闲置已下架或不存在',
        });
        if (opt.done) opt.done();
      });
  },

  /**
   * 下拉刷新（第五十四轮）：与「打开页面」共同构成浏览量口径。
   *
   * <p>用户口径是「页面每被打开或刷新一次即累计一次浏览量，同一用户多次查看可累加」——
   * 「打开」由 onLoad 的首次加载承担，「刷新」由这里承担（服务端每次详情请求都会 +1）。
   * 刷新走 silent：内容留在屏幕上，只有两个计数在变。
   */
  onPullDownRefresh() {
    if (!this.data.id) {
      wx.stopPullDownRefresh();
      return;
    }
    this.load(this.data.id, { silent: true, done: () => wx.stopPullDownRefresh() });
  },

  /**
   * 卖家公开资料（第五十三轮）：复用他人主页端点，一次拿齐昵称头像。
   * profile 属 /api/socials/**（网关白名单外，需登录）—— 本页是免登详情页，
   * 无令牌时不发这个请求（发了会 401 → 清登录态 → 触发全局静默重登/跳登录，
   * 把逛详情的访客强行拽去登录是灾难），直接占位「卖家」。
   * 登录用户请求失败（令牌过期等）同样降级占位，不挡详情主内容。
   */
  loadSeller(sellerId) {
    const placeholder = { userId: sellerId, nickname: '卖家', avatar: '' };
    if (!api.getToken()) {
      this.setData({ seller: placeholder });
      return;
    }
    api.social.follow
      .profile(sellerId)
      .then((user) => {
        this.setData({
          seller: {
            userId: sellerId,
            nickname: (user && user.nickname) || '用户' + sellerId,
            avatar: (user && user.avatar) || '',
          },
        });
      })
      .catch(() => {
        this.setData({ seller: placeholder });
      });
  },

  goSellerProfile() {
    if (!this.data.seller) return;
    wx.navigateTo({
      url: '/pages/user-profile/user-profile?id=' + this.data.seller.userId,
    });
  },

  /**
   * 我想要（第五十三轮）：进 LISTING 咨询会话。登录用 requireLogin ——
   * 咨询是「以我的身份向卖家发声」，未登录必须主动登录（与商品问一问同一纪律）。
   * 收件人只传 sellerId 作占位：服务端按闲置归属重新定人，前端传值不被信任。
   */
  onWantIt() {
    const card = this.data.card;
    if (!card || !card.sellerId) return;
    if (this.data.isOwner) {
      wx.showToast({ title: '这是你发布的闲置，可在「我的闲置」管理', icon: 'none' });
      return;
    }
    getApp()
      .requireLogin()
      .then(() => {
        wx.navigateTo({
          url:
            '/pages/chat/chat?peerId=' +
            card.sellerId +
            '&listingId=' +
            this.data.id +
            '&ask=1',
        });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  /**
   * 立即购买（第五十三轮 · 过渡态）：完整 C2C 担保交易（下单/锁定/托管/放款）
   * 属设计稿 Batch-3，且上线前须核实微信类目资质（设计稿 §12-6）。
   * 按钮先给出如实语义：担保支付上线前，交易需与卖家协商 —— 引导去问一问，
   * 绝不假装能下单。
   */
  onBuy() {
    if (this.data.isOwner) {
      wx.showToast({ title: '这是你发布的闲置', icon: 'none' });
      return;
    }
    wx.showModal({
      title: '担保交易即将上线',
      content: '平台担保支付正在准备中。上线前请先与卖家沟通确认成色与交易方式，再决定是否购买。',
      confirmText: '问问卖家',
      cancelText: '知道了',
      success: (res) => {
        if (res.confirm) this.onWantIt();
      },
    });
  },

  onPreview(e) {
    const idx = e.currentTarget.dataset.index || 0;
    wx.previewImage({ current: this.data.gallery[idx], urls: this.data.gallery });
  },

  onTapNew(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.id });
  },

  onBackMarket() {
    wx.navigateBack();
  },
});
