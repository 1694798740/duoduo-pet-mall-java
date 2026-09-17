package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商家端 · 商品管理（M9 商品侧，对应 PLAN.md 已知边界 B-04 的解除）。
 *
 * <p>三个刻意的边界，与交易侧的卖家中心（duoduo-trade SellerService）口径一致：
 *
 * <ol>
 *   <li><b>准入与归属分开</b>：准入看角色（非卖家 403），归属看 {@code t_spu.seller_id}。
 *       商品不存在返回 404、存在但不是你的返回 403 —— 刻意区分，不让越权探测伪装成「查无此单」。</li>
 *   <li><b>可见范围由登录态推导</b>：接口不接受调用方随意指定的 sellerId（运营侧的 sellerId
 *       是平台后台将来用的显式筛选参数，普通卖家传了也会被忽略并强制为自己的）。</li>
 *   <li><b>只做上下架，不做新建</b>：新建商品涉及类目 / 品牌 / SKU / 价格 / 库存的完整表单与审核，
 *       属于商家入驻运营流程，另行排期；本期能力是「管理已有的商品」。</li>
 * </ol>
 */
@Service
public class SellerProductService {

    private static final Logger log = LoggerFactory.getLogger(SellerProductService.class);

    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;

    public SellerProductService(SpuMapper spuMapper, SkuMapper skuMapper) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
    }

    /**
     * 我的商品列表（分页 + 标题关键词）。
     *
     * <p>运营 / 管理员可见全部（可选按 sellerId 过滤），为平台管理后台复用同一接口留口；
     * 普通卖家无条件强制 seller_id = 自己，请求里的 sellerId 参数不生效 ——
     * 「换个 ID 看别人的商品」这条路从一开始就不存在。
     */
    public PageResult<SellerProductView> list(String keyword, Long sellerId, int page, int pageSize) {
        LoginUser user = UserContext.require();
        if (!user.canAccessSellerConsole()) {
            throw BizException.forbidden("该账号不是卖家，无法使用商家端");
        }

        Long forcedSellerId;
        if (user.isAdmin() || user.isOperator()) {
            forcedSellerId = sellerId; // 运营侧显式筛选；为空则看全部
        } else {
            forcedSellerId = user.userId();
        }

        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));

        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getSellerId, forcedSellerId)
                .like(keyword != null && !keyword.isBlank(), SpuEntity::getTitle, keyword == null ? null : keyword.trim())
                .orderByDesc(SpuEntity::getUpdatedAt);
        Page<SpuEntity> result = spuMapper.selectPage(new Page<>(p, size), wrapper);

        List<SpuEntity> records = result.getRecords();
        Map<Long, int[]> skuStats = skuStatsBySpuIds(records.stream().map(SpuEntity::getId).toList());

        List<SellerProductView> views = records.stream()
                .map(spu -> {
                    int[] stat = skuStats.getOrDefault(spu.getId(), new int[]{0, 0});
                    return new SellerProductView(
                            spu.getId(),
                            spu.getTitle(),
                            spu.getMainImage(),
                            spu.getStatus(),
                            statusLabelOf(spu.getStatus()),
                            spu.getSellerId() == null ? 0L : spu.getSellerId(),
                            nz(spu.getMinPrice()),
                            nz(spu.getMaxPrice()),
                            spu.getSalesCount() == null ? 0 : spu.getSalesCount(),
                            stat[0],
                            stat[1]);
                })
                .toList();
        return PageResult.of(views, result.getTotal(), p, size);
    }

    /**
     * 上架 / 下架（幂等开关）。
     *
     * <p>为什么用「开关 + 幂等」而不是严格状态机：上下架可逆、无资金动作，
     * 重复请求与并发点击天然无副作用；真正要拦的是越权与目标状态非法。
     */
    public SellerProductView changeStatus(Long spuId, String action) {
        LoginUser user = UserContext.require();
        if (!user.canAccessSellerConsole()) {
            throw BizException.forbidden("该账号不是卖家，无法使用商家端");
        }
        String target = normalizeAction(action);

        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        if (!user.isAdmin() && !user.isOperator()
                && !user.userId().equals(nz(spu.getSellerId()))) {
            // 存在但不是你的：明确 403。商品 ID 是雪花 ID 不可枚举，统一 404 只会帮攻击者消除信号
            throw BizException.forbidden("该商品不属于当前账号");
        }

        if (!target.equals(spu.getStatus())) {
            String from = spu.getStatus();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            spu.setStatus(target);
            spu.setUpdatedAt(now);
            spuMapper.updateById(spu);
            log.info("商品 {} 由卖家 {} 上下架：{} -> {}", spuId, user.userId(), from, target);
        }
        return new SellerProductView(
                spu.getId(),
                spu.getTitle(),
                spu.getMainImage(),
                spu.getStatus(),
                statusLabelOf(spu.getStatus()),
                spu.getSellerId() == null ? 0L : spu.getSellerId(),
                nz(spu.getMinPrice()),
                nz(spu.getMaxPrice()),
                spu.getSalesCount() == null ? 0 : spu.getSalesCount(),
                0,
                0);
    }

    // ---------------- 内部实现 ----------------

    /** 目标状态白名单：不认识的动作直接 400，绝不静默当成下架 */
    private String normalizeAction(String action) {
        if (action == null) {
            throw BizException.validation("action 不能为空（ON_SALE / OFF_SHELF）");
        }
        String normalized = action.trim().toUpperCase();
        if (!STATUS_ON_SALE.equals(normalized) && !STATUS_OFF_SHELF.equals(normalized)) {
            throw BizException.validation("action 仅支持 ON_SALE / OFF_SHELF");
        }
        return normalized;
    }

    /**
     * 按 SPU 聚合 SKU 数量与库存总和（一次查询，避免列表 N+1）。
     * 数组下标 0 = SKU 数量，1 = 库存总和（物理库存 stock，不含冻结口径）。
     */
    private Map<Long, int[]> skuStatsBySpuIds(Collection<Long> spuIds) {
        Set<Long> distinct = spuIds.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<SkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .in(SkuEntity::getSpuId, distinct));
        return skus.stream().collect(Collectors.groupingBy(
                SkuEntity::getSpuId,
                Collectors.reducing(new int[]{0, 0},
                        sku -> new int[]{1, sku.getStock() == null ? 0 : sku.getStock()},
                        (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]})));
    }

    /** 状态文案由服务端给出，前端不维护映射表 */
    private String statusLabelOf(String status) {
        return switch (status == null ? "" : status) {
            case STATUS_ON_SALE -> "在售";
            case STATUS_OFF_SHELF -> "已下架";
            default -> status;
        };
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
