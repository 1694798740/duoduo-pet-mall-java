/**
 * 平台管理后台（M10）共享件。
 *
 * 与商家端 shared 同一份「角色判定要统一」的教训：后台五个页面共用同一套
 * 准入判定、同一组 stat 卡与同一条顶部导航，避免各写一份导致
 * 「概览页让进、审核页不让进」这种不一致。
 */

import React, { useEffect, useState } from 'react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { Alert } from '@duoduo/shared/components/ui';
import { onBadgeChanged } from '@duoduo/shared/lib/badge-events';
import { adminApi } from '@duoduo/api-sdk/endpoints';

/**
 * 角色判定已抽到中立模块 `src/lib/roles.ts`（见该文件注释：商城引一个判定函数
 * 不该把整个后台 UI 模块拖进自己的产物）。这里**重导出**，让后台各页的 import 保持不变、
 * 且"判定只有一处实现"这一约定继续成立。
 */
export { isAdminRole, isOperator } from '@duoduo/shared/lib/roles';

/**
 * 后台内部导航：把**标签导航里的工作台**挂到一条标签上。
 * ★ 本数组**不等于** `AdminRoot.tsx` 的全部后台路由 —— 「入驻审核台」与「商家子账号」
 *   刻意不在这里（它们从运营概览的待办入口进，属于"有活才去"的页面，常驻标签只会稀释注意力）。
 *   因此：改标签时不要顺手拿两边的条数互相对齐，判据是**是否该常驻**。
 */
const TABS = [
  { to: '/admin', label: '运营概览', end: true },
  // E24：租户健康度紧挨着运营概览 —— 两者都是"看数据"，且它是运营每天开台先扫一眼的
  // 那一屏（谁家出问题了）。放在这一批"管别人 / 管数据"的工单页之前，符合注意力顺序。
  { to: '/admin/tenant-health', label: '租户健康度', end: false },
  { to: '/admin/audits', label: '内容审核', end: false },
  { to: '/admin/product-audits', label: '商品审核', end: false },
  // H-03：审核台是「等我放行的队列」，变更历史是「回头查谁改过什么」的台账 ——
  // 两者相邻但语义不同，因此独立入口。
  { to: '/admin/product-history', label: '变更历史', end: false },
  // E28：抽查是「已经在卖了、我回头看」的复核，与上面两个「等着放行」的队列语义不同，
  // 因此是独立入口而不是并进审核台（混在一起运营会看错待办的紧急程度）。
  { to: '/admin/audit-sla', label: '抽查复核', end: false },
  { to: '/admin/share-reports', label: '举报处理', end: false },
  // H-02：评价是公开内容，违规评价要能下架 —— 与举报处理同属「内容治理」，
  // 但对象与处置口径不同（评价下架会联动商品评分重算），因此独立入口。
  { to: '/admin/reviews', label: '评价管理', end: false },
  { to: '/admin/categories', label: '类目管理', end: false },
  { to: '/admin/brands', label: '品牌管理', end: false },
  { to: '/admin/orders', label: '订单干预', end: false },
  // 矩阵第 9 项 · 导出任务：超过 5 万行的异步导出在这里轮询与下载。
  // 紧挨「订单干预」是因为目前唯一的消费方就是订单导出，运营的心智是「导不出来 → 来这里看」。
  { to: '/admin/export-jobs', label: '导出任务', end: false },
  { to: '/admin/after-sales', label: '售后仲裁', end: false },
  // R-P1：多多币是资金类工单页（负债汇总 / 人工调整 / 全站流水），与订单、售后同属
  // 「动钱」的一批，因此紧跟在售后仲裁后面，不与内容治理混排。
  { to: '/admin/coins', label: '多多币', end: false },
  { to: '/admin/operators', label: '运营账号', end: false },
  // E18：操作审计与上面的「内容审核」同名易混，标签用「操作审计」并把路径写成
  // /admin/operation-audits —— 前者是追责台账（只读），后者是内容审批队列（有操作列）。
  { to: '/admin/operation-audits', label: '操作审计', end: false },
  { to: '/admin/seckill', label: '秒杀活动', end: false },
  // E21：账号安全（两步验证）刻意放在导航末尾 —— 它是"关于我自己的"设置，
  // 与前面那批"管别人 / 管数据"的工单页语义不同；混在中间会让人以为又是一个审核队列。
  { to: '/admin/account-security', label: '账号安全', end: false },
];

/**
 * H-04 · 后台待办角标（2026-10-27 口径调整）。
 *
 * <p>五类队列分两种口径，**不能混为一谈**：
 * <ul>
 *   <li><b>评价 / 举报 / 售后仲裁</b>：点开详情看过了就该消 —— 走
 *       {@code .../unread-count}，服务端用已读集合排除本人查看过的条目；</li>
 *   <li><b>内容审核 / 商品审核</b>：它们的「详情」就是整张卡片（材料在列表里全量展开），
 *       没有二级页面可点，且审核结论会立刻让条目离开 PENDING —— 角标直接等于待审数，
 *       由审核动作收敛。若也做「看过就消」，未审的内容会从角标里消失却仍留在队列里，
 *       那是把「待办提醒」变成「已读回执」，反而更容易漏审。</li>
 * </ul>
 *
 * <p>触发：挂载即取 + 30 秒轮询 + 路由切换重取 + {@code BADGE_CHANGED_EVENT} 即时重取。
 * 请求失败按 0 处理 —— 角标是提醒，不能因为它报错挡住导航。
 */
const useAdminBadges = (): Record<string, number> => {
  const location = useLocation();
  const [badges, setBadges] = useState<Record<string, number>>({});

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const [listings, products, reviews, reports, aftersales] = await Promise.allSettled([
        adminApi.listingsForAudit({ auditStatus: 'PENDING', page: 1, pageSize: 1 }),
        adminApi.productsForAudit({ auditStatus: 'PENDING', page: 1, pageSize: 1 }),
        adminApi.reviewUnreadCount(),
        adminApi.shareReportUnreadCount(),
        adminApi.afterSaleUnreadCount(),
      ]);
      const total = (r: PromiseSettledResult<{ total?: number }>) =>
        r.status === 'fulfilled' ? Number(r.value?.total) || 0 : 0;
      /** 未读分桶：服务端只返回非 0 桶，空数组即 0 */
      const unread = (r: PromiseSettledResult<{ status: string; count: number }[]>) =>
        r.status === 'fulfilled' ? Number((r.value ?? [])[0]?.count) || 0 : 0;
      if (alive) {
        setBadges({
          '/admin/audits': total(listings),
          '/admin/product-audits': total(products),
          '/admin/reviews': unread(reviews),
          '/admin/share-reports': unread(reports),
          '/admin/after-sales': unread(aftersales),
        });
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 30_000);
    // 页面内「点开详情」上报已读后广播，父级导航同一刻收敛
    const off = onBadgeChanged(() => void load());
    return () => {
      alive = false;
      off();
      window.clearInterval(timer);
    };
  }, [location.pathname]);

  return badges;
};

/** 角标胶囊：>99 显示 99+。aria-hidden：不改变链接的可访问名称（e2e 按 label 精确匹配） */
const NavBadge: React.FC<{ count: number }> = ({ count }) =>
  count > 0 ? (
    <span
      aria-hidden
      style={{
        marginLeft: 6, padding: '0 6px', borderRadius: 9, minWidth: 18,
        background: 'var(--danger, #e5484d)', color: '#fff', fontSize: 11,
        lineHeight: '18px', textAlign: 'center', display: 'inline-block',
      }}
    >
      {count > 99 ? '99+' : count}
    </span>
  ) : null;

export const AdminTabs: React.FC = () => {
  const badges = useAdminBadges();
  return (
    <div className="row row-wrap" style={{ gap: 8, marginBottom: 16 }}>
      {TABS.map((tab) => (
        <NavLink
          key={tab.to}
          to={tab.to}
          end={tab.end}
          className={({ isActive }) => `btn btn-sm ${isActive ? 'btn-primary' : 'btn-ghost'}`}
        >
          {tab.label}
          <NavBadge count={badges[tab.to] ?? 0} />
        </NavLink>
      ))}
    </div>
  );
};

/** 非运营 / 管理员访问后台时的拦截页（后端 403 是边界，这里给的是可读提示） */
export const AdminDenied: React.FC = () => (
  <div className="card card-pad" style={{ marginTop: 24 }}>
    <h3 style={{ marginBottom: 10 }}>需要运营或管理员身份</h3>
    <Alert kind="error">
      平台管理后台仅对<b>运营 / 管理员</b>开放。这里是「整个平台」的数据与干预能力，
      不是卖家的「我的店」，因此必须与商家端分开把关。
    </Alert>
    <div className="row" style={{ marginTop: 14 }}>
      <Link className="btn btn-ghost" to="/">
        返回首页
      </Link>
    </div>
  </div>
);

/** 后台统一的数值卡（复用全局 .stat 样式） */
export const AdminStat: React.FC<{ label: string; value: React.ReactNode; hint?: string }> = ({
  label,
  value,
  hint,
}) => (
  <div className="stat">
    <div className="stat-label">{label}</div>
    <div className="stat-value">{value}</div>
    {hint ? <div className="stat-hint">{hint}</div> : null}
  </div>
);
