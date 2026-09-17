package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.ProductClient;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.dto.CartDtos.AddCartRequest;
import com.duoduo.trade.dto.CartDtos.CartItemView;
import com.duoduo.trade.dto.CartDtos.CartView;
import com.duoduo.trade.dto.CartDtos.InvalidReason;
import com.duoduo.trade.entity.CartItemEntity;
import com.duoduo.trade.mapper.CartItemMapper;
import feign.FeignException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ProductClient productClient;
    private final SnowflakeIdGenerator idGenerator;

    public CartService(CartItemMapper cartItemMapper,
                       ProductClient productClient,
                       SnowflakeIdGenerator idGenerator) {
        this.cartItemMapper = cartItemMapper;
        this.productClient = productClient;
        this.idGenerator = idGenerator;
    }

    // ---------------- 查询 ----------------

    /** 购物车列表：带商品实时价格、可售库存与失效原因 */
    public CartView list() {
        Long userId = UserContext.requireUserId();
        List<CartItemEntity> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .orderByDesc(CartItemEntity::getUpdatedAt)
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
                    reason));
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

        CartItemEntity existing = findBySku(userId, request.skuId());
        if (existing != null) {
            return mergeInto(userId, existing, quantity, snap);
        }

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
            cartItemMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发加购：唯一索引 uk_cart_user_sku 已拦下第二条，退化为累加即可
            CartItemEntity concurrent = findBySku(userId, request.skuId());
            if (concurrent == null) {
                // 命中的不是本约束（或刚被并发删除），交给全局异常处理器，不静默吞掉
                throw e;
            }
            return mergeInto(userId, concurrent, quantity, snap);
        }
        return toView(row, snap);
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

    private CartItemView mergeInto(Long userId, CartItemEntity existing, int delta, SkuSnapshot snap) {
        int merged = safeQuantity(existing) + delta;
        if (merged > MAX_QUANTITY) {
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "单个规格最多购买 " + MAX_QUANTITY + " 件，当前购物车已有 " + safeQuantity(existing) + " 件");
        }
        requireStock(merged, snap);
        existing.setQuantity(merged);
        existing.setSelected(SELECTED);
        existing.setUpdatedAt(nowUtc());
        cartItemMapper.updateById(existing);
        return toView(existing, snap);
    }

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
     * 商品服务不可用时抛 503（DEGRADED）而不是返回「空购物车」——
     * 把依赖故障伪装成「商品都没了」会让用户误删自己的购物车。
     */
    private Map<Long, SkuSnapshot> loadSnapshots(Collection<Long> skuIds) {
        Set<Long> distinct = skuIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        String csv = distinct.stream().map(String::valueOf).collect(Collectors.joining(","));
        ApiResponse<List<SkuSnapshot>> response;
        try {
            response = productClient.batchSkus(csv);
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                // 商品服务明确回了 4xx（如参数被拒），按「查不到」处理，不让整车挂掉
                return Map.of();
            }
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后重试", e);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后重试", e);
        }
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
                reason);
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
