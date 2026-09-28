// 我的闲置（第五十二轮）
//
// 与公开列表的关键差别：**必须展示全部状态**（在售 / 已下架 / 已售出 + 待审 / 已通过 / 已驳回）。
// 卖家看不到自己没通过审核的条目，就只能「发布完查无此项、反复重发」——
// 这也正是服务端专门有 GET /api/listings/mine/list、而不是复用公开列表的原因。
const api = require('../../utils/api');
const images = require('../../utils/images');

const PAGE_SIZE = 10;

const FILTERS = [
  { code: 'ALL', label: '全部' },
  { code: 'ON_SALE', label: '在售' },
  { code: 'OFF_SHELF', label: '已下架' },
  { code: 'SOLD', label: '已售出' },
];

/** 审核态文案与色号（色号驱动样式：待审=提示、通过=正常、驳回=警示） */
const AUDIT_LABEL = {
  PENDING: '审核中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

function decorate(it) {
  const audit = it.auditStatus || 'PENDING';
  return Object.assign({}, it, {
    image: images.listingImage(it.id, it.cover),
    priceText: api.fen2yuan(it.price),
    auditLabel: AUDIT_LABEL[audit] || audit,
    auditClass: 'fm-audit-' + audit.toLowerCase(),
    timeText: api.shortTime(it.createdAt),
    canShelve: it.status === 'OFF_SHELF',
    canUnshelve: it.status === 'ON_SALE',
    // 驳回原因要显示出来：不告诉卖家为什么被拒，他只能猜着改
    showReason: audit === 'REJECTED' && !!it.auditReason,
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
    api.listing
      .mine({ page, pageSize: PAGE_SIZE, status: this.data.status })
      .then((res) => {
        const data = (res && res.data) || {};
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
    // 在售且已过审的才有公开详情页；其余状态公开接口会 404，因此给提示而不是跳转
    if (item && item.status === 'ON_SALE' && item.auditStatus === 'APPROVED') {
      wx.navigateTo({ url: '/pages/flea-detail/flea-detail?id=' + id });
    } else {
      wx.showToast({ title: '该闲置尚未公开展示', icon: 'none' });
    }
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
        // 就地更新状态（比整页重拉更跟手，也避免列表滚动位置丢失）
        const items = this.data.items.map((x) =>
          String(x.id) === String(id)
            ? Object.assign({}, x, {
                status: next,
                statusLabel: next === 'ON_SALE' ? '在售' : '已下架',
                canShelve: next === 'OFF_SHELF',
                canUnshelve: next === 'ON_SALE',
              })
            : x
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
