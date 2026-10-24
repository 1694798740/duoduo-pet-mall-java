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

const SPECIES_TEXT = { cat: '猫', dog: '狗', other: '其他', bird: '鸟类', small: '小宠', fish: '水族', reptile: '爬宠' };
const LIFE_TEXT = { BABY: '幼年', ADULT: '成年', SENIOR: '老年', ALL: '全阶段' };

function specTextOf(sku) {
  if (sku.specText) return sku.specText;
  if (sku.spec && typeof sku.spec === 'object') {
    const text = Object.values(sku.spec).join(' / ');
    if (text) return text;
  }
  return sku.skuCode || '默认规格';
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
    /** 话题（第五十六轮）：空格分隔的一行文本，最多 2 个；服务端会再做规范化 */
    shareTopics: '',
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
        // ★ 2026-10-24：主图必须排第一，且真实上传的图片优先于本地占位映射池。
        //   此前只按占位池合成，商家换的主图在轮播里看不到（与 Web 同一处错位）。
        const mainImage = card.mainImage || (skus[0] && skus[0].backendImage) || '';
        const rawImages = Array.isArray(raw.images) ? raw.images.filter((u) => u) : [];
        const hasReal = rawImages.some((u) => /^https?:|^\/uploads\//i.test(u));
        let gallery = hasReal ? rawImages.slice() : images.gallery(this.data.id, mainImage);
        if (mainImage && gallery[0] !== mainImage) {
          gallery = [mainImage].concat(gallery.filter((u) => u !== mainImage));
        }
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
          // 卖家归属：自营商品（self）该值是官方客服账号（咨询归属），入驻商品是归属卖家；
          // 「问一问」跳聊天页要用它做 peerId —— 与服务端定人结果一致（第七十三轮）
          sellerId: card.sellerId || 0,
          // 供货类型（self 自营 / merchant 品牌入驻）：详情页身份标签的判定依据，
          // 与 Web 端 SupplyTag 同一字段同一口径（第七十三轮补齐两端一致）
          supplyType: card.supplyType || 'self',
          // 商品介绍（2026-10-24 起优先 intro；历史商品回落 detail 长文）
          description: raw.intro || raw.detail || '',
          detailHtml: raw.detailHtml || '',
          specParams: Array.isArray(raw.specParams) ? raw.specParams : [],
          packaging: Array.isArray(raw.packaging) ? raw.packaging : [],
          skus,
        };
        this.setData(
          {
            detail,
            loading: false,
            detailImages: images.detailImages(this.data.id),
            // 规格参数的 SPU 级事实源（与 Web 端同一来源）：物种/阶段/过敏原/食品类
            suitability: raw.suitability || {},
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

  /**
   * 规格参数 + 包装规格：与 Web 端详情页逐字段对齐（第七十三轮）。
   *
   * 基础参数取 SPU 级 suitability（品牌/分类/适用宠物/适用阶段/过敏原/食品类），
   * 缺省值与 Web 完全一致（通用/全阶段/未标注/是·否）；之前从 SKU 取物种阶段，
   * 与 Web 的 suitability 口径不同，会 出现「两端同字段不同值」。
   * 包装规格随选中 SKU 变化：spec 键值对 + SKU 编码 + 当前库存。没有的行不编造。
   */
  refreshSkuInfo() {
    const detail = this.data.detail;
    const sku = detail && detail.skus && detail.skus[this.data.selectedSkuIndex];
    if (!detail || !sku) {
      this.setData({ params: [], packageRows: [] });
      return;
    }
    const suit = this.data.suitability || {};
    const species = Array.isArray(suit.species) ? suit.species : [];
    const lifeStages = Array.isArray(suit.lifeStages) ? suit.lifeStages : [];
    const allergens = Array.isArray(suit.allergens) ? suit.allergens : [];

    // 商家填写的规格参数排最前（2026-10-24）：它是商品自己的事实，派生信息只是补充
    const customParams = (detail.specParams || [])
      .filter((x) => x && x.name)
      .map((x) => ({ k: x.name, v: x.value || '—' }));
    const params = customParams.concat([
      { k: '品牌', v: detail.brandName || '—' },
      { k: '分类', v: detail.categoryName || '—' },
      {
        k: '适用宠物',
        v: species.length ? species.map((s) => SPECIES_TEXT[s] || s).join(' / ') : '通用',
      },
      {
        k: '适用阶段',
        v: lifeStages.length ? lifeStages.map((l) => LIFE_TEXT[l] || l).join(' / ') : '全阶段',
      },
      { k: '过敏原', v: allergens.length ? allergens.join('、') : '未标注' },
      { k: '食品类', v: suit.food ? '是（不适用 7 天无理由）' : '否（支持 7 天无理由）' },
    ]);

    const packageRows = (detail.packaging || [])
      .filter((x) => x && x.name)
      .map((x) => ({ k: x.name, v: x.value || '—' }));
    const spec = sku.spec && typeof sku.spec === 'object' ? sku.spec : null;
    if (packageRows.length === 0 && spec) {
      Object.keys(spec).forEach((k) => {
        if (spec[k]) packageRows.push({ k: k, v: spec[k] });
      });
    } else if (packageRows.length === 0) {
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
   * 问一问（第四十七轮商品 / 第七十八轮改道客服）：向**客服**发起商品咨询。
   *
   * <p>为什么不再跳私信：商品咨询属于客服业务（自营归平台客服、入驻归商家），
   * 第七十八轮起有独立通道 /api/support/**，私信列表里也不再出现这类会话。
   * 客服不受「非互关限一条」约束，用户可以把问题问完。
   *
   * <p>自营商品不传 sellerId（服务端落到官方客服账号）；入驻商品带上卖家，
   * 服务端会校验商品确实属于该店铺，防止把 A 店商品塞进 B 店会话。
   */
  askSeller() {
    const detail = this.data.detail;
    if (!detail) return;
    getApp()
      .requireLogin()
      .then(() => {
        const selfGoods =
          detail.supplyType === 'self' || !detail.sellerId || String(detail.sellerId) === '0';
        wx.navigateTo({
          url:
            '/pages/support/support?productId=' +
            this.data.id +
            (selfGoods ? '' : '&sellerId=' + String(detail.sellerId)),
        });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页
        wx.showToast({ title: (err && err.message) || '请先登录', icon: 'none' });
      });
  },

  /**
   * 分享入口（第四十三轮改造，第四十四轮收紧）：底部「分享」按钮改为普通按钮 + 登录校验，
   * 点按先 requireLogin —— 未登录**强制主动登录**（不静默 wx.login，杜绝"被登录"到不明账号）；
   * 已登录弹出分享面板。
   *
   * <p>为什么不能「未登录弹 toast、已登录调起转发」一蹴而就：微信转发面板
   * 只能由 open-type="share" 的按钮**触发**（程序化调不起），所以登录校验
   * 只能做在「进入分享流程」这道门上 —— 校验通过后再展示面板，
   * 面板里的「转发给好友」才是真正的 open-type 按钮。
   */
  onShareTap() {
    getApp()
      .requireLogin()
      .then(() => this.setData({ showSharePanel: true }))
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录页（用户取消/返回时由 cancelLogin 拒绝）
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

  onShareTopicsInput(e) {
    this.setData({ shareTopics: e.detail.value });
  },

  /**
   * 把话题输入框解析成数组：按空白 / # / 顿号 / 逗号切分，去空、取前 2 个。
   *
   * <p>客户端切分只是输入体验（让用户能看到自己打了几个）；
   * **最终以服务端 normalize 的结果为准** —— 服务端会剥 #、去重、限长、丢非法名。
   * 这里即使切错也不会造成脏数据。
   */
  parseTopics() {
    return (this.data.shareTopics || '')
      .split(/[\s#、,，]+/)
      .map((s) => s.trim())
      .filter((s) => !!s)
      .slice(0, 2);
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
      .publish(this.data.id, content, this.parseTopics())
      .then(() => {
        this.setData({ publishing: false, showSharePanel: false, shareContent: '', shareTopics: '' });
        wx.showToast({ title: '已发布到分享圈', icon: 'success' });
      })
      .catch((err) => {
        this.setData({ publishing: false });
        wx.showToast({ title: (err && err.message) || '发布失败', icon: 'none' });
      });
  },

  goShareCircle() {
    this.setData({ showSharePanel: false });
    // 第四十四轮：分享圈升为一级 tab 页，只能 switchTab（navigateTo 对 tab 页无效）
    wx.switchTab({ url: '/pages/share-circle/share-circle' });
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
