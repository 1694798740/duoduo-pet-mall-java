const { getNavMetrics } = require('../../utils/nav.js');

// 自绘导航栏（替代原生导航栏，消除 tab 切换时原生导航栏重建导致的白条闪烁）
// 结构：① 流内占位块（撑开页面内容，避免被固定条遮住）
//       ② 固定条（状态栏高 + 标题栏高，蓝底白字，含可选刷新指示器）
Component({
  properties: {
    title: { type: String, value: '' },
    loading: { type: Boolean, value: false },
  },
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    navTotalHeight: 64,
  },
  lifetimes: {
    attached() {
      const m = getNavMetrics();
      this.setData({
        statusBarHeight: m.statusBarHeight,
        navBarHeight: m.navBarHeight,
        navTotalHeight: m.navTotalHeight,
      });
    },
  },
});
