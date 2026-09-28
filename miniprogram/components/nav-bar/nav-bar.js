const { getNavMetrics } = require('../../utils/nav.js');

// 自绘导航栏（替代原生导航栏，消除 tab 切换时原生导航栏重建导致的白条闪烁）
// 结构：① 流内占位块（撑开页面内容，避免被固定条遮住）
//       ② 固定条（状态栏高 + 标题栏高，蓝底白字，含可选刷新指示器）
//       ③ 右侧命名插槽（第四十八轮）：放置页面级动作图标（如「我的」页的私信图标），
//         定位在微信胶囊按钮左侧 —— 需 multipleSlots 才能用命名 slot。
Component({
  options: {
    multipleSlots: true,
  },
  properties: {
    title: { type: String, value: '' },
    loading: { type: Boolean, value: false },
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
});
