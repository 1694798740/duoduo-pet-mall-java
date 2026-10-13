/**
 * 他人主页（S6-D1 补缺 · Web 端对齐小程序 `pages/user-profile`）。
 *
 * <p>为什么补：网页端 C2C 链路此前**断在最后一跳** ——
 * 在关注/粉丝列表、闲置详情、分享圈里能看到别人，但点不进去看他是谁、还发过什么。
 * 小程序侧早有这一页（`GET /api/socials/users/{id}/profile`），所以**后端零改动**，
 * 本页只做前端接线。
 *
 * <p>入口三处（与小程序同源）：关注/粉丝列表 `Follow.tsx`、闲置详情 `ListingDetail.tsx`、
 * 分享圈 `ShareCircle.tsx`。
 *
 * <p>边界处理与小程序保持一致，刻意**不**发起注定失败的请求：
 * 后端 following/followers 只有「查自己」的语义，非本人主页看不到 TA 的关注列表 ——
 * 所以那两个数字只做展示、不可点，并给一句说明（而不是点了 403 再弹错误）。
 */

import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { followApi } from '@duoduo/api-sdk/endpoints';
import type { UserProfileView } from '@duoduo/api-sdk/types';
import { toFriendlyMessage } from '@duoduo/api-sdk/client';
import { Alert, LoadingBlock, useToast } from '@duoduo/shared/components/ui';
import { useAsync } from '@duoduo/shared/components/hooks';
import { useAuth } from '@duoduo/shared/store/auth';

export const UserProfilePage: React.FC = () => {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const { push } = useToast();
  const { user: me } = useAuth();
  const [busy, setBusy] = useState(false);

  // useAsync 的第二个参数是 enabled（布尔），第三个才是 deps —— 传错位不会报错，
  // 只会让"id 变了不重新拉取"，表现为切了人还显示上一个。这里显式写清。
  const { data, loading, error, reload } = useAsync<UserProfileView>(
    () => followApi.profile(id),
    true,
    [id],
  );

  if (loading) return <LoadingBlock />;
  if (error) return <Alert kind="error">{toFriendlyMessage(error)}</Alert>;
  if (!data) return null;

  // ★ 把 data 收进一个局部常量再用：TS 的收窄**不会跨闭包保持**
  //   （hook 返回的值随时可能变），闭包里直接读 data 会被判成 possibly undefined。
  const profile = data;
  const self = !!me && String(me.id) === String(profile.userId);
  const following = profile.viewerFollowing;

  async function toggleFollow() {
    if (busy) return;
    setBusy(true);
    try {
      // 关注 / 取关都是幂等接口，重复点击不会产生脏数据；成功后重取一次（计数会变）
      if (following) await followApi.unfollow(profile.userId);
      else await followApi.follow(profile.userId);
      push(following ? '已取消关注' : '已关注');
      await reload();
    } catch (e) {
      push(toFriendlyMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="col" style={{ gap: 14, maxWidth: 720, margin: '0 auto' }}>
      <div className="card card-pad">
        <div className="row" style={{ gap: 14, alignItems: 'center' }}>
          <div
            style={{
              width: 64, height: 64, borderRadius: '50%', flex: 'none',
              background: 'var(--surface-2)', display: 'grid', placeItems: 'center',
              overflow: 'hidden',
            }}
          >
            {data.avatar ? (
              <img src={data.avatar} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
            ) : (
              <span style={{ fontSize: 26 }}>🐾</span>
            )}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="row" style={{ gap: 8 }}>
              <b style={{ fontSize: 18 }}>{data.nickname || `用户 ${data.userId}`}</b>
              {data.mutual ? <span className="tag tag-success">互相关注</span> : null}
              {data.viewerFollowedBy && !data.mutual && !self ? (
                <span className="tag tag-brand">关注了你</span>
              ) : null}
            </div>
            <div className="small muted" style={{ marginTop: 4 }}>
              关注 {data.following} · 粉丝 {data.followers}
            </div>
          </div>
          {self ? (
            <span className="small muted">这是我自己</span>
          ) : (
            <button className="btn btn-primary btn-sm" disabled={busy} onClick={() => void toggleFollow()}>
              {following ? (data.mutual ? '互相关注' : '已关注') : '＋ 关注'}
            </button>
          )}
        </div>
      </div>

      <div className="card card-pad">
        <div className="row" style={{ gap: 10 }}>
          <button className="btn btn-ghost btn-sm" onClick={() => nav('/follow')}>
            我的关注 / 粉丝
          </button>
          <button className="btn btn-ghost btn-sm" onClick={() => nav('/listings')}>
            去闲置市场
          </button>
        </div>
        <div className="small muted" style={{ marginTop: 10 }}>
          关注列表目前只支持查看自己的；TA 的关注数与粉丝数仅供参考。
        </div>
      </div>
    </div>
  );
}
