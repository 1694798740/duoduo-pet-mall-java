package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.SpuAuditDtos.FieldChange;
import com.duoduo.product.dto.SpuAuditDtos.SpuAuditLogView;
import com.duoduo.product.entity.SpuAuditLogEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SpuAuditLogMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.CatalogLookup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品审核历史（A-08）单测。
 *
 * <h2>这些断言守的是什么</h2>
 * <ul>
 *   <li><b>diff 只留变化字段</b>：整行快照会让「改了一个标题」淹没在二十个未变字段里，
 *       而运维复盘时真正想知道的就是「那一次到底动了什么」。</li>
 *   <li><b>改前值是改前的</b>：调用方复用一个实体对象做更新，若不在变更前取快照，
 *       diff 永远是空的 —— 接口 200、历史有行，但什么都没记下来。</li>
 *   <li><b>操作人快照</b>：人可能改手机号、角色可能被调整，历史要保留「当时是谁」。</li>
 *   <li><b>自营不写</b>：自营免审，写进来只会让审核时间线出现平台自己的商品。</li>
 *   <li><b>读侧权限与坏数据降级</b>：历史是后台能力；一条脏 JSON 不能让整页 500。</li>
 * </ul>
 */
class SpuAuditHistoryServiceTest {

    private static final int FIELD_CAP = 2000;

    private final SpuAuditLogMapper logMapper = mock(SpuAuditLogMapper.class);
    private final SpuMapper spuMapper = mock(SpuMapper.class);
    private final CatalogLookup catalogLookup = mock(CatalogLookup.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpuAuditHistoryService service = new SpuAuditHistoryService(
            logMapper, spuMapper, catalogLookup, new SnowflakeIdGenerator(1, 1), objectMapper);

    @BeforeEach
    void stubCatalog() {
        when(catalogLookup.categoryNames(any())).thenReturn(Map.of(500L, "猫粮", 501L, "狗粮"));
        when(catalogLookup.brandNames(any())).thenReturn(Map.of(7L, "皇家", 8L, "渴望"));
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SpuAuditLogEntity.class);
    }

    // ---------------- 夹具 ----------------

    private static LoginUser seller() {
        return new LoginUser(101L, "13800000000", "SELLER", false);
    }

    private static LoginUser staff() {
        return new LoginUser(102L, "13800000001", "USER", false);
    }

    private static LoginUser operator() {
        return new LoginUser(900L, "13900000000", "OPERATOR", true);
    }

    /** 编辑后的商品：标题 / 主图 / 详情 / 类目相对 {@link #before()} 都变了 */
    private static SpuEntity spu() {
        SpuEntity spu = new SpuEntity();
        spu.setId(9001L);
        spu.setSellerId(101L);
        spu.setSupplyType("merchant");
        spu.setTitle("新标题");
        spu.setMainImage("https://img/new.png");
        spu.setDetail("新详情");
        spu.setCategoryId(501L);
        spu.setAuditStatus("APPROVED");
        spu.setAuditSource("AUTO");
        spu.setVersion(1);
        return spu;
    }

    private static SpuEntity spuAtVersion0() {
        SpuEntity spu = spu();
        spu.setVersion(0);
        return spu;
    }

    private static SpuAuditHistoryService.ContentSnapshot before() {
        return new SpuAuditHistoryService.ContentSnapshot(
                "旧标题", null, "https://img/old.png", null, "旧详情", null, null,
                null, null, 500L, null, "PENDING", null, null, 0);
    }

    private static SpuAuditHistoryService.ContentSnapshot statusSnapshot(
            String auditStatus, String source, int version) {
        return new SpuAuditHistoryService.ContentSnapshot(
                "新标题", null, "https://img/new.png", null, "新详情", null, null,
                null, null, 501L, null, auditStatus, source, null, version);
    }

    private SpuAuditLogEntity captureInsert() {
        ArgumentCaptor<SpuAuditLogEntity> captor = ArgumentCaptor.forClass(SpuAuditLogEntity.class);
        verify(logMapper).insert(captor.capture());
        return captor.getValue();
    }

    private List<FieldChange> changesOf(SpuAuditLogEntity row) throws Exception {
        return objectMapper.readValue(row.getChanges(), new TypeReference<List<FieldChange>>() {
        });
    }

    // ---------------- 写侧 ----------------

    @Test
    void 编辑只记录变化字段并带中文标签与改前改后值() throws Exception {
        UserContext.set(seller());

        service.recordEdit(before(), spu(), false);

        SpuAuditLogEntity row = captureInsert();
        assertThat(row.getEvent()).isEqualTo("EDIT");
        assertThat(row.getSpuId()).isEqualTo(9001L);
        assertThat(row.getSellerId()).isEqualTo(101L);
        assertThat(row.getFromAuditStatus()).isEqualTo("PENDING");
        assertThat(row.getToAuditStatus()).isEqualTo("APPROVED");
        assertThat(row.getAuditSource()).isEqualTo("AUTO");
        assertThat(row.getVersionBefore()).isZero();
        assertThat(row.getVersionAfter()).isEqualTo(1);
        assertThat(row.getActorType()).isEqualTo("SELLER");
        assertThat(row.getActorId()).isEqualTo(101L);
        assertThat(row.getActorPhone()).isEqualTo("13800000000");

        List<FieldChange> changes = changesOf(row);
        assertThat(changes).extracting(FieldChange::field)
                .containsExactly("title", "mainImage", "detail", "categoryId");
        assertThat(changes.get(0).label()).isEqualTo("标题");
        assertThat(changes.get(0).before()).isEqualTo("旧标题");
        assertThat(changes.get(0).after()).isEqualTo("新标题");
        assertThat(changes.get(0).truncated()).isFalse();
        // 类目 / 品牌存的是 id，展示给人看必须是名字；存 id 等于让人去查表
        assertThat(changes.get(3).label()).isEqualTo("类目");
        assertThat(changes.get(3).before()).isEqualTo("猫粮");
        assertThat(changes.get(3).after()).isEqualTo("狗粮");
    }

    @Test
    void 超长值按两千码点截断并打标() throws Exception {
        UserContext.set(seller());
        String longBefore = "旧".repeat(2100);
        String longAfter = "新".repeat(2100);
        SpuAuditHistoryService.ContentSnapshot before = new SpuAuditHistoryService.ContentSnapshot(
                "新标题", null, "https://img/new.png", null, longBefore, null, null,
                null, null, 501L, null, "APPROVED", "AUTO", null, 0);
        SpuEntity after = spu();
        after.setDetail(longAfter);

        service.recordEdit(before, after, false);

        List<FieldChange> changes = changesOf(captureInsert());
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).field()).isEqualTo("detail");
        assertThat(changes.get(0).before()).hasSize(FIELD_CAP);
        assertThat(changes.get(0).after()).hasSize(FIELD_CAP);
        assertThat(changes.get(0).truncated()).isTrue();
    }

    @Test
    void 操作人快照区分卖家店员运营与系统() {
        UserContext.set(seller());
        service.recordSubmit(spuAtVersion0(), false);
        UserContext.set(staff());
        service.recordSubmit(spuAtVersion0(), true);
        UserContext.set(operator());
        service.recordSubmit(spuAtVersion0(), false);
        UserContext.clear();
        service.recordSubmit(spuAtVersion0(), false);

        ArgumentCaptor<SpuAuditLogEntity> captor = ArgumentCaptor.forClass(SpuAuditLogEntity.class);
        verify(logMapper, times(4)).insert(captor.capture());
        List<SpuAuditLogEntity> rows = captor.getAllValues();
        assertThat(rows).extracting(SpuAuditLogEntity::getActorType)
                .containsExactly("SELLER", "STAFF", "OPERATOR", "SYSTEM");
        assertThat(rows.get(0).getActorId()).isEqualTo(101L);
        assertThat(rows.get(0).getActorPhone()).isEqualTo("13800000000");
        assertThat(rows.get(1).getActorId()).isEqualTo(102L);
        assertThat(rows.get(2).getActorId()).isEqualTo(900L);
        assertThat(rows.get(3).getActorId()).isNull();
    }

    @Test
    void 提交事件没有改前状态与版本() {
        UserContext.set(seller());

        service.recordSubmit(spuAtVersion0(), false);

        SpuAuditLogEntity row = captureInsert();
        assertThat(row.getEvent()).isEqualTo("SUBMIT");
        assertThat(row.getFromAuditStatus()).isNull();
        assertThat(row.getToAuditStatus()).isEqualTo("APPROVED");
        assertThat(row.getAuditSource()).isEqualTo("AUTO");
        assertThat(row.getVersionBefore()).isNull();
        assertThat(row.getVersionAfter()).isZero();
        assertThat(row.getChanges()).isNull();
    }

    @Test
    void 审核事件按结论区分通过与驳回() {
        UserContext.set(operator());

        SpuEntity approved = spu();
        approved.setAuditStatus("APPROVED");
        approved.setAuditSource("MANUAL");
        approved.setAuditReason(null);
        approved.setVersion(2);
        service.recordAudit(statusSnapshot("PENDING", null, 1), approved);

        SpuEntity rejected = spu();
        rejected.setAuditStatus("REJECTED");
        rejected.setAuditSource("MANUAL");
        rejected.setAuditReason("主图不符");
        rejected.setVersion(3);
        service.recordAudit(statusSnapshot("PENDING", null, 2), rejected);

        ArgumentCaptor<SpuAuditLogEntity> captor = ArgumentCaptor.forClass(SpuAuditLogEntity.class);
        verify(logMapper, times(2)).insert(captor.capture());
        List<SpuAuditLogEntity> rows = captor.getAllValues();
        assertThat(rows.get(0).getEvent()).isEqualTo("APPROVE");
        assertThat(rows.get(0).getReason()).isNull();
        assertThat(rows.get(0).getAuditSource()).isEqualTo("MANUAL");
        assertThat(rows.get(1).getEvent()).isEqualTo("REJECT");
        assertThat(rows.get(1).getReason()).isEqualTo("主图不符");
        assertThat(rows.get(1).getVersionBefore()).isEqualTo(2);
        assertThat(rows.get(1).getVersionAfter()).isEqualTo(3);
        assertThat(rows.get(1).getChanges()).isNull();
    }

    @Test
    void 抽查判违规记录抽样版本() {
        UserContext.set(operator());
        SpuEntity after = spu();
        after.setAuditStatus("REJECTED");
        after.setAuditSource("SAMPLE");
        after.setAuditReason("主图违规");
        after.setVersion(4);

        service.recordSampleReject(statusSnapshot("APPROVED", "AUTO", 3), after, 3);

        SpuAuditLogEntity row = captureInsert();
        assertThat(row.getEvent()).isEqualTo("SAMPLE_REJECT");
        assertThat(row.getSampledVersion()).isEqualTo(3);
        assertThat(row.getVersionBefore()).isEqualTo(3);
        assertThat(row.getVersionAfter()).isEqualTo(4);
        assertThat(row.getReason()).isEqualTo("主图违规");
        assertThat(row.getAuditSource()).isEqualTo("SAMPLE");
    }

    @Test
    void 自营商品不写审核历史() {
        UserContext.set(operator());
        SpuEntity self = spu();
        self.setSupplyType("self");
        self.setSellerId(0L);

        service.recordSubmit(self, false);
        service.recordEdit(statusSnapshot("APPROVED", "SELF", 0), self, false);
        service.recordAudit(statusSnapshot("APPROVED", "SELF", 0), self);
        service.recordSampleReject(statusSnapshot("APPROVED", "SELF", 0), self, 0);

        verify(logMapper, never()).insert(any());
    }

    // ---------------- 读侧 ----------------

    @Test
    void 历史只对运营开放_未知商品404() {
        UserContext.set(seller());
        assertThatThrownBy(() -> service.history(9001L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅运营");

        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(null);
        assertThatThrownBy(() -> service.history(9001L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品");
    }

    @Test
    void 历史按id倒序读取_limit上限100且非法值回落50() {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu());
        when(logMapper.selectList(any())).thenReturn(List.of());

        service.history(9001L, 500);
        service.history(9001L, 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SpuAuditLogEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(logMapper, times(2)).selectList(captor.capture());
        List<LambdaQueryWrapper<SpuAuditLogEntity>> wrappers = captor.getAllValues();
        assertThat(wrappers.get(0).getSqlSegment())
                .contains("spu_id").contains("ORDER BY").contains("DESC").contains("LIMIT 100");
        assertThat(wrappers.get(1).getSqlSegment()).contains("LIMIT 50");
    }

    @Test
    void 历史视图翻译事件与操作人_坏JSON降级为空() throws Exception {
        UserContext.set(operator());
        when(spuMapper.selectById(9001L)).thenReturn(spu());

        SpuAuditLogEntity good = row(2L, "APPROVE", "OPERATOR", 900L, "13900000000");
        good.setChanges(objectMapper.writeValueAsString(List.of(
                new FieldChange("title", "标题", "旧", "新", false))));
        SpuAuditLogEntity bad = row(1L, "EDIT", "SELLER", 101L, "13800000000");
        bad.setChanges("{not-json");
        when(logMapper.selectList(any())).thenReturn(List.of(good, bad));

        List<SpuAuditLogView> views = service.history(9001L, 10);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).id()).isEqualTo("2");
        assertThat(views.get(0).event()).isEqualTo("APPROVE");
        assertThat(views.get(0).eventLabel()).isEqualTo("审核通过");
        assertThat(views.get(0).actorType()).isEqualTo("OPERATOR");
        assertThat(views.get(0).actorLabel()).isEqualTo("运营");
        assertThat(views.get(0).actorId()).isEqualTo("900");
        assertThat(views.get(0).changes()).hasSize(1);
        assertThat(views.get(0).createdAt()).isEqualTo("2026-10-24T10:00Z");

        assertThat(views.get(1).eventLabel()).isEqualTo("编辑内容");
        assertThat(views.get(1).actorLabel()).isEqualTo("卖家");
        // 一条脏 JSON 不能让整页 500；丢掉这一条的 diff 比丢掉整页历史更可接受
        assertThat(views.get(1).changes()).isEmpty();
        assertThat(views.get(1).actorPhone()).isEqualTo("13800000000");
    }

    private static SpuAuditLogEntity row(long id, String event, String actorType, Long actorId, String phone) {
        SpuAuditLogEntity row = new SpuAuditLogEntity();
        row.setId(id);
        row.setSpuId(9001L);
        row.setSellerId(101L);
        row.setEvent(event);
        row.setActorType(actorType);
        row.setActorId(actorId);
        row.setActorPhone(phone);
        row.setFromAuditStatus("PENDING");
        row.setToAuditStatus("APPROVED");
        row.setAuditSource("MANUAL");
        row.setVersionBefore(1);
        row.setVersionAfter(2);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0));
        return row;
    }
}
