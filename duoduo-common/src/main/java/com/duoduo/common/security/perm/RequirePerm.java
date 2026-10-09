package com.duoduo.common.security.perm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式判权（E16）：标注在 Controller 方法或类上，由
 * {@code PermissionInterceptor} 在进入方法前校验。
 *
 * <p><b>为什么要有它</b>：命令式写法（方法体第一行 {@code OperatorGuard.requireOperator()}）
 * 有三个固有缺陷，都在真实事故里出现过或差点出现：
 * <ol>
 *   <li><b>漏写不会被发现</b>：新增一个运营端点忘了写那一行 → 接口静默变成「任何人都能调」。
 *       注解写在签名上，同一个方法的权限一眼可见，评审时也不需要翻方法体。</li>
 *   <li><b>写法有 N 种</b>：{@code requireOperator()} / 手拼 {@code "OPERATOR".equals(role)} /
 *       {@code isOperator() && ...}，口径分叉（E16 调研实测 6 模块 20+ 处不尽相同）。</li>
 *   <li><b>注解是「点」而不是「角色」</b>：{@code @RequirePerm(Permission.PRODUCT_AUDIT)}
 *       表达的是「这件事需要什么」，而不是「谁可以做」—— 后者会随组织变化，
 *       前者不会。判权点写角色名，就是把组织架构烧进了代码。</li>
 * </ol>
 *
 * <p><b>校验顺序</b>：方法级注解优先于类级（覆盖语义）。方法上没写、类上写了 → 用类上的；
 * 都没写 → 不校验（由业务自行 {@code requireXxx()}）。**不提供**「类级默认 + 方法级豁免」
 * 这类语法糖 —— 豁免语义最容易变成绕过漏洞。
 *
 * <p><b>★ 落地纪律（血泪）</b>：注解式判权的失效方向是「静默放开」——
 * 拦截器没注册、包路径没扫到、方法被代理绕过，表现都是「接口 200，谁都能调」，
 * 而没有任何报错。因此**每引入一处注解，必须配一条「无权限者访问 → 403」的实测断言**
 * （见 {@code audit-sla-smoke.ps1} 的越权段与 E19 越权矩阵）。
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePerm {

    /**
     * 所需权限点，取值来自 {@link Permission} 的常量。
     *
     * <p>刻意用 {@code String} 而不是枚举：权限点是跨模块共享的词汇，
     * 用常量便于各域直接引用而不必让 duoduo-common 依赖业务域。
     * 代价是编译期不校验拼写 —— 由 {@code RolePermissionMatrixTest} 的
     * 「所有注解用的权限点都必须在清单内」断言兜住。
     */
    String value();
}
