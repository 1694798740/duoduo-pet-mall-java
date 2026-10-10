package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditCategory;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditOutcome;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.AuditDtos.AuditLogView;
import com.duoduo.user.dto.AuditDtos.AuditQuery;
import com.duoduo.user.entity.AuditLogEntity;
import com.duoduo.user.mapper.AuditLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计日志的写入与检索（E18）。
 *
 * <h2>写入侧守两条完全相反的纪律</h2>
 * <ol>
 *   <li><b>绝不外抛</b>：写入失败返回 {@code false}，只打 ERROR 日志。审计是旁路 ——
 *       让它有能力把一次正常的业务操作变成 500，是为了审计牺牲业务，方向错了。</li>
 *   <li><b>绝不静默丢弃</b>：写不进去必须留下 ERROR；「以为记了、其实没记」
 *       是审计里最危险的状态。所以「失败返回 false」这件事本身要被测到。</li>
 * </ol>
 *
 * <h2>检索侧守「不许静默返回空」</h2>
 * 起止时间填反时若返回空列表，运营会得到「这段时间什么都没发生」这个**错误结论**，
 * 而真实原因只是两个参数反了。这类失败比报错难恢复得多（报错至少知道要重试）。
 * 因此这里断言它抛 {@code VALIDATION_ERROR}。
 */
class AuditLogServiceTest {

    private static final long ACTOR_ID = 810000000000000101L;
    private static final long GENERATED_ID = 810000000000000999L;

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);

    private final AuditLogService service = new AuditLogService(auditLogMapper, idGenerator);

    /**
     * 注册实体元数据：{@code LambdaQueryWrapper.eq(AuditLogEntity::getActorId, v)}
     * 要在运行期把方法引用解析成列名，纯 Mockito 单测里没有容器帮你建这块缓存。
     */
    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AuditLogEntity.class);
    }

    private static AuditLogRecord record() {
        return new AuditLogRecord(
                ACTOR_ID, "13684295254", "USER_ROLE_CHANGE", "SECURITY", "SUCCESS",
                710000000000000002L, "USER", 0L, "USER", "SELLER",
                "203.0.113.9", "req-1", "USER -> SELLER",
                "2026-10-09T12:00:00");
    }

    private AuditLogEntity captured() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ---------------- 写入 ----------------

    @Test
    @DisplayName("写入成功：字段逐个落到实体上（雪花 id 由生成器下发）")
    void 写入成功() {
        when(idGenerator.nextId()).thenReturn(GENERATED_ID);

        assertThat(service.record(record())).isTrue();

        AuditLogEntity entity = captured();
        assertThat(entity.getId()).isEqualTo(GENERATED_ID);
        assertThat(entity.getActorId()).isEqualTo(ACTOR_ID);
        assertThat(entity.getActorPhone()).isEqualTo("13684295254");
        assertThat(entity.getAction()).isEqualTo("USER_ROLE_CHANGE");
        assertThat(entity.getCategory()).isEqualTo("SECURITY");
        assertThat(entity.getOutcome()).isEqualTo("SUCCESS");
        assertThat(entity.getTargetId()).isEqualTo(710000000000000002L);
        assertThat(entity.getTargetType()).isEqualTo("USER");
        assertThat(entity.getTenantId()).isZero();
        assertThat(entity.getBeforeValue()).isEqualTo("USER");
        assertThat(entity.getAfterValue()).isEqualTo("SELLER");
        assertThat(entity.getIp()).isEqualTo("203.0.113.9");
        assertThat(entity.getRequestId()).isEqualTo("req-1");
        assertThat(entity.getDetail()).isEqualTo("USER -> SELLER");
        assertThat(entity.getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-10-09T12:00:00"));
    }

    @Test
    @DisplayName("★ 读不懂的类目 / 结果 / 动作一律折叠到保守档，而不是原样落库")
    void 读不懂的值折叠到保守档() {
        when(idGenerator.nextId()).thenReturn(GENERATED_ID);
        AuditLogRecord weird = new AuditLogRecord(
                ACTOR_ID, "13684295254", "SOME_UNKNOWN_ACTION", "not-a-category", "not-an-outcome",
                1L, "X", 0L, null, null, null, null, "x", "2026-10-09T12:00:00");

        service.record(weird);

        AuditLogEntity entity = captured();
        // 落库的是**归一化之后**的值：这样「按类目筛选」不会因为历史脏值漏掉记录，
        // 而且「读不懂的动作」自动落到不可删改档（SECURITY），不会被将来的清理脚本删掉
        assertThat(entity.getAction()).isEqualTo("UNKNOWN");
        assertThat(entity.getCategory()).isEqualTo(AuditCategory.SECURITY.name());
        assertThat(entity.getOutcome()).isEqualTo(AuditOutcome.FAILED.name());
    }

    @Test
    @DisplayName("NOT NULL 列缺值写空串：不能因为一个空字段让整条记录 insert 失败")
    void 非空列缺值写空串() {
        when(idGenerator.nextId()).thenReturn(GENERATED_ID);
        AuditLogRecord sparse = new AuditLogRecord(
                ACTOR_ID, null, "USER_ROLE_CHANGE", "SECURITY", "SUCCESS",
                0L, null, 0L, null, null, null, null, null, "2026-10-09T12:00:00");

        assertThat(service.record(sparse)).isTrue();

        AuditLogEntity entity = captured();
        assertThat(entity.getActorPhone()).as("actor_phone 是 NOT NULL").isEqualTo("");
        assertThat(entity.getTargetType()).as("target_type 是 NOT NULL").isEqualTo("");
        assertThat(entity.getDetail()).as("detail 是 NOT NULL").isEqualTo("");
        // 可空列保持 null — 不能顺手把它们也写成空串，「本来就没有」与「空值」是两件事
        assertThat(entity.getBeforeValue()).isNull();
        assertThat(entity.getIp()).isNull();
    }

    @Test
    @DisplayName("★ 时间戳不可解析时用接收时刻兜底，绝不因为一个字段格式丢掉整条记录")
    void 时间戳不可解析时兜底() {
        when(idGenerator.nextId()).thenReturn(GENERATED_ID);
        AuditLogRecord badTime = new AuditLogRecord(
                ACTOR_ID, "13684295254", "USER_ROLE_CHANGE", "SECURITY", "SUCCESS",
                1L, "USER", 0L, null, null, null, null, "x", "2026/10/09 12:00");

        assertThat(service.record(badTime)).as("格式不对也要写进去").isTrue();

        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        assertThat(Math.abs(Duration.between(captured().getCreatedAt(), nowUtc).toMinutes()))
                .as("兜底时间应接近当前 UTC 时刻")
                .isLessThan(2);
    }

    @Test
    @DisplayName("超长字段先截断再落库：不让数据库因为列宽把一条审计 reject 掉")
    void 超长字段先截断() {
        when(idGenerator.nextId()).thenReturn(GENERATED_ID);
        AuditLogRecord longDetail = new AuditLogRecord(
                ACTOR_ID, "13684295254", "USER_ROLE_CHANGE", "SECURITY", "SUCCESS",
                1L, "USER", 0L, null, null, null, null, "汉".repeat(900), "2026-10-09T12:00:00");

        service.record(longDetail);

        assertThat(captured().getDetail()).hasSize(AuditLogRecord.MAX_TEXT);
        assertThat(captured().getDetail()).endsWith("…");
    }

    @Test
    @DisplayName("★ 写入失败不许外抛，返回 false（审计是旁路，不能让业务 500）")
    void 写入失败不外抛() {
        when(idGenerator.nextId()).thenReturn(GENERATED_ID);
        when(auditLogMapper.insert(any(AuditLogEntity.class)))
                .thenThrow(new IllegalStateException("审计库不可用"));

        // 这里刻意用 doesNotThrowAnyException：若实现改成「抛出去让上层处理」，
        // 审计失败就会变成业务失败 —— 那正是本文件开头说的方向错误
        assertThat(service.record(record())).isFalse();
    }

    @Test
    @DisplayName("record(null) 返回 false，不做任何库操作")
    void 空记录直接返回失败() {
        assertThat(service.record(null)).isFalse();
        verify(auditLogMapper, never()).insert(any(AuditLogEntity.class));
    }

    // ---------------- 检索 ----------------

    private void stubPage(List<AuditLogEntity> rows, long total, int page, int size) {
        Page<AuditLogEntity> result = new Page<>(page, size);
        result.setRecords(rows);
        result.setTotal(total);
        when(auditLogMapper.selectPage(any(), any())).thenReturn(result);
    }

    /**
     * 捕获下发给数据库的查询条件（只看返回值看不出「过滤条件有没有真的加上」）。
     *
     * <p>返回类型是 {@code AbstractWrapper} 而不是 {@code Wrapper}：
     * 取 SQL 片段与实际参数值的方法只声明在抽象实现上，接口上没有。
     */
    @SuppressWarnings("unchecked")
    private com.baomidou.mybatisplus.core.conditions.AbstractWrapper<AuditLogEntity, ?, ?> capturedWrapper() {
        ArgumentCaptor<Wrapper<AuditLogEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(auditLogMapper).selectPage(any(), captor.capture());
        return (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<AuditLogEntity, ?, ?>) captor.getValue();
    }

    /** WHERE 片段。 */
    private String capturedSql() {
        return String.valueOf(capturedWrapper().getSqlSegment());
    }

    /**
     * 实际下发的参数值。
     *
     * <p><b>★ 必须先调一次 {@code getSqlSegment()} 把参数渲染出来</b>：
     * MyBatis-Plus 的参数是**懒格式化**的（{@code formatParam} 包在 {@code ISqlSegment}
     * 里，等到拼 SQL 那一刻才执行），不渲染就读的话 {@code getParamNameValuePairs()}
     * 会返回空集合 —— 断言会以「列表为空」的形式失败，看起来像「条件没加上」，
     * 而实际只是取值时机不对。
     */
    private java.util.Collection<Object> capturedParams() {
        var wrapper = capturedWrapper();
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    @Test
    @DisplayName("★ 起点晚于终点：显式报错，不许静默返回空")
    void 起止颠倒要报错() {
        AuditQuery query = new AuditQuery(null, null, null, null, null, null,
                "2026-10-10", "2026-10-01");

        // 返回空列表会让人以为「这段时间真的没发生什么」，而实际只是两个参数填反了 ——
        // 那是最容易让人带着错误结论离开的一种失败方式
        assertThatThrownBy(() -> service.search(query, 1, 20))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("起点晚于终点");
        verify(auditLogMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("时间参数格式不对：报校验错，不静默忽略该条件")
    void 时间格式错误要报错() {
        AuditQuery query = new AuditQuery(null, null, null, null, null, null, "2026/10/01", null);

        assertThatThrownBy(() -> service.search(query, 1, 20))
                .isInstanceOf(BizException.class);
        verify(auditLogMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("★ 只给到「日」的止端按**含**处理：运营选「到今天」必须能看到今天的记录")
    void 止端按含处理() {
        stubPage(List.of(), 0L, 1, 20);

        service.search(new AuditQuery(null, null, null, null, null, null, null, "2026-10-09"), 1, 20);

        assertThat(capturedSql()).contains("<=");
        // 写死 `< to` 会静默漏掉今天一整天，且没有任何提示 —— 所以断言补到当日的最后一刻
        assertThat(capturedParams())
                .anySatisfy(v -> assertThat(String.valueOf(v)).contains("2026-10-09T23:59:59.999999999"));
    }

    @Test
    @DisplayName("只给到「日」的起端按 00:00:00 处理")
    void 起端从零点开始() {
        stubPage(List.of(), 0L, 1, 20);

        service.search(new AuditQuery(null, null, null, null, null, null, "2026-10-01", null), 1, 20);

        assertThat(capturedParams())
                .anySatisfy(v -> assertThat(String.valueOf(v)).contains("2026-10-01T00:00"));
    }

    @Test
    @DisplayName("分页参数越界被夹紧：page 最小 1、pageSize 最大 100")
    void 分页参数夹紧() {
        stubPage(List.of(), 0L, 1, 100);

        PageResult<AuditLogView> result = service.search(null, 0, 500);

        assertThat(result.page()).as("page 0 应被夹到 1").isEqualTo(1);
        assertThat(result.pageSize()).as("pageSize 500 应被夹到上限 100").isEqualTo(100);
    }

    @Test
    @DisplayName("查询为 null 时不加过滤条件，也不报错")
    void 空查询条件不报错() {
        stubPage(List.of(), 0L, 1, 20);

        assertThat(service.search(null, 1, 20)).isNotNull();
        assertThat(capturedSql()).doesNotContain("actor_id =");
    }

    @Test
    @DisplayName("★ 只给时间范围（打开审计页的默认查询）不许 NPE")
    void 只给时间范围不许NPE() {
        stubPage(List.of(), 0L, 1, 20);

        // ★ 这条是回归守卫：`.eq(hasText(x), 列, x.trim())` 这种写法编译能过、逻辑看着对，
        //   但 Java 会先求值所有实参再调用 —— 条件为 false 时 x.trim() 照样执行。
        //   结果是「不带动作过滤」这句最常用的查询直接 NPE 500。
        //   只有把归一化值预先算成局部变量才能避免；本用例把它钉住。
        assertThat(service.search(
                new AuditQuery(null, null, null, null, null, null, "2026-10-01", "2026-10-09"), 1, 20))
                .isNotNull();

        // 单给其中一个文本条件时另外两个仍为空，同样不能炸
        assertThat(service.search(
                new AuditQuery(null, null, "FUNDS", null, null, null, null, null), 1, 20))
                .isNotNull();
    }

    @Test
    @DisplayName("条件类文本字段统一转大写再比对（库内落的是枚举名）")
    void 文本条件大小写归一() {
        stubPage(List.of(), 0L, 1, 20);

        service.search(new AuditQuery(null, null, "security", "user_role_change", "failed", null, null, null),
                1, 20);

        assertThat(capturedParams())
                .anySatisfy(v -> assertThat(String.valueOf(v)).isEqualTo("SECURITY"))
                .anySatisfy(v -> assertThat(String.valueOf(v)).isEqualTo("USER_ROLE_CHANGE"))
                .anySatisfy(v -> assertThat(String.valueOf(v)).isEqualTo("FAILED"));
    }

    @Test
    @DisplayName("下发视图：id / targetId / tenantId 一律字符串，immutable 由服务端算出")
    void 下发视图字段口径() {
        AuditLogEntity row = new AuditLogEntity();
        row.setId(810000000000000123L);
        row.setActorId(ACTOR_ID);
        row.setActorPhone("13684295254");
        row.setAction("AFTERSALE_ARBITRATE");
        row.setCategory("FUNDS");
        row.setOutcome("SUCCESS");
        row.setTargetId(710000000000000777L);
        row.setTargetType("AFTERSALE");
        row.setTenantId(710000000000000001L);
        row.setDetail("裁定退款");
        row.setCreatedAt(LocalDateTime.parse("2026-10-09T12:00:00"));
        stubPage(List.of(row), 1L, 1, 20);

        AuditLogView view = service.search(null, 1, 20).items().get(0);

        // 雪花 id 超出 JS 安全整数范围（见 T-39）：必须按字符串下发，否则前端会丢精度
        assertThat(view.id()).isEqualTo("810000000000000123");
        assertThat(view.targetId()).isEqualTo("710000000000000777");
        assertThat(view.tenantId()).isEqualTo("710000000000000001");
        // ★ immutable 由服务端按唯一判据算出：前端不自行判断，否则要再抄一份规则，两份迟早分叉
        assertThat(view.immutable()).as("资金类不可删改").isTrue();
        assertThat(view.category()).isEqualTo("FUNDS");
        assertThat(view.actorPhone()).as("审计页不脱敏：脱敏后就查不出是哪个账号做的").isEqualTo("13684295254");
        // LocalDateTime.toString() 会省略值为 0 的秒 —— 断言写全反倒会红，
        // 因此这里按真实下发形态断言（前端只做展示，不解析这串）
        assertThat(view.createdAt()).isEqualTo("2026-10-09T12:00");
    }

    @Test
    @DisplayName("下发视图：内容类不可删改判据为 false（两个方向都断言）")
    void 下发视图不可删改判据双向() {
        AuditLogEntity row = new AuditLogEntity();
        row.setId(1L);
        row.setAction("SECKILL_CREATE");
        row.setCategory("CONTENT");
        row.setOutcome("SUCCESS");
        stubPage(List.of(row), 1L, 1, 20);

        assertThat(service.search(null, 1, 20).items().get(0).immutable()).isFalse();
    }

    @Test
    @DisplayName("下发视图：读不懂的类目按最严档下发（前端会据此禁掉删除入口）")
    void 下发视图类目折叠() {
        AuditLogEntity row = new AuditLogEntity();
        row.setId(1L);
        row.setAction("SOME_LEGACY_ACTION");
        row.setCategory("WEIRD_VALUE");
        row.setOutcome("WEIRD_VALUE");
        stubPage(List.of(row), 1L, 1, 20);

        AuditLogView view = service.search(null, 1, 20).items().get(0);

        assertThat(view.category()).isEqualTo("SECURITY");
        assertThat(view.immutable()).isTrue();
        assertThat(view.outcome()).isEqualTo("FAILED");
    }
}
