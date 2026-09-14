package com.duoduo.product.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.BrandView;
import com.duoduo.product.dto.ProductDtos.CategoryNode;
import com.duoduo.product.entity.BrandEntity;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.mapper.BrandMapper;
import com.duoduo.product.mapper.CategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 类目与品牌查询。
 *
 * <p>类目只有几百条且几乎不变，生产形态会整体载入本地缓存；
 * 这里每次按 parentId 分组重建树，保证数据一致性优先。
 */
@Service
public class CategoryService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;

    public CategoryService(CategoryMapper categoryMapper, BrandMapper brandMapper) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
    }

    /** 两级类目树 */
    public List<CategoryNode> tree() {
        List<CategoryEntity> all = activeCategories();
        Map<Long, List<CategoryEntity>> childrenByParent = all.stream()
                .filter(c -> c.getParentId() != null && c.getParentId() != 0L)
                .collect(Collectors.groupingBy(CategoryEntity::getParentId, LinkedHashMap::new, Collectors.toList()));

        return all.stream()
                .filter(c -> c.getParentId() == null || c.getParentId() == 0L)
                .sorted(Comparator.comparing(CategoryEntity::getSortNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(parent -> toNode(parent, childrenByParent.getOrDefault(parent.getId(), List.of())))
                .toList();
    }

    /** 扁平列表，供后台管理与下拉选择使用 */
    public List<CategoryNode> flat() {
        List<CategoryEntity> all = activeCategories();
        return all.stream()
                .sorted(Comparator
                        .comparing(CategoryEntity::getDepth, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CategoryEntity::getSortNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new CategoryNode(c.getId(), c.getName(), c.getParentId(), nz(c.getDepth()), c.getIcon(), List.of()))
                .toList();
    }

    public CategoryNode get(Long id) {
        CategoryEntity entity = categoryMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("类目");
        }
        List<CategoryNode> children = activeCategories().stream()
                .filter(c -> id.equals(c.getParentId()))
                .sorted(Comparator.comparing(CategoryEntity::getSortNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new CategoryNode(c.getId(), c.getName(), c.getParentId(), nz(c.getDepth()), c.getIcon(), List.of()))
                .toList();
        return new CategoryNode(entity.getId(), entity.getName(), entity.getParentId(),
                nz(entity.getDepth()), entity.getIcon(), children);
    }

    /**
     * 把「可能是父类目」的 id 展开成用于过滤的 id 列表。
     * 用户点一级类目时应当能看到其下所有子类目的商品 —— 这是导航的基本预期。
     */
    public List<Long> expandCategoryIds(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        activeCategories().stream()
                .filter(c -> categoryId.equals(c.getParentId()))
                .forEach(c -> ids.add(c.getId()));
        return ids;
    }

    public List<BrandView> brands() {
        List<BrandEntity> brands = brandMapper.selectList(new LambdaQueryWrapper<BrandEntity>()
                .eq(BrandEntity::getStatus, STATUS_ACTIVE)
                .orderByAsc(BrandEntity::getId));
        return brands.stream()
                .map(b -> new BrandView(b.getId(), b.getName(), b.getLogo(), b.getCountry(), b.getStory()))
                .toList();
    }

    private List<CategoryEntity> activeCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getStatus, STATUS_ACTIVE)
                .orderByAsc(CategoryEntity::getSortNo));
    }

    private CategoryNode toNode(CategoryEntity entity, List<CategoryEntity> children) {
        List<CategoryNode> childNodes = children.stream()
                .sorted(Comparator.comparing(CategoryEntity::getSortNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new CategoryNode(c.getId(), c.getName(), c.getParentId(), nz(c.getDepth()), c.getIcon(), List.of()))
                .toList();
        return new CategoryNode(entity.getId(), entity.getName(), entity.getParentId(),
                nz(entity.getDepth()), entity.getIcon(), childNodes);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
