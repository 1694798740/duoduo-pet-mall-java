// 结算确认页（「立即购买」下单流程）
//
// 与购物车结算的关系：**同一套后端下单接口**（POST /api/orders），唯一差别是带上
// buySkuId + buyQuantity 走「直购」——不读购物车、下单后也不清购物车。
//
// 为什么单独一页而不是复用购物车：立即购买不该把商品塞进用户购物车（那是副作用），
// 而地址与券的选择交互与购物车一致（同一套 address pick 模式 + 服务端可用券接口）。
const api = require('../../utils/api');
const images = require('../../utils/images');
const pay = require('../../utils/pay');

const MAX_QTY = 200;

Page({
  data: {
    spuId: '',
    skuId: '',
    quantity: 1,
    sku: null,
    loading: false,
    error: '',
    submitting: false,
    address: null,
    amountFen: 0,
    amountText: '0',
    chosenCoupon: null,
    couponText: '不使用',
    discountText: '',
    payText: '0',
  },

  onLoad(query) {
    this.setData({
      spuId: query.spuId || '',
      skuId: query.skuId || '',
      quantity: Math.max(1, Math.min(MAX_QTY, Number(query.quantity) || 1)),
    });
    this.fetchDetail();
    this.fetchAddress();
  },

  fetchDetail() {
    this.setData({ loading: true, error: '' });
    api.product
      .detail(this.data.spuId)
      .then((raw) => {
        const skus = (raw && raw.skus) || [];
        const sku = skus.find((s) => String(s.id) === String(this.data.skuId)) || skus[0];
        if (!sku) {
          this.setData({ loading: false, error: '该规格已下架，请返回重新选择' });
          return;
        }
        const specText =
          sku.specText ||
          (sku.spec && typeof sku.spec === 'object' ? Object.values(sku.spec).join(' / ') : '') ||
          sku.skuCode ||
          '默认规格';
        const card = (raw && raw.card) || {};
        const view = {
          skuId: sku.id,
          specText,
          priceFen: sku.price,
          priceText: api.fen2yuan(sku.price),
          title: card.title || '商品',
          imageSrc: images.skuImage(sku.id, this.data.spuId, sku.image),
          stock: sku.stock,
          sellable: sku.sellable !== false,
        };
        // skuId 以服务端为准（首次进入可能是默认第一个规格）
        this.setData({ sku: view, skuId: view.skuId, loading: false }, () => this.recalc());
      })
      .catch((err) => {
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  fetchAddress() {
    api.address
      .default()
      .then((addr) => {
        if (addr) this.setData({ address: addr });
      })
      .catch(() => {});
  },

  // ── 数量 ────────────────────────────────────────────────────────────────
  inc() {
    const max = Math.min(MAX_QTY, (this.data.sku && this.data.sku.stock) || MAX_QTY);
    if (this.data.quantity >= max) {
      wx.showToast({ title: '已达可购买上限', icon: 'none' });
      return;
    }
    this.setData({ quantity: this.data.quantity + 1 }, () => this.recalc());
  },

  dec() {
    if (this.data.quantity <= 1) return;
    this.setData({ quantity: this.data.quantity - 1 }, () => this.recalc());
  },

  /** 金额变化 → 重新判定券可用性（门槛是按金额算的，服务端说了算） */
  recalc() {
    const sku = this.data.sku;
    if (!sku) return;
    const amountFen = (sku.priceFen || 0) * this.data.quantity;
    this.setData({ amountFen, amountText: api.fen2yuan(amountFen) });
    this.refreshCoupons(amountFen);
  },

  // ── 收货地址：复用地址簿的选择模式 ──────────────────────────────────────
  chooseAddress() {
    wx.navigateTo({
      url: '/pages/address/address?pick=1',
      events: {
        pickAddress: (addr) => {
          this.setData({ address: addr });
          wx.showToast({ title: '已切换收货地址', icon: 'none' });
        },
      },
    });
  },

  // ── 优惠券：可用性判定全在服务端 ────────────────────────────────────────
  refreshCoupons(amountFen) {
    if (!amountFen) {
      this.applyCoupon(null);
      return Promise.resolve();
    }
    return api.coupon
      .usable(amountFen)
      .then((result) => {
        const usable = ((result && result.coupons) || []).filter((c) => c.usable);
        this._usableCoupons = usable;
        if (this._couponDeclined) {
          // 用户明确选过「不使用」—— 不被自动推荐覆盖
          this.applyCoupon(null);
        } else if (result && result.bestCouponCode) {
          const best = usable.find((c) => c.code === result.bestCouponCode);
          if (best) this.applyCoupon(best);
        } else if (this.data.chosenCoupon) {
          // 已选券在新金额下不再可用 → 退回未选
          const still = usable.find((c) => c.code === this.data.chosenCoupon.code);
          this.applyCoupon(still || null);
        }
      })
      .catch(() => {});
  },

  chooseCoupon() {
    const usable = this._usableCoupons || [];
    if (usable.length === 0) {
      wx.showToast({ title: '暂无可用优惠券', icon: 'none' });
      return;
    }
    const labels = ['不使用优惠券'].concat(
      usable.map((c) => c.name + '（抵 ' + api.fen2yuan(c.discountAmount) + ' 元）')
    );
    wx.showActionSheet({
      itemList: labels,
      success: (res) => {
        if (res.tapIndex === 0) {
          this._couponDeclined = true;
          this.applyCoupon(null);
          return;
        }
        const picked = usable[res.tapIndex - 1];
        if (picked) {
          this._couponDeclined = false;
          this.applyCoupon(picked);
        }
      },
    });
  },

  applyCoupon(coupon) {
    const discountFen = (coupon && coupon.discountAmount) || 0;
    const payFen = Math.max(0, this.data.amountFen - discountFen);
    this.setData({
      chosenCoupon: coupon || null,
      couponText: coupon ? coupon.name : '不使用',
      discountText: coupon ? '-¥' + api.fen2yuan(discountFen) : '',
      payText: api.fen2yuan(payFen),
    });
  },

  // ── 提交订单 → 直接进收银台 ─────────────────────────────────────────────
  submit() {
    if (this.data.submitting) return;
    const sku = this.data.sku;
    if (!sku) return;
    if (!sku.sellable || sku.stock <= 0) {
      wx.showToast({ title: '该规格暂无库存', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    getApp()
      .ensureLogin()
      .then(() => Promise.resolve(this.data.address || null).then((picked) => picked || api.address.default()))
      .then((addr) => {
        if (!addr) {
          wx.showModal({
            title: '还没有收货地址',
            content: '先添加一个收货地址，就可以直接下单了。',
            confirmText: '去添加',
            cancelText: '取消',
            success: (res) => {
              if (res.confirm) wx.navigateTo({ url: '/pages/address/address' });
            },
          });
          return null;
        }
        return api.order
          .place(
            addr.id,
            this.data.chosenCoupon ? this.data.chosenCoupon.code : undefined,
            { skuId: sku.skuId, quantity: this.data.quantity }
          )
          .then((result) => {
            const orders = (result && result.orders) || [];
            const first = orders[0];
            if (!first) {
              wx.showToast({ title: '下单成功', icon: 'success' });
              setTimeout(() => wx.redirectTo({ url: '/pages/orders/orders' }), 600);
              return null;
            }
            // 下单即成单：直接发起支付并进收银台，支付成功/超时都回到订单列表
            return pay
              .checkout(first.orderNo, this, {
                onSuccess: () => {
                  wx.showToast({ title: '支付成功', icon: 'success' });
                },
                onTimeout: () => {
                  wx.showToast({ title: '支付未完成，可在订单页继续支付', icon: 'none' });
                },
              })
              .then(() => {
                // 收银台用 navigateTo 叠在本页之上，返回时本页已无意义 → 换到订单列表
                // （第四十五轮：orders 回归普通页面，navigateTo 即可）
                setTimeout(() => wx.redirectTo({ url: '/pages/orders/orders' }), 800);
              });
          });
      })
      .catch((err) => {
        if (err && err.needLogin) return; // 已引导登录
        wx.showToast({ title: (err && err.message) || '下单失败', icon: 'none' });
      })
      .then(() => this.setData({ submitting: false }));
  },
});
