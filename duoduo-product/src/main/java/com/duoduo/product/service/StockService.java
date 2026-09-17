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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 库存变更服务（冻结 / 释放 / 扣减），供交易服务内部调用。
 *
 * <p>职责边界：<b>库存的主数据在商品服务</b>，交易服务只持有「冻结台账」。
 * 因此交易服务下单时必须回调本服务把冻结量落到 SKU 上，
 * 这样「可售余量 = stock - frozen_stock」这个口径只有一处定义，不会两个服务各算一套。
 *
 * <p>并发安全：三个操作都是原子条件更新（见 {@link SkuMapper}），
 * 靠数据库的行级原子性防超卖，<b>不需要分布式锁</b>。
 *
 * <p>关于部分失败：本接口**不保证整批原子**。一个订单跨多个 SKU，
 * 逐个冻结时若中途失败，由调用方（交易服务）负责把已成功的部分释放掉 ——
 * 这是刻意的：跨服务的分布式事务成本远高于一次补偿调用，
 * 而补偿的幂等性由「释放接口在冻结量不足时返回失败」保证。
 */
@Service
public class StockService {

    public static final String ACTION_FREEZE = "freeze";
    public static final String ACTION_RELEASE = "release";
    public static final String ACTION_DEDUCT = "deduct";

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
                default -> throw BizException.validation("不支持的库存操作：" + action);
            };
            if (affected == 1) {
                results.add(new StockChangeResult(item.skuId(), item.quantity(), true, null));
            } else {
                allSuccess = false;
                results.add(new StockChangeResult(item.skuId(), item.quantity(), false, diagnose(action, item.skuId())));
            }
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
        if (!SKU_ACTIVE.equals(sku.getStatus())) {
            return Reason.SKU_INACTIVE;
        }
        if (ACTION_RELEASE.equals(action)) {
            return Reason.FREEZE_NOT_ENOUGH;
        }
        return Reason.INSUFFICIENT_STOCK;
    }
}
