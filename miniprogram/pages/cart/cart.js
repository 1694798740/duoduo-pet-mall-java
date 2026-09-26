// 购物车：勾选 / 数量步进 / 删除 / 结算（A8 语义：下单用默认地址 addressId，服务端写收货快照）
//
// A14 修复要点：
//  * 「合计」改用**服务端的结算口径**（selectedAmount / selectedCount）—— 后端下单只结已勾选
//    且有效的条目，前端若把所有条目都加起来，会出现「页面显示有金额、下单却说购物车为空」；
//  * 失效条目（下架/售罄/库存不足）单独标注原因并禁止勾选，不再混进合计；
//  * 删除前二次确认（此前只有「数量减到 0」才有确认）。
const api = require('../../utils/api');
const images = require('../../utils/images');

const REASON_LABEL = {
  SKU_NOT_FOUND: '商品已不存在',
  OFF_SHELF: '已下架',
  OUT_OF_STOCK: '已售罄',
  STOCK_NOT_ENOUGH: '库存不足',
};

Page({
  data: {
    // 收货地址：默认取「默认地址」，也可在结算前临时换（pick 模式）
    address: null,
    addressText: '',
    items: [],
    loading: false,
    error: '',
    ordering: false,
    selectedAmountText: '0',
    selectedCount: 0,
    // 优惠券（第三十九轮）：服务端判定可用性（门槛/有效期/锁定都在它那），默认选服务端推荐的最优券
    chosenCoupon: null,
    couponText: '不使用',
    discountText: '',
    hasUsableCoupon: false,
  },

  onShow() {
    // tab 页：每次进入都刷新（加购后回到本页要看到最新数据）
    this.fetchCart();
  },

  fetchCart() {
    this.setData({ loading: true, error: '' });
    return api
      .ensureSession()
      .then(() => api.cart.view())
      .then((view) => {
        const items = ((view && view.items) || []).map((it) =>
          Object.assign({}, it, {
            imageSrc: images.skuImage(it.skuId, it.spuId, it.image),
            priceText: api.fen2yuan(it.price),
            amountText: api.fen2yuan(it.amount),
            reasonText: it.valid ? '' : REASON_LABEL[it.invalidReason] || '已失效',
          })
        );
        this.setData({
          items,
          loading: false,
          selectedAmountText: api.fen2yuan(view ? view.selectedAmount : 0),
          selectedCount: (view && view.selectedCount) || 0,
        });
        // 地址独立刷新：失败不影响购物车主体
        this.fetchAddress();
        // 可用券依赖勾选金额：金额变了可用性就变了（静默失败，不影响结算）
        this.refreshCoupons((view && view.selectedAmount) || 0);
      })
      .catch((err) => {
        if (err && err.needLogin) {
          this.setData({ items: [], loading: false, error: '' });
          return;
        }
        this.setData({ loading: false, error: (err && err.message) || '加载失败' });
      });
  },

  /** 拉默认地址（用户没手动选过时用它） */
  fetchAddress() {
    return api.address
      .default()
      .then((addr) => {
        if (!this.data.address && addr) this.applyAddress(addr);
        return addr;
      })
      .catch(() => null);
  },

  applyAddress(addr) {
    this.setData({
      address: addr || null,
      addressText: addr
        ? (addr.receiverName || '') + '  ' + (addr.receiverPhone || '') + '\n' + (addr.fullAddress || '')
        : '',
    });
  },

  /** 打开地址簿「选择模式」，选中后回填（本次结算就用它） */
  chooseAddress() {
    wx.navigateTo({
      url: '/pages/address/address?pick=1',
      events: {
        pickAddress: (addr) => {
          this.applyAddress(addr);
          wx.showToast({ title: '已切换收货地址', icon: 'none' });
        },
      },
    });
  },

  // ── 优惠券（第三十九轮）：服务端判定可用，默认选它推荐的最优券 ──────────────
  refreshCoupons(selectedAmount) {
    this._selectedAmountFen = selectedAmount || 0;
    if (!selectedAmount) {
      // 没勾选商品谈不上门槛：清掉选择，避免「选了券却不能用于这笔订单」
      this.applyCoupon(null);
      return Promise.resolve();
    }
    return api.coupon
      .usable(selectedAmount)
      .then((result) => {
        const coupons = (result && result.coupons) || [];
        const usable = coupons.filter((c) => c.usable);
        this._usableCoupons = usable;
        this.setData({ hasUsableCoupon: usable.length > 0 });
        // 用户明确「不使用」优先 —— 不自动选回；否则默认选服务端推荐的最优券
        // （「默认帮用户省钱」只该有服务端一个实现）；已选的券若因金额变化变得
        // 不可用，自动退掉
        if (this._couponDeclined) {
          this.applyCoupon(null);
        } else if (result && result.bestCouponCode) {
          const best = usable.find((c) => c.code === result.bestCouponCode) || null;
          this.applyCoupon(best);
        } else if (this.data.chosenCoupon) {
          const still = usable.find((c) => c.code === this.data.chosenCoupon.code);
          if (!still) this.applyCoupon(null);
        }
      })
      .catch(() => {});
  },

  applyCoupon(coupon) {
    this.setData({
      chosenCoupon: coupon,
      couponText: coupon ? coupon.name + '（抵 ' + api.fen2yuan(coupon.discountAmount) + ' 元）' : '不使用',
      discountText: coupon ? '券抵扣 -' + api.fen2yuan(coupon.discountAmount) : '',
    });
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

  toggleSelect(e) {
    const skuId = e.currentTarget.dataset.sku;
    const item = this.data.items.find((it) => String(it.skuId) === String(skuId));
    if (!item) return;
    if (!item.valid) {
      wx.showToast({ title: item.reasonText || '该商品已失效', icon: 'none' });
      return;
    }
    api.cart
      .setSelection(skuId, !item.selected)
      .then(() => this.fetchCart())
      .catch(api.toastError);
  },

  inc(e) {
    this.changeQty(e.currentTarget.dataset.sku, 1);
  },

  dec(e) {
    this.changeQty(e.currentTarget.dataset.sku, -1);
  },

  changeQty(skuId, delta) {
    const item = this.data.items.find((it) => String(it.skuId) === String(skuId));
    if (!item) return;
    const next = Number(item.quantity) + delta;
    if (next < 1) {
      this.confirmRemove(skuId);
      return;
    }
    api.cart
      .updateQuantity(skuId, next)
      .then(() => this.fetchCart())
      .catch(api.toastError);
  },

  removeItem(e) {
    this.confirmRemove(e.currentTarget.dataset.sku);
  },

  confirmRemove(skuId) {
    wx.showModal({
      title: '移除商品',
      content: '确定把这个商品从购物车移除吗？',
      confirmColor: '#e5484d',
      success: (res) => {
        if (!res.confirm) return;
        api.cart
          .remove(skuId)
          .then(() => this.fetchCart())
          .catch(api.toastError);
      },
    });
  },

  placeOrder() {
    if (this.data.ordering) return;
    if (!this.data.items.length) return;
    if (this.data.selectedCount <= 0) {
      // 结算口径是「已勾选」，一条都没勾选时后端会直接拒单，这里先说清楚
      wx.showToast({ title: '请先勾选要结算的商品', icon: 'none' });
      return;
    }
    this.setData({ ordering: true });
    // 优先用页面上选中的地址；没有则回落到默认地址（两者都没有才引导去添加）
    Promise.resolve(this.data.address || null)
      .then((picked) => picked || api.address.default())
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
        // 只传券码，折扣由服务端锁券时计算（与 Web 端同一口径）；下单成功券即被核销
        return api.order.place(addr.id, this.data.chosenCoupon ? this.data.chosenCoupon.code : undefined).then((result) => {
          const count = (result && result.orderCount) || 1;
          wx.showToast({ title: count > 1 ? '已生成 ' + count + ' 个订单' : '下单成功', icon: 'success' });
          // 已结算条目被服务端清出购物车，先刷新再跳订单页；券已核销，选择一并清掉
          this._couponDeclined = false;
          this.applyCoupon(null);
          this.fetchCart();
          setTimeout(() => wx.switchTab({ url: '/pages/orders/orders' }), 600);
          return null;
        });
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '下单失败', icon: 'none' });
      })
      .then(() => this.setData({ ordering: false }));
  },
});
