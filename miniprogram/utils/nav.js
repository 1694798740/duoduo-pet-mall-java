// 自绘导航栏尺寸：状态栏高度 + 标题栏高度
// 标题栏高度按胶囊按钮位置反推 —— (胶囊 top - 状态栏高) * 2 + 胶囊高，
// 取不到胶囊信息时兜底 44px；状态栏取不到兜底 20px。
function getNavMetrics() {
  let statusBarHeight = 20;
  let navBarHeight = 44;
  try {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    if (info && info.statusBarHeight) statusBarHeight = info.statusBarHeight;
  } catch (e) {}
  try {
    const rect = wx.getMenuButtonBoundingClientRect();
    if (rect && rect.height) {
      const gap = rect.top - statusBarHeight;
      navBarHeight = (gap > 0 ? gap * 2 : 0) + rect.height;
    }
  } catch (e) {}
  return {
    statusBarHeight,
    navBarHeight,
    navTotalHeight: statusBarHeight + navBarHeight,
  };
}

module.exports = { getNavMetrics };
