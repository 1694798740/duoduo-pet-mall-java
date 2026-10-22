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
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ThemeToggle } from '@duoduo/shared/components/ThemeToggle';
import { canAccessMerchantWorkspace, isOperator } from '@duoduo/shared/lib/roles';
import { useAuth } from '@duoduo/shared/store/auth';
import { MerchantLoginPage } from './MerchantLogin';
import { SellerDashboardPage } from '../seller/Dashboard';
import { SellerOrderDetailPage } from '../seller/OrderDetail';
import { SellerOrdersPage } from '../seller/Orders';
import { SellerProductsPage } from '../seller/Products';
import { SellerSettlementsPage } from '../seller/Settlements';
import { SellerAfterSalesPage } from '../seller/AfterSales';
import { SellerSupportPage } from '../seller/SupportInbox';
import { SellerAccountPage } from '../seller/Account';
import { SellerApplyCard } from '../seller/shared';
import { MerchantApplyPage } from '../seller/Apply';
import { SellerStaffPage } from '../seller/Staff';

/** 商家工作台导航（商家页面自身没有导航栏，这份是**唯一**的一份） */
// E13 · 页签按身份过滤：店员不可见「结算对账」（后端同样 403，前端隐藏只是体验，
// 真正的门在 SellerService.settlements 里）。店主额外多「店员管理」。
const TABS_FOR_OWNER = [
  { to: '/seller', label: '经营概览', end: true },
  { to: '/seller/products', label: '商品管理', end: false },
  { to: '/seller/orders', label: '订单管理', end: false },
  // 客服信息：买家咨询的接待入口。自营店铺的接待人是官方客服账号（同样是 SELLER），
  // 第三方店铺是各自商家 —— 同一页、同一套接口，靠登录态区分，不靠前端传参。
  { to: '/seller/support', label: '客服信息', end: false },
  { to: '/seller/after-sales', label: '售后处理', end: false },
  { to: '/seller/settlements', label: '结算对账', end: false },
  { to: '/seller/staff', label: '店员管理', end: false },
];
const TABS_FOR_STAFF = [
  { to: '/seller', label: '经营概览', end: true },
  { to: '/seller/products', label: '商品管理', end: false },
  { to: '/seller/orders', label: '订单管理', end: false },
  // 客服信息对所有能进工作台的角色可见：运营/店员进去会看到说明页，
  // 而不是"页签凭空消失"——消失会让人以为功能没上线。
  { to: '/seller/support', label: '客服信息', end: false },
  { to: '/seller/after-sales', label: '售后处理', end: false },
];
const tabsFor = (role: string | undefined, merchant: { memberRole: string } | null | undefined) =>
  merchant?.memberRole === 'STAFF' || role?.toUpperCase() !== 'SELLER'
    ? TABS_FOR_STAFF
    : TABS_FOR_OWNER;

const MALL_URL = (import.meta.env.VITE_MALL_URL as string | undefined) ?? 'http://localhost:8088';
/** 平台管理后台入口（独立站点、独立主机名 → 独立 Cookie，与商家登录态互不影响） */
const ADMIN_URL = (import.meta.env.VITE_ADMIN_URL as string | undefined) ?? 'http://admin.localhost:8090';

/** 跳回商会站点（跨主机名，必须绝对地址） */
const BackToMall: React.FC = () => {
  window.location.replace(`${MALL_URL}/me`);
  return null;
};

const MerchantShell: React.FC = () => {
  const { user, loading, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

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
  if (!canAccessMerchantWorkspace(user.role, user.merchant)) {
    // 已登录但还不是商家：给**入驻引导**（沿用原商家端行为）—— 商家平台的登录页是招商入口，
    // 这里正是"实名 → 申请入驻"的落地页；不显示生硬的"无权限"。
    return (
      <div className="container" style={{ paddingTop: 24 }}>
        <SellerApplyCard />
        <p className="text-muted" style={{ marginTop: 12 }}>
          <a href={`${MALL_URL}/?from=merchant&expect=${user.id}`}>返回商城</a>
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
            {/* 头像 + 昵称整体是「个人中心」入口 → 工作台自己的账号页。
                ★ 不能跳商城 /me：那是**另一套登录态**（商城与商家端各自登录），
                  用户在工作台登录 A、商城登录 B 时，跳过去会看到 B，
                  表现为「商家登录后个人中心是买家」。 */}
            <Link
              to="/seller/account"
              className="row"
              style={{ gap: 8, alignItems: 'center', textDecoration: 'none', color: 'inherit' }}
              title="账号中心"
            >
              {user.avatar ? (
                <img className="nav-avatar" src={user.avatar} alt="" />
              ) : (
                <span
                  aria-hidden
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: '50%',
                    background: 'var(--surface-2)',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 12,
                  }}
                >
                  👤
                </span>
              )}
              <span className="text-muted">
                {user.nickname || user.phoneMasked}（{user.role}）
              </span>
            </Link>
            {/* 平台后台入口按角色显隐：只给运营 / 管理员（isOperator 与后端 LoginUser#isOperator 同口径）。
                商家（SELLER / 店员）看不到 —— 后台是平台级事务，不是店铺的。 */}
            {isOperator(user.role) && (
              <a className="btn btn-sm btn-ghost" href={`${ADMIN_URL}/admin.html`}>
                平台后台
              </a>
            )}
            <button
              className="btn btn-sm btn-ghost"
              type="button"
              onClick={() => {
                // 先确认本地仍有登录态；没有就直接按"登录已过期"处理，而不是静默跳转
                if (!user) {
                  navigate('/login?reason=expired', { replace: true });
                  return;
                }
                navigate('/seller/account');
              }}
            >
              个人中心
            </button>
            <ThemeToggle />
            <a className="btn btn-sm btn-ghost" href={`${MALL_URL}/?from=merchant&expect=${user.id}`}>
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
        {tabsFor(user?.role, user?.merchant).map((tab) => (
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

      {/*
        路由切换入场动画。★ 只包 <Outlet />：外层 container 里有 position:sticky 的顶栏，
        一旦给外层加 transform，sticky 会退化成相对定位、顶栏跟着滚走。
      */}
      <div className="route-enter" key={location.pathname}>
        <Outlet />
      </div>
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
      <Route path="/seller/support" element={<SellerSupportPage />} />
      {/* 账号中心：工作台自己的身份页，不与商城个人中心共用（两套独立登录态） */}
      <Route path="/seller/account" element={<SellerAccountPage />} />
      <Route path="/seller/products" element={<SellerProductsPage />} />
      <Route path="/seller/settlements" element={<SellerSettlementsPage />} />
      <Route path="/seller/staff" element={<SellerStaffPage />} />
      <Route path="/seller/after-sales" element={<SellerAfterSalesPage />} />
      {/* 商家页面里指向商城的个人中心链接（/me）→ 跨站点跳回商城 */}
      <Route path="/me" element={<BackToMall />} />
      <Route path="*" element={<Navigate to="/seller" replace />} />
    </Route>
  </Routes>
);
