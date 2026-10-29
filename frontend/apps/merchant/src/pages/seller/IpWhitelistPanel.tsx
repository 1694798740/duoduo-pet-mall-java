/**
 * 商家工作台 · 账号安全 · IP 白名单（E-06）。
 *
 * <h2>这个面板的全部难点只有一件事：别把店主锁在门外</h2>
 * 白名单一旦开启，网关会拦掉所有不在名单里的 `/api/seller/**` 访问 ——
 * 包括**这个页面本身**。所以交互上做了三件事：
 *   1. 把服务端看到的当前 IP 直接展示出来，并给「把当前 IP 加入名单」按钮；
 *   2. 若新名单不含当前 IP，服务端返回 422，这里转成一次**显式确认**（勾选后才重提）；
 *   3. 保存后若 `published=false`，明确提示「网关暂未同步」而不是假装成功 ——
 *      店主以为拦住了、实际没拦，比没拦住更危险。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { sellerSecurityApi } from '@duoduo/api-sdk/endpoints';
import type { MerchantIpWhitelistView } from '@duoduo/api-sdk/types';
import { useAsync } from '@duoduo/shared/components/hooks';
import { Alert, LoadingBlock, useToast } from '@duoduo/shared/components/ui';

export const IpWhitelistPanel: React.FC = () => {
  const { push } = useToast();
  const fetcher = useCallback(() => sellerSecurityApi.ipWhitelist(), []);
  const { data, loading, error, reload } = useAsync<MerchantIpWhitelistView>(fetcher, true, []);

  const [enabled, setEnabled] = useState(false);
  const [rulesText, setRulesText] = useState('');
  const [confirmLockout, setConfirmLockout] = useState(false);
  const [needConfirm, setNeedConfirm] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [syncedTo, setSyncedTo] = useState<'ok' | 'pending' | null>(null);

  // 每次读到服务端数据就回填表单（保存后 reload 会带回最新值，正好刷新一次）
  useEffect(() => {
    if (!data) {
      return;
    }
    setEnabled(data.enabled);
    setRulesText(data.rules.join('\n'));
  }, [data]);

  const submit = async (withConfirm: boolean) => {
    setSaveError(null);
    setSyncedTo(null);
    setSaving(true);
    try {
      const rules = rulesText
        .split(/\n|,/)
        .map((r) => r.trim())
        .filter((r) => r.length > 0);
      const saved = await sellerSecurityApi.saveIpWhitelist({
        enabled,
        rules,
        confirmLockout: withConfirm,
      });
      setNeedConfirm(false);
      setConfirmLockout(false);
      setSyncedTo(saved.published ? 'ok' : 'pending');
      push(saved.enabled ? '白名单已保存，网关已生效' : '白名单已关闭', 'success');
      reload();
    } catch (err) {
      const message = toFriendlyMessage(err);
      // 服务端在「当前 IP 不在新名单」时返回 422：转成一次显式确认，而不是让店主反复试
      if (message.includes('无法进入商家端')) {
        setNeedConfirm(true);
      }
      setSaveError(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ maxWidth: 720, marginTop: 18 }}>
      <h3 className="section-title" style={{ fontSize: 17 }}>账号安全 · IP 白名单</h3>
      <p className="small muted" style={{ marginTop: 0, marginBottom: 10 }}>
        开启后，只有名单内的网络才能访问商家工作台（店铺经营相关接口）。
        <b>买家端与商城不受影响</b>；店主与店员共用这份名单。
      </p>

      {loading && !data ? <LoadingBlock text="正在读取白名单…" /> : null}
      {error && !data ? <Alert kind="error">{error}</Alert> : null}

      {data ? (
        <div className="card card-pad">
          <div className="small muted" style={{ marginBottom: 10 }}>
            服务端看到的当前 IP：<code>{data.currentIp ?? '未知（未经过网关）'}</code>
            {data.currentIp ? (
              <button
                className="btn btn-ghost btn-sm"
                style={{ marginLeft: 8 }}
                onClick={() => {
                  if (!rulesText.includes(data.currentIp as string)) {
                    setRulesText((prev) => (prev.trim() ? `${prev.trim()}\n${data.currentIp}` : (data.currentIp as string)));
                  }
                }}
              >
                把当前 IP 加入名单
              </button>
            ) : null}
          </div>

          <label className="row" style={{ gap: 8, alignItems: 'center', cursor: 'pointer', marginBottom: 10 }}>
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            <span>启用 IP 白名单（仅名单内网络可进入商家工作台）</span>
          </label>

          <textarea
            className="input"
            rows={4}
            style={{ width: '100%', fontFamily: 'monospace' }}
            placeholder={'一行一条，例如：\n203.0.113.7\n10.0.0.0/8\n192.168.1.*'}
            value={rulesText}
            onChange={(e) => setRulesText(e.target.value)}
            disabled={!enabled}
          />
          <p className="small muted" style={{ marginTop: 6 }}>
            支持精确 IP、IPv4 CIDR（如 10.0.0.0/8）与前缀通配（如 192.168.1.*）；最多 20 条。
            启用时至少要有一条 —— 空名单会让整间店都进不来。
          </p>

          {saveError ? <Alert kind="error">{saveError}</Alert> : null}

          {needConfirm ? (
            <label
              className="row"
              style={{ gap: 8, alignItems: 'flex-start', cursor: 'pointer', marginTop: 4 }}
            >
              <input
                type="checkbox"
                checked={confirmLockout}
                onChange={(e) => setConfirmLockout(e.target.checked)}
              />
              <span className="small">
                我了解风险：当前 IP 不在新名单中，保存后我将无法从当前网络进入商家工作台
                （只能联系平台运营清除白名单）。
              </span>
            </label>
          ) : null}

          {syncedTo === 'pending' ? (
            <Alert kind="warn">
              已保存，但网关暂未同步（缓存不可用）。稍后会自动生效；若长时间未生效请联系平台运营。
            </Alert>
          ) : null}

          <div className="row" style={{ gap: 8, marginTop: 12 }}>
            <button
              className="btn btn-primary"
              disabled={saving || (needConfirm && !confirmLockout)}
              onClick={() => submit(needConfirm)}
            >
              {saving ? '保存中…' : '保存'}
            </button>
            <button
              className="btn btn-ghost"
              disabled={saving}
              onClick={() => {
                setEnabled(data.enabled);
                setRulesText(data.rules.join('\n'));
                setNeedConfirm(false);
                setConfirmLockout(false);
                setSaveError(null);
                setSyncedTo(null);
              }}
            >
              重置
            </button>
          </div>

          <p className="small muted" style={{ marginTop: 12, marginBottom: 0 }}>
            配置保存即时生效、每次改动都留审计。若配置错误导致自己进不来，
            可请平台运营在后台清除本店白名单后重新登录配置。
          </p>
        </div>
      ) : null}
    </div>
  );
};
