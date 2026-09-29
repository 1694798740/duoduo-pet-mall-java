// 我的分享（第五十六轮）：管理自己发过的分享 —— 编辑文字与话题、删除。
//
// 为什么单开一页而不是把「删除 / 编辑」塞进详情页：
//  ① 详情页已经有两个弹层（转发、举报），再塞编辑面板会让「这条分享我能做什么」难以看清；
//  ② 「我发过什么」本身就是一个需要列表视图的问题 —— 只靠详情页要一条条点进去才想起来。
//
// 删除语义（用户选定的口径）：**软删**。删掉之后别人转发过它的转发单仍然存在，
// 但引用块显示「原内容已被作者删除」—— 尊重删除意图，又不把别人的转发弄成空白卡片。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;
/** 一条分享最多带 2 个话题（与后端一致；这里只做输入提示，最终由服务端规范化） */
const MAX_TOPICS = 2;

function decorate(it) {
  return Object.assign({}, it, {
    image: images.productImage(it.spuId, it.productImage),
    priceText: api.fen2yuan(it.minPrice),
    timeText: api.shortTime(it.createdAt),
    topics: it.topics || [],
    isRepost: !!it.parentId,
  });
}

Page({
  data: {
    items: [],
    total: 0,
    page: 1,
    loading: false,
    noMore: false,
    error: '',
    // 编辑弹层
    editPanel: false,
    editTarget: null,
    editContent: '',
    editTopics: '',
    saving: false,
  },

  onLoad() {
    this.fetch(true);
  },

  onShow() {
    // 从详情页 / 发布页返回时可能有新增或删除，重拉第一页保持列表与现实一致。
    // （本页是「管理」性质，不像信息流那样需要保住滚动位置与编辑中的内容）
    if (this._loadedOnce) {
      this.fetch(true);
    }
    this._loadedOnce = true;
  },

  onPullDownRefresh() {
    this.fetch(true, () => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (this.data.loading || this.data.noMore) return;
    this.fetch(false);
  },

  fetch(reset, done) {
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: '' });
    api.share
      .mine(page, PAGE_SIZE)
      .then((data) => {
        const rows = ((data && data.items) || []).map(decorate);
        this.setData({
          items: reset ? rows : this.data.items.concat(rows),
          total: (data && data.total) || 0,
          page: page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
        });
        if (done) done();
      })
      .catch((err) => {
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
        if (done) done();
      });
  },

  retry() {
    this.fetch(true);
  },

  noop() {},

  goDetail(e) {
    wx.navigateTo({ url: '/pages/share-detail/share-detail?id=' + e.currentTarget.dataset.id });
  },

  goProduct(e) {
    wx.navigateTo({ url: '/pages/product/product?id=' + e.currentTarget.dataset.spu });
  },

  goShareCircle() {
    wx.switchTab({ url: '/pages/share-circle/share-circle' });
  },

  goTopic(e) {
    const name = e.currentTarget.dataset.name;
    if (!name) return;
    wx.navigateTo({ url: '/pages/share-topic/share-topic?name=' + encodeURIComponent(name) });
  },

  // ---------------- 编辑 ----------------

  /**
   * 打开编辑弹层：把当前内容与话题填进去。
   *
   * 话题用**空格分隔的一行文本**编辑，而不是做一个标签输入组件：
   * 最多 2 个，专用组件（增删芯片、去重提示）的成本远超收益。
   * 用户怎么写服务端都能容错（剥 #、去重、截断），所以这里可以宽松。
   */
  openEdit(e) {
    const id = e.currentTarget.dataset.id;
    const item = this.data.items.find((it) => String(it.id) === String(id));
    if (!item) return;
    this.setData({
      editPanel: true,
      editTarget: item,
      editContent: item.content || '',
      editTopics: (item.topics || []).join(' '),
    });
  },

  closeEdit() {
    this.setData({ editPanel: false, editTarget: null, editContent: '', editTopics: '' });
  },

  onEditContentInput(e) {
    this.setData({ editContent: e.detail.value });
  },

  onEditTopicsInput(e) {
    this.setData({ editTopics: e.detail.value });
  },

  /**
   * 保存编辑。
   *
   * topics 一律**显式传数组**（哪怕是空数组）—— 编辑面板里能看到并清空话题，
   * 所以「面板里是空的」就应该真的清空；传 null 会变成「不改动」，
   * 用户会发现话题删不掉（这正是 DTO 注释里强调的三态之一）。
   */
  saveEdit() {
    const target = this.data.editTarget;
    if (!target || this.data.saving) return;
    const isRepost = !!target.parentId;
    const content = (this.data.editContent || '').trim();
    if (!isRepost && !content) {
      wx.showToast({ title: '分享内容不能为空', icon: 'none' });
      return;
    }
    const topics = (this.data.editTopics || '')
      .split(/[\s#、,，]+/)
      .map((s) => s.trim())
      .filter((s) => !!s)
      .slice(0, MAX_TOPICS);

    this.setData({ saving: true });
    api.share
      .edit(target.id, content, topics)
      .then((updated) => {
        const index = this.data.items.findIndex((it) => String(it.id) === String(target.id));
        const patch = { saving: false, editPanel: false, editTarget: null };
        if (index >= 0) {
          patch['items[' + index + ']'] = decorate(updated);
        }
        this.setData(patch);
        wx.showToast({ title: '已保存', icon: 'success' });
      })
      .catch((err) => {
        this.setData({ saving: false });
        wx.showToast({ title: (err && err.message) || '保存失败', icon: 'none' });
      });
  },

  // ---------------- 删除 ----------------

  /**
   * 删除：二次确认后软删，成功后从列表移除。
   *
   * 必须二次确认：分享是不可逆的对外内容（删了别人转发的引用块就变成「已被作者删除」），
   * 一次误点就回不来。
   */
  confirmDelete(e) {
    const id = e.currentTarget.dataset.id;
    const item = this.data.items.find((it) => String(it.id) === String(id));
    if (!item) return;
    wx.showModal({
      title: '删除这条分享？',
      content: '删除后无法恢复。别人转发过它的话，那边会显示「原内容已被作者删除」。',
      confirmText: '删除',
      confirmColor: '#e54d42',
      success: (res) => {
        if (!res.confirm) return;
        api.share
          .remove(id)
          .then(() => {
            this.setData({
              items: this.data.items.filter((it) => String(it.id) !== String(id)),
              total: Math.max(0, this.data.total - 1),
            });
            wx.showToast({ title: '已删除', icon: 'success' });
          })
          .catch((err) => wx.showToast({ title: (err && err.message) || '删除失败', icon: 'none' }));
      },
    });
  },
});
