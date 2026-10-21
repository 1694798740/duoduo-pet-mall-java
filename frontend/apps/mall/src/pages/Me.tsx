/**
 * 个人中心。
 *
 * 数据来源：GET /api/users/me（经 AuthProvider 拿到）、
 *          PATCH /api/users/me（改昵称/头像）、POST /api/users/me/realname（实名）
 *
 * 关于实名认证：后端只保存身份证掩码，明文不入库、不回传。
 * 因此界面上的身份证输入框只做即时校验，提交后只回显 xxx****xxxx。
 *
 * <h2>页面分区（2026-10-21 重排）</h2>
 * 旧版是「左列只挂一张基本资料卡、右列从上到下堆五张卡」，左列下半整屏空白，
 * 视线要跨三屏才能找齐「账号状态 → 信用分 → 实名 → 快捷入口 → 注销」。
 * 现在按「身份 → 信用与认证 → 账号与隐私」三区从上往下：
 * <ol>
 *   <li>**身份区**：基本资料（左）+ 账号状态与快捷入口（右）—— 都是「我是谁」这一层；</li>
 *   <li>**信用与认证区**：信用分（左）+ 实名认证（右）—— 都是「平台如何评估 / 校验我」；</li>
 *   <li>**账号与隐私区**：账号注销 —— 不可逆操作独立成区放在最后，与前面的日常操作拉开距离。</li>
 * </ol>
 * 只挪卡片位置、不改任何样式类，功能与视觉风格保持不变。
 */

import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { refreshSession, toFriendlyMessage } from '@duoduo/api-sdk/client';
import { userApi } from '@duoduo/api-sdk/endpoints';
import { formatDate } from '@duoduo/api-sdk/client';
import { Alert, Field, LoadingBlock, Modal, useToast } from '@duoduo/shared/components/ui';
import type { DeleteBlockersView } from '@duoduo/api-sdk/types';
import { ImageUploader } from '@duoduo/shared/components/ImageUploader';
import { CreditPanel } from '@duoduo/shared/components/CreditBadge';
import { canAccessSellerConsole } from '@duoduo/shared/lib/roles';
import { useAuth } from '@duoduo/shared/store/auth';

/** 身份证 18 位：6 位地区 + 8 位生日 + 3 位顺序 + 1 位校验（末位可为 X） */
const ID_CARD_PATTERN = /^\d{17}[\dXx]$/;

/**
 * 角色中文名。
 * 库里存的是大写（USER / SELLER / OPERATOR / ADMIN），这里先转大写再比对 ——
 * 直接拿 'seller' 去等于一个大写值永远为 false，会把卖家显示成「买家」。
 */
const ROLE_LABELS: Record<string, string> = {
  USER: '买家',
  SELLER: '卖家',
  OPERATOR: '运营',
  ADMIN: '管理员',
};

export function roleLabelOf(role: string): string {
  return ROLE_LABELS[role.toUpperCase()] ?? role;
}

/**
 * C7 · 账号注销面板。
 *
 * 三条与后端状态机一一对应的规则：
 *   ① 展开即**预检**，把未完成的订单 / 售后 / 店铺 / 闲置摆出来 ——
 *      不能让用户填完密码才被告知不行；
 *   ② 提交需要密码 + 勾选确认，两个条件缺一不可（不可逆操作）；
 *   ③ 冷静期内登录被停用，撤销用「手机号 + 密码」，不依赖会话。
 *
 * <h2>撤销区（2026-10-21 修正）</h2>
 * <ul>
 *   <li>**只在冷静期 / 刚提交后出现** —— 账号状态正常时这块表单毫无意义，
 *       一直摆着反而让人以为「随时要填一遍手机号密码」；</li>
 *   <li>**输入框一律空白、一律手填**。此前的「预填了当前用户名和密码」不是代码赋值，
 *       是浏览器自动填充：这组 input 没声明 `autoComplete`，Chrome 会把登录表单记住的
 *       账号密码塞进来。撤销验证和登录是**两件事**（这里是"证明你是号主"而不是"再登一次"），
 *       被填上登录凭据既吓人又会诱导用户直接点提交 —— 所以表单级 `autoComplete="off"`，
 *       密码框再加一道 `new-password`（Chrome 对 password 框只认这个，不认 off）；</li>
 *   <li>**结果就地展示**（Alert）而不是只飘一条 toast —— toast 几秒就没了，
 *       撤销失败的人需要反复看清失败原因（手机号错了？密码错了？）。</li>
 * </ul>
 */
function DeleteAccountPanel() {
  const { push } = useToast();
  const { logout } = useAuth();
  const [show, setShow] = useState(false);
  const [loading, setLoading] = useState(false);
  const [blockers, setBlockers] = useState<DeleteBlockersView | null>(null);
  const [password, setPassword] = useState('');
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  // 二次确认弹窗：注销不可逆，必须在提交前再拦一道（与小程序端 wx.showModal 同一语义）
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [cancelPhone, setCancelPhone] = useState('');
  const [cancelPwd, setCancelPwd] = useState('');
  /** 撤销结果就地展示：toast 会消失，失败原因必须留 在页面上让人看清 */
  const [cancelResult, setCancelResult] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  // 提交成功后进入冷静期，本页会把会话清掉 —— 需要一个"已提交"的停留态
  const [submitted, setSubmitted] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setBlockers(await userApi.deleteAccount.precheck());
    } catch (err) {
      push(toFriendlyMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const cancelling = blockers?.accountStatus === 'CANCELLING';
  const blocked = !!blockers?.blocked;
  const graceDays = blockers?.graceDays ?? 7;

  /** 一次点击只开面板 + 预检，不做任何破坏性动作 */
  const open = async () => {
    setShow(true);
    setSubmitted(false);
    if (!blockers) await load();
  };

  /** 第二次点击才走到输入框，第三次（在弹窗里）才真正提交 */
  const askConfirm = () => {
    if (!password) return push('请输入登录密码');
    if (!confirmed) return push('请先勾选「我已了解后果」');
    setConfirmOpen(true);
  };

  const submit = async () => {
    setBusy(true);
    try {
      const view = await userApi.deleteAccount.request({ password, reason });
      setConfirmOpen(false);
      setSubmitted(true);
      setPassword('');
      setConfirmed(false);
      // 冷静期内后端已吊销全部 refresh 令牌并拒绝登录，本地会话留着只会让用户
      // 点什么都报"未登录" —— 直接登出，并停在结果页说明状态。
      await logout();
      push(`注销申请已提交，${view.graceDays} 天冷静期内无法登录，期满后执行`);
    } catch (err) {
      push(toFriendlyMessage(err));
      setConfirmOpen(false);
      await load(); // 409 时把阻塞清单刷出来
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    setCancelResult(null);
    if (!/^1\d{10}$/.test(cancelPhone.trim())) {
      setCancelResult({ kind: 'error', text: '请输入注册时使用的 11 位手机号（纯数字，不带空格或横线）' });
      return;
    }
    if (!cancelPwd) {
      setCancelResult({ kind: 'error', text: '请输入该账号的登录密码' });
      return;
    }
    setBusy(true);
    try {
      await userApi.deleteAccount.cancelByPhone({ phone: cancelPhone.trim(), password: cancelPwd });
      setCancelResult({ kind: 'success', text: '已撤销注销，账号恢复正常，现在可以用手机号重新登录了' });
      setCancelPwd('');
      setCancelPhone('');
      setSubmitted(false);
      await load();
    } catch (err) {
      // 401（手机号或密码不对）/ 404（没有进行中的注销）/ 409 等都原文透出，
      // 不猜原因 —— 猜错一句会把人引去改本来就对的输入
      setCancelResult({ kind: 'error', text: toFriendlyMessage(err) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card card-pad" style={{ marginTop: 16 }}>
      <h3 style={{ marginBottom: 10 }}>账号注销</h3>
      <p className="small muted" style={{ marginBottom: 10 }}>
        《用户隐私保护指引》承诺可注销账号。注销后手机号、昵称、头像、地址、宠物档案将被清除；
        订单等交易记录按法规留存并脱敏。
      </p>
      <button className="btn btn-ghost btn-sm" onClick={() => void open()}>
        {cancelling ? '查看注销进度 / 撤销' : '注销账号'}
      </button>

      {/* 冷静期/已提交：账号已不可用，直接把结果说清楚，不再展示申请表单 */}
      {cancelling || submitted ? (
        <Alert kind="warn">
          {submitted
            ? `注销申请已提交，账号进入 ${graceDays} 天冷静期，期间无法登录`
            : `账号正在 ${graceDays} 天注销冷静期`}
          {blockers?.scheduledAt ? `，将于 ${formatDate(blockers.scheduledAt)} 执行` : ''}
          。期间反悔可在下方撤销。
        </Alert>
      ) : null}

      {show && !submitted && !cancelling ? (
        <div style={{ marginTop: 14 }}>
          {loading ? (
            <LoadingBlock />
          ) : blocked ? (
            <Alert kind="warn">
              还有 {blockers?.items.length ?? 0} 件事没处理完，处理后再申请：
              <ul style={{ marginTop: 6 }}>
                {blockers?.items.map((it) => (
                  <li key={it.ref || it.type}>
                    {it.detail}
                    {it.ref ? `（${it.ref}）` : ''}
                  </li>
                ))}
              </ul>
            </Alert>
          ) : (
            <>
              <Field label="登录密码">
                <input
                  className="input"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="请输入登录密码"
                  autoComplete="current-password"
                />
              </Field>
              <Field label="注销原因（选填）">
                <input
                  className="input"
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="帮我们改进"
                />
              </Field>
              <label className="small" style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                我已了解：注销后个人数据无法恢复
              </label>
              <button
                className="btn btn-primary"
                style={{ marginTop: 10 }}
                disabled={busy}
                onClick={askConfirm}
              >
                {busy ? '提交中…' : '申请注销账号'}
              </button>
            </>
          )}
        </div>
      ) : null}

      {/* 撤销区只在冷静期出现：账号正常时它毫无意义，常驻只会让人困惑 */}
      {cancelling || submitted ? (
        <form
          autoComplete="off"
          onSubmit={(e) => {
            e.preventDefault();
            void cancel();
          }}
          style={{ marginTop: 16, borderTop: '1px solid var(--border, #e6e8eb)', paddingTop: 12 }}
        >
          <p className="small muted" style={{ marginBottom: 8 }}>
            撤销注销 —— 冷静期内登录已停用，这里用<strong>注册手机号 + 登录密码</strong>验证身份，
            不需要登录状态。两项都需要手动输入。
          </p>
          {cancelResult ? (
            <div style={{ marginBottom: 10 }}>
              <Alert kind={cancelResult.kind}>{cancelResult.text}</Alert>
            </div>
          ) : null}
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
            <Field label="注册手机号">
              <input
                className="input"
                style={{ width: 200 }}
                value={cancelPhone}
                onChange={(e) => setCancelPhone(e.target.value)}
                placeholder="请输入 11 位手机号"
                inputMode="numeric"
                autoComplete="off"
              />
            </Field>
            <Field label="登录密码">
              <input
                className="input"
                style={{ width: 200 }}
                type="password"
                value={cancelPwd}
                onChange={(e) => setCancelPwd(e.target.value)}
                placeholder="请手动输入登录密码"
                autoComplete="new-password"
              />
            </Field>
            <button className="btn btn-ghost btn-sm" disabled={busy} type="submit">
              {busy ? '验证中…' : '撤销注销'}
            </button>
          </div>
        </form>
      ) : null}

      {/* 二次确认：与小程序端 wx.showModal 的语义一致，文案也保持一致 */}
      <Modal
        open={confirmOpen}
        title="确认注销账号？"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost btn-sm" onClick={() => setConfirmOpen(false)}>
              再想想
            </button>
            <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void submit()}>
              {busy ? '提交中…' : '确认注销'}
            </button>
          </>
        }
      >
        <p style={{ lineHeight: 1.8, marginBottom: 10 }}>
          注销后以下数据将被<strong>清除且无法恢复</strong>：手机号、昵称、头像、实名信息、
          收货地址、宠物档案。
        </p>
        <p style={{ lineHeight: 1.8, marginBottom: 10 }}>
          订单、支付、售后等交易记录按法规留存，其中的收件人信息会做脱敏。
        </p>
        <p style={{ lineHeight: 1.8 }}>
          账号将进入 {graceDays} 天冷静期，期间<strong>无法登录</strong>，期满才真正执行；
          期间可以撤销。
        </p>
      </Modal>
    </div>
  );
}

export const MePage: React.FC = () => {
  const { user, pets, refreshMe, loading } = useAuth();
  const { push } = useToast();

  const [nickname, setNickname] = useState('');
  const [avatar, setAvatar] = useState('');
  const [busy, setBusy] = useState(false);
  /** 头像上传/转存中：此时提交会把「旧头像」存下去，必须一起禁用 */
  const [uploading, setUploading] = useState(false);
  /** 头像自动保存中（选完图立即落库并同步右上角） */
  const [avatarSaving, setAvatarSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [realName, setRealName] = useState('');
  const [idCard, setIdCard] = useState('');
  const [realBusy, setRealBusy] = useState(false);
  const [realError, setRealError] = useState<string | null>(null);

  const userId = user?.id;
  // 只在「换了账号」时回填表单：refreshMe 每次都换 user 对象引用，
  // 若依赖写 [user]，头像自动保存后一次 refreshMe 就会把用户正在编辑的昵称冲掉。
  useEffect(() => {
    if (user) {
      setNickname(user.nickname);
      setAvatar(user.avatar ?? '');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  if (loading) return <LoadingBlock text="正在加载个人信息…" />;
  if (!user) return null;

  /**
   * 头像**选完即存**（2026-10-19）：上传/贴地址/点 × 都立即落库并 refreshMe，
   * 右上角头像随之同步 —— 不要求用户再找一次「保存资料」。
   * 后端 PATCH /api/users/me 是三态语义：avatar 传 null/缺省=不改、传 ''=清空。
   */
  const saveAvatar = async (next: string) => {
    if (next === (user.avatar ?? '')) return;
    setAvatar(next);
    setAvatarSaving(true);
    try {
      await userApi.update({ avatar: next });
      await refreshMe();
      push(next ? '头像已更新，右上角已同步' : '头像已清除，右上角已恢复默认', 'success');
    } catch (err) {
      push(toFriendlyMessage(err), 'error');
      setAvatar(user.avatar ?? '');
    } finally {
      setAvatarSaving(false);
    }
  };

  const saveProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!nickname.trim()) {
      setError('昵称不能为空');
      return;
    }
    setBusy(true);
    try {
      await userApi.update({ nickname: nickname.trim(), avatar: avatar.trim() || undefined });
      await refreshMe();
      push('资料已更新', 'success');
    } catch (err) {
      setError(toFriendlyMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const submitRealName = async (e: React.FormEvent) => {
    e.preventDefault();
    setRealError(null);
    if (!realName.trim()) {
      setRealError('请填写真实姓名');
      return;
    }
    if (!ID_CARD_PATTERN.test(idCard.trim())) {
      setRealError('请输入合法的 18 位身份证号');
      return;
    }
    setRealBusy(true);
    try {
      await userApi.realName({ realName: realName.trim(), idCard: idCard.trim().toUpperCase() });
      // ★ 实名状态写在访问令牌里（15 分钟），认证成功后必须**立刻轮转一次令牌**，
      //   否则发布闲置仍会 403（个人中心读的是 /me 实时数据，两处就会「一边已认证、一边说没认证」）。
      //   R52 在小程序端修过同款，网页端此前漏了 —— 不刷新的话，用户要干等令牌过期或退出重登。
      await refreshSession();
      await refreshMe();
      setIdCard('');
      setRealName('');
      push('实名认证已完成', 'success');
    } catch (err) {
      setRealError(toFriendlyMessage(err));
    } finally {
      setRealBusy(false);
    }
  };

  const quickLinks = (
    <>
      {canAccessSellerConsole(user.role) ? (
        <Link className="btn btn-primary btn-sm" to="/seller">
          商家端
        </Link>
      ) : null}
      <Link className="btn btn-ghost btn-sm" to="/orders">
        我的订单
      </Link>
      <Link className="btn btn-ghost btn-sm" to="/coupons">
        我的券包
      </Link>
      <Link className="btn btn-ghost btn-sm" to="/after-sales">
        我的售后
      </Link>
      <Link className="btn btn-ghost btn-sm" to="/reviews/mine">
        我的评价
      </Link>
      <Link className="btn btn-ghost btn-sm" to="/cart">
        购物车
      </Link>
      <Link className="btn btn-ghost btn-sm" to="/pets">
        宠物档案
      </Link>
      <Link className="btn btn-ghost btn-sm" to="/listings">
        二手闲置
      </Link>
    </>
  );

  return (
    <div>
      <div className="section-head" style={{ marginTop: 8 }}>
        <h2 className="section-title">个人中心</h2>
        <span className="section-sub">账号 ID {user.id}</span>
      </div>

      {/* ── 第一区 · 身份：我是谁（资料 + 状态 + 常去的地方） ─────────────── */}
      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>基本资料</h3>

          {error ? (
            <div style={{ marginBottom: 12 }}>
              <Alert kind="error">{error}</Alert>
            </div>
          ) : null}

          <form onSubmit={saveProfile}>
            <Field label="手机号" hint="手机号不可修改，如需更换请联系客服">
              <input className="input" value={user.phoneMasked} disabled />
            </Field>

            <Field label="昵称" required>
              <input
                className="input"
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                maxLength={20}
              />
            </Field>

            <Field
              label="头像"
              hint="点击头像即可更换（悬停出现「编辑头像」）；点 × 清除后此处变回上传入口；右上角头像即时同步"
            >
              <ImageUploader
                value={avatar ? [avatar] : []}
                onChange={(next) => void saveAvatar(next[0] ?? '')}
                max={1}
                size={72}
                shape="circle"
                onBusyChange={setUploading}
                addLabel="上传头像"
                editOverlay="编辑头像"
                hint=""
                urlPlaceholder="或粘贴头像图片地址"
              />
            </Field>

            <button
              className="btn btn-primary"
              type="submit"
              disabled={busy || uploading || avatarSaving}
            >
              {busy ? '保存中…' : uploading || avatarSaving ? '头像处理中…' : '保存资料'}
            </button>
          </form>
        </div>

        <div>
          <div className="card card-pad">
            <h3 style={{ marginBottom: 12 }}>账号状态</h3>
            <dl className="spec-list">
              <dt>角色</dt>
              <dd>{roleLabelOf(user.role)}</dd>
              <dt>账号状态</dt>
              <dd>{user.status === 'ACTIVE' ? '正常' : user.status}</dd>
              <dt>注册时间</dt>
              <dd>{formatDate(user.createdAt)}</dd>
              <dt>宠物档案</dt>
              <dd>
                {pets.length ? (
                  <>
                    {pets.length} 个 · {pets.map((p) => p.name).join('、')}
                    <Link to="/pets" className="text-brand" style={{ marginLeft: 6 }}>
                      管理
                    </Link>
                  </>
                ) : (
                  <>
                    未建档
                    <Link to="/pets" className="text-brand" style={{ marginLeft: 6 }}>
                      去建档
                    </Link>
                  </>
                )}
              </dd>
            </dl>
          </div>

          <div className="card card-pad" style={{ marginTop: 16 }}>
            <h3 style={{ marginBottom: 10 }}>快捷入口</h3>
            <div className="row row-wrap">{quickLinks}</div>
            {canAccessSellerConsole(user.role) ? null : (
              <div className="small muted" style={{ marginTop: 10 }}>
                想卖东西？完成实名后到<Link to="/seller" className="text-brand"> 商家端 </Link>
                一键申请成为卖家。
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── 第二区 · 信用与认证：平台如何评估 / 校验我 ───────────────────── */}
      <div className="grid grid-2" style={{ marginTop: 16, alignItems: 'start' }}>
        <div className="card card-pad">
          <h3 style={{ marginBottom: 10 }}>信用分</h3>
          {/* 这里刻意**不**用 user.creditScore：那是用户库里的一份副本（定时任务回写），
              可能落后于履约域的实时计算值。同一页面上放两个来源的同一指标，
              迟早会出现「上面 100、下面 60」—— 所以只留这一个权威来源。 */}
          <CreditPanel userId={user.id} />
        </div>

        <div className="card card-pad">
          <h3 style={{ marginBottom: 12 }}>实名认证</h3>

          {user.realNameVerified ? (
            <dl className="spec-list">
              <dt>真实姓名</dt>
              <dd>{user.realName ?? '—'}</dd>
              <dt>身份证号</dt>
              <dd>{user.idCardMasked ?? '—'}</dd>
              <dt>状态</dt>
              <dd>
                <span className="tag tag-success">已认证</span>
              </dd>
            </dl>
          ) : (
            <>
              <Alert kind="info">
                实名信息仅用于交易合规校验，服务端只存储掩码，不回传明文。
              </Alert>
              {realError ? (
                <div style={{ margin: '12px 0' }}>
                  <Alert kind="error">{realError}</Alert>
                </div>
              ) : null}
              <form onSubmit={submitRealName} style={{ marginTop: 12 }}>
                <Field label="真实姓名" required>
                  <input
                    className="input"
                    value={realName}
                    onChange={(e) => setRealName(e.target.value)}
                    maxLength={20}
                  />
                </Field>
                <Field label="身份证号" required hint="18 位，末位可为 X">
                  <input
                    className="input"
                    value={idCard}
                    onChange={(e) => setIdCard(e.target.value.replace(/[^\dxX]/g, '').slice(0, 18))}
                    placeholder="请输入身份证号"
                  />
                </Field>
                <button className="btn btn-primary" type="submit" disabled={realBusy}>
                  {realBusy ? '提交中…' : '提交认证'}
                </button>
              </form>
            </>
          )}
        </div>
      </div>

      {/* ── 第三区 · 账号与隐私：不可逆操作独立成区，放在最后 ─────────────── */}
      {/* C7 · 账号注销：隐私指引已承诺可注销，入口必须真实可达 */}
      <DeleteAccountPanel />
    </div>
  );
};
