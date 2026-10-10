package com.duoduo.common.web.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.duoduo.common.tenant.TenantContext;
import com.duoduo.common.tenant.TenantTables;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租户条件拦截器的装配入口（E20）。
 *
 * <h2>它做的事</h2>
 * 用 MyBatis-Plus 的 {@link TenantLineInnerInterceptor}，给白名单表上的 SQL
 * **自动追加** {@code 租户列 = 当前租户} 的条件。这样"商家端只能看见自己店铺的数据"
 * 不再依赖"每个查询都记得写 where"—— 忘写一处就少一处防线的写法，本项目已经吃过太多次亏。
 *
 * <h2>为什么由调用方显式传表名</h2>
 * 不做"自动发现"。自动发现的失效方向是**静默放开**：新加一张租户表而没人想起来登记，
 * 它会以"完全不过滤"的姿态正常工作，接口 200、无日志。
 * 显式传表名还有两个好处：① 可以在**启动时**校验（见下面的 fail-fast）；
 * ② 每张表的隔离状态都能被 grep 到。
 *
 * <h2>为什么要按列名分组</h2>
 * MP 的 {@link TenantLineHandler#getTenantIdColumn()} 只能返回**一个**列名，
 * 而本项目 10 张租户表里恰好有 1 张列名不同（{@code t_audit_log} 用 {@code tenant_id}，
 * 其余 9 张用 {@code seller_id}）。所以本方法按列名把表分组，**每组一个拦截器实例** ——
 * 不给它分组的话只有两种结局：那张表漏隔离，或者拼出 {@code Unknown column} 的 SQL。
 *
 * <h2>★ 上下文未设置时不追加条件</h2>
 * 这是刻意的，不是遗漏。见 {@link TenantContext} 的类注释：平台运营跨租户巡查、
 * 服务间内部调用、定时任务、买家请求、无 {@code tid} 的老令牌都会走到这里，
 * 而它们本来就该看见全部。**它是纵深防御的第二层，不是唯一防线**
 * （第一层是网关入口 + 角色权限 + 各服务自己的归属校验）。
 */
public final class TenantLineInterceptors {

    private TenantLineInterceptors() {
    }

    /**
     * 为一批租户表构造拦截器（同一列名的表合并成一个实例）。
     *
     * @param tables 本服务用到的租户表名；**必须**是 {@link TenantTables} 白名单里的表，
     *               拼错立即抛异常（启动即失败，而不是运行期静默不过滤）
     * @return 至少一个拦截器；调用方按 多租户 → 分页 的顺序 add 进 {@code MybatisPlusInterceptor}
     * @throws IllegalArgumentException 表名不在租户表白名单里
     */
    public static List<InnerInterceptor> forTables(String... tables) {
        if (tables == null || tables.length == 0) {
            throw new IllegalArgumentException("未指定任何租户表 —— 传空等于这个服务不过滤，请显式列出");
        }
        // 列名 → 表集合（保持声明顺序，日志与异常信息可读）
        Map<String, Set<String>> byColumn = new LinkedHashMap<>();
        for (String table : tables) {
            String column = TenantTables.columnOf(table)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "表 " + table + " 不在租户表白名单（TenantTables）里：要么表名拼错了，"
                                    + "要么它确实没有租户维度。前者会让隔离静默失效，所以在这里直接拒绝启动。"));
            byColumn.computeIfAbsent(column, k -> new LinkedHashSet<>()).add(table);
        }

        List<InnerInterceptor> interceptors = new ArrayList<>(byColumn.size());
        for (Map.Entry<String, Set<String>> entry : byColumn.entrySet()) {
            interceptors.add(new TenantLineInnerInterceptor(
                    new ScopedTenantHandler(Set.copyOf(entry.getValue()), entry.getKey())));
        }
        return interceptors;
    }

    /**
     * 只认一张表清单的租户处理器。
     *
     * <p>{@code ignoreTable} 是整套机制的**唯一闸门**，它必须同时回答两件事：
     * ① 上下文没设置 → 忽略（不追加条件）；② 这张表不在我的清单里 → 忽略。
     * 两者写成一句判断，是为了避免"先判上下文、后判表名"漏掉一半的写法。
     */
    private record ScopedTenantHandler(Set<String> tables, String column) implements TenantLineHandler {

        @Override
        public Expression getTenantId() {
            Long tenantId = TenantContext.get().orElse(null);
            if (tenantId == null) {
                // 正常路径下到不了这里（ignoreTable 已经挡掉"未设置"）。
                // 真到了这里说明 MP 的调用顺序与我们的假设不符 —— 此时**抛**比"悄悄不加条件"安全：
                // 静默不加条件 = 隔离失效且无人知晓，抛出去至少会有一条 500 和一行堆栈。
                throw new IllegalStateException(
                        "租户上下文未设置，却被要求追加租户条件 —— 说明 ignoreTable 的判定与 MP 的调用假设不一致");
            }
            return new LongValue(tenantId);
        }

        @Override
        public String getTenantIdColumn() {
            return column;
        }

        @Override
        public boolean ignoreTable(String tableName) {
            if (!TenantContext.isPresent()) {
                return true;
            }
            // 归一化与白名单判定都委托 TenantTables —— 同一把尺子只允许有一份实现，
            // 两边各写一遍的话，改动一处忘另一处会表现为"某一种写法悄悄不过滤"。
            return !TenantTables.isTenantTable(tableName)
                    || !tables.contains(TenantTables.normalize(tableName));
        }
    }
}
