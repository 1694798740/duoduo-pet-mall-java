/**
 * 入驻申请页（E10）：提交材料 → 平台审核 → 通过后开店。
 *
 * <p>材料走既有上传通道（POST /api/uploads → /uploads/**）；
 * E12 资质材料私有通道落地后，这里只换 upload 的目标端点，表单交互不变。
 *
 * <p>状态展示：DRAFT/REJECTED 可编辑；SUBMITTED/REVIEWING 只读等待；
 * APPROVED 显示「进入工作台」（activate 重签令牌后角色即时生效）。
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { MERCHANT_MATERIAL_TYPES, MerchantApplicationView } from '@duoduo/api-sdk/types';
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
  SUSPENDED: '已暂停',
};

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
  const fileRefs = useRef<Record<string, HTMLInputElement | null>>({});

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const view: MerchantApplicationView = await userApi.getMerchantApplication();
      setApp(view);
      setStoreName(view.storeName.startsWith('待填写-') ? '' : view.storeName);
      setMaterials(view.materials ?? {});
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

  const upload = async (type: string, file: File) => {
    setBusy(true);
    try {
      // E12：材料走私有通道（落私有卷、读取鉴权），不再用公开的 /uploads
      const path = (await userApi.uploadMerchantMaterial(file)).path;
      setMaterials((prev) => ({ ...prev, [type]: path }));
      push('上传成功，记得保存草稿', 'success');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const view: MerchantApplicationView = await userApi.saveMerchantApplication({
        storeName: storeName || undefined,
        materials,
      });
      setApp(view);
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
      await userApi.saveMerchantApplication({ storeName: storeName || undefined, materials });
      const view: MerchantApplicationView = await userApi.submitMerchantApplication();
      setApp(view);
      push('已提交审核，请等待平台处理', 'success');
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

  return (
    <div className="card card-pad" style={{ maxWidth: 720, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 6 }}>商家入驻申请</h2>
      <div className="small muted" style={{ marginBottom: 14 }}>
        当前状态：<b>{STATUS_LABEL[app?.status ?? ''] ?? app?.status}</b>
        {app?.appliedAt ? ` ｜ 最近提交 ${app.appliedAt.slice(0, 10)}` : ''}
      </div>

      {error ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="error">{error}</Alert>
        </div>
      ) : null}

      {app?.status === 'SUBMITTED' || app?.status === 'REVIEWING' ? (
        <Alert kind="info">
          材料已提交，平台审核中。审核通过后回到本页即可开通店铺；
          审核期间材料不可修改，如需补充请联系平台运营。
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

      {app?.status === 'APPROVED' ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="success">
            审核已通过，店铺「{app.storeName}」已开通。点击下方按钮领取商家身份并进入工作台。
          </Alert>
          <button className="btn btn-primary" style={{ marginTop: 10 }} onClick={activate} disabled={busy}>
            {busy ? '处理中…' : '进入商家工作台'}
          </button>
        </div>
      ) : null}

      {editable ? (
        <>
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

          <div className="small muted" style={{ margin: '10px 0 6px' }}>
            资质材料（6 类必须齐全；jpg / png / webp / pdf，单个 ≤ 6MB，仅审核相关方可见）
          </div>
          {MERCHANT_MATERIAL_TYPES.map((t) => (
            <div key={t.type} className="row" style={{ alignItems: 'center', marginBottom: 8 }}>
              <div style={{ width: 160 }} className="small">
                {t.label}
              </div>
              {materials[t.type] ? (
                <>
                  <span className="small" style={{ color: 'var(--ok, #2a8)' }}>已上传 ✓</span>
                  {editable ? (
                    <button className="btn btn-ghost btn-sm" onClick={() => fileRefs.current[t.type]?.click()}>
                      重传
                    </button>
                  ) : null}
                </>
              ) : (
                <>
                  <span className="small muted">未上传</span>
                  <button className="btn btn-ghost btn-sm" onClick={() => fileRefs.current[t.type]?.click()}>
                    上传
                  </button>
                </>
              )}
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
            <button className="btn btn-ghost" onClick={save} disabled={busy}>
              保存草稿
            </button>
            <button className="btn btn-primary" onClick={submit} disabled={busy || missing.length > 0}>
              {busy ? '处理中…' : '提交审核'}
            </button>
          </div>
        </>
      ) : null}
    </div>
  );
};
