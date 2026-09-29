// 分享落地页（第四十三轮；第四十六轮支持转发；第五十六轮加评论区 / 话题 / 举报）
//
// 两种入口，query 二选一：
//  * ?id=<分享单ID>   —— 来自分享圈信息流 / 带评价的分享卡片：展示分享者 + 评价 + 商品卡；
//                        转发单同样走这里，展示的就是**转发人** + 附言 + 原帖引用；
//                        **第五十六轮起还带评论区**（评论 / 回复 / 评论点赞）与话题标签。
//  * ?spuId=<商品ID>  —— 来自好友转发的商品卡片（转发走微信原生、没有分享单）：
//                        展示「好友向你分享了这个商品」+ 商品卡。这种形态**没有评论区**
//                        （没有分享单就没有评论的宿主），也不展示举报。
//
// 旧版「查看分享页无响应」的根因：分享 path 指向商品页自身，点开卡片等于刷新原页。
// 现在落点固定为本页 —— 点开即见分享内容，再经「查看商品」进入详情。
//
// 第四十六轮补充：本页也提供「转发到分享圈」。转发成功后 **redirectTo 转发单详情**，
// 立刻以「我」的身份展示这条转发 —— 这是修复「转发后仍显示原发帖人」的直接体现。
//
// 第五十六轮三条取舍：
//  ① **评论只在有分享单时可见**（好友直转的商品卡没有分享宿主）；
//  ② 举报入口对**自己的内容不显示**（后端也会拒 422）—— 自己的内容该走删除；
//  ③ 删除 / 编辑不在本页，收在「我的分享」管理页：本页已经有两个弹层（转发、举报），
//     再塞编辑面板会让「这条分享我能做什么」变得难以一眼看清。
const api = require('../../utils/api');
const images = require('../../utils/images');

const COMMENT_PAGE_SIZE = 20;
const REPLY_PAGE_SIZE = 20;

/** 举报理由：预置选项而不是自由输入 —— 运营拿到的理由才可归类 */
const REPORT_REASONS = ['垃圾广告', '色情低俗', '违法违规', '不实信息', '引战辱骂', '其他'];

function decorate(view) {
  // 快照里的商品图是后端相对路径（/img/...svg），统一映射随包位图
  const image = images.productImage(view.spuId, view.productImage);
  return Object.assign({}, view, {
    image: image,
    priceText: api.fen2yuan(view.minPrice),
    timeText: api.shortTime(view.createdAt),
    topics: view.topics || [],
  });
}

/** 评论项装饰：时间文案 + 回复列表（懒加载，默认空） */
function decorateComment(c) {
  return Object.assign({}, c, {
    timeText: api.shortTime(c.createdAt),
    replies: [],
    expanded: false,
    repliesLoading: false,
  });
}

Page({
  data: {
    // friendShare=true 表示好友直转（无分享单，没有分享者信息，也没有评论区）
    friendShare: false,
    view: null,
    loading: false,
    error: '',
    // 转发弹层（第四十六轮）
    sharePanel: false,
    repostContent: '',
    reposting: false,
    // 评论区（第五十六轮）
    comments: [],
    commentTotal: 0,
    commentLoading: false,
    commentNoMore: false,
    commentInput: '',
    replyTo: null,
    sendingComment: false,
    // 举报弹层（第五十六轮）
    reportPanel: false,
    reportReasons: REPORT_REASONS,
    reporting: false,
  },

  onLoad(query) {
    if (query.id) {
      this.shareId = String(query.id);
      this.fetchShare(query.id);
      this.loadComments(true);
    } else if (query.spuId) {
      this.fetchProduct(query.spuId);
    } else {
      this.setData({ error: '分享内容不存在或已失效' });
    }
  },

  /** 带分享单：取分享详情（公开接口） */
  fetchShare(id) {
    this.setData({ loading: true, error: '' });
    api.share
      .detail(id)
      .then((view) => this.setData({ loading: false, view: decorate(view) }))
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  /** 好友直转：分享单不存在，用公开商品详情拼一张商品卡 */
  fetchProduct(spuId) {
    this.setData({ loading: true, error: '' });
    api.product
      .detail(spuId)
      .then((raw) => {
        const card = raw.card || {};
        this.setData({
          loading: false,
          friendShare: true,
          view: decorate({
            // 第五十三轮热修：id 保持字符串，Number() 会把 19 位雪花 id 精度打碎
            spuId: String(spuId),
            productTitle: card.title || '商品',
            productImage: card.mainImage || '',
            minPrice: card.minPrice || 0,
            content: '',
            nickname: '好友',
          }),
        });
      })
      .catch((err) => this.setData({ loading: false, error: (err && err.message) || '加载失败' }));
  },

  goProduct() {
    const view = this.data.view;
    if (!view || !view.spuId) return;
    wx.navigateTo({ url: '/pages/product/product?id=' + view.spuId });
  },

  goShareCircle() {
    // 第四十四轮：分享圈升为一级 tab 页，只能 switchTab
    wx.switchTab({ url: '/pages/share-circle/share-circle' });
  },

  /** 话题标签 → 话题聚合页（第五十六轮） */
  goTopic(e) {
    const name = e.currentTarget.dataset.name;
    if (!name) return;
    wx.navigateTo({ url: '/pages/share-topic/share-topic?name=' + encodeURIComponent(name) });
  },

  /** 阻止弹层内点击冒泡到遮罩 */
  noop() {},

  // ---------------- 转发（第四十六轮） ----------------

  /** 好友直转来的商品卡没有分享单，先转成分享单再转发（否则转发的对象不存在） */
  openForward() {
    if (this.data.friendShare || !this.data.view || !this.data.view.id) {
      wx.showToast({ title: '这条分享来自好友转发，请到分享圈转发', icon: 'none' });
      return;
    }
    this.setData({ sharePanel: true, repostContent: '' });
  },

  closeForward() {
    this.setData({ sharePanel: false, repostContent: '' });
  },

  onRepostInput(e) {
    this.setData({ repostContent: e.detail.value });
  },

  /**
   * 转发到分享圈：落一条以**我**为作者的转发行，然后 redirectTo 转发单详情。
   *
   * <p>redirectTo 而不是 navigateTo：转发完当前页已无意义（它的语境已被转发取代），
   * 直接替换成「我的转发页」，用户一眼看到身份正确 —— 并且返回不会退回到原帖。
   */
  repostToCircle() {
    const view = this.data.view;
    if (!view || !view.id || this.data.reposting) return;
    this.setData({ reposting: true });
    getApp()
      .requireLogin()
      .then(() => api.share.repost(view.id, this.data.repostContent))
      .then((created) => {
        this.setData({ reposting: false, sharePanel: false, repostContent: '' });
        wx.showToast({ title: '已转发到分享圈', icon: 'success' });
        wx.redirectTo({ url: '/pages/share-detail/share-detail?id=' + created.id });
      })
      .catch((err) => {
        this.setData({ reposting: false });
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '转发失败', icon: 'none' });
      });
  },

  // ---------------- 评论区（第五十六轮） ----------------

  /**
   * 评论列表：一级评论分页。
   *
   * silent 拉取（不清空已有列表、不闪屏），失败时只 toast —— 评论区加载失败
   * 不该把整页打成错误态（分享正文还在，用户仍然能看/能转发）。
   */
  loadComments(reset) {
    if (this.data.commentLoading || this.data.friendShare || !this.shareId) return;
    if (!reset && this.data.commentNoMore) return;
    const page = reset ? 1 : Math.floor(this.data.comments.length / COMMENT_PAGE_SIZE) + 1;
    this.setData({ commentLoading: true });
    api.share
      .comments(this.shareId, page, COMMENT_PAGE_SIZE)
      .then((res) => {
        const items = (res && res.items) || [];
        const decorated = items.map(decorateComment);
        this.setData({
          commentLoading: false,
          comments: reset ? decorated : this.data.comments.concat(decorated),
          commentTotal: (res && res.total) || 0,
          commentNoMore: items.length < COMMENT_PAGE_SIZE,
        });
      })
      .catch((err) => {
        this.setData({ commentLoading: false });
        wx.showToast({ title: (err && err.message) || '评论加载失败', icon: 'none' });
      });
  },

  onCommentInput(e) {
    this.setData({ commentInput: e.detail.value });
  },

  /** 加载更多一级评论（显式按钮，不依赖滚动触底 —— 本页底部还有输入条与弹层） */
  loadMoreComments() {
    this.loadComments(false);
  },

  /** 点「回复」：把输入焦点切到该评论（输入框 placeholder 会显示「回复 @某某」） */
  openReply(e) {
    const index = e.currentTarget.dataset.index;
    const item = this.data.comments[index];
    if (!item) return;
    this.setData({ replyTo: { id: item.id, nickname: item.nickname, index: index } });
  },

  cancelReply() {
    this.setData({ replyTo: null });
  },

  /**
   * 发表评论 / 回复。
   *
   * 成功后**本地回填**而不是整页重拉：重拉会把用户刚展开的回复、滚到的位置全部重置。
   * 一级评论插到最前（倒序更符合「最新在前」的阅读习惯吗？——这里用**追加**，
   * 因为服务端列表是正序（时间升序），追加与之一致，翻页不会错位）。
   */
  submitComment() {
    const content = (this.data.commentInput || '').trim();
    if (!content) {
      wx.showToast({ title: '先写点什么吧', icon: 'none' });
      return;
    }
    if (this.data.sendingComment) return;
    const replyTo = this.data.replyTo;
    this.setData({ sendingComment: true });
    getApp()
      .requireLogin()
      .then(() => api.share.comment(this.shareId, content, replyTo ? replyTo.id : null))
      .then((created) => {
        const patch = {
          sendingComment: false,
          commentInput: '',
          replyTo: null,
          commentTotal: this.data.commentTotal + 1,
        };
        if (replyTo) {
          // 回复：挂到对应评论下并展开（用户立刻能看到自己刚发的那条）
          const replies = (this.data.comments[replyTo.index] || {}).replies || [];
          patch['comments[' + replyTo.index + '].replies'] = replies.concat(decorateComment(created));
          patch['comments[' + replyTo.index + '].expanded'] = true;
          patch['comments[' + replyTo.index + '].replyCount'] =
            ((this.data.comments[replyTo.index] || {}).replyCount || 0) + 1;
        } else {
          patch.comments = this.data.comments.concat(decorateComment(created));
        }
        this.setData(patch);
        wx.showToast({ title: '已发布', icon: 'success' });
      })
      .catch((err) => {
        this.setData({ sendingComment: false });
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '发布失败', icon: 'none' });
      });
  },

  /** 展开 / 收起某条评论的回复（首次展开时拉那一页回复） */
  toggleReplies(e) {
    const index = e.currentTarget.dataset.index;
    const item = this.data.comments[index];
    if (!item) return;
    if (item.expanded) {
      this.setData({ ['comments[' + index + '].expanded']: false });
      return;
    }
    if ((item.replies || []).length || item.repliesLoading) {
      this.setData({ ['comments[' + index + '].expanded']: true });
      return;
    }
    this.setData({ ['comments[' + index + '].repliesLoading']: true });
    api.share
      .comments(this.shareId, 1, REPLY_PAGE_SIZE, item.id)
      .then((res) => {
        const replies = ((res && res.items) || []).map(decorateComment);
        this.setData({
          ['comments[' + index + '].replies']: replies,
          ['comments[' + index + '].expanded']: true,
          ['comments[' + index + '].repliesLoading']: false,
        });
      })
      .catch((err) => {
        this.setData({ ['comments[' + index + '].repliesLoading']: false });
        wx.showToast({ title: (err && err.message) || '回复加载失败', icon: 'none' });
      });
  },

  /** 评论点赞 toggle：本地回填 active 与 count（不重拉） */
  toggleCommentLike(e) {
    const index = e.currentTarget.dataset.index;
    const item = this.data.comments[index];
    if (!item) return;
    getApp()
      .requireLogin()
      .then(() => api.share.likeComment(item.id))
      .then((state) => {
        this.setData({
          ['comments[' + index + '].liked']: state.active,
          ['comments[' + index + '].likeCount']: state.count,
        });
      })
      .catch((err) => {
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' });
      });
  },

  // ---------------- 举报（第五十六轮） ----------------

  openReport() {
    this.setData({ reportPanel: true });
  },

  closeReport() {
    this.setData({ reportPanel: false });
  },

  /** 选一个理由即提交（少一步点击；理由是预置项，不需要再确认） */
  pickReport(e) {
    const reason = this.data.reportReasons[e.currentTarget.dataset.index];
    const view = this.data.view;
    if (!reason || !view || !view.id || this.data.reporting) return;
    this.setData({ reporting: true });
    getApp()
      .requireLogin()
      .then(() => api.share.report(view.id, reason))
      .then(() => {
        this.setData({ reporting: false, reportPanel: false });
        wx.showToast({ title: '已收到举报，平台会尽快处理', icon: 'none' });
      })
      .catch((err) => {
        this.setData({ reporting: false, reportPanel: false });
        if (err && err.needLogin) return;
        wx.showToast({ title: (err && err.message) || '举报失败', icon: 'none' });
      });
  },

  /** 落地页也可再转发：带分享单转卡片（含评价语境），直转则继续转商品 */
  onShareAppMessage() {
    const view = this.data.view || {};
    const hasShareId = !this.data.friendShare && view.id;
    return {
      title: (view.productTitle || '多多宠物商店') + '｜好友觉得不错，来看看',
      path: hasShareId
        ? '/pages/share-detail/share-detail?id=' + view.id
        : '/pages/share-detail/share-detail?spuId=' + (view.spuId || ''),
      imageUrl: images.productImage(view.spuId, view.productImage) || '/images/share/cover.jpg',
    };
  },
});
