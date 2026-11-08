package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.client.TradeClient;
import com.duoduo.product.client.dto.ListingOrderCreated;
import com.duoduo.product.client.dto.ListingOrderPlacement;
import com.duoduo.product.client.dto.ListingSoldOrderView;
import com.duoduo.product.dto.ProductDtos.ListingOrderRequest;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.mapper.ListingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 闲置担保交易服务（B-11 · B2 实现，B1 测试见 {@code ListingOrderServiceTest}）。
 *
 * <h2>职责边界</h2>
 * 商品域只管两件事：
 * <ul>
 *   <li><b>单件锁</b>：闲置是「一物一价、卖完即止」，下单的本质是一次
 *       条件更新（{@code ON_SALE → SOLD}，CAS）—— 并发只有一个人成功；
 *       条件里同时锁住 {@code audit_status} 与 {@code deleted_at}，
 *       「审核中被买走」「软删后被买走」这两种幽灵成交也一并挡掉。</li>
 *   <li><b>入口归属</b>：卖出列表 / 发货以登录态为准（USER 即可，
 *       不走 /api/seller/** 的 SELLER 门禁 —— 个人卖家没有那个角色）。</li>
 * </ul>
 * 订单创建、发货状态机、托管/放款/退款的事实全部在交易域：
 * 商品域通过 {@link TradeClient} 委托，不跨库查（硬规矩 4）。
 *
 * <h2>状态机（listing.status）</h2>
 * <pre>
 *          下单 CAS（唯一成交闸）           支付 → 托管 → 发货 → 确认收货 → 放款
 * ON_SALE ────────────────→ SOLD ───────────────────────────────────────→ 终态（SOLD）
 *    ↑                        │
 *    └──── 补偿回滚（建单失败）│
 *    └──── 释放（取消/超时/退款钩子，幂等）
 * </pre>
 */
@Service
public class ListingOrderService {

    private static final Logger log = LoggerFactory.getLogger(ListingOrderService.class);

    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_SOLD = "SOLD";
    private static final String AUDIT_APPROVED = "APPROVED";

    private final ListingMapper listingMapper;
    private final TradeClient tradeClient;

    public ListingOrderService(ListingMapper listingMapper, TradeClient tradeClient) {
        this.listingMapper = listingMapper;
        this.tradeClient = tradeClient;
    }

    /**
     * 买家下单：请求硬校验 → 事实校验 → CAS 锁定 → 委托交易域建单。
     *
     * <p>补偿纪律：交易域拒绝或不可达时必须把 SOLD 回滚成 ON_SALE 再抛 ——
     * 一次远程抖动锁死一条真实闲置（卖家看不见、买家买不了、也没人知道），
     * 比下单失败本身坏得多。
     */
    public ListingOrderCreated placeOrder(Long buyerId, ListingOrderRequest request) {
        requireValidRequest(request);

        ListingEntity listing = listingMapper.selectById(request.listingId());
        if (listing == null || listing.getDeletedAt() != null) {
            throw BizException.notFound("闲置商品");
        }
        if (listing.getSellerId() != null && listing.getSellerId().equals(buyerId)) {
            throw BizException.businessRule("这是你自己发布的闲置，无需购买");
        }
        if (!STATUS_ON_SALE.equals(listing.getStatus()) || !AUDIT_APPROVED.equals(listing.getAuditStatus())) {
            throw BizException.businessRule("该闲置当前不可购买（已下架或未通过审核）");
        }

        int locked = lockAsSold(listing.getId());
        if (locked == 0) {
            // CAS 落空 = 窗口期内被并发买走/下架/删除 —— 先查后改在这里一定会翻车
            throw BizException.businessRule("手慢了，该闲置刚被买走或已下架");
        }

        ListingOrderPlacement placement = new ListingOrderPlacement(
                buyerId, listing.getId(), listing.getSellerId(),
                listing.getTitle(), listing.getPrice(),
                request.receiverName(), request.receiverPhone(), request.receiverAddress(),
                request.idempotentKey());
        try {
            ApiResponse<ListingOrderCreated> resp = tradeClient.placeListingOrder(placement);
            if (resp == null || !"SUCCESS".equals(resp.code())) {
                releaseQuietly(listing.getId(), "交易域拒绝建单");
                throw translate(resp, "下单失败");
            }
            return resp.data();
        } catch (BizException already) {
            throw already; // 上面已带补偿，别再包一层
        } catch (Exception e) {
            releaseQuietly(listing.getId(), "交易域不可达");
            throw new BizException(ErrorCode.INTERNAL, "下单失败，请稍后重试");
        }
    }

    /** 卖家视角的卖出订单列表（数据事实在交易域 t_order，此处只透传） */
    public PageResult<ListingSoldOrderView> listSold(Long sellerId, String status, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? 10 : Math.min(pageSize, 100);
        String statusFilter = StringUtils.hasText(status) ? status.trim() : null;
        try {
            ApiResponse<PageResult<ListingSoldOrderView>> resp =
                    tradeClient.soldOrders(sellerId, statusFilter, pageNo, size);
            if (resp == null || !"SUCCESS".equals(resp.code())) {
                throw translate(resp, "卖出订单暂时查不到");
            }
            return resp.data();
        } catch (BizException already) {
            throw already;
        } catch (Exception e) {
            log.error("卖家 {} 拉取卖出订单失败", sellerId, e);
            throw new BizException(ErrorCode.INTERNAL, "卖出订单暂时查不到，请稍后重试");
        }
    }

    /**
     * 卖家发货：归属与状态校验在交易域（C2C 单 sellerId==操作者、PAID 才可发），
     * 商品域只带登录态转发 —— 校验收在交易域一处，避免两处口径漂移。
     */
    public void ship(Long operatorId, String orderNo, String logisticsCompany, String logisticsNo) {
        if (!StringUtils.hasText(orderNo) || !StringUtils.hasText(logisticsCompany)
                || !StringUtils.hasText(logisticsNo)) {
            throw BizException.validation("订单号与物流公司/单号不能为空");
        }
        try {
            ApiResponse<Void> resp =
                    tradeClient.shipListingOrder(orderNo.trim(), operatorId,
                            logisticsCompany.trim(), logisticsNo.trim());
            if (resp == null || !"SUCCESS".equals(resp.code())) {
                throw translate(resp, "发货失败");
            }
        } catch (BizException already) {
            throw already;
        } catch (Exception e) {
            log.error("闲置订单 {} 发货转发失败", orderNo, e);
            throw new BizException(ErrorCode.INTERNAL, "发货暂时失败，请稍后重试");
        }
    }

    /**
     * 释放闲置（SOLD→ON_SALE）：取消 / 支付超时 / 未发货退款三类钩子的落点。
     * 幂等：已不是 SOLD 时返回 false、不抛 —— 三个钩子可能对同一单重复触发
     * （以及取消与退款几乎同时到达），重复释放必须安全。
     */
    public boolean releaseToOnSale(Long listingId) {
        if (listingId == null) {
            return false;
        }
        return listingMapper.update(null, new LambdaUpdateWrapper<ListingEntity>()
                .set(ListingEntity::getStatus, STATUS_ON_SALE)
                .eq(ListingEntity::getId, listingId)
                .eq(ListingEntity::getStatus, STATUS_SOLD)
                .isNull(ListingEntity::getDeletedAt)) > 0;
    }

    // ------------------------------------------------------------------
    // 内部动作
    // ------------------------------------------------------------------

    /** CAS 成交闸：ON_SALE+已过审+未软删 才允许转 SOLD；返回影响行数（0=被抢走） */
    private int lockAsSold(Long listingId) {
        return listingMapper.update(null, new LambdaUpdateWrapper<ListingEntity>()
                .set(ListingEntity::getStatus, STATUS_SOLD)
                .eq(ListingEntity::getId, listingId)
                .eq(ListingEntity::getStatus, STATUS_ON_SALE)
                .eq(ListingEntity::getAuditStatus, AUDIT_APPROVED)
                .isNull(ListingEntity::getDeletedAt));
    }

    /** 补偿回滚：尽力而为、绝不另抛（调用方自己已有要抛的错） */
    private void releaseQuietly(Long listingId, String why) {
        try {
            boolean released = releaseToOnSale(listingId);
            log.warn("闲置 {} 已补偿回滚 SOLD→ON_SALE（{}）released={}", listingId, why, released);
        } catch (Exception e) {
            // 回滚本身失败说明库出问题了，只能记日志等待人工/对账 —— 不许把补偿异常盖住原异常
            log.error("闲置 {} 补偿回滚失败！请人工核对状态（{}）", listingId, why, e);
        }
    }

    private static void requireValidRequest(ListingOrderRequest request) {
        if (request == null) {
            throw BizException.validation("请求不能为空");
        }
        if (request.listingId() == null) {
            throw BizException.validation("缺少闲置 ID");
        }
        if (!StringUtils.hasText(request.receiverName())
                || !StringUtils.hasText(request.receiverPhone())
                || !StringUtils.hasText(request.receiverAddress())) {
            throw BizException.validation("收货人 / 电话 / 地址不能为空");
        }
        if (!StringUtils.hasText(request.idempotentKey())) {
            throw BizException.validation("缺少幂等键，请重试并携带新键");
        }
    }

    /**
     * 交易域的错误码 → 本域 BizException：合法枚举名原样透传（422/429 等语义
     * 才能一路传到前端），认不出的码兜底 BUSINESS_RULE —— 绝不吞错。
     */
    private static BizException translate(ApiResponse<?> resp, String fallbackMessage) {
        String code = resp == null ? null : resp.code();
        String message = resp == null || !StringUtils.hasText(resp.message())
                ? fallbackMessage : resp.message();
        ErrorCode errorCode;
        try {
            errorCode = code == null ? ErrorCode.INTERNAL : ErrorCode.valueOf(code);
        } catch (IllegalArgumentException unknownCode) {
            errorCode = ErrorCode.BUSINESS_RULE;
        }
        return new BizException(errorCode, message);
    }
}
