package com.duoduo.product.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.StockDtos.Reason;
import com.duoduo.product.dto.StockDtos.StockChangeItem;
import com.duoduo.product.dto.StockDtos.StockChangeRequest;
import com.duoduo.product.dto.StockDtos.StockChangeResponse;
import com.duoduo.product.dto.StockDtos.StockChangeResult;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SkuStockReservationEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SkuStockReservationMapper;
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
 * <p><b>关于 restock 的重复调用</b>（第五十八轮已修，B-02 关闭）：它不像 freeze/release 那样自带
 * 「幂等护栏」（冻结量足够才成功、不足即重复），重复入库会**真的多加库存且不报错**。
 * 现在入库前先撞 {@code t_sku_restock_log} 的主键（{@code (ref_no, sku_id)}）：
 * 只有首次插入成功才执行加库存；重复请求返回「成功」但**不再加库存**。
 * 调用方仍需用台账做状态迁移（那样能少一次远程调用），但即使它重试，也不会再加一遍。
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
    private final SkuStockReservationMapper reservationMapper;

    public StockService(SkuMapper skuMapper, SkuStockReservationMapper reservationMapper) {
        this.skuMapper = skuMapper;
        this.reservationMapper = reservationMapper;
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

        if (!ACTION_FREEZE.equals(action) && !ACTION_RELEASE.equals(action)
                && !ACTION_DEDUCT.equals(action) && !ACTION_RESTOCK.equals(action)) {
            throw BizException.validation("不支持的库存操作：" + action);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 入库（B-02）：refNo 是幂等号，缺了就等于放弃了去重能力 —— 直接拒绝而不是「照样加库存」。
        // 宁可让调用方补上参数报错，也不要留一个「看起来成功、实际会双加」的口子。
        if (ACTION_RESTOCK.equals(action) && (request.refNo() == null || request.refNo().isBlank())) {
            throw BizException.validation("入库（restock）必须带 refNo 幂等号");
        }
        // 冻结 / 释放 / 扣减都必须带订单号（B-03）：商品域要按订单记预占，
        // 没有订单号就无从判断「这次释放的是谁的冻结」——那正是跨订单误释放的根源。
        if (!ACTION_RESTOCK.equals(action)
                && (request.orderId() == null || request.orderId() <= 0)) {
            throw BizException.validation(action + " 必须带 orderId（订单级库存预占）");
        }

        List<StockChangeResult> results = new ArrayList<>(request.items().size());
        boolean allSuccess = true;
        for (StockChangeItem item : request.items()) {
            StockChangeResult result = switch (action) {
                case ACTION_FREEZE -> freezeOne(request.orderId(), item, now);
                case ACTION_RELEASE -> releaseOne(request.orderId(), item, now);
                case ACTION_DEDUCT -> deductOne(request.orderId(), item, now);
                case ACTION_RESTOCK -> restockOne(item, request.refNo(), now);
                default -> throw BizException.validation("不支持的库存操作：" + action);
            };
            if (!result.success()) {
                allSuccess = false;
            }
            results.add(result);
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
            markRollbackOnly();
        }
        return new StockChangeResponse(action, allSuccess, List.copyOf(results));
    }

    /**
     * 标记当前事务回滚。
     *
     * <p>单独抽出来是为了单测能在无事务上下文里验证「失败即整批回滚」的决策，
     * 而不必为了一个方法去起 Spring 事务管理器。
     */
    void markRollbackOnly() {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }

    /**
     * 冻结：先登记订单级预占，再原子增加 SKU 冻结量。
     *
     * <p>顺序不能反：若先冻 SKU 再登记台账，登记失败时 SKU 已被冻住，而调用方拿不到
     * 「这份冻结属于谁」的信息，只能靠对账猜。反过来，登记成功但冻结失败时，
     * 本事务会整批回滚，预占行也一并撤销，不会留下幽灵占用。
     *
     * <p>重复请求（响应丢失后的重试）：主键撞车返回 0，再读已有预占。
     * 数量一致 → 幂等成功（本轮实际新增 0）；数量不一致 → 拒绝，绝不按新数量再冻一次。
     */
    private StockChangeResult freezeOne(Long orderId, StockChangeItem item, LocalDateTime now) {
        int claimed = reservationMapper.insertIgnore(orderId, item.skuId(), item.quantity(), now);
        if (claimed == 0) {
            SkuStockReservationEntity existing = reservationMapper.selectForUpdate(orderId, item.skuId());
            if (existing == null) {
                return failed(item, Reason.RESERVATION_STATE_CONFLICT);
            }
            if (!SkuStockReservationEntity.STATUS_FROZEN.equals(existing.getStatus())) {
                return failed(item, Reason.RESERVATION_STATE_CONFLICT);
            }
            if (!item.quantity().equals(existing.getQuantity())) {
                return failed(item, Reason.RESERVATION_QTY_MISMATCH);
            }
            return new StockChangeResult(item.skuId(), 0, true, null);
        }
        int affected = skuMapper.freezeStock(item.skuId(), item.quantity(), now);
        return affected == 1
                ? new StockChangeResult(item.skuId(), item.quantity(), true, null)
                : failed(item, diagnose(ACTION_FREEZE, item.skuId()));
    }

    /**
     * 释放：只释放本订单登记过的那份预占。
     *
     * <p>「本订单没有预占行」按幂等成功处理（库存不动）：这通常意味着冻结请求根本没成功，
     * 或者上一次释放已经提交、只是响应丢了。此时报错只会让调用方无限重试；
     * 而如果确实存在「交易域以为冻了、商品域没有记录」的偏差，对账守卫会把它抓出来，
     * 不需要靠释放接口在这里猜。
     */
    private StockChangeResult releaseOne(Long orderId, StockChangeItem item, LocalDateTime now) {
        SkuStockReservationEntity existing = reservationMapper.selectForUpdate(orderId, item.skuId());
        if (existing == null) {
            return new StockChangeResult(item.skuId(), 0, true, null);
        }
        if (SkuStockReservationEntity.STATUS_RELEASED.equals(existing.getStatus())) {
            return new StockChangeResult(item.skuId(), 0, true, null);
        }
        if (!SkuStockReservationEntity.STATUS_FROZEN.equals(existing.getStatus())) {
            return failed(item, Reason.RESERVATION_STATE_CONFLICT);
        }
        if (!item.quantity().equals(existing.getQuantity())) {
            return failed(item, Reason.RESERVATION_QTY_MISMATCH);
        }
        int affected = skuMapper.releaseStock(item.skuId(), item.quantity(), now);
        if (affected != 1) {
            return failed(item, diagnose(ACTION_RELEASE, item.skuId()));
        }
        if (reservationMapper.markReleased(orderId, item.skuId(), now) != 1) {
            return failed(item, Reason.RESERVATION_STATE_CONFLICT);
        }
        return new StockChangeResult(item.skuId(), item.quantity(), true, null);
    }

    /**
     * 扣减：只扣本订单登记过的那份预占，库存与冻结量同时减少。
     *
     * <p>没有预占行时**不能**像释放那样按成功处理：钱已经收了，库存却没扣，
     * 这是真正的资损方向，必须让支付事务回滚并暴露出来。
     * 已扣减过的重试则按幂等成功返回，避免支付回调重放把库存扣两次。
     */
    private StockChangeResult deductOne(Long orderId, StockChangeItem item, LocalDateTime now) {
        SkuStockReservationEntity existing = reservationMapper.selectForUpdate(orderId, item.skuId());
        if (existing == null) {
            return failed(item, Reason.RESERVATION_NOT_FOUND);
        }
        if (SkuStockReservationEntity.STATUS_DEDUCTED.equals(existing.getStatus())) {
            return new StockChangeResult(item.skuId(), 0, true, null);
        }
        if (!SkuStockReservationEntity.STATUS_FROZEN.equals(existing.getStatus())) {
            return failed(item, Reason.RESERVATION_STATE_CONFLICT);
        }
        if (!item.quantity().equals(existing.getQuantity())) {
            return failed(item, Reason.RESERVATION_QTY_MISMATCH);
        }
        int affected = skuMapper.deductStock(item.skuId(), item.quantity(), now);
        if (affected != 1) {
            return failed(item, diagnose(ACTION_DEDUCT, item.skuId()));
        }
        if (reservationMapper.markDeducted(orderId, item.skuId(), now) != 1) {
            return failed(item, Reason.RESERVATION_STATE_CONFLICT);
        }
        return new StockChangeResult(item.skuId(), item.quantity(), true, null);
    }

    private StockChangeResult restockOne(StockChangeItem item, String refNo, LocalDateTime now) {
        // 幂等闸：只有「首次插入」才真正加库存。重复提交（响应丢失后的重试）在这里返回 0，
        // 于是本轮跳过更新、按**成功**返回（货确实已经回库了）——既不重复加，也不让调用方重试到死。
        int claimed = skuMapper.insertRestockLogIgnore(refNo, item.skuId(), item.quantity());
        if (claimed == 0) {
            // 回执必须如实：本轮**实际加回 0 件**（此前已入过库），不能报成加了 quantity 件 ——
            // 否则调用方与审计都会以为这批货回了两次。
            return new StockChangeResult(item.skuId(), 0, true, null);
        }
        int affected = skuMapper.restockStock(item.skuId(), item.quantity(), now);
        return affected == 1
                ? new StockChangeResult(item.skuId(), item.quantity(), true, null)
                : failed(item, diagnose(ACTION_RESTOCK, item.skuId()));
    }

    private static StockChangeResult failed(StockChangeItem item, String reason) {
        return new StockChangeResult(item.skuId(), item.quantity(), false, reason);
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
        // 释放的 WHERE 里同样没有 status 条件：下架不影响「把冻结放掉」，失败只能是冻结量不足。
        if (ACTION_RELEASE.equals(action)) {
            return Reason.FREEZE_NOT_ENOUGH;
        }
        if (ACTION_FREEZE.equals(action) && !SKU_ACTIVE.equals(sku.getStatus())) {
            return Reason.SKU_INACTIVE;
        }
        return Reason.INSUFFICIENT_STOCK;
    }
}
