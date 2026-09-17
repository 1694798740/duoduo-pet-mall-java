package com.duoduo.product.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProductDtos;
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
    private final SnowflakeIdGenerator idGenerator;

    public CategoryService(CategoryMapper categoryMapper, BrandMapper brandMapper,
                           SnowflakeIdGenerator idGenerator) {
        this.categoryMapper = categoryMapper;
        this.brandMapper = brandMapper;
        this.idGenerator = idGenerator;
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

    /** 扁平列表，供下拉选择使用：只含启用中的类目 */
    public List<CategoryNode> flat() {
        List<CategoryEntity> all = activeCategories();
        return all.stream()
                .sorted(Comparator
                        .comparing(CategoryEntity::getDepth, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(CategoryEntity::getSortNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> toFlatNode(c))
                .toList();
    }

    /**
     * 后台管理用扁平列表：不过滤状态。
     *
     * <p>停用的类目也必须可见 —— 否则运营停用后就再也找不到它，无法重新启用。
     * 公开的下拉/树形接口（{@link #tree()} / {@link #flat()}）仍只暴露 ACTIVE。
     */
    public List<CategoryNode> flatForAdmin() {
        List<CategoryEntity> all = categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .orderByAsc(CategoryEntity::getDepth)
                .orderByAsc(CategoryEntity::getSortNo));
        return all.stream()
                .map(c -> toFlatNode(c))
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
                .map(c -> new CategoryNode(c.getId(), c.getName(), c.getParentId(), nz(c.getDepth()), c.getIcon(), c.getStatus(), List.of()))
                .toList();
        return new CategoryNode(entity.getId(), entity.getName(), entity.getParentId(),
                nz(entity.getDepth()), entity.getIcon(), entity.getStatus(), children);
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
                .map(b -> toBrandView(b))
                .toList();
    }

    private BrandView toBrandView(BrandEntity b) {
        return new BrandView(b.getId(), b.getName(), b.getLogo(), b.getCountry(), b.getStory(), b.getStatus());
    }

    private List<CategoryEntity> activeCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getStatus, STATUS_ACTIVE)
                .orderByAsc(CategoryEntity::getSortNo));
    }

    private CategoryNode toNode(CategoryEntity entity, List<CategoryEntity> children) {
        List<CategoryNode> childNodes = children.stream()
                .sorted(Comparator.comparing(CategoryEntity::getSortNo, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> new CategoryNode(c.getId(), c.getName(), c.getParentId(), nz(c.getDepth()), c.getIcon(), c.getStatus(), List.of()))
                .toList();
        return new CategoryNode(entity.getId(), entity.getName(), entity.getParentId(),
                nz(entity.getDepth()), entity.getIcon(), entity.getStatus(), childNodes);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    // ---------------- 运营管理（M10）：类目 / 品牌 ----------------

    private static final String STATUS_INACTIVE = "INACTIVE";

    public CategoryNode createCategory(ProductDtos.CategorySaveRequest request) {
        String name = requireText(request.name(), "类目名称");
        CategoryEntity entity = new CategoryEntity();
        // id-type=input：主键必须显式赋值，种子数据靠 SQL 给定，这里走雪花
        entity.setId(idGenerator.nextId());
        entity.setName(name);
        entity.setParentId(request.parentId() == null ? 0L : request.parentId());
        entity.setDepth(request.parentId() == null || request.parentId() == 0L ? 1 : 2);
        entity.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        entity.setIcon(request.icon());
        entity.setStatus(STATUS_ACTIVE);
        entity.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getCreatedAt());
        categoryMapper.insert(entity);
        return toFlatNode(entity);
    }

    public CategoryNode updateCategory(Long id, ProductDtos.CategorySaveRequest request) {
        CategoryEntity entity = categoryMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("类目");
        }
        if (request.name() != null) {
            entity.setName(requireText(request.name(), "类目名称"));
        }
        if (request.parentId() != null) {
            entity.setParentId(request.parentId());
            entity.setDepth(request.parentId() == 0L ? 1 : 2);
        }
        if (request.sortNo() != null) {
            entity.setSortNo(request.sortNo());
        }
        if (request.icon() != null) {
            entity.setIcon(request.icon());
        }
        entity.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        categoryMapper.updateById(entity);
        return toFlatNode(entity);
    }

    /** 停用 / 启用类目（软删除：有子类目时一并处理由调用方决定，这里只处理自身） */
    public void setCategoryStatus(Long id, boolean active) {
        CategoryEntity entity = categoryMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("类目");
        }
        entity.setStatus(active ? STATUS_ACTIVE : STATUS_INACTIVE);
        entity.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        categoryMapper.updateById(entity);
    }

    /** 删除类目：有子类目则拒绝，避免留下孤儿节点 */
    public void deleteCategory(Long id) {
        if (categoryMapper.selectById(id) == null) {
            throw BizException.notFound("类目");
        }
        Long children = categoryMapper.selectCount(new LambdaQueryWrapper<CategoryEntity>()
                .eq(CategoryEntity::getParentId, id));
        if (children != null && children > 0) {
            throw BizException.businessRule("该类目下还有子类目，请先删除子类目");
        }
        categoryMapper.deleteById(id);
    }

    /** 运营侧品牌列表（含停用，与公开查询区分） */
    public List<BrandView> adminBrands() {
        return brandMapper.selectList(new LambdaQueryWrapper<BrandEntity>()
                        .orderByAsc(BrandEntity::getId)).stream()
                .map(b -> toBrandView(b))
                .toList();
    }

    public BrandView createBrand(ProductDtos.BrandSaveRequest request) {
        BrandEntity entity = new BrandEntity();
        entity.setId(idGenerator.nextId());
        entity.setName(requireText(request.name(), "品牌名称"));
        entity.setLogo(request.logo());
        entity.setCountry(request.country());
        entity.setStory(request.story());
        entity.setStatus(STATUS_ACTIVE);
        entity.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getCreatedAt());
        brandMapper.insert(entity);
        return toBrandView(entity);
    }

    public BrandView updateBrand(Long id, ProductDtos.BrandSaveRequest request) {
        BrandEntity entity = brandMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("品牌");
        }
        if (request.name() != null) {
            entity.setName(requireText(request.name(), "品牌名称"));
        }
        if (request.logo() != null) {
            entity.setLogo(request.logo());
        }
        if (request.country() != null) {
            entity.setCountry(request.country());
        }
        if (request.story() != null) {
            entity.setStory(request.story());
        }
        entity.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        brandMapper.updateById(entity);
        return toBrandView(entity);
    }

    public void setBrandStatus(Long id, boolean active) {
        BrandEntity entity = brandMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("品牌");
        }
        entity.setStatus(active ? STATUS_ACTIVE : STATUS_INACTIVE);
        entity.setUpdatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        brandMapper.updateById(entity);
    }

    public void deleteBrand(Long id) {
        if (brandMapper.selectById(id) == null) {
            throw BizException.notFound("品牌");
        }
        brandMapper.deleteById(id);
    }

    private CategoryNode toFlatNode(CategoryEntity entity) {
        return new CategoryNode(entity.getId(), entity.getName(), entity.getParentId(),
                nz(entity.getDepth()), entity.getIcon(), entity.getStatus(), List.of());
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BizException.validation(field + "不能为空");
        }
        return value.trim();
    }
}
