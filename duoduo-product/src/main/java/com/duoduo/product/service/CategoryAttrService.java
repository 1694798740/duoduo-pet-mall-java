package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProductDtos.CategoryAttrSaveRequest;
import com.duoduo.product.dto.ProductDtos.CategoryAttrView;
import com.duoduo.product.dto.ProductDtos.KvItem;
import com.duoduo.product.entity.CategoryAttrEntity;
import com.duoduo.product.entity.CategoryEntity;
import com.duoduo.product.mapper.CategoryAttrMapper;
import com.duoduo.product.mapper.CategoryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 类目属性模板（A-10）。
 *
 * <p><b>为什么属性值不单独建表</b>：历史商品的规格参数是自由 KV（{@code t_spu.spec_params}），
 * 存量数据没有属性 id，也没有类目模板。若本期强推值表，就要先回填全部历史商品，
 * 而回填只能靠猜（「净含量」到底对应哪个属性？）—— 猜出来的结构和自由 KV 一样不可信，
 * 还多了一份要同步的事实。因此模板只管「发布 / 改规格时的校验闸门」，
 * 值仍按<b>属性名</b>从 KV 里取；模板停用或删除后，那些值自然退化为自定义属性。
 *
 * <p><b>校验范围</b>：
 * <ul>
 *   <li>新建商品：始终校验（必填缺失直接拒绝发布）；</li>
 *   <li>编辑商品：只有<b>改了规格参数或换了类目</b>时才校验 ——
 *       只改标题 / 库存的老商品不应被后来新增的模板阻断（否则运营一加必填项，
 *       存量商品全部无法编辑）。</li>
 * </ul>
 */
@Service
public class CategoryAttrService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int NAME_MAX = 30;
    private static final int OPTION_MAX = 30;
    private static final int MAX_OPTIONS = 30;
    /** 与 t_category_attr.options VARCHAR(1000) 对齐 */
    private static final int OPTIONS_JSON_MAX = 1000;

    private final CategoryAttrMapper attrMapper;
    private final CategoryMapper categoryMapper;
    private final SnowflakeIdGenerator idGenerator;

    public CategoryAttrService(CategoryAttrMapper attrMapper, CategoryMapper categoryMapper,
                               SnowflakeIdGenerator idGenerator) {
        this.attrMapper = attrMapper;
        this.categoryMapper = categoryMapper;
        this.idGenerator = idGenerator;
    }

    // ---------------- 读 ----------------

    /** C 端 / 商家端：只返回启用中的模板项，按排序号升序 */
    public List<CategoryAttrView> activeFor(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        return attrMapper.selectList(new LambdaQueryWrapper<CategoryAttrEntity>()
                        .eq(CategoryAttrEntity::getCategoryId, categoryId)
                        .eq(CategoryAttrEntity::getStatus, CategoryAttrEntity.STATUS_ACTIVE)
                        .orderByAsc(CategoryAttrEntity::getSortNo)
                        .orderByAsc(CategoryAttrEntity::getId))
                .stream().map(this::toView).toList();
    }

    /** 运营后台：含停用项 —— 否则停用后就再也找不到、无法重新启用 */
    public List<CategoryAttrView> adminList(Long categoryId) {
        requireCategory(categoryId);
        return attrMapper.selectList(new LambdaQueryWrapper<CategoryAttrEntity>()
                        .eq(CategoryAttrEntity::getCategoryId, categoryId)
                        .orderByAsc(CategoryAttrEntity::getSortNo)
                        .orderByAsc(CategoryAttrEntity::getId))
                .stream().map(this::toView).toList();
    }

    // ---------------- 运营维护 ----------------

    public CategoryAttrView create(Long categoryId, CategoryAttrSaveRequest request) {
        requireCategory(categoryId);
        if (request == null) {
            throw BizException.validation("请求体不能为空");
        }
        String name = requireName(request.name());
        String inputType = normalizeType(request.inputType(), CategoryAttrEntity.TYPE_TEXT);
        List<String> options = normalizeOptions(inputType, request.options());

        CategoryAttrEntity entity = new CategoryAttrEntity();
        entity.setId(idGenerator.nextId());
        entity.setCategoryId(categoryId);
        entity.setName(name);
        entity.setInputType(inputType);
        entity.setRequired(Boolean.TRUE.equals(request.required()));
        entity.setOptions(serializeOptions(options));
        entity.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        entity.setStatus(CategoryAttrEntity.STATUS_ACTIVE);
        entity.setCreatedAt(now());
        entity.setUpdatedAt(entity.getCreatedAt());
        try {
            attrMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 唯一索引是并发下的唯一可靠判据（先查再插在这里没有意义）
            throw BizException.conflict("该类目下已存在属性「" + name + "」");
        }
        return toView(entity);
    }

    public CategoryAttrView update(Long id, CategoryAttrSaveRequest request) {
        CategoryAttrEntity entity = requireAttr(id);
        if (request == null) {
            throw BizException.validation("请求体不能为空");
        }
        if (request.name() != null) {
            entity.setName(requireName(request.name()));
        }
        if (request.inputType() != null) {
            entity.setInputType(normalizeType(request.inputType(), entity.getInputType()));
        }
        // 类型变化会改变 options 的合法性：转成非 SELECT 时必须清空，转成 SELECT 时沿用旧选项（若有）
        List<String> options = request.options() != null
                ? normalizeOptions(entity.getInputType(), request.options())
                : normalizeOptions(entity.getInputType(), parseOptions(entity.getOptions()));
        entity.setOptions(serializeOptions(options));
        if (request.required() != null) {
            entity.setRequired(request.required());
        }
        if (request.sortNo() != null) {
            entity.setSortNo(request.sortNo());
        }
        entity.setUpdatedAt(now());

        // options 可空：必须显式 set(null)，updateById 的 NOT_NULL 策略不会替我们写空值
        LambdaUpdateWrapper<CategoryAttrEntity> write = new LambdaUpdateWrapper<CategoryAttrEntity>()
                .eq(CategoryAttrEntity::getId, id)
                .set(CategoryAttrEntity::getName, entity.getName())
                .set(CategoryAttrEntity::getInputType, entity.getInputType())
                .set(CategoryAttrEntity::getRequired, entity.getRequired())
                .set(CategoryAttrEntity::getOptions, entity.getOptions())
                .set(CategoryAttrEntity::getSortNo, entity.getSortNo())
                .set(CategoryAttrEntity::getUpdatedAt, entity.getUpdatedAt());
        try {
            attrMapper.update(null, write);
        } catch (DuplicateKeyException e) {
            throw BizException.conflict("该类目下已存在属性「" + entity.getName() + "」");
        }
        return toView(entity);
    }

    public void setStatus(Long id, boolean active) {
        CategoryAttrEntity entity = requireAttr(id);
        entity.setStatus(active ? CategoryAttrEntity.STATUS_ACTIVE : CategoryAttrEntity.STATUS_INACTIVE);
        entity.setUpdatedAt(now());
        attrMapper.updateById(entity);
    }

    public void delete(Long id) {
        requireAttr(id);
        attrMapper.deleteById(id);
    }

    // ---------------- 发布 / 编辑校验 ----------------

    /**
     * 按类目模板校验规格参数。
     *
     * <p>只校验启用中的模板项；未填的自定义 KV 不受影响。错误一次性汇总必填缺失，
     * 类型 / 选项错误则立即抛出 —— 前者是「多填几个就行」，后者要商家先改对某个值。
     */
    public void validateForPublish(Long categoryId, List<KvItem> specParams) {
        if (categoryId == null) {
            return;
        }
        List<CategoryAttrEntity> attrs = attrMapper.selectList(new LambdaQueryWrapper<CategoryAttrEntity>()
                .eq(CategoryAttrEntity::getCategoryId, categoryId)
                .eq(CategoryAttrEntity::getStatus, CategoryAttrEntity.STATUS_ACTIVE)
                .orderByAsc(CategoryAttrEntity::getSortNo)
                .orderByAsc(CategoryAttrEntity::getId));
        if (attrs.isEmpty()) {
            return;
        }

        Map<String, String> values = new HashMap<>();
        if (specParams != null) {
            for (KvItem item : specParams) {
                if (item == null) {
                    continue;
                }
                String name = item.name() == null ? "" : item.name().trim();
                String value = item.value() == null ? "" : item.value().trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    values.putIfAbsent(name, value);
                }
            }
        }

        List<String> missing = new ArrayList<>();
        for (CategoryAttrEntity attr : attrs) {
            String value = values.get(attr.getName());
            if (value == null || value.isBlank()) {
                if (Boolean.TRUE.equals(attr.getRequired())) {
                    missing.add(attr.getName());
                }
                continue;
            }
            if (CategoryAttrEntity.TYPE_NUMBER.equals(attr.getInputType()) && !isNumber(value)) {
                throw BizException.validation("属性「" + attr.getName() + "」必须是数字");
            }
            if (CategoryAttrEntity.TYPE_SELECT.equals(attr.getInputType())) {
                List<String> options = parseOptions(attr.getOptions());
                // 选项为空属于模板配置异常：不阻断商家（运营会在后台发现并修正）
                if (!options.isEmpty() && !options.contains(value)) {
                    throw BizException.validation("属性「" + attr.getName() + "」只能选："
                            + String.join(" / ", options));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw BizException.validation("以下必填属性未填写：" + String.join("、", missing));
        }
    }

    // ---------------- 内部 ----------------

    private CategoryAttrEntity requireAttr(Long id) {
        CategoryAttrEntity entity = id == null ? null : attrMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("类目属性");
        }
        return entity;
    }

    private void requireCategory(Long categoryId) {
        if (categoryId == null) {
            throw BizException.validation("类目不能为空");
        }
        CategoryEntity category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BizException.notFound("类目");
        }
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.validation("属性名不能为空");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX) {
            throw BizException.validation("属性名不能超过 " + NAME_MAX + " 个字");
        }
        return name;
    }

    private String normalizeType(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String type = raw.trim().toUpperCase();
        if (!CategoryAttrEntity.TYPE_TEXT.equals(type)
                && !CategoryAttrEntity.TYPE_NUMBER.equals(type)
                && !CategoryAttrEntity.TYPE_SELECT.equals(type)) {
            throw BizException.validation("属性类型只能是 TEXT / NUMBER / SELECT");
        }
        return type;
    }

    private List<String> normalizeOptions(String inputType, List<String> raw) {
        if (!CategoryAttrEntity.TYPE_SELECT.equals(inputType)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String option : raw) {
                if (option == null || option.isBlank()) {
                    continue;
                }
                String value = option.trim();
                if (value.length() > OPTION_MAX) {
                    throw BizException.validation("选项不能超过 " + OPTION_MAX + " 个字：" + value);
                }
                out.add(value);
            }
        }
        if (out.isEmpty()) {
            throw BizException.validation("SELECT 类型至少要有一个选项");
        }
        if (out.size() > MAX_OPTIONS) {
            throw BizException.validation("选项最多 " + MAX_OPTIONS + " 个");
        }
        List<String> options = new ArrayList<>(out);
        if (serializeOptions(options).length() > OPTIONS_JSON_MAX) {
            throw BizException.validation("选项总长度超过上限，请精简");
        }
        return options;
    }

    private String serializeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(options);
        } catch (Exception e) {
            throw new IllegalStateException("属性选项序列化失败", e);
        }
    }

    private List<String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JSON.readValue(json, new TypeReference<List<String>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            // 历史手工改库可能写入非 JSON：降级为「无选项」而不是让发布 500
            return List.of();
        }
    }

    private boolean isNumber(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private CategoryAttrView toView(CategoryAttrEntity entity) {
        return new CategoryAttrView(entity.getId(), entity.getCategoryId(), entity.getName(),
                entity.getInputType(), Boolean.TRUE.equals(entity.getRequired()),
                parseOptions(entity.getOptions()), entity.getSortNo() == null ? 0 : entity.getSortNo(),
                entity.getStatus());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
