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
import { LoadingBlock } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';
import { AiGuruPage } from './pages/AiGuru';
import { CartPage } from './pages/Cart';
import { CheckoutPage } from './pages/Checkout';
import { CouponsPage } from './pages/Coupons';
import { SeckillPage } from './pages/Seckill';
import { HomePage } from './pages/Home';
import { ListingDetailPage } from './pages/ListingDetail';
import { ListingsPage } from './pages/Listings';
import { UserProfilePage } from './pages/UserProfile';
import { MyListingsPage } from './pages/MyListings';
import { NotificationsPage } from './pages/Notifications';
import { FollowPage } from './pages/Follow';
import { MessagesPage } from './pages/Messages';
import { ChatPage } from './pages/Chat';
import { SupportPage } from './pages/Support';
import { FavoritesPage } from './pages/Favorites';
import { HistoryPage } from './pages/History';
import { ShareCirclePage } from './pages/ShareCircle';
import { ShareDetailPage } from './pages/ShareDetail';
import { ShareMinePage } from './pages/ShareMine';
import { ListingFormPage } from './pages/ListingForm';
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
import { StorePage } from './pages/Store';
import { AfterSalesPage } from './pages/AfterSales';
import { AfterSaleNewPage } from './pages/AfterSaleNew';
import { AfterSaleDetailPage } from './pages/AfterSaleDetail';

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
      {/* 他人主页（S6-D1 补缺）：关注/粉丝列表、闲置详情、分享圈三处入口都跳这里。
          后端接口小程序早就在用，本路由只做前端接线。 */}
      <Route path="/user/:id" element={<UserProfilePage />} />
      {/* 店铺主页（第八十二轮）：商品卡/详情页店名/购物车组头跳这里。
          公开可逛（不套 RequireAuth），sellerId=0 即自营货架。 */}
      <Route path="/store/:sellerId" element={<StorePage />} />
      <Route path="/login" element={<LoginPage />} />

      {/* ---------- 我的闲置（C2C 管理，第六十六轮补齐 Web 端） ----------
          刻意**不套 RequireSeller**：发布与管理闲置只要求「登录 + 实名」，
          与「卖家身份」（自营店铺）是两条线 —— 普通用户就是闲置卖家本人，
          把入口锁在卖家角色后面等于让 C2C 在 Web 上不可用。
          未登录时 RequireAuth 会带 ?next= 回跳，登录后原路返回。 */}
      <Route
        path="/listings/mine"
        element={
          <RequireAuth>
            <MyListingsPage />
          </RequireAuth>
        }
      />
      <Route
        path="/listings/new"
        element={
          <RequireAuth>
            <ListingFormPage />
          </RequireAuth>
        }
      />
      <Route
        path="/listings/:id/edit"
        element={
          <RequireAuth>
            <ListingFormPage />
          </RequireAuth>
        }
      />
      {/* ---------- 分享圈（第七十一轮功能对齐 P1） ----------
          信息流/话题公开可逛；字面量路由（mine / topic）必须先于 :id 注册意图上更清晰，
          React Router v6 按特异性排序本身也能正确匹配。 */}
      <Route path="/share" element={<ShareCirclePage />} />
      <Route path="/share/topic/:name" element={<ShareCirclePage />} />
      <Route
        path="/share/mine"
        element={
          <RequireAuth>
            <ShareMinePage />
          </RequireAuth>
        }
      />
      <Route path="/share/:id" element={<ShareDetailPage />} />
      <Route
        path="/notifications"
        element={
          <RequireAuth>
            <NotificationsPage />
          </RequireAuth>
        }
      />
      <Route
        path="/follow"
        element={
          <RequireAuth>
            <FollowPage />
          </RequireAuth>
        }
      />

      {/* 私信（R74-5）：会话列表与详情都需登录 —— 私信本身承载身份，不像分享圈可匿名浏览 */}
      <Route
        path="/messages"
        element={
          <RequireAuth>
            <MessagesPage />
          </RequireAuth>
        }
      />
      <Route
        path="/messages/:peerId"
        element={
          <RequireAuth>
            <ChatPage />
          </RequireAuth>
        }
      />

      {/* 客服（第七十八轮从私信独立）：自营客服与商家客服共用一条通道，但不进私信列表 */}
      <Route
        path="/favorites"
        element={
          <RequireAuth>
            <FavoritesPage />
          </RequireAuth>
        }
      />
      <Route
        path="/history"
        element={
          <RequireAuth>
            <HistoryPage />
          </RequireAuth>
        }
      />

      <Route
        path="/support"
        element={
          <RequireAuth>
            <SupportPage />
          </RequireAuth>
        }
      />

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

      {/* ---------- 限时秒杀（A14，交易域 duoduo-trade） ----------
          与领券中心同口径：**不加** RequireAuth，活动列表未登录可读（导流页）；
          抢购需要登录 + 收货地址，页面内引导，比把人拦在门外转化高。
          下单成功跳 /orders/:orderNo 走既有沙箱支付。 */}
      <Route path="/seckill" element={<SeckillPage />} />

      {/* ---------- AI 导购「多多」（A9，duoduo-ai :8086） ----------
          刻意**不加** RequireAuth：`/api/duoduo/chat` 在网关白名单里，对话本身不承载身份，
          未登录也能问答（验收②）；「加购 / 下单」这类需要身份的动作由回复文案引导登录，
          页面再补一条登录入口 —— 把「能不能问」和「能不能买」分开。 */}
      <Route path="/ai" element={<AiGuruPage />} />


      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  </Layout>
);
