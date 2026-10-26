package com.duoduo.product.service;

import com.duoduo.product.dto.ProductDtos.ProductCard;
import com.duoduo.product.dto.ProductStatSum;
import com.duoduo.product.mapper.ProductDailyStatMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 商品曝光 / 点击统计（A-17）。
 *
 * <h2>写失败为什么不影响主流程</h2>
 * 统计是<b>旁路数据</b>：一条商品列表因为「统计表写不进去」而 500，
 * 等于让分析功能把交易主链路拖垮。因此所有写操作都吞掉异常、只记 WARN。
 * 代价是极端情况下会丢计数 —— 诊断页看到的是「至少这么多次」，
 * 而不是审计级的精确账本，这与它的用途（找优化方向）匹配。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li><b>曝光</b>：商品卡出现在公开列表 / 搜索 / 店铺页 / 首页。同一请求同一商品只记 1 次
 *       （{@link LinkedHashSet} 去重）；同一用户刷新两次会记 2 次，这是「展示次数」而不是 UV。</li>
 *   <li><b>点击</b>：公开详情成功打开后才记（未过审 / 已删除的 404 不计），
 *       与详情页可见性同一道闸。</li>
 * </ul>
 */
@Service
public class ProductStatService {

    private static final Logger log = LoggerFactory.getLogger(ProductStatService.class);

    private final ProductDailyStatMapper statMapper;

    public ProductStatService(ProductDailyStatMapper statMapper) {
        this.statMapper = statMapper;
    }

    /** 记一批商品卡的曝光（自动去重、自动忽略空列表）。 */
    public void recordExposure(Collection<ProductCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (ProductCard card : cards) {
            if (card != null && card.id() != null) {
                ids.add(card.id());
            }
        }
        recordExposureIds(ids);
    }

    /** 记一批 spuId 的曝光；写库失败只记 WARN。 */
    public void recordExposureIds(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long id : spuIds) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            statMapper.addExposure(new ArrayList<>(distinct), now.toLocalDate(), now);
        } catch (Exception ex) {
            log.warn("记录商品曝光失败（统计为旁路数据，不影响页面）：{}", ex.toString());
        }
    }

    /** 记一次商品详情点击；写库失败只记 WARN。 */
    public void recordClick(Long spuId) {
        if (spuId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            statMapper.addClick(spuId, now.toLocalDate(), now);
        } catch (Exception ex) {
            log.warn("记录商品点击失败（统计为旁路数据，不影响详情）：{}", ex.toString());
        }
    }

    /**
     * 汇总窗口内每个商品的曝光 / 点击。
     *
     * <p>返回 {@code Map<spuId, long[]{exposure, clicks}>}；查不到的 spuId 不出现在 Map 里，
     * 由调用方按 0 处理（新商品没有统计数据是常态，不是异常）。
     */
    public Map<Long, long[]> sumRange(Collection<Long> spuIds, LocalDate from, LocalDate to) {
        if (spuIds == null || spuIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (Long id : spuIds) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<ProductStatSum> sums = statMapper.sumRange(new ArrayList<>(distinct), from, to);
        Map<Long, long[]> result = new HashMap<>();
        for (ProductStatSum sum : sums) {
            if (sum.getSpuId() != null) {
                result.put(sum.getSpuId(), new long[]{sum.getExposure(), sum.getClicks()});
            }
        }
        return result;
    }
}
