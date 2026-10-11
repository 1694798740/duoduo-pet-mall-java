package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangeItem;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangeOutcome;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangePayload;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品服务访问网关：把「怎么调用商品服务」和「调用失败怎么处理」收敛到一处。
 *
 * <p>为什么要有这一层：购物车与订单都要取 SKU 快照、都要做降级处理。
 * 如果各自写一遍，两边对「商品服务返回 4xx 算不算故障」「超时要不要重试」的理解迟早会分叉，
 * 表现出来就是同一份数据在两个页面行为不一致 —— 这种问题极难排查。
 *
 * <p>降级口径（全项目统一）：
 * <ul>
 *   <li>商品服务返回 <b>4xx</b> → 视为「查不到 / 参数被拒」，返回空结果，让上层按「商品不存在」处理；</li>
 *   <li>商品服务<b>不可用或返回 5xx</b> → 抛 503 {@code DEGRADED}，
 *       <b>不</b>伪装成「购物车空了」或「商品都不存在」——
 *       把依赖故障说成业务事实，会让用户做出错误操作（比如以为自己买的商品被删了）。</li>
 * </ul>
 */
@Component
public class ProductGateway {

    private static final Logger log = LoggerFactory.getLogger(ProductGateway.class);

    private final ProductClient productClient;

    public ProductGateway(ProductClient productClient) {
        this.productClient = productClient;
    }

    /** 批量取 SKU 快照；单个失败不影响整批 */
    public Map<Long, SkuSnapshot> loadSnapshots(Collection<Long> skuIds) {
        Set<Long> distinct = skuIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        String csv = distinct.stream().map(String::valueOf).collect(Collectors.joining(","));
        ApiResponse<List<SkuSnapshot>> response = call(() -> productClient.batchSkus(csv), "批量查询 SKU");
        if (response == null || response.data() == null) {
            return Map.of();
        }
        Map<Long, SkuSnapshot> map = new HashMap<>();
        for (SkuSnapshot snap : response.data()) {
            if (snap != null && snap.skuId() != null) {
                map.put(snap.skuId(), snap);
            }
        }
        return map;
    }

    /** 冻结库存；返回逐项结果，调用方按 {@code allSuccess} 决定是否补偿 */
    public StockChangeOutcome freeze(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.freezeStock(new StockChangePayload(items, null)),
                "冻结库存(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    public StockChangeOutcome release(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.releaseStock(new StockChangePayload(items, null)),
                "释放库存(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    public StockChangeOutcome deduct(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.deductStock(new StockChangePayload(items, null)),
                "扣减库存(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    /**
     * 退货入库（退款后把货补回库存）。
     *
     * <p><b>幂等号（第五十八轮 · B-02）</b：这里额外带上 {@code refNo = restock:{orderId}}。
     * 商品侧的入库没有天然幂等护栏，光靠本侧台账（RESTOCK_PENDING → RESTOCKED）挡不住
     * 「调用已生效、响应丢失」——那种情况下本侧判失败、台账留在 PENDING，下一轮补偿会再调一次，
     * 库存就凭空多一份。带上幂等号后，即使重试，商品侧也会撞主键跳过加库存。
     *
     * <p>幂等号按订单而不按台账行：一次退款的入库就是「这个订单的货回库」这件事，
     * 同一订单重复请求应当被去重；若同一订单发生第二次独立退款，则本就不该再回一次库。
     */
    public StockChangeOutcome restock(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.restockStock(new StockChangePayload(items, "restock:" + orderId)),
                "退货入库(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    /**
     * 统一异常转换。
     * Feign 对 4xx 会抛 {@link FeignException}，此时 body 里其实是标准的 ApiResponse，
     * 但这里刻意不解析它：4xx 意味着「这次调用本身不成立」，
     * 让上层按业务语义（如商品不存在）处理，而不是把商品服务的错误码原样透传出去。
     */
    private <T> T call(java.util.function.Supplier<T> action, String what) {
        try {
            return action.get();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                log.warn("商品服务返回 {}（{}）", e.status(), what);
                return null;
            }
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后重试", e);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后重试", e);
        }
    }

    // ==================== S3 对账专用：严格语义 ====================

    /**
     * 严格批量取 SKU 快照 —— <b>任何"读不到"都抛错，绝不静默返回空</b>。
     *
     * <p><b>为什么不能复用上面的 {@link #loadSnapshots}</b>：那个方法把 4xx 折成空 Map，
     * 对购物车是对的（"这件商品查不到"就是业务事实）。但对账里「读不到」与「真没有」
     * 是**两件完全不同的事**：
     * <ul>
     *   <li>真没有 → 商品域确实没这个 SKU → <b>是一条差异</b>（冻结挂在不存在的商品上）；</li>
     *   <li>读不到 → 参数被拒 / 响应体为空 → <b>什么结论都没有</b>，
     *       此时若按"真没有"处理，会把一次调用故障报成 N 条假差异 ——
     *       而假差异的代价是人工排查一整轮，并且从此没人相信这份报表。</li>
     * </ul>
     * 所以这里宁可让整轮对账记成 FAILED（未知），也不产出可能错误的差异。
     */
    public Map<Long, SkuSnapshot> loadSnapshotsStrict(Collection<Long> skuIds) {
        Set<Long> distinct = skuIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        String csv = distinct.stream().map(String::valueOf).collect(Collectors.joining(","));
        ApiResponse<List<SkuSnapshot>> response =
                callStrict(() -> productClient.batchSkus(csv), "批量查询 SKU（对账）");
        if (response.data() == null) {
            throw new BizException(ErrorCode.DEGRADED, "商品服务批量查询返回空数据体（对账）");
        }
        Map<Long, SkuSnapshot> map = new HashMap<>();
        for (SkuSnapshot snap : response.data()) {
            if (snap != null && snap.skuId() != null) {
                map.put(snap.skuId(), snap);
            }
        }
        return map;
    }

    /**
     * 商品域持有冻结库存 / 库存异常的 SKU id（对账候选集，严格语义）。
     *
     * <p>为什么需要这个反查：交易域能送过来的只有"我这边记着有冻结"的 SKU，
     * 只按那个集合比对，「交易域少记了冻结」这个方向永远闭合不了。
     */
    public List<Long> frozenHolderSkuIds() {
        ApiResponse<List<Long>> response =
                callStrict(productClient::frozenHolderSkus, "查询冻结库存候选 SKU（对账）");
        if (response.data() == null) {
            throw new BizException(ErrorCode.DEGRADED, "商品服务冻结候选查询返回空数据体（对账）");
        }
        return response.data().stream().filter(Objects::nonNull).toList();
    }

    /** 严格调用：响应缺失 / 4xx / 5xx / 超时一律抛 503 DEGRADED，调用方据此把整轮对账记成未知 */
    private <T> T callStrict(java.util.function.Supplier<T> action, String what) {
        try {
            T result = action.get();
            if (result == null) {
                throw new BizException(ErrorCode.DEGRADED, "商品服务无响应（" + what + "）");
            }
            return result;
        } catch (FeignException e) {
            throw new BizException(ErrorCode.DEGRADED,
                    "商品服务返回 " + e.status() + "（" + what + "）", e);
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后重试", e);
        }
    }
}
