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
    // 先用「当前已选券」把三行金额整体刷一遍，再让服务端覆盖券的选择。
    // 为什么不能只等服务端返回：请求未回来之前金额会停在旧值（首屏就是初值 0）。
    this.syncAmounts(amountFen, this.data.chosenCoupon);
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
          // 服务端推荐的券理论上一定在可用列表里；万一不在，退回「不使用」
          // 而不是什么都不做 —— 什么都不做会让金额停在上一次的券上。
          this.applyCoupon(best || null);
        } else {
          // ★ 最常见的一支：服务端没有推荐券（用户手上没有可用券 / 未达门槛 / 未登录）。
          // 此前这里写的是 `else if (this.data.chosenCoupon)`，把「未选券 + 无推荐券」
          // 这条最常见的状态整个漏掉了 —— 于是 applyCoupon 一次都不调用，
          // 「应付」永远停在 data 初值 '0'，页面上出现「商品金额 189 / 应付 0」的自相矛盾。
          const still = this.data.chosenCoupon
            ? usable.find((c) => c.code === this.data.chosenCoupon.code) || null
            : null;
          this.applyCoupon(still);
        }
      })
      // 券接口失败（401 / 超时）也必须把金额刷成「不使用券」的正确值：
      // 只吞异常不刷新，页面上同样会留下「商品金额 189 / 应付 0」。
      .catch(() => this.applyCoupon(null));
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

  /**
   * 金额行的**唯一写入口**：商品金额 / 优惠抵扣 / 应付三行必须一起更新。
   *
   * <p>为什么必须收成一个入口：此前 `payText` 只有 `applyCoupon()` 会写、`recalc()`
   * 只写 `amountText`，于是「既没用券、服务端也没推荐券」这条最常见的分支一旦走到，
   * 应付就永远停在 data 初值 `'0'`（截图里的「商品金额 ¥189 / 应付 ¥0」）。
   * 三个数同源同写，才不会出现「某个分支忘了写某一个」。
   *
   * <p>金额一律由 `amountFen` 现算，不从 data 里回读 `amountText` ——
   * 文案是给人看的，拿它当计算输入会在四舍五入处失准。
   */
  syncAmounts(amountFen, coupon) {
    const amount = amountFen || 0;
    const discountFen = (coupon && coupon.discountAmount) || 0;
    this.setData({
      amountFen: amount,
      amountText: api.fen2yuan(amount),
      chosenCoupon: coupon || null,
      couponText: coupon ? coupon.name : '不使用',
      discountText: coupon ? '-¥' + api.fen2yuan(discountFen) : '',
      payText: api.fen2yuan(Math.max(0, amount - discountFen)),
    });
  },

  applyCoupon(coupon) {
    this.syncAmounts(this.data.amountFen, coupon);
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
