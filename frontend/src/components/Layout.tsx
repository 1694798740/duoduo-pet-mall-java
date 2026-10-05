/**
 * 全局布局：顶部导航 + 购物车角标 + 用户菜单。
 *
 * 导航项只保留本期真正有后端支撑的页面：
 * 领券中心已随 A5 上线（duoduo-marketing 已部署），故放到一级导航；
 * 评价（fulfilment）、运营后台的其余页面按角色 / 场景在页内进入。
 *
 * 「优惠券」入口**对未登录也显示**：领券中心是公开落地页，先让人看到有什么券，
 * 点「立即领取」时再引导登录 —— 与 App.tsx 里该路由不套 RequireAuth 是同一个考虑。
 *
 * 「AI 导购」入口同样对未登录显示：对话接口在网关白名单里，未登录也能问（A9 验收②）。
 * 把入口藏起来等于把「先来聊两句」这条最轻的转化路径砍掉。
 *
 * 「商家端」入口按角色显示：卖家中心（M5-9）落在 trade 服务上，不依赖上述服务，
 * 但普通买家点进去只会看到入驻引导，把入口藏掉更干净。
 * 「管理后台」入口（M10）同理：只对运营 / 管理员显示，与后端 isOperator 同口径。
 * 后台入口刻意做成**两条**（顶栏一个链接 + 头像下拉一条「进入管理后台」）：
 * 后台不是买家动线上的页面，只有顶栏一条时用户一旦滚动或窄屏折叠就找不到，
 * 而它的诉求恰恰是「稳定、随时能回来」。两条都指向 /admin（运营概览），
 * 判定共用 isOperator()，不存在两套口径。
 */

import React, { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { canAccessSellerConsole } from '../pages/seller/shared';
import { isOperator } from '../pages/admin/shared';
import { useApp } from '../store/app';
import { useAuth } from '../store/auth';

const NAV = [
  { to: '/', label: '首页' },
  { to: '/search', label: '逛商品' },
  { to: '/ai', label: 'AI 导购' },
  { to: '/listings', label: '二手闲置' },
  { to: '/coupons', label: '优惠券' },
  { to: '/seckill', label: '秒杀' },
  { to: '/pets', label: '我的宠物' },
  { to: '/addresses', label: '收货地址' },
  { to: '/orders', label: '我的订单' },
];

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, logout } = useAuth();
  const { cartCount, refreshCart } = useApp();
  const [keyword, setKeyword] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();

  // 登录态变化时同步角标；未登录时 count 接口会 401，refreshCart 内部已兜底为 0
  useEffect(() => {
    void refreshCart();
  }, [user, refreshCart]);

  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // 商家端入口只在「真的是卖家」时出现；角色判定与后端同源（忽略大小写）
  // 管理后台入口同理：只对运营 / 管理员显示（M10）
  const navItems = useMemo(() => {
    let items = NAV;
    if (canAccessSellerConsole(user?.role)) {
      items = [...items, { to: '/seller', label: '商家端' }];
    }
    if (isOperator(user?.role)) {
      items = [...items, { to: '/admin', label: '管理后台' }];
    }
    return items;
  }, [user?.role]);

  const submitSearch = (e: React.FormEvent) => {
    e.preventDefault();
    const q = keyword.trim();
    navigate(q ? `/search?keyword=${encodeURIComponent(q)}` : '/search');
  };

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="container app-header-inner">
          <Link className="brand" to="/">
            <span className="brand-logo">🐾</span>
            <span>多多宠物商店</span>
          </Link>

          <nav className="nav-links">
            {navItems.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>

          <div className="header-right">
            <form className="search-inline" onSubmit={submitSearch}>
              <span className="muted">🔍</span>
              <input
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="搜猫粮 / 猫砂 / 逗猫棒…"
                aria-label="搜索商品"
              />
            </form>

            <Link className="icon-btn" to="/cart" aria-label="购物车">
              🛒
              {cartCount > 0 ? (
                <span className="badge-dot">{cartCount > 99 ? '99+' : cartCount}</span>
              ) : null}
            </Link>

            {user ? (
              <div style={{ position: 'relative' }}>
                <button className="icon-btn" onClick={() => setMenuOpen((v) => !v)}>
                  👤 <span className="small">{user.nickname}</span>
                </button>
                {menuOpen ? (
                  <div
                    className="card"
                    style={{ position: 'absolute', right: 0, top: 46, width: 176, padding: 8, zIndex: 80 }}
                  >
                    <Link className="nav-link" to="/me" style={{ display: 'block' }}>
                      个人中心
                    </Link>
                    <Link className="nav-link" to="/orders" style={{ display: 'block' }}>
                      我的订单
                    </Link>
                    <Link className="nav-link" to="/coupons" style={{ display: 'block' }}>
                      我的券包
                    </Link>
                    {canAccessSellerConsole(user.role) ? (
                      <Link className="nav-link" to="/seller" style={{ display: 'block' }}>
                        商家端
                      </Link>
                    ) : null}
                    {/* 平台管理后台入口（与导航栏那条同源同判）——
                        只对运营 / 管理员显示，与后端 OperatorGuard 同口径。
                        ★ 这里必须用 isOperator()，不能用 useAuth().isAdmin：
                        后者写的是全小写比较（user.role === 'admin'），而库内是大写
                        存储，它**永远返回 false** —— 用它会让入口静默不出现，
                        且不报任何错，属于最难归因的一类问题。
                        刷新后仍在的机制：Layout 包住全部路由，user 由
                        GET /api/users/me 重取（Cookie dd_rt 有效期 7 天），
                        因此「关掉页面再打开」入口照样在 —— 见
                        scripts/e2e/admin-entry-ui-check.mjs 的复开断言。 */}
                    {isOperator(user.role) ? (
                      <Link className="nav-link" to="/admin" style={{ display: 'block' }}>
                        进入管理后台
                      </Link>
                    ) : null}
                    <Link className="nav-link" to="/pets" style={{ display: 'block' }}>
                      我的宠物
                    </Link>
                    <div className="divider" style={{ margin: '6px 0' }} />
                    <button
                      className="nav-link"
                      style={{
                        display: 'block',
                        width: '100%',
                        textAlign: 'left',
                        border: 'none',
                        background: 'none',
                      }}
                      onClick={async () => {
                        await logout();
                        navigate('/');
                      }}
                    >
                      退出登录
                    </button>
                  </div>
                ) : null}
              </div>
            ) : (
              <Link className="btn btn-primary btn-sm" to="/login">
                登录 / 注册
              </Link>
            )}
          </div>
        </div>
      </header>

      <main className="app-main">
        <div className="container">{children}</div>
      </main>

      <footer
        style={{
          borderTop: '1px solid var(--border)',
          background: 'var(--surface)',
          padding: '18px 0',
        }}
      >
        <div className="container row-between row-wrap small muted">
          <span>多多宠物商店 · 自营保真 × 个人闲置担保交易</span>
          <span>演示环境 · 支付走沙箱渠道 · 资金由平台托管</span>
        </div>
      </footer>
    </div>
  );
};
