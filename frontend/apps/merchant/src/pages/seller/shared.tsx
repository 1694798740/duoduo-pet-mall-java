/**
 * 商家端共享件：角色判定（已抽到 `src/lib/roles.ts`，此处重导出）+ 「还不是卖家」引导。
 *
 * 为什么判定要单独放中立模块：商城顶栏也要按它显示「商家端」入口，
 * 若从本文件引会把整个商家 UI 拖进商城产物（E5/E6 各踩过一次）。
 */

import React from 'react';
import { Link } from 'react-router-dom';
import { Alert } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';

export { canAccessSellerConsole } from '@duoduo/shared/lib/roles';

/** 店员/无权限访问经营页时的拦截提示（E13） */
export const SellerDenied: React.FC<{ reason?: string }> = ({ reason }) => (
  <div className="card card-pad" style={{ marginTop: 24 }}>
    <h3 style={{ marginBottom: 10 }}>需要店主身份</h3>
    <Alert kind="error">{reason ?? '该页面仅对店主开放'}</Alert>
  </div>
);

export const SellerApplyCard: React.FC<{ compact?: boolean }> = ({ compact }) => {
  const { user } = useAuth();

  const verified = Boolean(user?.realNameVerified);

  // E10：入驻改为「提交材料 → 平台审核 → 开店」，本卡片只负责把人带到申请页；
  // 旧的「实名即升卖家」一键接口已随旧端点删除（无审核的开店路径必须封死）。

  return (
    <div className="card card-pad" style={compact ? undefined : { marginTop: 24 }}>
      <h3 style={{ marginBottom: 10 }}>商家端需要卖家身份</h3>

      <Alert kind="info">
        入驻需要两步：<b>先完成实名认证</b>（钱是放款给卖家的，放款给一个不可追责的主体，
        担保交易的信任基础就没了），再<b>提交资质材料</b>（营业执照、法人身份证、对公账户等
        6 类），由平台人工审核。审核通过后即可开通店铺。
      </Alert>

      <div className="row row-wrap" style={{ marginTop: 14 }}>
        {verified ? (
          <Link className="btn btn-primary" to="/apply">
            填写入驻申请
          </Link>
        ) : (
          <Link className="btn btn-primary" to="/me">
            先去实名认证
          </Link>
        )}
      </div>

      {verified ? null : (
        <div className="small muted" style={{ marginTop: 10 }}>
          当前账号：{user?.nickname ?? '—'}（角色 {user?.role ?? '—'}）
        </div>
      )}

      <div className="small muted" style={{ marginTop: 10 }}>
        说明：审核通过后在申请页领取商家身份，新令牌即时生效，无需重新登录。
      </div>
    </div>
  );
};
