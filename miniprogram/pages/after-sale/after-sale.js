// 售后申请：选择类型 + 填写诉求 → 提交
//
// 后端契约（履约域 A3）：POST /api/aftersales {orderNo, type, reason, images[]}
//   · type：REFUND_ONLY（仅退款）/ RETURN_REFUND（退货退款）
//   · reason：≤ 500 字（本地校验与后端一致，避免"本地过了、后端 400"）
//   · images：可空，≤6 张（与后端 duoduo.aftersale.max-images 一致）——
//     本页支持直接拍照/选图上传（POST /api/uploads，A15），返回 http(s) 直链随单提交；
//     上传失败不阻塞提交（图片可后补，但小程序端没有后补入口，所以失败时明确告知）。
//   · 重复规则：唯一键 (订单, 类型) → 同订单同类型重复提交 409「已立案」；
//     不同类型允许各提一次，因此本地按「已提交类型」过滤掉不可选项，而不是一刀切不给入口。
const api = require('../../utils/api');

const TYPES = [
  { key: 'REFUND_ONLY', label: '仅退款', desc: '没收到货 / 不想要了' },
  { key: 'RETURN_REFUND', label: '退货退款', desc: '已收到货，需要退回' },
];

const REASON_MAX = 500;
const IMAGES_MAX = 6;

Page({
  data: {
    orderNo: '',
    types: TYPES,
    type: '',
    reason: '',
    reasonMax: REASON_MAX,
    submitting: false,
    loading: true,
    error: '',
    // 该订单已提交的售后单（同类型不能重复，不同类型还能提）
    existingList: [],
    // 还可以申请的类型（从 TYPES 里剔除已提交过的）
    remainingTypes: TYPES,
    // 举证图：[{path, url, failed}] —— path 本地预览，url 上传成功后的直链，failed 上传失败标记。
    // 单数组而不是 path/url 两个并行数组：并行数组靠 indexOf 对位，同名路径或中途移除都会错位。
    images: [],
    uploadingCount: 0,
    imagesMax: IMAGES_MAX,
  },

  onLoad(query) {
    this.setData({ orderNo: (query && query.orderNo) || '' });
    this.checkExisting();
  },

  /** 查这个订单提过哪些类型的售后，把还能申请的类型筛出来 */
  checkExisting() {
    if (!this.data.orderNo) {
      this.setData({ loading: false, error: '缺少订单号' });
      return Promise.resolve();
    }
    this.setData({ loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.aftersale.mine(1, 50))
      .then((data) => {
        const list = (data && data.items) || [];
        const hits = list.filter((a) => a.orderNo === this.data.orderNo);
        const filedTypes = hits.map((a) => a.type);
        const remainingTypes = TYPES.filter((t) => filedTypes.indexOf(t.key) < 0);
        this.setData({
          existingList: hits,
          remainingTypes,
          type: remainingTypes.length > 0 ? remainingTypes[0].key : '',
          loading: false,
        });
      })
      .catch((err) => {
        // 查不到已有售后不影响提交（后端唯一键兜底 409），只是少一层提醒
        this.setData({ loading: false, error: (err && err.message) || '' });
      });
  },

  pickType(e) {
    this.setData({ type: e.currentTarget.dataset.key });
  },

  // ── 举证图：选图 → 逐张上传 → 预览/删除 ────────────────────────────────
  /** 张数上限按「已选（含上传中/失败）」计，超了不让再选 */
  addImage() {
    const used = this.data.images.length;
    if (used >= IMAGES_MAX) {
      wx.showToast({ title: '最多 ' + IMAGES_MAX + ' 张', icon: 'none' });
      return;
    }
    wx.chooseMedia({
      count: IMAGES_MAX - used,
      mediaType: ['image'],
      sizeType: ['compressed'],
      success: (res) => {
        const files = (res.tempFiles || []).map((f) => f.tempFilePath).filter(Boolean);
        if (files.length === 0) return;
        this.uploadImages(files);
      },
    });
  },

  /**
   * 逐张上传，按「入队时的下标」回写结果（下标在入队时就定死，不依赖 indexOf）。
   * 失败的图不挪位、不移除：原地标「上传失败」，由用户删除 —— 中途 splice 会让
   * 其它仍在途的上传回写错位，比留一张失败图糟糕得多。
   */
  uploadImages(files) {
    const startIdx = this.data.images.length;
    const items = files.map((path) => ({ path, url: '', failed: false }));
    this.setData({
      images: this.data.images.concat(items),
      uploadingCount: this.data.uploadingCount + files.length,
    });
    files.forEach((filePath, i) => {
      const idx = startIdx + i;
      api
        .upload(filePath)
        .then((data) => {
          const patch = { uploadingCount: this.data.uploadingCount - 1 };
          patch['images[' + idx + '].url'] = (data && data.url) || '';
          this.setData(patch);
        })
        .catch((err) => {
          const patch = {
            uploadingCount: this.data.uploadingCount - 1,
            ['images[' + idx + '].failed']: true,
          };
          this.setData(patch);
          wx.showToast({ title: (err && err.message) || '图片上传失败', icon: 'none' });
        });
    });
  },

  removeImage(e) {
    const at = e.currentTarget.dataset.index;
    const images = this.data.images.slice();
    images.splice(at, 1);
    this.setData({ images });
  },

  previewImage(e) {
    const current = e.currentTarget.dataset.src;
    if (!current) return; // 还在上传中的占位不给点开
    wx.previewImage({ current, urls: this.data.images.map((it) => it.url || it.path) });
  },

  onReason(e) {
    this.setData({ reason: e.detail.value });
  },

  submit() {
    if (this.data.submitting) return;
    // 表单只在还有可申请类型时渲染，这里再兜一层（防异步竞态下类型已被提走）
    if (this.data.remainingTypes.length === 0) return;
    if (!this.data.type) {
      wx.showToast({ title: '请选择售后类型', icon: 'none' });
      return;
    }
    if (this.data.uploadingCount > 0) {
      wx.showToast({ title: '图片还在上传，请稍候', icon: 'none' });
      return;
    }
    // 只提交上传成功的直链；有占位/失败图说明有图没传上，明确拦下而不是悄悄丢证据
    const uploaded = this.data.images.map((it) => it.url).filter(Boolean);
    if (uploaded.length < this.data.images.length) {
      wx.showToast({ title: '有图片未上传成功，请删除后重试', icon: 'none' });
      return;
    }
    const reason = (this.data.reason || '').trim();
    if (!reason) {
      wx.showToast({ title: '请填写售后诉求', icon: 'none' });
      return;
    }
    if (reason.length > REASON_MAX) {
      wx.showToast({ title: '诉求不能超过 ' + REASON_MAX + ' 字', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    api.aftersale
      .create(this.data.orderNo, this.data.type, reason, uploaded)
      .then(() => {
        this.setData({ submitting: false });
        wx.showModal({
          title: '已提交售后',
          content: '卖家会尽快处理，处理进度可在订单详情查看。',
          showCancel: false,
          confirmText: '知道了',
          complete: () => wx.navigateBack(),
        });
      })
      .catch((err) => {
        this.setData({ submitting: false });
        wx.showToast({ title: (err && err.message) || '提交失败', icon: 'none' });
      });
  },
});
