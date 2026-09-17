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
                () -> productClient.freezeStock(new StockChangePayload(items)),
                "冻结库存(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    public StockChangeOutcome release(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.releaseStock(new StockChangePayload(items)),
                "释放库存(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    public StockChangeOutcome deduct(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.deductStock(new StockChangePayload(items)),
                "扣减库存(订单 " + orderId + ")");
        return response == null ? null : response.data();
    }

    /**
     * 退货入库（退款后把货补回库存）。
     *
     * <p>注意：入库接口<b>不像 freeze/release 那样自带幂等护栏</b>，
     * 重复调用会真的把库存加多。调用方必须把「只调一次」交给台账状态迁移来保证
     * （见 {@code StockFreezeEntity} 的 RESTOCK_PENDING → RESTOCKED）。
     */
    public StockChangeOutcome restock(Long orderId, List<StockChangeItem> items) {
        ApiResponse<StockChangeOutcome> response = call(
                () -> productClient.restockStock(new StockChangePayload(items)),
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
}
