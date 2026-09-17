package com.duoduo.product.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.StockDtos.Reason;
import com.duoduo.product.dto.StockDtos.StockChangeItem;
import com.duoduo.product.dto.StockDtos.StockChangeRequest;
import com.duoduo.product.dto.StockDtos.StockChangeResponse;
import com.duoduo.product.dto.StockDtos.StockChangeResult;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.mapper.SkuMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 库存变更服务（冻结 / 释放 / 扣减 / 入库），供交易服务内部调用。
 *
 * <p>职责边界：<b>库存的主数据在商品服务</b>，交易服务只持有「冻结台账」。
 * 因此交易服务下单时必须回调本服务把冻结量落到 SKU 上，
 * 这样「可售余量 = stock - frozen_stock」这个口径只有一处定义，不会两个服务各算一套。
 *
 * <p>四个操作各自的语义与条件：
 * <ul>
 *   <li>{@code freeze}（下单）：可售余量足够才冻，要求 SKU 在售；</li>
 *   <li>{@code release}（取消 / 超时关单）：冻结量足够才放，不要求 SKU 在售（下架了也要能放）；</li>
 *   <li>{@code deduct}（支付成功）：库存与冻结量**同时**减少，避免「库存扣了冻结没减」；</li>
 *   <li>{@code restock}（退款入库）：库存加回，<b>不校验在售状态</b>（库存是物理事实）。</li>
 * </ul>
 *
 * <p>并发安全：四个操作都是原子条件更新（见 {@link SkuMapper}），
 * 靠数据库的行级原子性防超卖，<b>不需要分布式锁</b>。
 *
 * <p>关于原子性：本接口对<b>单次请求内的整批 SKU 是原子的</b>（同一本地事务），
 * 任意一项失败则整批回滚，并照常返回逐项结果供调用方定位原因。
 * 这样调用方拿到失败时，远端状态一定是「什么都没改」，不需要再依赖跨服务补偿 ——
 * 补偿调用本身也可能失败，那才是真正难查的库存残留。
 *
 * <p><b>关于 restock 的重复调用</b>：它不像 freeze/release 那样自带「幂等护栏」
 * （冻结量足够才成功、冻结量不足即重复），重复入库会真的多加库存。
 * 因此调用方（交易服务）必须用台账把「入库」做成一次性的、可重试的状态迁移，
 * 而不是依赖本接口自身幂等。详见交易侧 {@code StockFreezeEntity} 的状态说明。
 */
@Service
public class StockService {

    public static final String ACTION_FREEZE = "freeze";
    public static final String ACTION_RELEASE = "release";
    public static final String ACTION_DEDUCT = "deduct";
    public static final String ACTION_RESTOCK = "restock";

    /** 单次请求最多处理多少个 SKU，防止一次把库压住 */
    private static final int MAX_ITEMS = 50;
    private static final int MAX_QUANTITY = 200;
    private static final String SKU_ACTIVE = "ACTIVE";

    private final SkuMapper skuMapper;

    public StockService(SkuMapper skuMapper) {
        this.skuMapper = skuMapper;
    }

    @Transactional
    public StockChangeResponse apply(String action, StockChangeRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw BizException.validation("items 不能为空");
        }
        if (request.items().size() > MAX_ITEMS) {
            throw BizException.validation("一次最多变更 " + MAX_ITEMS + " 个 SKU");
        }
        // 同一 SKU 在同一批里出现两次会导致「先成功后失败」的歧义，直接拒绝，让调用方先合并
        Set<Long> seen = new LinkedHashSet<>();
        for (StockChangeItem item : request.items()) {
            if (item == null || item.skuId() == null) {
                throw BizException.validation("skuId 不能为空");
            }
            if (item.quantity() == null || item.quantity() < 1 || item.quantity() > MAX_QUANTITY) {
                throw BizException.validation("quantity 需在 1 到 " + MAX_QUANTITY + " 之间");
            }
            if (!seen.add(item.skuId())) {
                throw BizException.validation("同一批请求中 skuId 不能重复：" + item.skuId());
            }
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<StockChangeResult> results = new ArrayList<>(request.items().size());
        boolean allSuccess = true;
        for (StockChangeItem item : request.items()) {
            int affected = switch (action) {
                case ACTION_FREEZE -> skuMapper.freezeStock(item.skuId(), item.quantity(), now);
                case ACTION_RELEASE -> skuMapper.releaseStock(item.skuId(), item.quantity(), now);
                case ACTION_DEDUCT -> skuMapper.deductStock(item.skuId(), item.quantity(), now);
                case ACTION_RESTOCK -> skuMapper.restockStock(item.skuId(), item.quantity(), now);
                default -> throw BizException.validation("不支持的库存操作：" + action);
            };
            if (affected == 1) {
                results.add(new StockChangeResult(item.skuId(), item.quantity(), true, null));
            } else {
                allSuccess = false;
                results.add(new StockChangeResult(item.skuId(), item.quantity(), false, diagnose(action, item.skuId())));
            }
        }

        // 整批原子：只要有一项没成功，把本事务标记为回滚，
        // 前面已经成功的那些 SKU 也会一并撤销。
        //
        // 为什么不「部分成功就返回部分成功」：调用方（交易服务）拿到部分成功后必须自己补偿，
        // 而一旦补偿调用本身失败（网络抖动、对方重启），就会留下**库存被扣了但订单没成立**的残留 ——
        // 这种残留用户侧看不见，只能靠人工对账发现。
        // 用一次本地事务换取「要么全成、要么全不成」，比依赖跨服务的补偿可靠得多。
        //
        // 注意：这里不抛异常，而是标记回滚后**照常返回逐项结果** ——
        // 调用方需要知道「是哪个 SKU、因为什么失败」才能给出准确提示。
        if (!allSuccess) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return new StockChangeResponse(action, allSuccess, List.copyOf(results));
    }

    /**
     * 条件更新影响 0 行时，补一次查询把原因说清楚。
     * 这次查询只用于生成错误信息，<b>不参与判断</b>（判断已由原子更新的 WHERE 完成），
     * 所以即使并发下数据变了，也不会影响正确性。
     */
    private String diagnose(String action, Long skuId) {
        SkuEntity sku = skuMapper.selectById(skuId);
        if (sku == null) {
            return Reason.SKU_NOT_FOUND;
        }
        // 入库的 WHERE 里没有 status 条件，因此「已下架」不是入库失败的原因；
        // 走到这里说明是别的异常（例如并发删除了该 SKU），单独给一个原因码便于排查。
        if (ACTION_RESTOCK.equals(action)) {
            return Reason.RESTOCK_FAILED;
        }
        if (!SKU_ACTIVE.equals(sku.getStatus())) {
            return Reason.SKU_INACTIVE;
        }
        if (ACTION_RELEASE.equals(action)) {
            return Reason.FREEZE_NOT_ENOUGH;
        }
        return Reason.INSUFFICIENT_STOCK;
    }
}
