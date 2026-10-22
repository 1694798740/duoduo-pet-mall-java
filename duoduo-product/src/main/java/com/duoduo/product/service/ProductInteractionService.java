package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.entity.ProductFavoriteEntity;
import com.duoduo.product.entity.ProductViewEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ProductFavoriteMapper;
import com.duoduo.product.mapper.ProductViewMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏夹与浏览足迹（第七十九轮 C 批）。
 *
 * <p>两张表都是「用户 × 商品」的唯一关系：收藏是显式动作，足迹是隐式记录。
 * 足迹的写入必须 best-effort —— 浏览商品失败一次可以接受，但绝不能因为
 * 记录足迹失败让商品详情打不开（那是把附属功能做成了主链路的单点）。
 */
@Service
public class ProductInteractionService {

    private static final Logger log = LoggerFactory.getLogger(ProductInteractionService.class);
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProductFavoriteMapper favoriteMapper;
    private final ProductViewMapper viewMapper;
    private final SpuMapper spuMapper;
    private final SnowflakeIdGenerator idGenerator;

    public ProductInteractionService(ProductFavoriteMapper favoriteMapper,
                                     ProductViewMapper viewMapper,
                                     SpuMapper spuMapper,
                                     SnowflakeIdGenerator idGenerator) {
        this.favoriteMapper = favoriteMapper;
        this.viewMapper = viewMapper;
        this.spuMapper = spuMapper;
        this.idGenerator = idGenerator;
    }

    /** 切换收藏；返回切换后的状态（true = 已收藏） */
    public boolean toggleFavorite(Long userId, Long spuId) {
        ProductFavoriteEntity existing = favoriteMapper.selectOne(
                new LambdaQueryWrapper<ProductFavoriteEntity>()
                        .eq(ProductFavoriteEntity::getUserId, userId)
                        .eq(ProductFavoriteEntity::getSpuId, spuId)
                        .last("LIMIT 1"));
        if (existing != null) {
            favoriteMapper.deleteById(existing.getId());
            return false;
        }
        ProductFavoriteEntity entity = new ProductFavoriteEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setSpuId(spuId);
        entity.setCreatedAt(now());
        try {
            favoriteMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发双击：唯一索引兜底，语义上已经是「已收藏」
            return true;
        }
        return true;
    }

    /** 批量查询收藏状态（商品详情按钮回填） */
    public List<Long> favoritedSpuIds(Long userId, List<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return favoriteMapper.selectList(new LambdaQueryWrapper<ProductFavoriteEntity>()
                        .eq(ProductFavoriteEntity::getUserId, userId)
                        .in(ProductFavoriteEntity::getSpuId, spuIds))
                .stream().map(ProductFavoriteEntity::getSpuId).toList();
    }

    /** 我的收藏（按收藏时间倒序） */
    public PageResult<PickerProduct> listFavorites(Long userId, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE);
        List<ProductFavoriteEntity> rows = favoriteMapper.selectList(
                new LambdaQueryWrapper<ProductFavoriteEntity>()
                        .eq(ProductFavoriteEntity::getUserId, userId)
                        .orderByDesc(ProductFavoriteEntity::getCreatedAt)
                        .last("LIMIT " + size + " OFFSET " + (p - 1) * size));
        long total = favoriteMapper.selectCount(new LambdaQueryWrapper<ProductFavoriteEntity>()
                .eq(ProductFavoriteEntity::getUserId, userId));
        List<PickerProduct> items = enrich(rows.stream().map(ProductFavoriteEntity::getSpuId).toList());
        return PageResult.of(items, total, p, size);
    }

    /** 记录足迹：同一商品只更新最近时间（upsert），失败只记日志 */
    public void recordView(Long userId, Long spuId) {
        try {
            ProductViewEntity patch = new ProductViewEntity();
            patch.setViewedAt(now());
            int updated = viewMapper.update(patch, new LambdaQueryWrapper<ProductViewEntity>()
                    .eq(ProductViewEntity::getUserId, userId)
                    .eq(ProductViewEntity::getSpuId, spuId));
            if (updated > 0) {
                return;
            }
            ProductViewEntity entity = new ProductViewEntity();
            entity.setId(idGenerator.nextId());
            entity.setUserId(userId);
            entity.setSpuId(spuId);
            entity.setViewedAt(now());
            viewMapper.insert(entity);
        } catch (Exception e) {
            log.warn("记录浏览足迹失败 userId={} spuId={}（不影响商品详情）", userId, spuId, e);
        }
    }

    /** 我的足迹（按浏览时间倒序） */
    public PageResult<PickerProduct> listViews(Long userId, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE);
        List<ProductViewEntity> rows = viewMapper.selectList(
                new LambdaQueryWrapper<ProductViewEntity>()
                        .eq(ProductViewEntity::getUserId, userId)
                        .orderByDesc(ProductViewEntity::getViewedAt)
                        .last("LIMIT " + size + " OFFSET " + (p - 1) * size));
        long total = viewMapper.selectCount(new LambdaQueryWrapper<ProductViewEntity>()
                .eq(ProductViewEntity::getUserId, userId));
        List<PickerProduct> items = enrich(rows.stream().map(ProductViewEntity::getSpuId).toList());
        return PageResult.of(items, total, p, size);
    }

    /** 清空足迹（用户主动） */
    public long clearViews(Long userId) {
        return viewMapper.delete(new LambdaQueryWrapper<ProductViewEntity>()
                .eq(ProductViewEntity::getUserId, userId));
    }

    /** 把 spuId 列表补成可展示的商品卡（顺序保持不变） */
    private List<PickerProduct> enrich(List<Long> spuIds) {
        if (spuIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SpuEntity> spus = new HashMap<>();
        spuMapper.selectBatchIds(spuIds).forEach(s -> spus.put(s.getId(), s));
        List<PickerProduct> items = new ArrayList<>(spuIds.size());
        for (Long id : spuIds) {
            SpuEntity spu = spus.get(id);
            if (spu == null) {
                continue;
            }
            items.add(new PickerProduct(spu.getId(), spu.getTitle(), spu.getMainImage(),
                    spu.getMinPrice(), spu.getStatus()));
        }
        return items;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 「发送商品」选择器用的轻量卡片；status 用于前端标注「已下架」 */
    public record PickerProduct(Long id, String title, String image, Long price, String status) {
    }
}
