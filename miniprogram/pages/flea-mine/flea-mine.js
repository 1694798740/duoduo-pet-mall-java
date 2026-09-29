// 我的闲置（第五十二轮）
//
// 与公开列表的关键差别：**必须展示全部状态**（在售 / 已下架 / 已售出 + 审核中 / 审核失败）。
// 卖家看不到自己没通过审核的条目，就只能「发布完查无此项、反复重发」——
// 这也正是服务端专门有 GET /api/listings/mine/list、而不是复用公开列表的原因。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

/**
 * 筛选标签（第五十三轮热修二）：5 个状态构成**不重不漏**的划分 —— 一件闲置只出现在一个标签下。
 *
 * 服务端 `status`（在售/下架/已售）与 `auditStatus`（审核中/通过/驳回）是**正交**的两维，
 * 新发布的条目天然是「ON_SALE + PENDING」。所以只按 status 筛「在售」会把还在审核的条目
 * 一起捞出来 —— 同一件闲置同时出现在「审核中」和「在售」两个标签里，正是用户报的问题。
 */
const FILTERS = [
  { code: 'ALL', label: '全部' },
  { code: 'AUDIT_PENDING', label: '审核中' },
  { code: 'ON_SALE', label: '在售' },
  { code: 'AUDIT_REJECTED', label: '审核失败' },
  { code: 'OFF_SHELF', label: '已下架' },
  { code: 'SOLD', label: '已售出' },
];

/**
 * 标签 code → 接口参数。
 *
 * ★ 交易维度的三个标签（在售 / 已下架 / 已售出）**必须**同时带 `auditStatus=APPROVED`：
 * 未过审的条目还不算真正上架（公开列表里根本搜不到），不能算「在售」；
 * 被驳回的条目服务端已同步落 OFF_SHELF，同样不能算「已下架」（那是卖家的主动行为）。
 */
const FILTER_PARAMS = {
  AUDIT_PENDING: { auditStatus: 'PENDING' },
  AUDIT_REJECTED: { auditStatus: 'REJECTED' },
  ON_SALE: { status: 'ON_SALE', auditStatus: 'APPROVED' },
  OFF_SHELF: { status: 'OFF_SHELF', auditStatus: 'APPROVED' },
  SOLD: { status: 'SOLD', auditStatus: 'APPROVED' },
};

/**
 * 单一显示状态（卡片徽章）：与上面的标签同源，保证「徽章说的状态」就是「它所在的标签」。
 *
 * 审核态优先于交易态：未过审的条目在卖，卖家看到「在售」会误以为已经挂出去了；
 * 已售出 / 已下架的判定放在审核态之后，因为过审后这些交易态才真正成立。
 */
function statusOf(it) {
  const audit = it.auditStatus || 'PENDING';
  if (audit === 'PENDING') return { text: '审核中', cls: 'fm-badge-pending' };
  if (audit === 'REJECTED') return { text: '审核失败', cls: 'fm-badge-rejected' };
  if (it.status === 'SOLD') return { text: '已售出', cls: 'fm-badge-sold' };
  if (it.status === 'OFF_SHELF') return { text: '已下架', cls: 'fm-badge-off' };
  return { text: '在售', cls: 'fm-badge-onsale' };
}

function decorate(it) {
  const audit = it.auditStatus || 'PENDING';
  const st = statusOf(it);
  return Object.assign({}, it, {
    image: images.listingImage(it.id, it.cover),
    priceText: api.fen2yuan(it.price),
    // 单一状态徽章（第五十三轮热修二）：不再同时挂「审核中」+「在售」
    statusText: st.text,
    statusCls: st.cls,
    timeText: api.shortTime(it.createdAt),
    canShelve: it.status === 'OFF_SHELF' && audit === 'APPROVED',
    canUnshelve: it.status === 'ON_SALE' && audit === 'APPROVED',
    // 驳回原因要显示出来：不告诉卖家为什么被拒，他只能猜着改
    showReason: audit === 'REJECTED' && !!it.auditReason,
    // 重新提审（第五十三轮）：只有被驳回的条目能改内容重提
    canResubmit: audit === 'REJECTED',
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
    status: 'ALL',
    filters: FILTERS,
    acting: false,
  },

  onLoad() {
    if (!api.getToken()) {
      api.openLoginPage();
      wx.navigateBack();
      return;
    }
    this.load(true);
  },

  onShow() {
    // 从发布页 redirect 回来时要重新拉：否则看不到刚发的那条
    if (this._loaded) {
      this.load(true);
    }
    this._loaded = true;
  },

  onPullDownRefresh() {
    this.setData({ refreshing: true });
    this.load(true, () => {
      this.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom() {
    if (!this.data.noMore && !this.data.loading) this.load(false);
  },

  load(reset, done) {
    if (this.data.loading) {
      if (done) done();
      return;
    }
    const page = reset ? 1 : this.data.page + 1;
    this.setData({ loading: true, error: reset ? '' : this.data.error });
    // 筛选 code → 接口参数（第五十三轮热修二）：每个标签都带上审核维度，
    // 保证 5 个标签互斥（同一件闲置只命中一个，不会既在「审核中」又在「在售」）
    const filter = this.data.status;
    const params = Object.assign({ page, pageSize: PAGE_SIZE }, FILTER_PARAMS[filter] || {});
    api.listing
      .mine(params)
      .then((res) => {
        // 请求层成功时已返回包络里的 data（即分页结果 {items,total,...} 本身），
        // 这里直接用 —— 再取 res.data 会得到 undefined，列表永远为空（R52 热修实录）
        const data = res || {};
        const rows = (data.items || data.records || []).map(decorate);
        const items = reset ? rows : this.data.items.concat(rows);
        this.setData({
          items,
          total: data.total || items.length,
          page,
          noMore: rows.length < PAGE_SIZE,
          loading: false,
        });
        if (done) done();
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '加载失败，请下拉重试',
        });
        if (done) done();
      });
  },

  onPickFilter(e) {
    this.setData({ status: e.currentTarget.dataset.code });
    this.load(true);
  },

  onTapPublish() {
    wx.navigateTo({ url: '/pages/flea-publish/flea-publish' });
  },

  onTapItem(e) {
    const id = e.currentTarget.dataset.id;
    const item = this.data.items.find((x) => String(x.id) === String(id));
    // 在售且已过审的才有公开详情页；其余状态公开接口会 404
    if (item && item.status === 'ON_SALE' && item.auditStatus === 'APPROVED') {
      wx.navigateTo({ url: '/pages/flea-detail/flea-detail?id=' + id });
    } else if (item && item.auditStatus === 'REJECTED') {
      this.onResubmit(e); // 被驳回的条目：直接送编辑页，比一句「未公开」更有用
    } else {
      wx.showToast({ title: '该闲置尚未公开展示', icon: 'none' });
    }
  },

  /** 修改并重新提审（第五十三轮）：进发布页编辑模式，回填内容后走 resubmit */
  onResubmit(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({
      url: '/pages/flea-publish/flea-publish?id=' + id + '&mode=edit',
    });
  },

  /** 上架 / 下架 */
  onToggleStatus(e) {
    if (this.data.acting) return;
    const id = e.currentTarget.dataset.id;
    const next = e.currentTarget.dataset.action;
    const item = this.data.items.find((x) => String(x.id) === String(id));
    const verb = next === 'ON_SALE' ? '上架' : '下架';

    this.setData({ acting: true });
    api.listing
      .changeStatus(id, next)
      .then(() => {
        this.setData({ acting: false });
        wx.showToast({ title: '已' + verb, icon: 'success' });
        // 就地更新状态（比整页重拉更跟手，也避免列表滚动位置丢失）：
        // 重新走一遍 decorate —— 徽章与按钮权限都从 status/auditStatus 推导，
        // 保证「单一状态」口径在就地更新后依然成立（否则会出现旧徽章 + 新按钮的错配）
        const items = this.data.items.map((x) =>
          String(x.id) === String(id) ? decorate(Object.assign({}, x, { status: next })) : x
        );
        this.setData({ items });
        // 当前筛选项可能已不匹配（例如在「在售」页里下架了一条）→ 重拉保持一致
        if (this.data.status !== 'ALL') this.load(true);
      })
      .catch((err) => {
        this.setData({ acting: false });
        const msg = (err && err.message) || verb + '失败';
        // 服务端对上架有审核态闸门（未过审不能上架），把它的原话透给用户
        wx.showModal({ title: verb + '失败', content: msg, showCancel: false });
      });
  },
});
