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
            // 已售（SOLD）可能是买家自己的上一单（网络超时后的重试）：先问交易域幂等键，
            // 有单重放、没单才真是不可买 —— 否则重试请求在事实校验就被拒，永远付不了款
            //（B-11 冒烟实测抓到：CAS 反查前就被这行挡住）。
            ListingOrderCreated replayed = findReplayFromTrade(buyerId, request.idempotentKey());
            if (replayed != null) {
                return replayed;
            }
            throw BizException.businessRule("该闲置当前不可购买（已下架或未通过审核）");
        }

        int locked = lockAsSold(listing.getId());
        if (locked == 0) {
            // CAS 落空有两种截然不同的含义，不能一律判「被买走」（B-11 冒烟实测抓到）：
            //   ① 同一买家带同一幂等键的重试 —— 首单已成立、锁也是自己下的，
            //      此时必须重放首单；报「手慢了」会让买家下出第二张单（或永远付不了款）。
            //   ② 真被别人买走了 —— 422。
            // 区分方式：拿 (buyerId, idempotentKey) 去交易域问一声，有单就是 ①。
            ListingOrderCreated replayed = findReplayFromTrade(buyerId, request.idempotentKey());
            if (replayed != null) {
                return replayed;
            }
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
        } catch (feign.FeignException e) {
            // 跨域 4xx 是交易域的业务拒绝：Feign 把它们抛成异常而不是返回体
            //（B-11 冒烟实测抓到：403 曾在这里被吞成 500）。解析 body 里的 code/message 原样转译。
            releaseQuietly(listing.getId(), "交易域拒绝建单");
            throw translateFeign(e, "下单失败");
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
        } catch (feign.FeignException e) {
            throw translateFeign(e, "卖出订单暂时查不到");
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
        } catch (feign.FeignException e) {
            // 交易域的 403（非本人发货）/422（状态机不满足）必须原样到达前端（B-11 冒烟实测）
            throw translateFeign(e, "发货失败");
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

    /**
     * Feign 4xx 的错误体 → 本域 BizException（与 trade CoinGateway 的 extractMessage 同款纪律）。
     *
     * <p>Feign 对非 2xx 抛 {@link feign.FeignException} 而不是把 body 当返回值 ——
     * 交易域的 {@code GlobalExceptionHandler} 已把 BizException 转成
     * {@code ApiResponse.fail(code, message)} + 对应 HTTP 状态，这里解析回来原样转译：
     * 403 该是 403、422 该是 422，绝不吞成 500（B-11 冒烟实测踩坑）。
     * 5xx 与解析失败降级 DEGRADED —— 「交易服务暂时不可用」比谎报业务拒绝诚实。
     */
    private static BizException translateFeign(feign.FeignException e, String fallbackMessage) {
        if (e.status() >= 400 && e.status() < 500) {
            String code = null;
            String message = null;
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.contentUTF8());
                if (node != null) {
                    if (node.hasNonNull("code")) {
                        code = node.get("code").asText();
                    }
                    if (node.hasNonNull("message")) {
                        message = node.get("message").asText();
                    }
                }
            } catch (Exception ignored) {
                // 解析失败走兜底
            }
            String text = message == null ? fallbackMessage : message;
            if (code != null) {
                try {
                    return new BizException(ErrorCode.valueOf(code), text);
                } catch (IllegalArgumentException unknownCode) {
                    // 认不出的码也带上原消息，只是语义归 BUSINESS_RULE
                }
            }
            return new BizException(ErrorCode.BUSINESS_RULE, text);
        }
        return new BizException(ErrorCode.DEGRADED, "交易服务暂时不可用，请稍后重试", e);
    }

    /**
     * CAS 落空后的幂等反查（best-effort）：反查也失败时只能按「被买走」处理 ——
     * 这虽然可能误伤极窄窗口里的重试（交易域建单成功但两边都没拿到响应），
     * 但把别人的真实成交让给后来者更糟。
     */
    private ListingOrderCreated findReplayFromTrade(Long buyerId, String idempotentKey) {
        try {
            ApiResponse<ListingOrderCreated> resp =
                    tradeClient.findCreatedByIdempotentKey(buyerId, idempotentKey);
            return resp != null && "SUCCESS".equals(resp.code()) ? resp.data() : null;
        } catch (Exception e) {
            log.warn("CAS 落空后反查交易域幂等单失败，按「被买走」处理（buyer={}，key={}）",
                    buyerId, idempotentKey, e);
            return null;
        }
    }
}
