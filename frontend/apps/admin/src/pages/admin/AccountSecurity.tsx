/**
 * 平台管理后台 · 账号安全（E21 · 两步验证）。
 *
 * <p><b>为什么这个页面单独存在</b>：平台账号一个账号能审商品、关订单、改权限、看全站资金。
 * 只靠密码的话，一次撞库或一次钓鱼就足以拿到整个后台。两步验证让"拿到密码"不再等于"拿到后台"，
 * 而这个页面就是**唯一**的绑定入口 —— 后端刻意没有提供"帮别人绑定"的接口。
 *
 * <p>F-05 起面板抽到 `@duoduo/shared/components/TwoFactorSetup`，与商家工作台共用同一份流程；
 * 本页只负责标题与后台导航。
 */
import { AdminTabs } from './shared';
import { TwoFactorSetupPanel } from '@duoduo/shared/components/TwoFactorSetup';

export const AdminAccountSecurityPage: React.FC = () => (
  <div>
    <h2 className="section-title">账号安全 · 两步验证</h2>
    <AdminTabs />
    <TwoFactorSetupPanel audience="admin" />
  </div>
);
