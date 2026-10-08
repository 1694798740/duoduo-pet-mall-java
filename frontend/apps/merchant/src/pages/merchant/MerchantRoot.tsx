/**
 * 第三方商家平台 · 入口外壳与路由（E6）。
 *
 * <p>与平台管理后台（`pages/admin/AdminRoot.tsx`）同构：独立站点、自有登录页、自建导航。
 * 两处**刻意的差别**：
 *   ① 商家页面**自身没有共享导航栏**（它们原来靠商城顶栏在页面之间跳），所以本外壳**必须**提供
 *      商家工作台导航 —— 与后台相反（后台页面自带 AdminTabs，外壳若再加一份就会出现两条导航，
 *      那是 E4 阶段踩过的坑，这里按"先看页面自己带了什么"的教训处理）；
 *   ② 商家页面自带标题（如"商家端 · 概览"），外壳**不再叠加标题**，只做全局条 + 导航。
 *
 * <p>页面之间的跳转：商家各页内部用的是站内链接（`/seller/...`），在本商家应用内依然成立。
 */
import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router-dom';
import { canAccessSellerConsole } from '@duoduo/shared/lib/roles';
import { useAuth } from '@duoduo/shared/store/auth';
import { MerchantLoginPage } from './MerchantLogin';
import { SellerDashboardPage } from '../seller/Dashboard';
import { SellerOrderDetailPage } from '../seller/OrderDetail';
import { SellerOrdersPage } from '../seller/Orders';
import { SellerProductsPage } from '../seller/Products';
import { SellerSettlementsPage } from '../seller/Settlements';
import { SellerAfterSalesPage } from '../seller/AfterSales';
import { SellerApplyCard } from '../seller/shared';
import { MerchantApplyPage } from '../seller/Apply';

/** 商家工作台导航（商家页面自身没有导航栏，这份是**唯一**的一份） */
const TABS = [
  { to: '/seller', label: '经营概览', end: true },
  { to: '/seller/products', label: '商品管理', end: false },
  { to: '/seller/orders', label: '订单管理', end: false },
  { to: '/seller/after-sales', label: '售后处理', end: false },
  { to: '/seller/settlements', label: '结算对账', end: false },
];

const MALL_URL = (import.meta.env.VITE_MALL_URL as string | undefined) ?? 'http://localhost:8088';

/** 跳回商会站点（跨主机名，必须绝对地址） */
const BackToMall: React.FC = () => {
  window.location.replace(`${MALL_URL}/me`);
  return null;
};

const MerchantShell: React.FC = () => {
  const { user, loading, logout } = useAuth();
  const navigate = useNavigate();

  if (loading) {
    return (
      <div className="container" style={{ paddingTop: 24 }}>
        <div className="card">正在校验登录状态…</div>
      </div>
    );
  }
  if (!user) {
    // 商家平台不依赖商城登录：未登录 → 本平台自有登录页
    return <Navigate to="/login" replace />;
  }
  if (!canAccessSellerConsole(user.role)) {
    // 已登录但还不是商家：给**入驻引导**（沿用原商家端行为）—— 商家平台的登录页是招商入口，
    // 这里正是"实名 → 申请入驻"的落地页；不显示生硬的"无权限"。
    return (
      <div className="container" style={{ paddingTop: 24 }}>
        <SellerApplyCard />
        <p className="text-muted" style={{ marginTop: 12 }}>
          <a href={MALL_URL}>返回商城</a>
        </p>
      </div>
    );
  }

  return (
    <div className="container" style={{ paddingTop: 14, paddingBottom: 48 }}>
      <header
        className="card"
        style={{ padding: '10px 16px', marginBottom: 14, position: 'sticky', top: 0, zIndex: 20 }}
      >
        <div className="row row-between row-wrap" style={{ gap: 12 }}>
          <strong>多多宠物 · 商家工作台</strong>
          <div className="row row-wrap" style={{ gap: 8, alignItems: 'center' }}>
            <span className="text-muted">{user.nickname || user.phoneMasked}（{user.role}）</span>
            <a className="btn btn-sm btn-ghost" href={MALL_URL}>
              返回商城
            </a>
            <button
              className="btn btn-sm btn-ghost"
              onClick={() => void logout().then(() => navigate('/login', { replace: true }))}
            >
              退出
            </button>
          </div>
        </div>
      </header>

      <nav className="row row-wrap" style={{ gap: 8, marginBottom: 16 }}>
        {TABS.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) => `btn btn-sm ${isActive ? 'btn-primary' : 'btn-ghost'}`}
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>

      <Outlet />
    </div>
  );
};

/** 商家平台全部路由（与商城摘除前的 `/seller/*` 一一对应） */
export const MerchantRoot: React.FC = () => (
  <Routes>
    <Route path="/login" element={<MerchantLoginPage />} />
    <Route element={<MerchantShell />}>
      <Route path="/apply" element={<MerchantApplyPage />} />
      <Route path="/seller" element={<SellerDashboardPage />} />
      <Route path="/seller/orders" element={<SellerOrdersPage />} />
      <Route path="/seller/orders/:orderNo" element={<SellerOrderDetailPage />} />
      <Route path="/seller/products" element={<SellerProductsPage />} />
      <Route path="/seller/settlements" element={<SellerSettlementsPage />} />
      <Route path="/seller/after-sales" element={<SellerAfterSalesPage />} />
      {/* 商家页面里指向商城的个人中心链接（/me）→ 跨站点跳回商城 */}
      <Route path="/me" element={<BackToMall />} />
      <Route path="*" element={<Navigate to="/seller" replace />} />
    </Route>
  </Routes>
);
