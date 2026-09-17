/**
 * 全局布局：顶部导航 + 购物车角标 + 用户菜单。
 *
 * 导航项只保留本期真正有后端支撑的页面：
 * 领券中心 / 评价 / 运营后台依赖尚未部署的服务（duoduo-marketing、fulfilment、admin），
 * 网关会返 503，故不放出来。
 *
 * 「商家端」入口按角色显示：卖家中心（M5-9）落在 trade 服务上，不依赖上述服务，
 * 但普通买家点进去只会看到入驻引导，把入口藏掉更干净。
 */

import React, { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { canAccessSellerConsole } from '../pages/seller/shared';
import { useApp } from '../store/app';
import { useAuth } from '../store/auth';

const NAV = [
  { to: '/', label: '首页' },
  { to: '/search', label: '逛商品' },
  { to: '/listings', label: '二手闲置' },
  { to: '/pets', label: '我的宠物' },
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
  const navItems = useMemo(
    () =>
      canAccessSellerConsole(user?.role)
        ? [...NAV, { to: '/seller', label: '商家端' }]
        : NAV,
    [user?.role],
  );

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
                    {canAccessSellerConsole(user.role) ? (
                      <Link className="nav-link" to="/seller" style={{ display: 'block' }}>
                        商家端
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
