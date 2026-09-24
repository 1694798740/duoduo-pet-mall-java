/**
 * 路由表。
 *
 * 已挂页面：
 *   主交易闭环 10 页 —— 首页 / 搜索 / 商品详情 / 闲置列表 / 闲置详情 / 登录
 *     / 购物车 / 结算 / 订单列表 / 订单详情（含资金托管）/ 宠物档案 / 我的
 *   商家端 4 页（M5-9 / M9）—— 概览 / 卖出订单 / 订单详情（履约）/ 资金对账
 *   平台管理后台 5 页（M10）—— 运营概览（含资金对账）/ 内容审核 / 类目 / 品牌 / 订单干预
 *   售后仲裁 5 页（A3）—— 买家：我的售后 / 申请售后 / 售后详情；
 *     卖家：售后服务；运营：售后仲裁工作台
 *   优惠券 1 页（A5）—— 领券中心 + 我的券包（营销域 duoduo-marketing）
 *
 * 需登录的页面统一由 RequireAuth 包裹：
 *  · 会话校验期间渲染加载态，避免「先闪一下登录页再跳回来」
 *  · 未登录时带上 ?next= 回跳地址，登录成功后原路返回
 *
 * 商家端额外套 RequireSeller：它只校验登录态，<b>不</b>在这里拦角色。
 * 非卖家进商家端时，页面自己渲染「申请成为卖家」的引导（含实名前置说明），
 * 比把人直接踢回首页有用得多；真正的权限边界仍在后端（403）。
 */

import React from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Layout } from './components/Layout';
import { LoadingBlock } from './components/ui';
import { useAuth } from './store/auth';
import { AiGuruPage } from './pages/AiGuru';
import { CartPage } from './pages/Cart';
import { CheckoutPage } from './pages/Checkout';
import { CouponsPage } from './pages/Coupons';
import { HomePage } from './pages/Home';
import { ListingDetailPage } from './pages/ListingDetail';
import { ListingsPage } from './pages/Listings';
import { LoginPage } from './pages/Login';
import { MePage } from './pages/Me';
import { NotFoundPage } from './pages/NotFound';
import { OrderDetailPage } from './pages/OrderDetail';
import { OrdersPage } from './pages/Orders';
import { PetsPage } from './pages/Pets';
import { AddressesPage } from './pages/Addresses';
import { ProductDetailPage } from './pages/ProductDetail';
import { ReviewNewPage } from './pages/ReviewNew';
import { MyReviewsPage } from './pages/MyReviews';
import { SearchPage } from './pages/Search';
import { SellerDashboardPage } from './pages/seller/Dashboard';
import { SellerOrderDetailPage } from './pages/seller/OrderDetail';
import { SellerOrdersPage } from './pages/seller/Orders';
import { SellerProductsPage } from './pages/seller/Products';
import { SellerSettlementsPage } from './pages/seller/Settlements';
import { AdminDashboardPage } from './pages/admin/Dashboard';
import { AdminAuditsPage } from './pages/admin/Audits';
import { AdminCategoriesPage } from './pages/admin/Categories';
import { AdminBrandsPage } from './pages/admin/Brands';
import { AdminOperatorsPage } from './pages/admin/Operators';
import { AdminOrdersPage } from './pages/admin/Orders';
import { AdminAfterSalesPage } from './pages/admin/AfterSales';
import { AfterSalesPage } from './pages/AfterSales';
import { AfterSaleNewPage } from './pages/AfterSaleNew';
import { AfterSaleDetailPage } from './pages/AfterSaleDetail';
import { SellerAfterSalesPage } from './pages/seller/AfterSales';

export const RequireAuth: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <LoadingBlock text="正在校验登录状态…" />;
  if (!user) {
    return (
      <Navigate
        to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
        replace
      />
    );
  }
  return <>{children}</>;
};

/**
 * 商家端守卫：只保证「已登录」，不拦角色。
 * 角色不对时由页面渲染入驻引导 —— 见文件头注释。
 */
export const RequireSeller: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <LoadingBlock text="正在校验登录状态…" />;
  if (!user) {
    return (
      <Navigate
        to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
        replace
      />
    );
  }
  return <>{children}</>;
};

/**
 * 平台管理后台守卫（M10）：与商家端同一哲学 —— 这里只保证「已登录」，
 * 非运营 / 管理员进入时由各页面统一渲染 AdminDenied 拦截页；
 * 真正的权限边界在后端 OperatorGuard（403）。
 */
export const RequireOperator: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) return <LoadingBlock text="正在校验登录状态…" />;
  if (!user) {
    return (
      <Navigate
        to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
        replace
      />
    );
  }
  return <>{children}</>;
};

export const App: React.FC = () => (
  <Layout>
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/search" element={<SearchPage />} />
      <Route path="/listings" element={<ListingsPage />} />
      <Route path="/product/:id" element={<ProductDetailPage />} />
      <Route path="/listing/:id" element={<ListingDetailPage />} />
      <Route path="/login" element={<LoginPage />} />

      <Route
        path="/pets"
        element={
          <RequireAuth>
            <PetsPage />
          </RequireAuth>
        }
      />
      <Route
        path="/addresses"
        element={
          <RequireAuth>
            <AddressesPage />
          </RequireAuth>
        }
      />
      <Route
        path="/cart"
        element={
          <RequireAuth>
            <CartPage />
          </RequireAuth>
        }
      />
      <Route
        path="/checkout"
        element={
          <RequireAuth>
            <CheckoutPage />
          </RequireAuth>
        }
      />
      <Route
        path="/orders"
        element={
          <RequireAuth>
            <OrdersPage />
          </RequireAuth>
        }
      />
      <Route
        path="/orders/:orderNo"
        element={
          <RequireAuth>
            <OrderDetailPage />
          </RequireAuth>
        }
      />
      <Route
        path="/me"
        element={
          <RequireAuth>
            <MePage />
          </RequireAuth>
        }
      />

      {/* ---------- 评价与晒单（M11/P2-1，履约域 duoduo-fulfilment） ---------- */}
      <Route
        path="/reviews/new"
        element={
          <RequireAuth>
            <ReviewNewPage />
          </RequireAuth>
        }
      />
      <Route
        path="/reviews/mine"
        element={
          <RequireAuth>
            <MyReviewsPage />
          </RequireAuth>
        }
      />

      {/* ---------- 售后仲裁（A3，履约域 duoduo-fulfilment） ---------- */}
      <Route
        path="/after-sales"
        element={
          <RequireAuth>
            <AfterSalesPage />
          </RequireAuth>
        }
      />
      <Route
        path="/after-sales/new"
        element={
          <RequireAuth>
            <AfterSaleNewPage />
          </RequireAuth>
        }
      />
      <Route
        path="/after-sales/:id"
        element={
          <RequireAuth>
            <AfterSaleDetailPage />
          </RequireAuth>
        }
      />

      {/* ---------- 优惠券（A5，营销域 duoduo-marketing） ----------
          刻意**不加** RequireAuth：领券中心是公开落地页（未登录也看得到有什么券），
          未登录时页面自己在领券按钮上引导去登录，比先把人拦在门外转化高。
          券包页签同样在页内处理未登录态（渲染「去登录」引导）。 */}
      <Route path="/coupons" element={<CouponsPage />} />

      {/* ---------- AI 导购「多多」（A9，duoduo-ai :8086） ----------
          刻意**不加** RequireAuth：`/api/duoduo/chat` 在网关白名单里，对话本身不承载身份，
          未登录也能问答（验收②）；「加购 / 下单」这类需要身份的动作由回复文案引导登录，
          页面再补一条登录入口 —— 把「能不能问」和「能不能买」分开。 */}
      <Route path="/ai" element={<AiGuruPage />} />

      {/* ---------- 商家端（M5-9 / M9，需登录；非卖家由页面引导入驻） ---------- */}
      <Route
        path="/seller"
        element={
          <RequireSeller>
            <SellerDashboardPage />
          </RequireSeller>
        }
      />
      <Route
        path="/seller/orders"
        element={
          <RequireSeller>
            <SellerOrdersPage />
          </RequireSeller>
        }
      />
      <Route
        path="/seller/orders/:orderNo"
        element={
          <RequireSeller>
            <SellerOrderDetailPage />
          </RequireSeller>
        }
      />
      <Route
        path="/seller/settlements"
        element={
          <RequireSeller>
            <SellerSettlementsPage />
          </RequireSeller>
        }
      />
      {/* 商品管理落在 product 服务（/api/seller/products/**），与订单侧同一套准入 */}
      <Route
        path="/seller/products"
        element={
          <RequireSeller>
            <SellerProductsPage />
          </RequireSeller>
        }
      />
      <Route
        path="/seller/after-sales"
        element={
          <RequireSeller>
            <SellerAfterSalesPage />
          </RequireSeller>
        }
      />

      {/* ---------- 平台管理后台（M10，需登录；非运营由页面渲染 AdminDenied） ---------- */}
      <Route
        path="/admin"
        element={
          <RequireOperator>
            <AdminDashboardPage />
          </RequireOperator>
        }
      />
      <Route
        path="/admin/audits"
        element={
          <RequireOperator>
            <AdminAuditsPage />
          </RequireOperator>
        }
      />
      <Route
        path="/admin/categories"
        element={
          <RequireOperator>
            <AdminCategoriesPage />
          </RequireOperator>
        }
      />
      <Route
        path="/admin/brands"
        element={
          <RequireOperator>
            <AdminBrandsPage />
          </RequireOperator>
        }
      />
      <Route
        path="/admin/orders"
        element={
          <RequireOperator>
            <AdminOrdersPage />
          </RequireOperator>
        }
      />
      <Route
        path="/admin/after-sales"
        element={
          <RequireOperator>
            <AdminAfterSalesPage />
          </RequireOperator>
        }
      />
      <Route
        path="/admin/operators"
        element={
          <RequireOperator>
            <AdminOperatorsPage />
          </RequireOperator>
        }
      />

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  </Layout>
);
