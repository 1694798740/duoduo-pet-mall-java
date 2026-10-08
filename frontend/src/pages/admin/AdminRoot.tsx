/**
 * 平台管理后台的**独立入口**（T3 第一步：单应用多入口 → 独立产物 + 独立网址）。
 *
 * <p>为什么先做"多入口"而不是立刻拆成三个独立工程：拆工程要重排目录、依赖与构建（3 人日量级），
 * 中途一旦停下就会留下"构建变红"的半成品。多入口能让后台**先拿到独立网址与独立 HTML 产物**
 * （可单独部署、单独加访问策略），而每一步都可构建、可验收。
 * 下一步再把本目录整体迁到 `apps/admin` 并抽出共享包（见任务清单 T3）。
 *
 * <p>与商城入口的关系：**后端判定仍是唯一权威**（非运营一律 403）；
 * 这里只负责"入口级"的体验（非运营看到可读提示，而不是一屏 403）。
 */
import { Link, Navigate, Outlet, Route, Routes } from 'react-router-dom';
import { useAuth } from '../../store/auth';
import { AdminDenied, AdminTabs, isOperator } from './shared';
import { AdminDashboardPage } from './Dashboard';
import { AdminAuditsPage } from './Audits';
import { AdminCategoriesPage } from './Categories';
import { AdminBrandsPage } from './Brands';
import { AdminOperatorsPage } from './Operators';
import { AdminSeckillPage } from './Seckill';
import { AdminOrdersPage } from './Orders';
import { AdminAfterSalesPage } from './AfterSales';
import { AdminShareReportsPage } from './ShareReports';

/** 后台外壳：登录/权限校验 + 顶部条 + 工单标签栏 */
const AdminShell: React.FC = () => {
  const { user, loading, logout } = useAuth();

  if (loading) {
    return (
      <div className="card" style={{ margin: 24 }}>
        正在校验登录状态…
      </div>
    );
  }
  if (!user) {
    // 后台入口与商城入口分离后，未登录不再跳商城登录页（避免用户以为"后台没做"），
    // 而是明确告知去商城登录后回来。下一步做独立域名时改为后台自有登录页。
    return (
      <div className="card" style={{ margin: 24, maxWidth: 520 }}>
        <h3 style={{ marginTop: 0 }}>请先登录平台账号</h3>
        <p className="text-muted">
          平台管理后台仅对平台运营 / 管理员开放。请先到商城完成登录，再回到本页。
        </p>
        <Link className="btn btn-primary" to="/login">
          去商城登录
        </Link>
      </div>
    );
  }
  if (!isOperator(user.role)) {
    return <AdminDenied />;
  }

  return (
    <>
      <header className="card" style={{ margin: 16, padding: '12px 16px' }}>
        <div className="row row-between row-wrap" style={{ gap: 12 }}>
          <strong>多多宠物 · 平台管理后台</strong>
          <div className="row row-wrap" style={{ gap: 8 }}>
            <span className="text-muted">
              {user.nickname || user.phoneMasked}（{user.role}）
            </span>
            <Link className="btn btn-sm btn-ghost" to="/">
              返回商城
            </Link>
            <button className="btn btn-sm btn-ghost" onClick={() => void logout()}>
              退出
            </button>
          </div>
        </div>
      </header>
      <main style={{ padding: '0 16px 32px' }}>
        <AdminTabs />
        <Outlet />
      </main>
    </>
  );
};

/** 后台入口的全部路由（与商城入口的 /admin/* 路由表保持一致） */
export const AdminRoot: React.FC = () => (
  <Routes>
    <Route element={<AdminShell />}>
      <Route path="/admin" element={<AdminDashboardPage />} />
      <Route path="/admin/audits" element={<AdminAuditsPage />} />
      <Route path="/admin/categories" element={<AdminCategoriesPage />} />
      <Route path="/admin/brands" element={<AdminBrandsPage />} />
      <Route path="/admin/orders" element={<AdminOrdersPage />} />
      <Route path="/admin/after-sales" element={<AdminAfterSalesPage />} />
      <Route path="/admin/share-reports" element={<AdminShareReportsPage />} />
      <Route path="/admin/operators" element={<AdminOperatorsPage />} />
      <Route path="/admin/seckill" element={<AdminSeckillPage />} />
      {/* 入口根路径与未知路径都收敛到概览，避免后台入口出现空白页 */}
      <Route path="*" element={<Navigate to="/admin" replace />} />
    </Route>
  </Routes>
);
