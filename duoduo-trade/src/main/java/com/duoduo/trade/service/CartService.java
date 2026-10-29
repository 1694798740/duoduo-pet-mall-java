package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.ProductGateway;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.dto.CartDtos.AddCartRequest;
import com.duoduo.trade.dto.CartDtos.CartItemView;
import com.duoduo.trade.dto.CartDtos.CartView;
import com.duoduo.trade.dto.CartDtos.InvalidReason;
import com.duoduo.trade.entity.CartItemEntity;
import com.duoduo.trade.mapper.CartItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 购物车。
 *
 * <p>四个刻意的工程决策：
 * <ol>
 *   <li><b>不存商品快照</b>：购物车展示当前价格，展示时回源商品服务批量取；
 *       与订单明细相反（订单必须冻结下单时的价格）。</li>
 *   <li><b>加购走「先写再兜底」</b>：并发加购不靠分布式锁，靠
 *       {@code uk_cart_user_sku} 唯一索引把竞态变成一次可处理的 DuplicateKey，
 *       再退化为累加更新。锁只用来减少冲突，正确性由索引保证。</li>
 *   <li><b>失效判定在服务端</b>：下架 / 售罄 / 库存不足三类失效由服务端给出机器可读的原因码，
 *       且失效条目<b>不计入结算金额</b> —— 否则结算页金额与订单金额会对不上。</li>
 *   <li><b>不信任客户端传 userId</b>：用户身份只从 {@link UserContext} 取（网关透传的 X-User-*），
 *       请求体里没有也不需要 userId 字段。</li>
 * </ol>
 */
@Service
public class CartService {

    /** 单个规格的最大购买量，与请求层 @Max 保持一致；服务层再校验一次，防止绕过 HTTP 直接调用 */
    private static final int MAX_QUANTITY = 200;
    private static final int SELECTED = 1;
    private static final int UNSELECTED = 0;
    private static final String SPU_ON_SALE = "ON_SALE";
    private static final String SKU_ACTIVE = "ACTIVE";

    private final CartItemMapper cartItemMapper;
    private final ProductGateway productGateway;
    private final SnowflakeIdGenerator idGenerator;
    /** 多多币任务事件（R-P1 第三轮）：加购成功即上报，失败不影响加购 */
    private final com.duoduo.trade.client.CoinGateway coinGateway;

    public CartService(CartItemMapper cartItemMapper,
                       ProductGateway productGateway,
                       SnowflakeIdGenerator idGenerator,
                       com.duoduo.trade.client.CoinGateway coinGateway) {
        this.cartItemMapper = cartItemMapper;
        this.productGateway = productGateway;
        this.idGenerator = idGenerator;
        this.coinGateway = coinGateway;
    }

    // ---------------- 查询 ----------------

    /**
     * 购物车列表：带商品实时价格、可售库存与失效原因。
     *
     * <p>排序锁定为「加入购物车时间倒序」，**只按 created_at，不掺 updated_at**。
     * 曾经用 {@code orderByDesc(updated_at)}，结果是「勾选 / 改数量」这类与顺序无关的操作
     * 会把商品顶到最前面 —— 用户看到的就是「点一下全选，列表被随机打乱」。
     * 展示顺序必须只依赖一个从不因操作而变动的字段。
     * created_at 同毫秒时用 id 兜底（雪花有序），保证每行位置绝对确定。
     */
    public CartView list() {
        Long userId = UserContext.requireUserId();
        List<CartItemEntity> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .orderByDesc(CartItemEntity::getCreatedAt)
                .orderByDesc(CartItemEntity::getId));
        if (rows.isEmpty()) {
            return new CartView(List.of(), 0, 0, 0, 0L, List.of());
        }

        Map<Long, SkuSnapshot> snapshots = loadSnapshots(
                rows.stream().map(CartItemEntity::getSkuId).toList());

        List<CartItemView> views = new ArrayList<>(rows.size());
        Set<String> invalidReasons = new LinkedHashSet<>();
        int totalQuantity = 0;
        int selectedCount = 0;
        long selectedAmount = 0L;

        for (CartItemEntity row : rows) {
            SkuSnapshot snap = snapshots.get(row.getSkuId());
            int quantity = safeQuantity(row);
            totalQuantity += quantity;
            boolean selected = isSelected(row);
            String reason = invalidReason(snap, quantity);
            boolean valid = reason == null;
            long price = snap == null ? 0L : snap.price();
            long amount = valid ? price * quantity : 0L;
            if (!valid) {
                invalidReasons.add(reason);
            }
            if (valid && selected) {
                selectedCount++;
                selectedAmount += amount;
            }
            views.add(new CartItemView(
                    row.getSkuId(),
                    row.getSpuId(),
                    snap == null ? null : snap.spuTitle(),
                    snap == null ? null : snap.specText(),
                    snap == null ? null : snap.image(),
                    price,
                    quantity,
                    amount,
                    snap == null ? 0 : snap.availableStock(),
                    selected,
                    valid,
                    reason,
                    rowSellerId(snap),
                    snap == null ? null : snap.storeName(),
                    com.duoduo.common.seller.SellerOwnership.isSelfSeller(rowSellerId(snap))));
        }
        return new CartView(List.copyOf(views), views.size(), totalQuantity,
                selectedCount, selectedAmount, List.copyOf(invalidReasons));
    }

    /** 购物车件数（角标用），不查商品服务 */
    public int count() {
        Long userId = UserContext.requireUserId();
        List<CartItemEntity> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .select(CartItemEntity::getQuantity)
                .eq(CartItemEntity::getUserId, userId));
        return rows.stream().mapToInt(CartService::safeQuantity).sum();
    }

    // ---------------- 写 ----------------

    /**
     * 加入购物车。
     * 同 SKU 重复加购累加数量（不新增行），加购后默认勾选。
     */
    @Transactional
    public CartItemView add(AddCartRequest request) {
        Long userId = UserContext.requireUserId();
        int quantity = request.quantity() == null ? 1 : request.quantity();
        checkQuantity(quantity);
        SkuSnapshot snap = requireSellableSnapshot(request.skuId());

        /*
         * 加购的正确性**完全交给数据库**：一条 upsert 同时覆盖「不在就插、在就累加」。
         *
         * 这里踩过三个连环坑，全都是实测复现出来的，记下来免得再走一遍：
         *
         * 1) 最早是「INSERT，catch DuplicateKey，再 SELECT + updateById」。
         *    但本方法带 @Transactional，MySQL 默认 REPEATABLE READ ——
         *    事务快照看不到别的事务刚提交的行，兜底里的 SELECT 恒为 null，
         *    只能把异常原样抛回去（并发 10 稳定 9/120 报 500）。
         *
         * 2) 把 SELECT 换成 UPDATE 自增后又变成**死锁**：失败的 INSERT 会在唯一索引上
         *    留下锁，十几个事务互相等对方的锁，MySQL 直接判死锁回滚
         *    （200 次里 8 次 500，日志全是 Deadlock found when trying to get lock）。
         *
         * 3) 最容易漏的是**丢失更新**：原本还有一条「行已存在 → 读出来 +delta 再 updateById」
         *    的捷径。一致读不加锁，十个事务读到同一个旧值再各自写回，
         *    结果是 200 次加购**全部返回成功，总数却只有 46**。
         *    这类缺陷不报错、不告警，只有对账才会发现 —— 比 500 更危险。
         *
         * ON DUPLICATE KEY UPDATE 是**同一条语句**内判定并自增，没有中间态，三个坑一起消失。
         */
        requireStock(quantity, snap);
        CartItemEntity row = new CartItemEntity();
        row.setId(idGenerator.nextId());
        row.setUserId(userId);
        row.setSpuId(snap.spuId());
        row.setSkuId(snap.skuId());
        row.setQuantity(quantity);
        row.setSelected(SELECTED);
        LocalDateTime now = nowUtc();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            cartItemMapper.upsert(row.getId(), userId, row.getSpuId(), row.getSkuId(), quantity, now);
        } catch (RuntimeException e) {
            // upsert 理论上不会再撞唯一键；真撞了（例如并发 DDL / 索引重建期）退回纯 UPDATE 一次，
            // 那也是当前读、不受快照影响。仍然失败就必须抛出去，绝不静默吞。
            if (!isCartUniqueConflict(e) || cartItemMapper.addQuantity(userId, request.skuId(), quantity, now) == 0) {
                throw e;
            }
        }
        // 本事务刚写过这一行，一定能读到它；读不到就按本次请求的数量回视图，
        // 宁可展示偏小，也不要在「加购已经成功」之后再抛一个 500。
        CartItemEntity saved = findBySku(userId, request.skuId());
        CartItemEntity result = saved != null ? saved : row;
        checkMergedLimit(result, snap);
        // 加购成功 → 任务事件（best-effort；事件幂等，重复加购不会重复发奖）
        coinGateway.taskEvent(userId, "CART_ADD", String.valueOf(request.skuId()));
        return toView(result, snap);
    }

    /** 累加后的数量必须同时满足「单规格上限」与「可售库存」；不满足就抛，事务回滚，不会留下半截数据 */
    private void checkMergedLimit(CartItemEntity item, SkuSnapshot snap) {
        int merged = safeQuantity(item);
        if (merged > MAX_QUANTITY) {
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "单个规格最多购买 " + MAX_QUANTITY + " 件，当前购物车已有 " + merged + " 件");
        }
        requireStock(merged, snap);
    }

    /**
     * 判断是不是「购物车同一用户同一 SKU 已存在」这条唯一键冲突。
     *
     * <p>沿异常链找 {@code Duplicate entry} 且键名是本表的 {@code uk_cart_user_sku}。
     * 只认这一条：别的表、别的原因（字段超长、外键）一律不算，继续往上抛。
     */
    static boolean isCartUniqueConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t == t.getCause()) break; // 防自引用成环
            String msg = String.valueOf(t.getMessage());
            if (msg.contains("Duplicate entry") && msg.contains("uk_cart_user_sku")) {
                return true;
            }
        }
        return false;
    }

    /** 修改数量（会校验库存上限） */
    @Transactional
    public CartItemView updateQuantity(Long skuId, int quantity) {
        Long userId = UserContext.requireUserId();
        checkQuantity(quantity);
        CartItemEntity row = requireItem(userId, skuId);
        SkuSnapshot snap = requireSellableSnapshot(skuId);
        requireStock(quantity, snap);
        row.setQuantity(quantity);
        row.setUpdatedAt(nowUtc());
        cartItemMapper.updateById(row);
        return toView(row, snap);
    }

    /** 勾选 / 取消勾选 */
    @Transactional
    public CartItemView updateSelected(Long skuId, boolean selected) {
        Long userId = UserContext.requireUserId();
        CartItemEntity row = requireItem(userId, skuId);
        row.setSelected(selected ? SELECTED : UNSELECTED);
        row.setUpdatedAt(nowUtc());
        cartItemMapper.updateById(row);
        return toView(row, loadSnapshots(List.of(skuId)).get(skuId));
    }

    /** 删除一条 */
    @Transactional
    public void remove(Long skuId) {
        Long userId = UserContext.requireUserId();
        requireItem(userId, skuId);
        cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, skuId));
    }

    /** 清空已勾选且有效的条目（结算成功后调用；订单模块接入前先提供能力） */
    @Transactional
    public int removeSelected() {
        Long userId = UserContext.requireUserId();
        List<CartItemEntity> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSelected, SELECTED));
        if (rows.isEmpty()) {
            return 0;
        }
        List<Long> ids = rows.stream().map(CartItemEntity::getId).toList();
        cartItemMapper.deleteBatchIds(ids);
        return ids.size();
    }

    // ---------------- 内部实现 ----------------

    private CartItemEntity findBySku(Long userId, Long skuId) {
        return cartItemMapper.selectOne(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSkuId, skuId));
    }

    private CartItemEntity requireItem(Long userId, Long skuId) {
        CartItemEntity row = findBySku(userId, skuId);
        if (row == null) {
            throw BizException.notFound("购物车中的该商品");
        }
        return row;
    }

    /** 取「可下单」的 SKU 快照；不存在 / 下架 / 售罄分别给出精确错误 */
    private SkuSnapshot requireSellableSnapshot(Long skuId) {
        SkuSnapshot snap = loadSnapshots(List.of(skuId)).get(skuId);
        if (snap == null) {
            throw BizException.notFound("商品规格");
        }
        if (!SPU_ON_SALE.equals(snap.spuStatus()) || !SKU_ACTIVE.equals(snap.skuStatus())) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "该商品已下架，无法加入购物车");
        }
        if (snap.availableStock() <= 0) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT, "该商品已售罄");
        }
        return snap;
    }

    private void requireStock(int quantity, SkuSnapshot snap) {
        if (quantity > snap.availableStock()) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT,
                    "库存不足，当前最多可购 " + snap.availableStock() + " 件");
        }
    }

    private void checkQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw BizException.validation("购买数量需在 1 到 " + MAX_QUANTITY + " 之间");
        }
    }

    /**
     * 批量取 SKU 快照。
     * 降级口径统一收在 {@link ProductGateway} 里（4xx 当查不到、不可用抛 503），
     * 本类不再自己处理 Feign 异常，避免两个服务对同一份数据给出不同行为。
     */
    private Map<Long, SkuSnapshot> loadSnapshots(Collection<Long> skuIds) {
        return productGateway.loadSnapshots(skuIds);
    }

    /** 判定失效原因；返回 null 表示有效 */
    private String invalidReason(SkuSnapshot snap, int quantity) {
        if (snap == null) {
            return InvalidReason.SKU_NOT_FOUND;
        }
        if (!SPU_ON_SALE.equals(snap.spuStatus()) || !SKU_ACTIVE.equals(snap.skuStatus())) {
            return InvalidReason.OFF_SHELF;
        }
        if (snap.availableStock() <= 0) {
            return InvalidReason.OUT_OF_STOCK;
        }
        if (quantity > snap.availableStock()) {
            return InvalidReason.STOCK_NOT_ENOUGH;
        }
        return null;
    }

    private CartItemView toView(CartItemEntity row, SkuSnapshot snap) {
        int quantity = safeQuantity(row);
        String reason = invalidReason(snap, quantity);
        boolean valid = reason == null;
        long price = snap == null ? 0L : snap.price();
        return new CartItemView(
                row.getSkuId(),
                row.getSpuId(),
                snap == null ? null : snap.spuTitle(),
                snap == null ? null : snap.specText(),
                snap == null ? null : snap.image(),
                price,
                quantity,
                valid ? price * quantity : 0L,
                snap == null ? 0 : snap.availableStock(),
                isSelected(row),
                valid,
                reason,
                rowSellerId(snap),
                snap == null ? null : snap.storeName(),
                com.duoduo.common.seller.SellerOwnership.isSelfSeller(rowSellerId(snap)));
    }

    /** 快照缺失（商品已物理删除）时归属不可知，回 null 而不是 0 —— 0 会被当成自营 */
    private static Long rowSellerId(SkuSnapshot snap) {
        return snap == null ? null : snap.sellerId();
    }

    private static int safeQuantity(CartItemEntity row) {
        return row.getQuantity() == null ? 0 : row.getQuantity();
    }

    private static boolean isSelected(CartItemEntity row) {
        return row.getSelected() != null && row.getSelected() == SELECTED;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
