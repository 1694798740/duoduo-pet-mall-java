/**
 * 入驻申请页（E10，E15 增补资质有效期与续期）。
 *
 * <p>材料走 E12 私有通道（POST /api/merchant-materials → /api/merchant-materials/**）；
 * 上传与填表解耦：先传文件、再填有效期、最后统一保存。
 *
 * <p>状态与可编辑范围（E15 后有两套「能改」的语义，不能混）：
 * <ul>
 *   <li>{@code DRAFT / REJECTED} —— 还没开张，走<b>提交审核</b>（{@code editable}）；</li>
 *   <li>{@code APPROVED / SUSPENDED} —— 已开张，走<b>资质续期</b>（{@code renewable}）；
 *       APPROVED 是临期主动续，SUSPENDED 是过期被停业后补办；</li>
 *   <li>{@code SUBMITTED / REVIEWING} —— 材料已送审，一律只读：
 *       送审的样本中途被换掉，审核结论就与材料失去对应关系。</li>
 * </ul>
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  MERCHANT_LICENSE_STAGE_LABEL,
  MERCHANT_MATERIAL_TYPES,
  MerchantApplicationView,
} from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { userApi } from '@duoduo/api-sdk/endpoints';
import { Alert, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useAuth } from '@duoduo/shared/store/auth';

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  REVIEWING: '审核中',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  SUSPENDED: '已停业',
};

/** 需要填有效期的材料（经营承诺书这类没有到期日，不给日期框，免得商家被逼着编一个） */
const EXPIRY_REQUIRED_TYPES = new Set([
  'BUSINESS_LICENSE',
  'LEGAL_ID_FRONT',
  'LEGAL_ID_BACK',
  'INDUSTRY_PERMIT',
]);

export const MerchantApplyPage: React.FC = () => {
  const navigate = useNavigate();
  const { refreshMe } = useAuth();
  const { push } = useToast();
  const [app, setApp] = useState<MerchantApplicationView | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [storeName, setStoreName] = useState('');
  const [materials, setMaterials] = useState<Record<string, string>>({});
  const [materialExpiry, setMaterialExpiry] = useState<Record<string, string>>({});
  const fileRefs = useRef<Record<string, HTMLInputElement | null>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const view: MerchantApplicationView = await userApi.getMerchantApplication();
      setApp(view);
      setStoreName(view.storeName.startsWith('待填写-') ? '' : view.storeName);
      setMaterials(view.materials ?? {});
      setMaterialExpiry(view.materialExpiry ?? {});
      setError(null);
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const editable = app?.status === 'DRAFT' || app?.status === 'REJECTED';
  const renewable = app?.status === 'APPROVED' || app?.status === 'SUSPENDED';
  const formVisible = editable || renewable;

  const upload = async (type: string, file: File) => {
    setBusy(true);
    try {
      // E12：材料走私有通道（落私有卷、读取鉴权），不再用公开的 /uploads
      const path = (await userApi.uploadMerchantMaterial(file)).path;
      setMaterials((prev) => ({ ...prev, [type]: path }));
      push('上传成功，记得保存', 'success');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  /** 只提交「有填过的」有效期：没动过的材料传了空值会被服务端理解成「改为长期有效」 */
  const expiryPayload = (): Record<string, string> => {
    const out: Record<string, string> = {};
    for (const t of MERCHANT_MATERIAL_TYPES) {
      if (!EXPIRY_REQUIRED_TYPES.has(t.type)) continue;
      const v = materialExpiry[t.type];
      if (v) out[t.type] = v;
    }
    return out;
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const view: MerchantApplicationView = await userApi.saveMerchantApplication({
        storeName: storeName || undefined,
        materials,
        materialExpiry: expiryPayload(),
      });
      setApp(view);
      setMaterialExpiry(view.materialExpiry ?? {});
      push('草稿已保存', 'success');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      // 先落草稿再提交：材料 state 里可能有刚上传还没保存的项
      await userApi.saveMerchantApplication({
        storeName: storeName || undefined,
        materials,
        materialExpiry: expiryPayload(),
      });
      const view: MerchantApplicationView = await userApi.submitMerchantApplication();
      setApp(view);
      push('已提交审核，请等待平台处理', 'success');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const renew = async () => {
    setBusy(true);
    setError(null);
    try {
      const view: MerchantApplicationView = await userApi.renewMerchantApplication({
        materials,
        materialExpiry: expiryPayload(),
      });
      setApp(view);
      setMaterialExpiry(view.materialExpiry ?? {});
      push('续期材料已提交，审核通过后即可恢复营业', 'success');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const activate = async () => {
    setBusy(true);
    setError(null);
    try {
      await userApi.activateMerchant();
      await refreshMe();
      push('店铺已开通，欢迎入驻', 'success');
      navigate('/seller');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return <LoadingBlock text="载入入驻申请…" />;
  }

  const missing = MERCHANT_MATERIAL_TYPES.filter((t) => !materials[t.type]);
  const stage = app?.licenseStage ?? 'NONE';
  const remaining = app?.licenseRemainingDays;
  const expireDate = app?.licenseExpireAt?.slice(0, 10);

  return (
    <div className="card card-pad" style={{ maxWidth: 720, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 6 }}>商家入驻申请</h2>
      <div className="small muted" style={{ marginBottom: 14 }}>
        当前状态：<b>{STATUS_LABEL[app?.status ?? ''] ?? app?.status}</b>
        {app?.appliedAt ? ` ｜ 最近提交 ${app.appliedAt.slice(0, 10)}` : ''}
        {expireDate
          ? ` ｜ 资质有效期至 ${expireDate}（剩余 ${remaining} 天 · ${MERCHANT_LICENSE_STAGE_LABEL[stage]}）`
          : ' ｜ 资质长期有效'}
      </div>

      {error ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="error">{error}</Alert>
        </div>
      ) : null}

      {/* ---- E15 · 资质到期横幅：分档给不同强度的提示 ---- */}
      {stage === 'EXPIRED' || app?.status === 'SUSPENDED' ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="error">
            <b>店铺已停业。</b>
            {app?.suspendedReason ? `原因：${app.suspendedReason}。` : ''}
            已售出订单不受影响（可正常发货与售后）。请在下方上传有效期内的新证件并提交续期，
            审核通过后店铺即可恢复营业。
          </Alert>
        </div>
      ) : null}

      {stage === 'T7' && app?.status === 'APPROVED' ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="warn">
            <b>资质即将到期：</b>还剩 {remaining} 天（{expireDate} 到期）。
            到期后店铺会自动停业、在售商品一并下架，请尽快提交续期。
          </Alert>
        </div>
      ) : null}

      {stage === 'T30' && app?.status === 'APPROVED' ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="info">
            资质将于 {expireDate} 到期（还剩 {remaining} 天）。可以现在提交续期材料，
            避免临近到期时手忙脚乱。
          </Alert>
        </div>
      ) : null}

      {app?.status === 'SUBMITTED' || app?.status === 'REVIEWING' ? (
        <Alert kind="info">
          材料已提交，平台审核中。审核期间材料不可修改，如需补充请联系平台运营。
        </Alert>
      ) : null}

      {app?.status === 'REJECTED' ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="error">
            申请被驳回，请按以下原因修改后重新提交：
            <ul style={{ margin: '8px 0 0 18px' }}>
              {(app.rejectReasons ?? []).map((r, i) => (
                <li key={i}>
                  <b>{r.scope === 'GENERAL' ? '整体' : r.scope === 'STORE_NAME' ? '店铺名' : r.scope}</b>
                  ：{r.note}
                </li>
              ))}
            </ul>
          </Alert>
        </div>
      ) : null}

      {app?.status === 'APPROVED' && stage === 'NONE' ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="success">
            审核已通过，店铺「{app.storeName}」已开通。点击下方按钮领取商家身份并进入工作台。
          </Alert>
          <button className="btn btn-primary" style={{ marginTop: 10 }} onClick={activate} disabled={busy}>
            {busy ? '处理中…' : '进入商家工作台'}
          </button>
        </div>
      ) : null}

      {formVisible ? (
        <>
          {renewalHeading(app, editable)}

          {editable ? (
            <div style={{ marginBottom: 12 }}>
              <label className="small muted">店铺名称（2–30 字，提交后进入审核不可改）</label>
              <input
                className="input"
                value={storeName}
                onChange={(e) => setStoreName(e.target.value)}
                placeholder="例如：豆豆宠物用品专营店"
                maxLength={30}
              />
            </div>
          ) : null}

          <div className="small muted" style={{ margin: '10px 0 6px' }}>
            资质材料（6 类必须齐全；jpg / png / webp / pdf，单个 ≤ 6MB，仅审核相关方可见）
            {renewable ? '　—— 续期只需重传已到期或即将到期的那几份' : ''}
          </div>
          {MERCHANT_MATERIAL_TYPES.map((t) => (
            <div key={t.type} className="row" style={{ alignItems: 'center', marginBottom: 8 }}>
              <div style={{ width: 160 }} className="small">
                {t.label}
              </div>
              {materials[t.type] ? (
                <>
                  <span className="small" style={{ color: 'var(--ok, #2a8)' }}>已上传 ✓</span>
                  <button className="btn btn-ghost btn-sm" onClick={() => fileRefs.current[t.type]?.click()}>
                    重传
                  </button>
                </>
              ) : (
                <>
                  <span className="small muted">未上传</span>
                  <button className="btn btn-ghost btn-sm" onClick={() => fileRefs.current[t.type]?.click()}>
                    上传
                  </button>
                </>
              )}
              {EXPIRY_REQUIRED_TYPES.has(t.type) ? (
                <label className="row small muted" style={{ alignItems: 'center', gap: 4, marginLeft: 10 }}>
                  有效期至
                  <input
                    type="date"
                    className="input"
                    style={{ width: 150 }}
                    value={materialExpiry[t.type] ?? ''}
                    onChange={(e) =>
                      setMaterialExpiry((prev) => ({ ...prev, [t.type]: e.target.value }))
                    }
                  />
                </label>
              ) : null}
              <input
                ref={(el) => {
                  fileRefs.current[t.type] = el;
                }}
                type="file"
                accept="image/jpeg,image/png,image/webp,application/pdf"
                style={{ display: 'none' }}
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  if (f) void upload(t.type, f);
                  e.target.value = '';
                }}
              />
            </div>
          ))}

          {editable && missing.length > 0 ? (
            <div className="small muted" style={{ marginTop: 6 }}>
              还缺 {missing.length} 类：{missing.map((t) => t.label).join('、')}
            </div>
          ) : null}

          <div className="row row-wrap" style={{ marginTop: 16 }}>
            {editable ? (
              <>
                <button className="btn btn-ghost" onClick={save} disabled={busy}>
                  保存草稿
                </button>
                <button className="btn btn-primary" onClick={submit} disabled={busy || missing.length > 0}>
                  {busy ? '处理中…' : '提交审核'}
                </button>
              </>
            ) : (
              <button className="btn btn-primary" onClick={renew} disabled={busy}>
                {busy ? '处理中…' : '提交续期申请'}
              </button>
            )}
          </div>
        </>
      ) : null}
    </div>
  );
};

/** 续期表单上方的说明：同一套表单服务两种意图，这里负责把当前是哪一种讲清楚 */
function renewalHeading(app: MerchantApplicationView | null, editable: boolean): React.ReactNode {
  if (editable || !app) {
    return null;
  }
  const suspended = app.status === 'SUSPENDED';
  return (
    <div className="small muted" style={{ margin: '4px 0 10px' }}>
      {suspended
        ? '店铺已停业。上传新的证件并填写有效期后提交，审核通过即恢复营业。'
        : '提交续期材料。审核期间店铺正常营业，无需担心材料被退回时影响生意。'}
    </div>
  );
}
