package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProductDtos;
import com.duoduo.product.dto.SpuAuditDtos.FieldChange;
import com.duoduo.product.dto.SpuAuditDtos.SpuAuditLogView;
import com.duoduo.product.entity.SpuAuditLogEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SpuAuditLogMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.CatalogLookup;
import com.duoduo.product.support.ProductContent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 商品审核历史（A-08）。
 *
 * <p><b>写入是 strict 的</b>：历史行与业务写在同一个事务里，历史写不进去，业务失败。
 * 这与 E18 的通用审计（best-effort、失败只记日志）刻意相反 —— 通用审计追求
 * 「别拖慢业务」，审核留痕追求「能复盘」，两者的取舍不可能同时成立。
 *
 * <p>只记第三方（merchant）商品的审核维度事件。自营商品免审，没有审核时间线；
 * 改价 / 改库存 / 上下架是正交高频动作，也不进本表。
 */
@Service
public class SpuAuditHistoryService {

    private static final Logger log = LoggerFactory.getLogger(SpuAuditHistoryService.class);

    public static final String EVENT_SUBMIT = "SUBMIT";
    public static final String EVENT_EDIT = "EDIT";
    public static final String EVENT_RESUBMIT = "RESUBMIT";
    public static final String EVENT_APPROVE = "APPROVE";
    public static final String EVENT_REJECT = "REJECT";
    public static final String EVENT_SAMPLE_REJECT = "SAMPLE_REJECT";

    public static final String ACTOR_SELLER = "SELLER";
    public static final String ACTOR_STAFF = "STAFF";
    public static final String ACTOR_OPERATOR = "OPERATOR";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    /** 单个字段的展示值上限（码点）。超长截断并打标，不静默丢整条变更。 */
    private static final int FIELD_MAX_CODE_POINTS = 2000;

    private static final String SUPPLY_MERCHANT = "merchant";
    private static final String AUDIT_REJECTED = "REJECTED";

    private final SpuAuditLogMapper logMapper;
    private final SpuMapper spuMapper;
    private final CatalogLookup catalogLookup;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public SpuAuditHistoryService(SpuAuditLogMapper logMapper,
                                  SpuMapper spuMapper,
                                  CatalogLookup catalogLookup,
                                  SnowflakeIdGenerator idGenerator,
                                  ObjectMapper objectMapper) {
        this.logMapper = logMapper;
        this.spuMapper = spuMapper;
        this.catalogLookup = catalogLookup;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * 审核相关的字段快照。
     *
     * <p>调用方在<b>改内存实体之前</b>取一次 before，改完再取一次 after。
     * 直接传 {@link SpuEntity} 不行：整个更新流程复用的是同一个对象，
     * 事后去读「改前值」只会读到已经改过的值，diff 永远是空的。
     */
    public record ContentSnapshot(
            String title,
            String subtitle,
            String mainImage,
            String images,
            String detail,
            String intro,
            String detailHtml,
            String specParams,
            String packaging,
            Long categoryId,
            Long brandId,
            String auditStatus,
            String auditSource,
            String auditReason,
            Integer version
    ) {
        public static ContentSnapshot of(SpuEntity spu) {
            return new ContentSnapshot(
                    spu.getTitle(), spu.getSubtitle(), spu.getMainImage(), spu.getImages(),
                    spu.getDetail(), spu.getIntro(), spu.getDetailHtml(),
                    spu.getSpecParams(), spu.getPackaging(),
                    spu.getCategoryId(), spu.getBrandId(),
                    spu.getAuditStatus(), spu.getAuditSource(), spu.getAuditReason(),
                    spu.getVersion());
        }
    }

    // ==================== 写侧 ====================

    public void recordSubmit(SpuEntity spu, boolean staffMember) {
        if (!recordable(spu)) {
            return;
        }
        insert(EVENT_SUBMIT, null, spu.getAuditStatus(), spu.getAuditSource(), null,
                null, zeroIfNull(spu.getVersion()), null, null, spu, staffMember);
    }

    public void recordEdit(ContentSnapshot before, SpuEntity after, boolean staffMember) {
        if (before == null || !recordable(after)) {
            return;
        }
        insert(EVENT_EDIT, before.auditStatus(), after.getAuditStatus(), after.getAuditSource(), null,
                before.version(), zeroIfNull(after.getVersion()), null,
                diff(before, after), after, staffMember);
    }

    /**
     * 规格结构变更（A-14）：与普通内容编辑同属 EDIT 事件，额外落一条
     * {@code field=skus / label=规格} 的改前 / 改后文本。
     *
     * <p>为什么规格不塞进 {@link ContentSnapshot}：快照是 SPU 主档的字段投影，
     * 规格在另一张表、数量可变；把可变集合塞进「一行快照」会让 diff 逻辑与
     * 版本语义都变复杂。这里由调用方在改前 / 改后各生成一段稳定文本，
     * 与其它字段共用同一套截断与序列化。
     */
    public void recordSkuEdit(ContentSnapshot before, SpuEntity after,
                              String skusBefore, String skusAfter, boolean staffMember) {
        if (before == null || !recordable(after)) {
            return;
        }
        List<FieldChange> changes = diff(before, after);
        add(changes, "skus", "规格", skusBefore, skusAfter, skusBefore, skusAfter);
        insert(EVENT_EDIT, before.auditStatus(), after.getAuditStatus(), after.getAuditSource(), null,
                before.version(), zeroIfNull(after.getVersion()), null, changes, after, staffMember);
    }

    public void recordResubmit(ContentSnapshot before, SpuEntity after, boolean staffMember) {
        if (before == null || !recordable(after)) {
            return;
        }
        insert(EVENT_RESUBMIT, before.auditStatus(), after.getAuditStatus(), after.getAuditSource(), null,
                before.version(), zeroIfNull(after.getVersion()), null, null, after, staffMember);
    }

    /** 审核结论事件；事件名由结论决定，不额外传参 —— 少一个可能传反的参数。 */
    public void recordAudit(ContentSnapshot before, SpuEntity after) {
        if (before == null || !recordable(after)) {
            return;
        }
        String event = AUDIT_REJECTED.equals(after.getAuditStatus()) ? EVENT_REJECT : EVENT_APPROVE;
        insert(event, before.auditStatus(), after.getAuditStatus(), after.getAuditSource(),
                after.getAuditReason(), before.version(), zeroIfNull(after.getVersion()),
                null, null, after, false);
    }

    public void recordSampleReject(ContentSnapshot before, SpuEntity after, Integer sampledVersion) {
        if (before == null || !recordable(after)) {
            return;
        }
        insert(EVENT_SAMPLE_REJECT, before.auditStatus(), after.getAuditStatus(), after.getAuditSource(),
                after.getAuditReason(), before.version(), zeroIfNull(after.getVersion()),
                sampledVersion, null, after, false);
    }

    private void insert(String event, String fromStatus, String toStatus, String source, String reason,
                        Integer versionBefore, Integer versionAfter, Integer sampledVersion,
                        List<FieldChange> changes, SpuEntity spu, boolean staffMember) {
        Actor actor = actor(staffMember);
        SpuAuditLogEntity row = new SpuAuditLogEntity();
        row.setId(idGenerator.nextId());
        row.setSpuId(spu.getId());
        row.setSellerId(spu.getSellerId() == null ? 0L : spu.getSellerId());
        row.setEvent(event);
        row.setActorType(actor.type());
        row.setActorId(actor.id());
        row.setActorPhone(actor.phone());
        row.setFromAuditStatus(fromStatus);
        row.setToAuditStatus(toStatus);
        row.setAuditSource(source);
        row.setReason(trimToNull(reason));
        row.setVersionBefore(versionBefore);
        row.setVersionAfter(versionAfter);
        row.setSampledVersion(sampledVersion);
        row.setChanges(changes == null || changes.isEmpty() ? null : toJson(changes));
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        logMapper.insert(row);
    }

    private record Actor(String type, Long id, String phone) {
    }

    /**
     * 操作人快照。店员在系统里仍然是 USER 角色（权限来自店铺成员身份），
     * 所以不能只看角色 —— 必须由调用方告诉这里是「以店员身份行动」。
     */
    private static Actor actor(boolean staffMember) {
        LoginUser user = UserContext.get();
        if (user == null) {
            return new Actor(ACTOR_SYSTEM, null, null);
        }
        String type = staffMember
                ? ACTOR_STAFF
                : (user.isAdmin() || user.isOperator() ? ACTOR_OPERATOR : ACTOR_SELLER);
        return new Actor(type, user.userId(), user.phone());
    }

    private static boolean recordable(SpuEntity spu) {
        return spu != null && spu.getId() != null && SUPPLY_MERCHANT.equals(spu.getSupplyType());
    }

    // ==================== 字段级 diff ====================

    private List<FieldChange> diff(ContentSnapshot before, SpuEntity after) {
        List<FieldChange> changes = new ArrayList<>();
        add(changes, "title", "标题", before.title(), after.getTitle(),
                before.title(), after.getTitle());
        add(changes, "subtitle", "副标题", before.subtitle(), after.getSubtitle(),
                before.subtitle(), after.getSubtitle());
        add(changes, "mainImage", "主图", before.mainImage(), after.getMainImage(),
                before.mainImage(), after.getMainImage());
        add(changes, "images", "图片集", before.images(), after.getImages(),
                imagesText(before.images()), imagesText(after.getImages()));
        add(changes, "detail", "详情", before.detail(), after.getDetail(),
                before.detail(), after.getDetail());
        add(changes, "intro", "商品介绍", before.intro(), after.getIntro(),
                before.intro(), after.getIntro());
        // 富文本：原始 HTML 变了就算变更；展示值用纯文本，避免让运营读 <p> 标签
        add(changes, "detailHtml", "富文本详情", before.detailHtml(), after.getDetailHtml(),
                ProductContent.htmlText(before.detailHtml()), ProductContent.htmlText(after.getDetailHtml()));
        add(changes, "specParams", "规格参数", before.specParams(), after.getSpecParams(),
                kvText(before.specParams()), kvText(after.getSpecParams()));
        add(changes, "packaging", "包装规格", before.packaging(), after.getPackaging(),
                kvText(before.packaging()), kvText(after.getPackaging()));
        if (!Objects.equals(before.categoryId(), after.getCategoryId())) {
            Map<Long, String> names = safeNames(true, before.categoryId(), after.getCategoryId());
            add(changes, "categoryId", "类目", before.categoryId(), after.getCategoryId(),
                    nameOrId(names, before.categoryId()), nameOrId(names, after.getCategoryId()));
        }
        if (!Objects.equals(before.brandId(), after.getBrandId())) {
            Map<Long, String> names = safeNames(false, before.brandId(), after.getBrandId());
            add(changes, "brandId", "品牌", before.brandId(), after.getBrandId(),
                    nameOrId(names, before.brandId()), nameOrId(names, after.getBrandId()));
        }
        return changes;
    }

    private static void add(List<FieldChange> out, String field, String label,
                            Object beforeRaw, Object afterRaw,
                            String beforeDisplay, String afterDisplay) {
        if (Objects.equals(beforeRaw, afterRaw)) {
            return;
        }
        Text before = truncate(beforeDisplay);
        Text after = truncate(afterDisplay);
        out.add(new FieldChange(field, label, before.value(), after.value(),
                before.truncated() || after.truncated()));
    }

    private record Text(String value, boolean truncated) {
    }

    private static Text truncate(String value) {
        if (value == null) {
            return new Text(null, false);
        }
        if (value.codePointCount(0, value.length()) <= FIELD_MAX_CODE_POINTS) {
            return new Text(value, false);
        }
        int end = value.offsetByCodePoints(0, FIELD_MAX_CODE_POINTS);
        return new Text(value.substring(0, end), true);
    }

    private static String imagesText(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    private static String kvText(String json) {
        List<ProductDtos.KvItem> items = ProductContent.parseKv(json);
        if (items.isEmpty()) {
            return null;
        }
        return items.stream()
                .map(item -> item.name() + ": " + item.value())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 查类目 / 品牌名。查不到不阻断编辑 —— 名字是展示信息，历史落库才是事实；
     * 查不到时回退成 #id，比让一次字典抖动把卖家的编辑顶回去更合理。
     */
    private Map<Long, String> safeNames(boolean category, Long beforeId, Long afterId) {
        List<Long> ids = new ArrayList<>(2);
        if (beforeId != null) {
            ids.add(beforeId);
        }
        if (afterId != null) {
            ids.add(afterId);
        }
        try {
            Map<Long, String> names = category
                    ? catalogLookup.categoryNames(ids) : catalogLookup.brandNames(ids);
            return names == null ? Map.of() : names;
        } catch (Exception ex) {
            log.warn("审核历史：{}名查询失败，按 id 展示 {} / {} : {}",
                    category ? "类目" : "品牌", beforeId, afterId, ex.getMessage());
            return Map.of();
        }
    }

    private static String nameOrId(Map<Long, String> names, Long id) {
        if (id == null) {
            return null;
        }
        String name = names.get(id);
        return name == null || name.isBlank() ? "#" + id : name;
    }

    // ==================== 读侧 ====================

    /**
     * 某个商品的审核历史（最新在前）。
     *
     * <p>只对运营开放。limit 上限 100：历史是给复盘用的时间线，不是全量导出通道 ——
     * 不封顶的话一次请求可以把几年的记录全捞出来。
     */
    public List<SpuAuditLogView> history(Long spuId, Integer limit) {
        OperatorGuard.requireOperator();
        if (spuId == null) {
            throw BizException.notFound("商品");
        }
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<SpuAuditLogEntity> rows = logMapper.selectList(new LambdaQueryWrapper<SpuAuditLogEntity>()
                .eq(SpuAuditLogEntity::getSpuId, spuId)
                .orderByDesc(SpuAuditLogEntity::getId)
                .last("LIMIT " + size));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(this::toView).toList();
    }

    private SpuAuditLogView toView(SpuAuditLogEntity row) {
        return new SpuAuditLogView(
                row.getId() == null ? null : String.valueOf(row.getId()),
                row.getEvent(),
                eventLabel(row.getEvent()),
                row.getActorType(),
                actorLabel(row.getActorType()),
                row.getActorId() == null ? null : String.valueOf(row.getActorId()),
                row.getActorPhone(),
                row.getFromAuditStatus(),
                row.getToAuditStatus(),
                row.getAuditSource(),
                row.getReason(),
                row.getVersionBefore(),
                row.getVersionAfter(),
                row.getSampledVersion(),
                parseChanges(row),
                isoOf(row.getCreatedAt()));
    }

    private List<FieldChange> parseChanges(SpuAuditLogEntity row) {
        String json = row.getChanges();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<FieldChange> parsed = objectMapper.readValue(json, new TypeReference<List<FieldChange>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception ex) {
            // 一条脏 JSON 不能把整页历史变成 500；丢掉这一条的 diff，其余照常展示
            log.warn("审核历史 changes 解析失败（id={}），按空变更展示：{}", row.getId(), ex.getMessage());
            return List.of();
        }
    }

    private String toJson(List<FieldChange> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception ex) {
            // strict：序列化失败说明这条历史不完整，业务必须回滚而不是留一条残缺记录
            throw new IllegalStateException("审核历史序列化失败", ex);
        }
    }

    private static String isoOf(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toString();
    }

    private static Integer zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String eventLabel(String event) {
        if (event == null) {
            return "";
        }
        return switch (event) {
            case EVENT_SUBMIT -> "提交审核";
            case EVENT_EDIT -> "编辑内容";
            case EVENT_RESUBMIT -> "重新提审";
            case EVENT_APPROVE -> "审核通过";
            case EVENT_REJECT -> "审核驳回";
            case EVENT_SAMPLE_REJECT -> "抽查判违规";
            default -> event;
        };
    }

    public static String actorLabel(String actorType) {
        if (actorType == null) {
            return "";
        }
        return switch (actorType) {
            case ACTOR_SELLER -> "卖家";
            case ACTOR_STAFF -> "店员";
            case ACTOR_OPERATOR -> "运营";
            case ACTOR_SYSTEM -> "系统";
            default -> actorType;
        };
    }
}
