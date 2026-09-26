// 商品详情：图片轮播 + 规格选择 + 加购/立即购买 + 规格参数/包装规格 + 图文详情 + 评价 + 分享
//
// 后端 GET /api/products/{id} 返回 { card, detail, images, skus, suitability }。
// 图片说明（A14 修复轮）：后端的 main_image / images 是 `/img/products/1001.svg`
// 这类**相对路径 SVG** —— 小程序 <image> 既不能解析相对路径、也不支持 SVG，因此统一经
// utils/images 映射为随包位图；轮播扩到 5 张、图文详情配图另取（第四十二轮）。
//
// 立即购买（第四十二轮）：跳 pages/checkout 确认单（选地址 + 选券），
// 后端同一下单接口带 buySkuId/buyQuantity 走直购 —— 不动用户购物车。
const api = require('../../utils/api');
const images = require('../../utils/images');

const SPECIES_TEXT = { cat: '猫', dog: '犬', bird: '鸟类', small: '小宠', fish: '水族', reptile: '爬宠' };
const LIFE_TEXT = { BABY: '幼年', ADULT: '成年', SENIOR: '老年', ALL: '全阶段' };

function specTextOf(sku) {
  if (sku.specText) return sku.specText;
  if (sku.spec && typeof sku.spec === 'object') {
    const text = Object.values(sku.spec).join(' / ');
    if (text) return text;
  }
  return sku.skuCode || '默认规格';
}

function speciesText(list) {
  const arr = list || [];
  if (!arr.length) return '';
  return arr.map((s) => SPECIES_TEXT[s] || s).join(' / ');
}

function lifeText(list) {
  const arr = list || [];
  if (!arr.length) return '';
  return arr.map((s) => LIFE_TEXT[s] || s).join(' / ');
}

Page({
  data: {
    id: '',
    detail: null,
    loading: false,
    error: '',
    selectedSkuIndex: 0,
    swiperIndex: 0,
    adding: false,
    reviews: [],
    reviewSummary: null,
    // 第四十二轮：规格参数 / 包装规格（真实数据，随选中规格更新）
    params: [],
    packageRows: [],
    detailImages: [],
    // 第四十三轮：分享面板（写评价 → 转发好友 / 发布到分享圈）
    showSharePanel: false,
    shareContent: '',
    publishing: false,
  },

  onLoad(query) {
    this.setData({ id: query.id || '' });
    this.fetchDetail();
  },

  fetchDetail() {
    this.setData({ loading: true, error: '', selectedSkuIndex: 0 });
    api.product
      .detail(this.data.id)
      .then((raw) => {
        const card = raw.card || {};
        const skus = (raw.skus || []).map((s) => ({
          id: s.id,
          specText: specTextOf(s),
          spec: s.spec || null,
          skuCode: s.skuCode || '',
          price: s.price,
          priceText: api.fen2yuan(s.price),
          marketPrice: s.marketPrice,
          imageSrc: images.skuImage(s.id, this.data.id, s.image),
          backendImage: s.image, // 原始下发值留着，交给 images.gallery 判断是否绝对 URL
          stock: s.stock,
          sellable: s.sellable !== false,
          species: s.species,
          lifeStages: s.lifeStages,
          allergens: s.allergens,
        }));
        const gallery = images.gallery(this.data.id, card.mainImage || (skus[0] && skus[0].backendImage));
        const detail = {
          title: card.title || '商品',
          subtitle: card.subtitle || '',
          gallery: gallery,
          minPriceText: api.fen2yuan(card.minPrice || (skus.length ? skus[0].price : 0)),
          salesCount: card.salesCount || 0,
          rating: card.rating,
          ratingCount: card.ratingCount,
          inStock: card.inStock !== false,
          brandName: card.brandName || '',
          categoryName: card.categoryName || '',
          // 后端的 detail 是介绍长文本
          description: raw.detail || '',
          skus,
        };
        this.setData(
          {
            detail,
            loading: false,
            detailImages: images.detailImages(this.data.id),
          },
          () => this.refreshSkuInfo()
        );
        // 评价静默加载（失败不打扰主流程）
        api.product
          .reviewSummary(this.data.id)
          .then((s) => this.setData({ reviewSummary: s }))
          .catch(() => {});
        api.product
          .reviews(this.data.id, 1, 5)
          .then((data) => this.setData({ reviews: (data && data.items) || [] }))
          .catch(() => {});
      })
      .catch((err) => {
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  /** 规格参数 + 包装规格：只展示后端真实下发的字段，没有的行不出（不编造） */
  refreshSkuInfo() {
    const detail = this.data.detail;
    const sku = detail && detail.skus && detail.skus[this.data.selectedSkuIndex];
    if (!detail || !sku) {
      this.setData({ params: [], packageRows: [] });
      return;
    }
    const params = [];
    if (detail.brandName) params.push({ k: '品牌', v: detail.brandName });
    if (detail.categoryName) params.push({ k: '分类', v: detail.categoryName });
    const sp = speciesText(sku.species);
    if (sp) params.push({ k: '适用宠物', v: sp });
    const lf = lifeText(sku.lifeStages);
    if (lf) params.push({ k: '适用阶段', v: lf });
    if (sku.allergens) params.push({ k: '过敏原提示', v: sku.allergens });

    const packageRows = [];
    const spec = sku.spec && typeof sku.spec === 'object' ? sku.spec : null;
    if (spec) {
      Object.keys(spec).forEach((k) => {
        if (spec[k]) packageRows.push({ k: k, v: spec[k] });
      });
    } else {
      packageRows.push({ k: '规格', v: sku.specText });
    }
    if (sku.skuCode) packageRows.push({ k: 'SKU 编码', v: sku.skuCode });
    packageRows.push({ k: '当前库存', v: sku.stock > 0 ? sku.stock + ' 件' : '补货中' });

    this.setData({ params: params, packageRows: packageRows });
  },

  selectSku(e) {
    this.setData({ selectedSkuIndex: Number(e.currentTarget.dataset.index) }, () => this.refreshSkuInfo());
  },

  /** 轮播切换时把当前图同步给下方缩略图条 */
  onSwiperChange(e) {
    this.setData({ swiperIndex: e.detail.current });
  },

  previewImage(e) {
    const urls = (this.data.detail && this.data.detail.gallery) || [];
    if (!urls.length) return;
    wx.previewImage({ current: e.currentTarget.dataset.src, urls });
  },

  currentSku() {
    const skus = (this.data.detail && this.data.detail.skus) || [];
    return skus[this.data.selectedSkuIndex];
  },

  addCart() {
    const sku = this.currentSku();
    if (!sku) {
      wx.showToast({ title: '该商品暂无可售规格', icon: 'none' });
      return;
    }
    if (this.data.adding) return;
    getApp()
      .ensureLogin()
      .then(() => {
        if (!sku.sellable || sku.stock <= 0) {
          wx.showToast({ title: '该规格暂无库存', icon: 'none' });
          return;
        }
        this.setData({ adding: true });
        return api.cart
          .add(sku.id, 1)
          .then(() => {
            this.setData({ adding: false });
            wx.showToast({ title: '已加入购物车', icon: 'success' });
          })
          .catch((err) => {
            this.setData({ adding: false });
            wx.showToast({ title: (err && err.message) || '加购失败', icon: 'none' });
          });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  /** 立即购买：进确认单（选地址 + 选券 → 提交 → 收银台），不走购物车 */
  buyNow() {
    const sku = this.currentSku();
    if (!sku) {
      wx.showToast({ title: '该商品暂无可售规格', icon: 'none' });
      return;
    }
    if (!sku.sellable || sku.stock <= 0) {
      wx.showToast({ title: '该规格暂无库存', icon: 'none' });
      return;
    }
    getApp()
      .ensureLogin()
      .then(() => {
        wx.navigateTo({
          url:
            '/pages/checkout/checkout?spuId=' +
            this.data.id +
            '&skuId=' +
            sku.id +
            '&quantity=1',
        });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  askAi() {
    const title = this.data.detail ? this.data.detail.title : '';
    wx.navigateTo({
      url: '/pages/ai/ai?prefill=' + encodeURIComponent('帮我看看「' + title + '」值不值得买？'),
    });
  },

  /**
   * 分享入口（第四十三轮改造）：底部「分享」按钮改为普通按钮 + 登录校验，
   * 点按先 ensureLogin —— 未登录直接被引导登录；已登录弹出分享面板。
   *
   * <p>为什么不能「未登录弹 toast、已登录调起转发」一蹴而就：微信转发面板
   * 只能由 open-type="share" 的按钮**触发**（程序化调不起），所以登录校验
   * 只能做在「进入分享流程」这道门上 —— 校验通过后再展示面板，
   * 面板里的「转发给好友」才是真正的 open-type 按钮。
   */
  onShareTap() {
    getApp()
      .ensureLogin()
      .then(() => this.setData({ showSharePanel: true }))
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  closeSharePanel() {
    this.setData({ showSharePanel: false });
  },

  /** 阻止面板内点击冒泡到遮罩（遮罩 bindtap 负责关闭） */
  noop() {},

  onShareContentInput(e) {
    this.setData({ shareContent: e.detail.value });
  },

  /** 转发给好友：open-type=share 按钮的伴随点击，只负责收起面板（转发由微信接管） */
  onForwardTap() {
    this.setData({ showSharePanel: false });
  },

  /** 发布到分享圈：内容必填，走 POST /api/shares（需登录，快照由服务端取事实） */
  publishShare() {
    const content = (this.data.shareContent || '').trim();
    if (!content) {
      wx.showToast({ title: '先写点评价再发布吧', icon: 'none' });
      return;
    }
    if (this.data.publishing) return;
    this.setData({ publishing: true });
    api.share
      .publish(this.data.id, content)
      .then(() => {
        this.setData({ publishing: false, showSharePanel: false, shareContent: '' });
        wx.showToast({ title: '已发布到分享圈', icon: 'success' });
      })
      .catch((err) => {
        this.setData({ publishing: false });
        wx.showToast({ title: (err && err.message) || '发布失败', icon: 'none' });
      });
  },

  goShareCircle() {
    this.setData({ showSharePanel: false });
    wx.navigateTo({ url: '/pages/share-circle/share-circle' });
  },

  /**
   * 分享给好友/群（社交裂变入口）：卡片标题带价格，封面用商品首图（真机有效）。
   *
   * <p>第四十三轮修复「查看分享页无响应」：落点从商品页自身改为**分享落地页**
   * share-detail（?spuId=）—— 旧落点就是当前页，点开卡片等于刷新原页，看起来毫无反应。
   */
  onShareAppMessage() {
    const detail = this.data.detail || {};
    const title =
      (detail.title || '多多宠物商店') + (detail.minPriceText ? '｜¥' + detail.minPriceText : '');
    return {
      title: title,
      path: '/pages/share-detail/share-detail?spuId=' + this.data.id,
      imageUrl: (detail.gallery && detail.gallery[0]) || '/images/share/cover.jpg',
    };
  },

  /** 分享到朋友圈（右上角菜单 → 分享到朋友圈） */
  onShareTimeline() {
    const detail = this.data.detail || {};
    return {
      title: (detail.title || '多多宠物商店') + '｜来多多宠物商店看看',
      query: 'id=' + this.data.id,
      imageUrl: (detail.gallery && detail.gallery[0]) || '/images/share/cover.jpg',
    };
  },
});
