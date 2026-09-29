const { getNavMetrics } = require('../../utils/nav.js');

// 自绘导航栏（替代原生导航栏，消除 tab 切换时原生导航栏重建导致的白条闪烁）
// 结构：① 流内占位块（撑开页面内容，避免被固定条遮住）
//       ② 固定条（状态栏高 + 标题栏高，蓝底白字，含可选刷新指示器）
//       ③ 左侧返回键（back 属性开启；非 tab 页推进式打开必须保留返回路径）
//       ④ 右侧命名插槽 slot="right"（页面需要自定义右侧内容时才用）
//
// 第五十四轮：删除了原内置的右上角通知图标。理由是入口重复 ——
// tabBar 里已有「通知」一级入口，导航栏再放一个指向同一页面的图标，
// 用户会以为是两个不同功能（而点进去是同一页），属于纯粹的信息噪声。
Component({
  options: {
    multipleSlots: true,
  },
  properties: {
    title: { type: String, value: '' },
    loading: { type: Boolean, value: false },
    /** 显示左上角返回键（非 tab 页用） */
    back: { type: Boolean, value: false },
  },
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    navTotalHeight: 64,
    navRightGap: 96, // 右侧插槽距屏幕右缘的间距（px），attached 时按胶囊实算
  },
  lifetimes: {
    attached() {
      const m = getNavMetrics();
      let navRightGap = 96;
      try {
        const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
        const rect = wx.getMenuButtonBoundingClientRect();
        // 图标右缘贴在胶囊左边缘再留 8px 间隙：胶囊左边缘到屏幕右缘 = 宽 + 右边距
        if (info && info.windowWidth && rect && rect.left) {
          // 插槽右缘贴在胶囊左边缘再留 8px 间隙：胶囊左边缘到屏幕右缘 = 宽 + 右边距
        navRightGap = Math.max(info.windowWidth - rect.left + 8, 48);
        }
      } catch (e) {}
      this.setData({
        statusBarHeight: m.statusBarHeight,
        navBarHeight: m.navBarHeight,
        navTotalHeight: m.navTotalHeight,
        navRightGap,
      });
    },
  },
  methods: {
    /** 返回键：优先回退上一页；已在页面栈底（如从转发卡片直进）则回首页 */
    goBack() {
      wx.navigateBack({
        fail: () => {
          wx.switchTab({ url: '/pages/index/index' });
        },
      });
    },
  },
});
