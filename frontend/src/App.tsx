/**
 * 路由表。
 *
 * 已挂页面：
 *   主交易闭环 10 页 —— 首页 / 搜索 / 商品详情 / 闲置列表 / 闲置详情 / 登录
 *     / 购物车 / 结算 / 订单列表 / 订单详情（含资金托管）/ 宠物档案 / 我的
 *   商家端 4 页（M5-9 / M9）—— 概览 / 卖出订单 / 订单详情（履约）/ 资金对账
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
import { CartPage } from './pages/Cart';
import { CheckoutPage } from './pages/Checkout';
import { HomePage } from './pages/Home';
import { ListingDetailPage } from './pages/ListingDetail';
import { ListingsPage } from './pages/Listings';
import { LoginPage } from './pages/Login';
import { MePage } from './pages/Me';
import { NotFoundPage } from './pages/NotFound';
import { OrderDetailPage } from './pages/OrderDetail';
import { OrdersPage } from './pages/Orders';
import { PetsPage } from './pages/Pets';
import { ProductDetailPage } from './pages/ProductDetail';
import { SearchPage } from './pages/Search';
import { SellerDashboardPage } from './pages/seller/Dashboard';
import { SellerOrderDetailPage } from './pages/seller/OrderDetail';
import { SellerOrdersPage } from './pages/seller/Orders';
import { SellerSettlementsPage } from './pages/seller/Settlements';

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

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  </Layout>
);
