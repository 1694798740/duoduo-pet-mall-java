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
        return api.order.place(addr.id).then((result) => {
          const count = (result && result.orderCount) || 1;
          wx.showToast({ title: count > 1 ? '已生成 ' + count + ' 个订单' : '下单成功', icon: 'success' });
          // 已结算条目被服务端清出购物车，先刷新再跳订单页
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
