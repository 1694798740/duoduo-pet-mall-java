package com.duoduo.product.support;

import com.duoduo.product.entity.BrandEntity;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.mapper.BrandMapper;
import com.duoduo.product.mapper.CategoryMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 类目 / 品牌名称批量查询器。
 *
 * <p>解决的核心问题是 <b>N+1 查询</b>：列表页每行商品都要显示类目名与品牌名，
 * 若逐行查询，一页 20 条就是 40 次额外查询。这里统一按 id 集合<b>一次性批量取</b>，
 * 再做内存映射。
 *
 * <p>类目与品牌都是超小字典表（百级），生产形态下会在启动后全量载入本地缓存、
 * 通过配置中心的变更事件刷新；本阶段保持每次批量查询，保证数据即时一致。
 */
@Component
public class CatalogLookup {

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;

    public CatalogLookup(CategoryMapper categoryMapper, BrandMapper brandMapper) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
    }

    /** 批量取类目名，缺 id 时回退为 null，调用方自行兜底展示 */
    public Map<Long, String> categoryNames(Collection<Long> ids) {
        return names(ids, categoryMapper::selectBatchIds, CategoryEntity::getId, CategoryEntity::getName);
    }

    public Map<Long, String> brandNames(Collection<Long> ids) {
        return names(ids, brandMapper::selectBatchIds, BrandEntity::getId, BrandEntity::getName);
    }

    public Map<Long, CategoryEntity> categories(Collection<Long> ids) {
        Set<Long> distinct = distinct(ids);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(CategoryEntity::getId, c -> c, (a, b) -> a));
    }

    public Map<Long, BrandEntity> brands(Collection<Long> ids) {
        Set<Long> distinct = distinct(ids);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return brandMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(BrandEntity::getId, b -> b, (a, b) -> a));
    }

    private <T> Map<Long, String> names(Collection<Long> ids,
                                        Function<Collection<Long>, List<T>> loader,
                                        Function<T, Long> idGetter,
                                        Function<T, String> nameGetter) {
        Set<Long> distinct = distinct(ids);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>(distinct.size());
        for (T item : loader.apply(distinct)) {
            result.put(idGetter.apply(item), nameGetter.apply(item));
        }
        return result;
    }

    private Set<Long> distinct(Collection<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
