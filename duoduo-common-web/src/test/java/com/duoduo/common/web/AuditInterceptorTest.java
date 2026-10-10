package com.duoduo.common.web;

import com.duoduo.common.api.RequestIdHolder;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditCategory;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditOutcome;
import com.duoduo.common.audit.AuditSink;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 后台写操作留痕拦截器（E18）。
 *
 * <h2>本文件最重要的一条：失败判定必须同时看响应状态码</h2>
 * 业务异常（422 / 403 / 404）会被 {@code GlobalExceptionHandler} 解析成**正常响应**，
 * 此时 Spring 传给 {@code afterCompletion} 的 {@code ex} 是 {@code null} ——
 * 异常已经被处理掉了。若实现只判断 {@code ex != null}，
 * <b>所有「被业务规则拒绝」的动作都会被记成 SUCCESS</b>：
 * 一条写着成功其实被拒的记录，比没有记录更糟，因为它会让人相信一次越权成功了。
 *
 * <p>因此 {@code 状态码为 4xx 且 ex 为 null} 这个组合是**必须单独覆盖**的用例：
 * 它是「写完代码看着没问题、跑起来全记成成功」的唯一分界线。
 *
 * <h2>其次是「上下文不泄漏」</h2>
 * 拦截器的 {@code afterCompletion} 跑在业务线程上，线程会被复用。
 * 不清理 ThreadLocal 就会把上一条记录的 target / 前后值带到下一个请求上，
 * 写出张冠李戴的审计行。所以这里对「完成后上下文为空」逐个分支断言
 * （成功 / 失败 / 抑制 / sink 抛异常 / 无 sink）。
 */
class AuditInterceptorTest {

    private static final long ACTOR_ID = 810000000000000001L;

    // ---------------- 测试用控制器：注解形态与真实端点一致 ----------------

    @SuppressWarnings("unused")
    static class SampleController {

        @Audited(action = AuditAction.USER_ROLE_CHANGE)
        public void changeRole() {
        }

        @Audited(action = AuditAction.MERCHANT_REJECT)
        public void rejectMerchant() {
        }

        /** 资金类：失败也**不**留痕（例如重放死信这种「失败无副作用」的动作）。 */
        @Audited(action = AuditAction.SETTLEMENT_DEBT_SETTLE, recordOnFailure = false)
        public void settleDebt() {
        }

        /** 没有任何注解：不该留痕（用户侧正常业务操作走各自的表）。 */
        public void listOrders() {
        }
    }

    private static final class CapturingSink implements AuditSink {
        final List<AuditLogRecord> records = new ArrayList<>();
        RuntimeException boom;

        @Override
        public void write(AuditLogRecord record) {
            if (boom != null) {
                throw boom;
            }
            records.add(record);
        }
    }

    private final CapturingSink sink = new CapturingSink();
    private final AuditInterceptor interceptor = new AuditInterceptor(provider(sink));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AuditSink> provider(AuditSink sink) {
        ObjectProvider<AuditSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sink);
        return provider;
    }

    @BeforeEach
    void loginAs() {
        UserContext.set(new LoginUser(ACTOR_ID, "13684295254", "ADMIN", true));
        RequestIdHolder.set("req-e18-test");
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        RequestIdHolder.clear();
        AuditTrail.clear();
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method method = SampleController.class.getMethod(methodName);
        return new HandlerMethod(new SampleController(), method);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users/9/role");
        request.addHeader("X-Real-IP", "203.0.113.9");
        return request;
    }

    /** 跑一次完整的 preHandle → afterCompletion 往返（真实生命周期）。 */
    private AuditLogRecord run(String methodName, MockHttpServletResponse response, RuntimeException ex)
            throws NoSuchMethodException {
        MockHttpServletRequest request = request();
        Object handler = handler(methodName);
        // ★ 取「本次新增的那条」而不是 records.get(0)：同一个用例内可能连跑多次
        //   （例如核对 400 / 399 / 200 三个状态码），取第 0 条会拿到上一次的结果，
        //   断言看起来在测新值、实际在测旧值 —— 这类测试自身的缺陷很容易被当成实现缺陷。
        int before = sink.records.size();
        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(request, response, handler, ex);
        return sink.records.size() > before ? sink.records.get(sink.records.size() - 1) : null;
    }

    private AuditLogRecord run(String methodName, int status) throws NoSuchMethodException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return run(methodName, response, null);
    }

    // ---------------- 用例 ----------------

    @Test
    @DisplayName("成功时写一条 SUCCESS，人 / 动作 / 类目 / IP / 链路 / 摘要齐全")
    void 成功时写一条记录() throws Exception {
        AuditLogRecord record = run("changeRole", 200);

        assertThat(record).isNotNull();
        assertThat(record.actorId()).isEqualTo(ACTOR_ID);
        assertThat(record.actorPhone()).isEqualTo("13684295254");
        assertThat(record.action()).isEqualTo("USER_ROLE_CHANGE");
        assertThat(record.category()).isEqualTo(AuditCategory.SECURITY.name());
        assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCESS.name());
        assertThat(record.ip()).as("取网关透传的 X-Real-IP").isEqualTo("203.0.113.9");
        assertThat(record.requestId()).isEqualTo("req-e18-test");
        // 业务没写摘要时退回「方法 + 路径」——detail 在库里 NOT NULL，永远不能给 null
        assertThat(record.detail()).isEqualTo("POST /api/admin/users/9/role");
    }

    @Test
    @DisplayName("★ 4xx 且 ex 为 null（业务异常已被全局处理器消费）必须记成 FAILED")
    void 状态码4xx且无异常时记失败() throws Exception {
        // ★ 这条是本文件存在的理由。若实现只判断 ex != null，本用例会红，
        //   而现实中它会表现为「所有被业务规则拒绝的动作都写着成功」。
        AuditLogRecord record = run("rejectMerchant", 422);

        assertThat(record).as("被拒绝的动作也必须留痕（审计的一半价值在「谁试过但没成功」）").isNotNull();
        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED.name());
        assertThat(record.detail()).as("摘要要说清这次没生效，别让人误读成成功")
                .contains("未生效")
                .contains("422");
    }

    @Test
    @DisplayName("3xx 不算失败：只有 >=400 才记 FAILED 边界")
    void 状态码边界() throws Exception {
        // 边界值单独钉：把 >= 写成 > 会让 400 被记成成功，而 400 正是参数校验失败最常见的码
        assertThat(run("changeRole", 400).outcome()).isEqualTo(AuditOutcome.FAILED.name());
        assertThat(run("changeRole", 399).outcome()).isEqualTo(AuditOutcome.SUCCESS.name());
        assertThat(run("changeRole", 200).outcome()).isEqualTo(AuditOutcome.SUCCESS.name());
    }

    @Test
    @DisplayName("抛异常时记 FAILED，并且异常本身仍向外传播")
    void 异常时记失败() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuditLogRecord record = run("changeRole", response, new IllegalStateException("业务炸了"));

        assertThat(record).isNotNull();
        assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED.name());
    }

    @Test
    @DisplayName("recordOnFailure = false 的端点失败时不留痕（成功时照样留）")
    void 可关闭失败留痕() throws Exception {
        assertThat(run("settleDebt", 500)).as("已声明失败不留痕").isNull();
        assertThat(run("settleDebt", 200)).as("成功时仍然要留痕").isNotNull();
    }

    @Test
    @DisplayName("业务调用 skip 时不留痕（幂等确认），且上下文照样被清理")
    void 抑制时不留痕() throws Exception {
        MockHttpServletRequest request = request();
        HandlerMethod handler = handler("changeRole");

        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        // 更贴近真实：preHandle 建上下文后，业务侧声明「无事实变化」
        AuditTrail.skip("目标已是该角色，无实际变更");
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        assertThat(sink.records).as("幂等确认不构成事件，不该写进审计表").isEmpty();
        assertThat(AuditTrail.current()).as("★ 抑制分支也必须清理上下文").isNull();
    }

    @Test
    @DisplayName("业务补充的 target / tenant / 前后值会进入记录")
    void 业务补充信息进入记录() throws Exception {
        MockHttpServletRequest request = request();
        HandlerMethod handler = handler("changeRole");

        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        AuditTrail.target(555L, "USER");
        AuditTrail.tenant(0L);
        AuditTrail.change("USER", "SELLER");
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        AuditLogRecord record = sink.records.get(0);
        assertThat(record.targetId()).isEqualTo(555L);
        assertThat(record.targetType()).isEqualTo("USER");
        assertThat(record.tenantId()).as("平台级动作（角色不属于任何店铺）应为 0").isZero();
        assertThat(record.beforeValue()).isEqualTo("USER");
        assertThat(record.afterValue()).isEqualTo("SELLER");
        assertThat(record.detail()).isEqualTo("USER -> SELLER");
    }

    @Test
    @DisplayName("无注解的端点完全不留痕（用户侧正常业务操作不进这张表）")
    void 无注解端点不留痕() throws Exception {
        MockHttpServletRequest request = request();
        HandlerMethod handler = handler("listOrders");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        assertThat(proceed).as("拦截器永远放行，不做准入判定").isTrue();
        interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);

        assertThat(sink.records).isEmpty();
    }

    @Test
    @DisplayName("★ 每个分支跑完上下文都为空（线程复用不能把记录带到下个请求）")
    void 上下文不泄漏() throws Exception {
        MockHttpServletResponse ok = new MockHttpServletResponse();
        run("changeRole", ok, null);
        assertThat(AuditTrail.current()).as("成功分支").isNull();

        MockHttpServletResponse failed = new MockHttpServletResponse();
        failed.setStatus(422);
        run("changeRole", failed, null);
        assertThat(AuditTrail.current()).as("失败分支").isNull();

        MockHttpServletResponse boomStatus = new MockHttpServletResponse();
        run("settleDebt", boomStatus, new IllegalStateException("x"));
        assertThat(AuditTrail.current()).as("被 recordOnFailure 拦下的分支").isNull();

        MockHttpServletResponse ignored = new MockHttpServletResponse();
        interceptor.afterCompletion(request(), ignored, handler("listOrders"), null);
        assertThat(AuditTrail.current()).as("无注解分支").isNull();
    }

    @Test
    @DisplayName("sink 抛异常被吞掉：审计不可用不能让业务不可用")
    void sink异常被吞掉() throws Exception {
        sink.boom = new IllegalStateException("审计库挂了");

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatCode(() -> run("changeRole", response, null)).doesNotThrowAnyException();

        assertThat(AuditTrail.current()).as("即使 sink 炸了，上下文也必须被清理").isNull();
    }

    @Test
    @DisplayName("没有 sink 实现时不 NPE，也不写（该服务不参与审计汇总）")
    void 无sink实现时不崩溃() throws Exception {
        AuditInterceptor noSink = new AuditInterceptor(provider(null));

        MockHttpServletRequest request = request();
        HandlerMethod handler = handler("changeRole");
        noSink.preHandle(request, new MockHttpServletResponse(), handler);

        assertThatCode(() -> noSink.afterCompletion(request, new MockHttpServletResponse(), handler, null))
                .doesNotThrowAnyException();
        assertThat(AuditTrail.current()).isNull();
    }

    @Test
    @DisplayName("非 HandlerMethod 的 handler（静态资源等）不参与留痕")
    void 非HandlerMethod不留痕() {
        MockHttpServletRequest request = request();
        Object handler = new Object();

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), handler)).isTrue();
        assertThatCode(() -> interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null))
                .doesNotThrowAnyException();
        assertThat(sink.records).isEmpty();
    }

    @Test
    @DisplayName("afterCompletion 在没有活动上下文时直接返回，不做多余操作")
    void 无上下文时不做事() throws Exception {
        // preHandle 没跑过（模拟拦截器被跳过的情况）→ 不该建上下文、不该写记录
        HandlerMethod handler = handler("changeRole");
        interceptor.afterCompletion(request(), new MockHttpServletResponse(), handler, null);

        assertThat(sink.records).isEmpty();
        assertThat(AuditTrail.current()).isNull();
    }

    @Test
    @DisplayName("createdAt 是可解析的 UTC ISO-8601 字符串（跨服务不产生 8 小时偏差）")
    void 时间是UTC字符串() throws Exception {
        AuditLogRecord record = run("changeRole", 200);

        assertThat(record.createdAt()).as("用 ISO_LOCAL_DATE_TIME 形态").doesNotContain("Z");
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(record.createdAt());
        } catch (DateTimeParseException ex) {
            throw new AssertionError("createdAt 必须是可解析的 ISO 本地时间字符串：" + record.createdAt(), ex);
        }
        // 与当前 UTC 时刻相差不超过 2 分钟。若实现误用了系统默认时区（本项目约定 UTC），
        // 在东八区环境上会差 8 小时 —— 这条断言就是为了在本地就能发现它。
        LocalDateTime nowUtc = LocalDateTime.now(java.time.ZoneOffset.UTC);
        assertThat(Math.abs(java.time.Duration.between(parsed, nowUtc).toMinutes()))
                .as("时间戳必须接近当前 UTC 时刻（差 8 小时说明用了本地时区）")
                .isLessThan(2);
    }

    @Test
    @DisplayName("操作人缺失时 actorPhone 写空串而不是 null（列是 NOT NULL）")
    void 操作人缺失写空串() throws Exception {
        UserContext.clear();

        AuditLogRecord record = run("changeRole", 200);

        assertThat(record.actorId()).isNull();
        assertThat(record.actorPhone()).as("列 NOT NULL：写 null 会让整条 insert 失败").isEqualTo("");
    }
}
