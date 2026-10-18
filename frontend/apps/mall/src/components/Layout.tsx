/**
 * 全局布局：顶部导航 + 购物车角标 + **未读角标（S7）** + 用户菜单。
 *
 * 未读角标由本组件**启用**（登录后开 60 秒兜底轮询、页面重新可见时刷新），
 * 但口径与刷新实现都在 `@duoduo/shared/store/app` —— 本组件只决定「什么时候开着」。
 * 结果就是：用户在**任何**页面（首页 / 商品详情 / 分享圈…）都能从顶栏头像上看到未读数。
 *
 * 导航项只保留本期真正有后端支撑的页面：
 * 领券中心已随 A5 上线（duoduo-marketing 已部署），故放到一级导航；
 * 评价（fulfilment）、运营后台的其余页面按角色 / 场景在页内进入。
 *
 * 顶栏与头像下拉的分工（本轮起）：**顶栏 = 「逛 / 买」一级动线**
 * （首页 / 逛商品 / AI 导购 / 二手闲置 / 优惠券 / 秒杀）；
 * **个人事务与工作台入口统一收进头像下拉**（个人中心 / 我的订单 / 我的券包 /
 * 我的宠物 / 收货地址 ｜ 商家端 / 进入管理后台）。
 * 原因不是审美而是硬约束 —— 见下面 `NAV` 的注释（11 项顶栏在 1920px 屏幕上都会截断）。
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

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { canAccessSellerConsole, isOperator } from '@duoduo/shared/lib/roles';
import { useApp } from '@duoduo/shared/store/app';
import { useAuth } from '@duoduo/shared/store/auth';

/**
 * 顶栏只放「逛 / 买」这条一级动线 —— 个人事务（订单 / 宠物 / 收货地址）统一收进头像下拉。
 *
 * <p>为什么必须收：顶栏所在的 `.container` 最大宽度是 1200px（`--max-w`），一行可用约 1160px；
 * 而「9 项基础导航 + 商家端 + 管理后台（运营可见）= 11 项」需要约 812px，
 * 连同品牌 146、搜索框 260、购物车 38、用户按钮 92，这一行共需约 **1408px** ——
 * **在 1920px 的屏幕上都不够**。于是浏览器让可滚动的 `.nav-links` 独自承担收缩
 * （812 → 594，独自让出 218px），把「我的订单 / 商家端 / 管理后台」挤出可视区；
 * 而滚动条被 `scrollbar-width: none` 显式隐藏、也没有任何溢出提示，
 * 用户看到的就只是「标签凭空消失了」（实测：未登录访客丢 2 项、买家丢 1 项、运营丢 3 项）。
 *
 * <p>收成 6 项基础导航后，运营账号也只需约 584px < 594px，任何窗口宽度都不再截断。
 * 个人事务并没被删掉，只是移到头像下拉 —— **入口去重不是全删，要分清哪个是导航骨架、
 * 哪个是个人事务入口**。
 */
/**
 * 平台管理后台站点地址（T-ARCH 第 2 步）。
 *
 * <p>后台已经从商城**独立部署**（独立主机名/端口/登录），因此商城侧的入口必须是
 * **跨站点绝对地址**，而不是原来的站内路由 `/admin` —— 后者已被摘除。
 * 地址由部署注入（VITE_ADMIN_URL），默认本机 admin.localhost:8090。
 */
const ADMIN_URL =
  (import.meta.env.VITE_ADMIN_URL as string | undefined) ?? 'http://admin.localhost:8090/admin.html';

/** 第三方商家平台的独立站点地址（E6）：同样是**跨站点**跳转，故用绝对地址。 */
const MERCHANT_URL =
  (import.meta.env.VITE_MERCHANT_URL as string | undefined) ?? 'http://merchant.localhost:8091/merchant.html';

/** 顶部导航项：站内用 `to`（路由），跨站点用 `href`（外部链接） */
type NavItem = { label: string; to: string } | { label: string; href: string };

const NAV: NavItem[] = [
  { to: '/', label: '首页' },
  { to: '/search', label: '逛商品' },
  { to: '/ai', label: 'AI 导购' },
  { to: '/listings', label: '二手闲置' },
  { to: '/coupons', label: '优惠券' },
  { to: '/seckill', label: '秒杀' },
];

/** 角标文案：>99 统一成「99+」（三位数在这个尺寸下会被裁切） */
const badgeText = (n: number) => (n > 99 ? '99+' : String(n));

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, logout } = useAuth();
  const {
    cartCount,
    refreshCart,
    unread,
    refreshUnread,
    clearUnread,
    startUnreadPolling,
    stopUnreadPolling,
  } = useApp();
  const [keyword, setKeyword] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  // 头像下拉的容器与触发按钮（R74-2：Esc / 点外部关闭、关掉后把焦点还给按钮）
  const menuRef = useRef<HTMLDivElement | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);
  const navigate = useNavigate();
  const location = useLocation();

  // 登录态变化时同步角标；未登录时 count 接口会 401，refreshCart 内部已兜底为 0
  useEffect(() => {
    void refreshCart();
  }, [user, refreshCart]);

  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  /**
   * 未读角标（S7）：登录后启用全站 60 秒兜底轮询，登出即停并清零。
   *
   * 口径与刷新点都在 `@duoduo/shared/store/app`（唯一数据源 `unread-summary`，服务端合并通知与私信）；
   * 这里只负责「什么时候启用」。R74-5 时「不为此加全站轮询」的取舍已被本轮决策取代 ——
   * 那个取舍的代价是：用户停在别的页面时**完全看不到**新消息，而消息提醒的价值恰恰在那时候。
   */
  const loggedIn = Boolean(user);
  useEffect(() => {
    if (!loggedIn) {
      stopUnreadPolling();
      clearUnread();
      return undefined;
    }
    void refreshUnread();
    startUnreadPolling();
    return () => stopUnreadPolling();
  }, [loggedIn, refreshUnread, clearUnread, startUnreadPolling, stopUnreadPolling]);

  // 页面重新可见时立刻刷一次（对齐小程序的「回前台刷新」）：切走再回来不该还显示 60 秒前的数字
  useEffect(() => {
    if (!loggedIn) return undefined;
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshUnread();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [loggedIn, refreshUnread]);

  // 打开头像菜单时也刷一次 —— 用户正要看这个数字，不该是 60 秒前那个
  useEffect(() => {
    if (!menuOpen || !loggedIn) return;
    void refreshUnread();
  }, [menuOpen, loggedIn, refreshUnread]);

  /**
   * 头像下拉的关闭行为（R74-2）。
   *
   * 此前只有「切换路由时自动收起」一条出口，于是：按 Esc 关不掉、点菜单外面也关不掉 ——
   * 而菜单固定在右上角且 zIndex 高于页面，**正好压住页面右上角的按钮**，
   * 用户只能靠「点一个别的链接跳走」或刷新页面才能把它弄掉。
   *
   * 两条约定（与 `components/ui.tsx` 的 Modal 同姿势，改一处别忘另一处）：
   *  1. **Esc 关闭并把焦点还给触发按钮**（键盘用户关掉菜单后不该丢掉位置）；
   *  2. **点击菜单外部关闭**：监听要挂在 document 上，且用 `mousedown`
   *     （click 在“按下与抬起之间鼠标移出”时会丢事件）；判定用「目标是否在菜单容器内」，
   *     容器**把触发按钮一起包住** —— 否则点按钮会先被判成“外部点击”关掉、再被 onClick 打开，
   *     表现为「点一下没反应」。
   */
  useEffect(() => {
    if (!menuOpen) return undefined;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMenuOpen(false);
        menuButtonRef.current?.focus();
      }
    };
    const onMouseDown = (e: MouseEvent) => {
      const target = e.target as Node | null;
      if (target && menuRef.current && !menuRef.current.contains(target)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('mousedown', onMouseDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('mousedown', onMouseDown);
    };
  }, [menuOpen]);

  // 商家端入口只在「真的是卖家」时出现；角色判定与后端同源（忽略大小写）
  // 管理后台入口同理：只对运营 / 管理员显示（M10）
  const navItems = useMemo<NavItem[]>(() => {
    let items = NAV;
    if (canAccessSellerConsole(user?.role)) {
      // 跨站点入口（商家平台已独立部署）
      items = [...items, { href: MERCHANT_URL, label: '商家端' }];
    }
    if (isOperator(user?.role)) {
      // 跨站点入口：带 href（外部链接），不再是站内路由
      items = [...items, { href: ADMIN_URL, label: '管理后台' }];
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
            <span className="brand-text">多多宠物商店</span>
          </Link>

          <nav className="nav-links">
            {navItems.map((item) =>
              'href' in item ? (
                // 跨站点入口（平台管理后台）：用 <a> 而不是 <NavLink> —— 它不在本应用的路由表里
                <a key={item.href} className="nav-link" href={item.href}>
                  {item.label}
                </a>
              ) : (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                >
                  {item.label}
                </NavLink>
              ),
            )}
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
              {cartCount > 0 ? <span className="badge-dot">{badgeText(cartCount)}</span> : null}
            </Link>

            {user ? (
              <div ref={menuRef} style={{ position: 'relative' }}>
                <button
                  ref={menuButtonRef}
                  className="icon-btn"
                  aria-haspopup="menu"
                  aria-expanded={menuOpen}
                  onClick={() => setMenuOpen((v) => !v)}
                >
                  👤 <span className="small">{user.nickname}</span>
                  {/* 全站可见的未读角标（S7）：用户在任何页面都能看到有新消息 —— 这正是本轮的核心诉求 */}
                  {unread.total > 0 ? (
                    <span className="badge-dot">{badgeText(unread.total)}</span>
                  ) : null}
                </button>
                {menuOpen ? (
                  <div
                    className="card user-menu"
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
                    <Link className="nav-link" to="/pets" style={{ display: 'block' }}>
                      我的宠物
                    </Link>
                    <Link className="nav-link" to="/addresses" style={{ display: 'block' }}>
                      收货地址
                    </Link>
                    {/* 「我的闲置」放在个人事务里而不是顶栏：顶栏只放「逛 / 买」一级动线
                        （见上面 NAV 的注释），而这是「我要卖」的个人事务入口。
                        ★ 刻意不加角色判断 —— 发布与管理闲置只要求「登录 + 实名」，
                        与「卖家身份」（自营店铺）是两条线，普通用户就是闲置卖家本人。 */}
                    <Link className="nav-link" to="/listings/mine" style={{ display: 'block' }}>
                      我的闲置
                    </Link>
                    {/* 「分享圈」进下拉而不进顶栏：顶栏 6 项是 R64 实测的截断红线
                        （运营账号 6+2 项 ≈ 584px 已贴近 594px 上限），再加一项运营必截断 */}
                    <Link className="nav-link" to="/share" style={{ display: 'block' }}>
                      分享圈
                    </Link>
                    <Link className="nav-link" to="/share/mine" style={{ display: 'block' }}>
                      我的分享
                    </Link>
                    {/* 未读数字来自 store 的**合并口径**（通知 + 私信一次取回），不再各查各的。
                        2026-10-18：入口从「通知中心（1）」改为图标 + 右上角数字角标 ——
                        括号数字读起来像标题的一部分，角标才是"有几条没看"的通用表达。 */}
                    <Link
                      className="nav-link"
                      to="/notifications"
                      style={{ display: 'flex', alignItems: 'center', gap: 8 }}
                    >
                      <span style={{ position: 'relative', display: 'inline-block', lineHeight: 1 }}>
                        <span aria-hidden>🔔</span>
                        {unread.notice > 0 ? (
                          <span className="badge-dot">{badgeText(unread.notice)}</span>
                        ) : null}
                      </span>
                      通知中心
                    </Link>
                    <Link
                      className="nav-link"
                      to="/messages"
                      style={{ display: 'flex', alignItems: 'center', gap: 8 }}
                    >
                      <span style={{ position: 'relative', display: 'inline-block', lineHeight: 1 }}>
                        <span aria-hidden>💬</span>
                        {unread.message > 0 ? (
                          <span className="badge-dot">{badgeText(unread.message)}</span>
                        ) : null}
                      </span>
                      私信
                    </Link>
                    <Link className="nav-link" to="/follow" style={{ display: 'block' }}>
                      关注与粉丝
                    </Link>
                    {/* 工作台入口单独排在个人事务之后 —— 它们是「另一重身份」的入口，
                        不是个人资料，混在中间会让菜单读起来没有层次。 */}
                    {canAccessSellerConsole(user.role) ? (
                      <Link className="nav-link" to="/seller" style={{ display: 'block' }}>
                        商家端
                      </Link>
                    ) : null}
                    {/* 平台管理后台入口（与顶栏那条同源同判）——
                        只对运营 / 管理员显示，与后端 OperatorGuard 同口径。
                        ★ 必须用 isOperator()：判定**只有这一处权威实现**（src/lib/roles.ts，中立模块）。
                        auth store 里曾经有个 `isAdmin` 走了全小写比较（user.role === 'admin'），
                        而库内是大写存储，它**永远返回 false** —— 入口静默不出现且不报任何错。
                        该字段已在 R74-3 删除，重新加回会编译失败（见 store/auth.tsx 顶部注释）。
                        刷新后仍在的机制：Layout 包住全部路由，user 由
                        GET /api/users/me 重取（Cookie dd_rt 有效期 7 天），
                        因此「关掉页面再打开」入口照样在 —— 见
                        scripts/e2e/admin-entry-ui-check.mjs 的复开断言。 */}
                    {isOperator(user.role) ? (
                      <a className="nav-link" href={ADMIN_URL} style={{ display: 'block' }}>
                        进入管理后台
                      </a>
                    ) : null}
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
              <div className="row" style={{ gap: 12, alignItems: 'center' }}>
                {/* 未登录也要有分享圈入口：信息流本身公开可逛（匿名可看），
                    此前入口只在登录后的头像下拉里 —— 匿名访客在整个页面找不到它，
                    这是可发现性缺陷（2026-10-06 用户报障）。仍未放顶栏：R64 截断红线。 */}
                <Link className="nav-link" to="/share">分享圈</Link>
                <Link className="btn btn-primary btn-sm" to="/login">
                  登录 / 注册
                </Link>
              </div>
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
