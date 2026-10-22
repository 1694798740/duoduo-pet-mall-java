/**
 * 全局布局：顶部导航 + 购物车角标 + **未读角标（S7）** + 用户菜单。
 *
 * 未读角标由本组件**启用**（登录后开 10 秒兜底轮询；下单/支付/切页/回前台/打开菜单时各自立即刷新），
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
import { Link, NavLink, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { canAccessSellerConsole, isOperator } from '@duoduo/shared/lib/roles';
import { useApp } from '@duoduo/shared/store/app';
import { useAuth } from '@duoduo/shared/store/auth';
import { ThemeToggle } from '@duoduo/shared/components/ThemeToggle';
import { userApi } from '@duoduo/api-sdk/endpoints';

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

/** 面板 meta 行的角色展示名。仅用于展示；权限判定一律走 lib/roles.ts 的判定函数。 */
const ROLE_LABELS: Record<string, string> = {
  USER: '买家',
  SELLER: '卖家',
  OPERATOR: '运营',
  ADMIN: '管理员',
};

/**
 * 面板条目：图标 + 文案 +（可选未读角标）+ 右侧箭头，B 站面板同构。
 *
 * 刻意**不加** `role="menuitem"`：加了会覆盖 <a> 原生的 link 角色，
 * 既有的 `getByRole('link', { name: '个人中心' })` 就全找不到了；
 * 容器同样不用 role="menu"（menu 语义要求子项必须是 menuitem，混用反而是无效 ARIA）。
 */
const MenuLink: React.FC<{ to: string; icon: string; label: string; badge?: number; onClick?: (e: React.MouseEvent) => void }> = ({
  to,
  icon,
  label,
  badge,
  onClick,
}) => (
  <Link className="user-panel-item" to={to} onClick={onClick}>
    <span className="user-panel-icon" aria-hidden>
      {icon}
    </span>
    <span className="user-panel-label">{label}</span>
    {badge && badge > 0 ? <span className="badge-inline">{badgeText(badge)}</span> : null}
    <span className="user-panel-arrow" aria-hidden>
      ›
    </span>
  </Link>
);

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, logout, loading: authLoading } = useAuth();
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

  /**
   * 头像悬浮面板（2026-10-20 仿 B 站）的开关。
   *
   * ★ 这里**只是一个布尔**，动画完全交给 CSS：面板常驻 DOM，`is-open` 一摘，
   *   `opacity` / `transform` 的 transition 立刻从当前值插值回去（200ms），
   *   `visibility` 靠 `transition-delay` 延迟到淡出结束才变 hidden。
   *
   * 为什么不再是 'closed | open | closing' 三态 + 200ms 定时器（两个真实故障）：
   *   ① 三态版配的是**条件渲染**：元素插入 DOM 的第一帧就已经带着 is-open，
   *      浏览器拿不到「起始计算样式」，transition 根本不触发 —— 表现在用户眼里
   *      就是「瞬间弹到全尺寸」，`scale(0.88)` 从未被渲染过；
   *   ② 'closing' 期间仍保留 is-open，等于先干等 200ms 才摘类，淡出被推迟，
   *      观感变成「先愣一下、再突然消失」。
   * 唯一需要的定时器是进入方向的 80ms 意图延迟：鼠标扫过顶栏去点别的按钮时不该误弹。
   */
  const [menuOpen, setMenuOpen] = useState(false);
  const openTimer = useRef<number | null>(null);
  const cancelOpenTimer = () => {
    if (openTimer.current !== null) {
      clearTimeout(openTimer.current);
      openTimer.current = null;
    }
  };
  /** 立即展开（点击 / 键盘 Enter） */
  const openUserMenu = () => {
    cancelOpenTimer();
    setMenuOpen(true);
  };
  /** 悬浮进入：留 80ms 意图延迟 —— 鼠标扫过顶栏去点别的按钮时不该误弹面板 */
  const scheduleOpenUserMenu = () => {
    cancelOpenTimer();
    openTimer.current = window.setTimeout(() => setMenuOpen(true), 80);
  };
  /** 收起（悬浮离开 / Esc / 点外部 / 登出）：摘类即开始淡出，无需定时器 */
  const closeUserMenu = () => {
    cancelOpenTimer();
    setMenuOpen(false);
  };
  // 头像下拉的容器与触发按钮（R74-2：Esc / 点外部关闭、关掉后把焦点还给按钮）
  const menuRef = useRef<HTMLDivElement | null>(null);
  const menuButtonRef = useRef<HTMLButtonElement | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  /**
   * 跨站身份闸门（双身份链路）。
   *
   * <p>商家工作台带 {@code from=merchant&expect=<userId>} 进来时：
   * <ul>
   *   <li>商城登录的就是同一账号 → 直接放行（清掉查询参数，地址栏不残留）；</li>
   *   <li>未登录或登录的是**另一个**账号 → 视为登录已过期：清掉商城会话，
   *       跳登录页并带上 {@code reason=expired} 提示。</li>
   * </ul>
   *
   * <p>为什么"不同账号"要清会话而不是并存：这两个入口的语义是「进入我的商城账户」，
   * 如果保留另一个账号的会话，用户会以为自己看到的是自己的工作台账号。
   * 清会话＋重新登录是唯一不会造成身份误认的处理。
   */
  const fromMerchant = searchParams.get('from') === 'merchant';
  const expectUserId = searchParams.get('expect');
  useEffect(() => {
    if (!fromMerchant || !expectUserId || authLoading) return;
    if (location.pathname === '/login') return;
    if (user && String(user.id) === expectUserId) {
      const next = new URLSearchParams(searchParams);
      next.delete('from');
      next.delete('expect');
      // 用 navigate 显式带上 hash：setSearchParams 会丢掉 #realname，
      // 用户落到的就不是"实名认证"那一块了（跨站入口带锚点正是为了直达）。
      navigate(
        { pathname: location.pathname, search: next.toString(), hash: location.hash },
        { replace: true },
      );
      return;
    }
    void logout().finally(() => {
      navigate(`/login?reason=expired&next=${encodeURIComponent(location.pathname + location.hash)}`, { replace: true });
    });
    }, [fromMerchant, expectUserId, authLoading, user, location.pathname, location.hash, navigate, logout, searchParams]);

  // 登录态变化时同步角标；未登录时 count 接口会 401，refreshCart 内部已兜底为 0
  useEffect(() => {
    void refreshCart();
  }, [user, refreshCart]);

  useEffect(() => {
    // 路由变化＝注意力已切走，直关（不需要淡出）
    closeUserMenu();
  }, [location.pathname]);

  /**
   * 未读角标（S7）：登录后启用全站 10 秒兜底轮询（自己的动作不等轮询，见页面内的显式刷新），登出即停并清零。
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
      closeUserMenu();
      return undefined;
    }
    void refreshUnread();
    startUnreadPolling();
    return () => stopUnreadPolling();
  }, [loggedIn, refreshUnread, clearUnread, startUnreadPolling, stopUnreadPolling]);

  // 页面重新可见时立刻刷一次（对齐小程序的「回前台刷新」）：切走再回来不该还显示 10 秒前的数字
  useEffect(() => {
    if (!loggedIn) return undefined;
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refreshUnread();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [loggedIn, refreshUnread]);

  // 打开头像面板时也刷一次 —— 用户正要看这个数字，不该是 10 秒前那个
  useEffect(() => {
    if (!menuOpen || !loggedIn) return;
    void refreshUnread();
  }, [menuOpen, loggedIn, refreshUnread]);

  // 站内切换页面时刷一次（2026-10-18）：用户下单/支付后通常会跳到订单详情，
  // 跳转本身就是一个明确的"可能产生了新通知"的信号，没必要等下一次轮询。
  useEffect(() => {
    if (!loggedIn) return;
    void refreshUnread();
  }, [location.pathname, loggedIn, refreshUnread]);

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
        // Esc 走淡出（与悬浮离开同一条路径），焦点还给按钮
        closeUserMenu();
        menuButtonRef.current?.focus();
      }
    };
    const onMouseDown = (e: MouseEvent) => {
      const target = e.target as Node | null;
      if (target && menuRef.current && !menuRef.current.contains(target)) {
        closeUserMenu();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('mousedown', onMouseDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('mousedown', onMouseDown);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

            {/* A2 · 主题切换：放在图标按钮区，与购物车/通知同排 */}
            <ThemeToggle />

            <Link className="icon-btn" to="/cart" aria-label="购物车">
              🛒
              {cartCount > 0 ? <span className="badge-dot">{badgeText(cartCount)}</span> : null}
            </Link>

            {user ? (
              <div
                ref={menuRef}
                className={`user-entry${menuOpen ? ' is-open' : ''}`}
                onMouseEnter={scheduleOpenUserMenu}
                onMouseLeave={closeUserMenu}
              >
                {/*
                  触发按钮：仿 B 站 —— 右上角**只放头像**（不放昵称文字），圆形、稍大（42px）。
                  accessible name 仍带昵称（aria-label）：读屏器与既有 e2e 的
                  `getByRole('button', { name: /昵称/ })` 都靠它定位。
                  ★ 点击是「打开」不是「开关」：悬浮已经把面板打开了，此刻再点若执行
                  toggle 会立即把面板关掉 —— 自动化点击与真人「悬停后点击」都会踩中。
                */}
                <button
                  ref={menuButtonRef}
                  className="icon-btn avatar-btn"
                  aria-haspopup="menu"
                  aria-expanded={menuOpen}
                  aria-label={`账号菜单：${user.nickname}`}
                  onClick={openUserMenu}
                >
                  {user.avatar ? (
                    <img className="nav-avatar" src={user.avatar} alt="" />
                  ) : (
                    <span className="avatar-fallback" aria-hidden>👤</span>
                  )}
                  {/* 全站可见的未读角标（S7）：用户在任何页面都能看到有新消息 */}
                  {unread.total > 0 ? (
                    <span className="badge-dot">{badgeText(unread.total)}</span>
                  ) : null}
                </button>

                {/* ★ 面板**始终挂载**，只看宿主上的 is-open 类切换：
                    条件渲染时，元素插入 DOM 的第一帧就已经带着 is-open，
                    浏览器拿不到「起始计算样式」，transition 不会触发 ——
                    表现就是「瞬间显示为全尺寸」，scale(0.88) 从未被渲染过。
                    常驻后 closed 态真实存在（opacity:0 + visibility:hidden +
                    pointer-events:none，读屏器与 Tab 都进不去），切换类名时
                    才有前后两个计算样式可以插值，动画才会真的播。 */}
                <div className="user-menu user-panel" aria-label="账号菜单">
                  {/* 头部：头像左、昵称/身份右（B 站面板同构，横排省高度） */}
                  <div className="user-panel-head">
                    {user.avatar ? (
                      <img className="user-panel-avatar" src={user.avatar} alt="" />
                    ) : (
                      <span className="user-panel-avatar" aria-hidden>👤</span>
                    )}
                    <span className="user-panel-id">
                      <strong className="user-panel-name">{user.nickname}</strong>
                      <span className="user-panel-meta">
                        {ROLE_LABELS[user.role] ?? user.role}
                        {user.phoneMasked ? ` · ${user.phoneMasked}` : ''}
                      </span>
                    </span>
                  </div>

                  <div className="user-panel-group">
                    <MenuLink
                      to="/me"
                      icon="👤"
                      label="个人中心"
                      onClick={(e) => {
                        // 点开之前先验一次会话：令牌过期时不要"点进去了但一片空白"
                        e.preventDefault();
                        void userApi
                          .me()
                          .then(() => navigate('/me'))
                          .catch(() => {
                            void logout().finally(() =>
                              navigate('/login?reason=expired&next=%2Fme', { replace: true }),
                            );
                          });
                      }}
                    />
                    <MenuLink to="/favorites" icon="⭐" label="我的收藏夹" />
                    <MenuLink to="/history" icon="🕘" label="我的足迹" />
                    <MenuLink to="/orders" icon="🧾" label="我的订单" />
                    <MenuLink to="/after-sales" icon="🛡️" label="我的售后" />
                    <MenuLink to="/coupons" icon="🎟️" label="我的券包" />
                  </div>

                  <div className="user-panel-group">
                    <MenuLink to="/pets" icon="🐾" label="我的宠物" />
                    <MenuLink to="/addresses" icon="📍" label="收货地址" />
                    {/* 「我的闲置」放这里而不是顶栏：顶栏只放「逛 / 买」一级动线（见 NAV 注释）。
                        ★ 刻意不加角色判断 —— 发布与管理闲置只要求「登录 + 实名」，
                        与「卖家身份」（自营店铺）是两条线，普通用户就是闲置卖家本人。 */}
                    <MenuLink to="/listings/mine" icon="🏷️" label="我的闲置" />
                  </div>

                  <div className="user-panel-group">
                    <MenuLink to="/share" icon="🌏" label="分享圈" />
                    <MenuLink to="/share/mine" icon="✍️" label="我的分享" />
                    <MenuLink to="/follow" icon="❤️" label="关注与粉丝" />
                    {/* 通知中心 = 通知未读 + 客服未读（客服已归入「商家」分类展示） */}
                    <MenuLink
                      to="/notifications"
                      icon="🔔"
                      label="通知中心"
                      badge={Number(unread.notice) + Number(unread.support)}
                    />
                    <MenuLink to="/messages" icon="💬" label="私信" badge={Number(unread.message)} />
                  </div>

                  {/* 工作台入口单独成组 —— 它们是「另一重身份」的入口，不是个人资料。
                      判定与后端同源：商家端用 canAccessSellerConsole，后台用 isOperator()
                      （auth store 的 isAdmin 已在 R74-3 删除，重新加回会编译失败）。 */}
                  {canAccessSellerConsole(user.role) || isOperator(user.role) ? (
                    <div className="user-panel-group">
                      {canAccessSellerConsole(user.role) ? (
                        <MenuLink to="/seller" icon="🏪" label="商家端" />
                      ) : null}
                      {isOperator(user.role) ? (
                        <a className="user-panel-item" href={ADMIN_URL}>
                          <span className="user-panel-icon" aria-hidden>🛠️</span>
                          <span className="user-panel-label">进入管理后台</span>
                          <span className="user-panel-arrow" aria-hidden>›</span>
                        </a>
                      ) : null}
                    </div>
                  ) : null}

                  <div className="user-panel-group">
                    <button
                      className="user-panel-item user-panel-logout"
                      onClick={async () => {
                        closeUserMenu();
                        await logout();
                        navigate('/');
                      }}
                    >
                      <span className="user-panel-icon" aria-hidden>↩</span>
                      <span className="user-panel-label">退出登录</span>
                    </button>
                  </div>
                </div>
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
        {/*
          路由切换入场动画（2026-10-19 动效统一）。
          key 取 location.pathname：路径一变 React 就重挂这棵子树，CSS 动画随之重新播放。

          ★ 动画只能挂在这一层（页面内容容器），绝不能挂到 .app-shell 或 <main> 上：
            祖先元素只要带 transform，就会成为固定/粘性定位的包含块，
            顶栏的 position:sticky 会退化成相对定位、整个头部跟着页面一起滚走。
        */}
        <div className="container route-enter" key={location.pathname}>
          {children}
        </div>
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
