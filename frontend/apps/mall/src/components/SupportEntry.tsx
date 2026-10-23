import React from 'react';
import { useNavigate } from 'react-router-dom';

/**
 * 「问客服」统一入口。
 *
 * <h2>为什么不再请求后端拿客服账号</h2>
 * 客服会话自第七十八轮起有独立通道（`/support` → `/api/support/**`），
 * 接待方是谁由服务端按 sellerId 决定：不传 = 自营客服，传商品卖家 = 该商家的客服。
 * 前端不需要（也不应该）知道官方客服账号 id —— 那是配置，换人只改服务端一处。
 *
 * <h2>为什么不把用户送进私信</h2>
 * 私信有「非互关限一条」的社交规则，客服没有；混在一起最直接的后果是
 * 用户问了一句就被挡住，还得等客服回关。两条通道从路由到数据表都是分开的。
 */
export const SupportEntry: React.FC<{ small?: boolean; sellerId?: string; productId?: string }> = ({
  small,
  sellerId,
  productId,
}) => {
  const navigate = useNavigate();

  const open = () => {
    const query = new URLSearchParams();
    // sellerId=0 是自营口径（不是店铺 id）：照样带上，服务端会把它落到官方客服。
    // 只有「完全没传」才走列表页 —— 传 0 的语义是「我知道要找自营客服」。
    if (sellerId) query.set('sellerId', sellerId);
    if (productId) query.set('productId', productId);
    const suffix = query.toString();
    navigate(suffix ? `/support?${suffix}` : '/support');
  };

  return (
    <button
      type="button"
      className={`btn btn-ghost${small ? ' btn-sm' : ''}`}
      onClick={open}
      title="与客服私信沟通（自营或该商品所属商家）"
    >
      问客服
    </button>
  );
};
