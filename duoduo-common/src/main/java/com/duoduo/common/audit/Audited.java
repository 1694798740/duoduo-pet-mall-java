package com.duoduo.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个后台写操作需要留痕（E18）。
 *
 * <p><b>为什么用注解而不是在方法体里调一行留痕代码</b>：调用式写法有两个
 * 固有毛病，都会静默发生：
 * <ol>
 *   <li><b>新增端点时忘了写那一行</b> —— 端点正常工作、没有任何报错，
 *       只是它不在审计表里。要发现只能靠人工比对「端点清单 vs 动作清单」，
 *       而这份比对没人会定期做。</li>
 *   <li><b>写了但写错了位置</b>（比如在事务提交前写、或异常分支漏写），
 *       表现是「记录有了但状态没变」或「状态变了却没有记录」。</li>
 * </ol>
 * 注解把这些交给**一个拦截器**统一处理：加了注解就一定留痕，且留痕内容
 * （谁 / 何时 / 从哪个 IP / 哪条链路 / 成功还是失败）由框架统一采集，
 * 业务代码只需要在需要时补充「目标是谁、前后值是什么」（{@link AuditTrail}）。
 *
 * <p><b>★ 加在哪些端点上</b>：全部<b>后台写端点</b>（{@code /api/admin/**} 的
 * POST/PUT/PATCH/DELETE），以及<b>商家子账号管理</b>（{@code /api/seller/staff/**}）——
 * 后者虽是商家端，但它在 E13 起就要求留痕（平台需要能区分「店主自停」与「平台处置」），
 * 属于既有事实而不是本轮扩张。
 *
 * <p><b>加在哪些端点上不合适</b>：用户侧的正常业务操作（下单、评价、发分享、
 * 上下架自己的商品）不进这张表 —— 那是业务事件，有各自的表与查询入口。
 * 混进来会让审计页被海量正常流量淹没，真正需要追责的记录反而找不到。
 *
 * <p><b>★ 一个动作 = 一条记录</b>：若某个端点已经在服务层显式写了审计
 * （定时任务与内部调用会复用同一段业务代码），就<b>不要</b>再给它加注解 ——
 * 否则同一个动作出现两条记录，审计页上的计数就失去了意义。
 * 判断依据是「这段业务代码是否可能在没有 HTTP 请求的线程里被执行」：
 * 可能 → 服务层显式写；不可能 → 用注解。
 *
 * <p><b>与权限注解的关系</b>：{@code @RequirePerm} 回答「能不能做」，
 * {@code @Audited} 回答「做了要留痕」。两者独立 —— 一个端点可以只有其一。
 * 审计拦截器注册在权限拦截器**之前**，因此「越权被拒」也会留痕
 * （结果记为 {@link AuditOutcome#FAILED}）：连续失败的提权尝试正是最该被看见的信号。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /**
     * 动作标识。决定类目（是否不可删改）与默认目标类型，见 {@link AuditAction}。
     *
     * <p>刻意不做「不填就按方法名推断」的兜底：推断出来的动作名会随重构改名而变，
     * 而审计标识一旦改动，历史查询就会分叉成两套口径。必须显式指定。
     */
    AuditAction action();

    /**
     * 是否在动作失败时也记录。
     *
     * <p>默认 {@code true}。把它设成 {@code false} 只在一种情况下有意义：
     * 该端点的失败是**高频且无信息量**的（例如轮询式的重试被打回），
     * 记下来只会淹没真正的异常。当前所有后台端点都没这个需要，因此没有一处设为 false ——
     * 保留这个开关是为了让「想关掉的人」有一个显式的地方做决定，而不是去改拦截器。
     */
    boolean recordOnFailure() default true;
}
