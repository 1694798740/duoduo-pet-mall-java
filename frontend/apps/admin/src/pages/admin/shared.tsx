/**
 * 平台管理后台（M10）共享件。
 *
 * 与商家端 shared 同一份「角色判定要统一」的教训：后台五个页面共用同一套
 * 准入判定、同一组 stat 卡与同一条顶部导航，避免各写一份导致
 * 「概览页让进、审核页不让进」这种不一致。
 */

import React from 'react';
import { Link, NavLink } from 'react-router-dom';
import { Alert } from '@duoduo/shared/components/ui';

/**
 * 角色判定已抽到中立模块 `src/lib/roles.ts`（见该文件注释：商城引一个判定函数
 * 不该把整个后台 UI 模块拖进自己的产物）。这里**重导出**，让后台各页的 import 保持不变、
 * 且"判定只有一处实现"这一约定继续成立。
 */
export { isAdminRole, isOperator } from '@duoduo/shared/lib/roles';

/** 后台内部导航：把十个工作台挂到一条标签上 */
const TABS = [
  { to: '/admin', label: '运营概览', end: true },
  { to: '/admin/audits', label: '内容审核', end: false },
  { to: '/admin/product-audits', label: '商品审核', end: false },
  // E28：抽查是「已经在卖了、我回头看」的复核，与上面两个「等着放行」的队列语义不同，
  // 因此是独立入口而不是并进审核台（混在一起运营会看错待办的紧急程度）。
  { to: '/admin/audit-sla', label: '抽查复核', end: false },
  { to: '/admin/share-reports', label: '举报处理', end: false },
  { to: '/admin/categories', label: '类目管理', end: false },
  { to: '/admin/brands', label: '品牌管理', end: false },
  { to: '/admin/orders', label: '订单干预', end: false },
  { to: '/admin/after-sales', label: '售后仲裁', end: false },
  { to: '/admin/operators', label: '运营账号', end: false },
  { to: '/admin/seckill', label: '秒杀活动', end: false },
];

export const AdminTabs: React.FC = () => (
  <div className="row row-wrap" style={{ gap: 8, marginBottom: 16 }}>
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
  </div>
);

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
