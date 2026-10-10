package com.duoduo.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户上下文与租户表清单单测（E20）。
 *
 * <h2>这批断言要钉住的是"最容易写错且不报错"的三件事</h2>
 * <ol>
 *   <li><b>0 是有效租户（自营），不是"未设置"</b>：一旦两者混同，
 *       "自营请求被当成未设置 → 不追加条件"（隔离对自营失效）
 *       或"未设置被当成自营 → 追加 seller_id=0"（把所有平台数据藏起来）
 *       都会以"接口 200、数据只是不对"的形式出现；</li>
 *   <li><b>清理必须彻底</b>：容器线程复用，漏清理 = 上一个请求的租户串到下一个请求；</li>
 *   <li><b>表名归一</b>：{@code T_SPU} / {@code `t_spu`} / {@code duoduo_product.t_spu}
 *       三种写法都必须命中白名单 —— 不归一的话它们**静默不命中**，
 *       表现是"某条 SQL 悄悄没被隔离"。</li>
 * </ol>
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void 默认未设置_既不等于自营也不等于任何租户() {
        assertThat(TenantContext.isPresent()).isFalse();
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 自营零值是有效租户_不能被当成未设置() {
        TenantContext.set(0L);

        assertThat(TenantContext.isPresent()).isTrue();
        assertThat(TenantContext.get()).contains(0L);
    }

    @Test
    void 设null等价于清理_不留半状态() {
        TenantContext.set(710000000000000001L);
        TenantContext.set(null);

        assertThat(TenantContext.isPresent()).isFalse();
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 清理之后不再可见() {
        TenantContext.set(710000000000000002L);
        TenantContext.clear();

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 线程之间互不串味() throws Exception {
        TenantContext.set(710000000000000003L);

        AtomicReference<Boolean> otherThreadPresent = new AtomicReference<>();
        AtomicReference<Long> otherThreadValue = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            otherThreadPresent.set(TenantContext.isPresent());
            otherThreadValue.set(TenantContext.get().orElse(null));
        });
        worker.start();
        worker.join();

        // 若 ThreadLocal 被误写成 static 字段 / InheritableThreadLocal，这里会看到父线程的租户
        assertThat(otherThreadPresent.get()).isFalse();
        assertThat(otherThreadValue.get()).isNull();
        // 父线程自己不受影响
        assertThat(TenantContext.get()).contains(710000000000000003L);
    }

    // ---------------- TenantTables ----------------

    @Test
    void 租户表清单与库内实算一致_九个表名覆盖十张物理表() {
        // 这份清单是"隔离覆盖到哪些表"的唯一声明；数量变了必须是有意为之。
        // information_schema 实算（2026-10-10）：product 3 + trade 3 + fulfilment 2 + user 2 = 10 张物理表。
        // ★ 但表名只有 9 个：t_audit_sample 在 product（商品上架抽查）与 user（资质抽查）各一张。
        //   这不是"数错了"，而正是本类按「表名 → 列名」做 key 的前提：同名必须同列（实测两张都是 seller_id）。
        //   所以这里同时钉两个数 —— 只钉一个的话，另一个变了照样绿。
        assertThat(TenantTables.allTableNames()).hasSize(9);
        assertThat(TenantTables.allTableNames()).containsExactlyInAnyOrder(
                "t_spu", "t_listing", "t_audit_sample",
                "t_order", "t_seller_debt", "t_settlement",
                "t_after_sale", "t_review",
                "t_audit_log");
        assertThat(TenantTables.sharedTableNames()).containsExactly("t_audit_sample");
        assertThat(TenantTables.physicalTableCount()).isEqualTo(10);
    }

    @Test
    void 只有审计日志用tenant_id_其余一律seller_id() {
        assertThat(TenantTables.columnOf("t_audit_log")).contains("tenant_id");
        assertThat(TenantTables.columnOf("t_spu")).contains("seller_id");
        assertThat(TenantTables.columnOf("t_audit_sample")).contains("seller_id");

        long tenantIdColumnCount = Set.of("t_spu", "t_listing", "t_audit_sample",
                        "t_order", "t_seller_debt", "t_settlement",
                        "t_after_sale", "t_review", "t_audit_log").stream()
                .filter(t -> TenantTables.columnOf(t).orElseThrow().equals("tenant_id"))
                .count();
        assertThat(tenantIdColumnCount).isEqualTo(1L);
    }

    @Test
    void 表名归一_大小写与反引号与库前缀都要命中() {
        assertThat(TenantTables.isTenantTable("T_SPU")).isTrue();
        assertThat(TenantTables.isTenantTable("`t_spu`")).isTrue();
        assertThat(TenantTables.isTenantTable("duoduo_product.t_spu")).isTrue();
        assertThat(TenantTables.columnOf("duoduo_user.t_audit_log")).contains("tenant_id");

        // 刻意不做子串/模糊匹配：`t_spu_audit` 这种"看起来像"的名字必须不命中
        assertThat(TenantTables.isTenantTable("t_spu_audit")).isFalse();
        assertThat(TenantTables.isTenantTable("t_merchant")).isFalse();
        assertThat(TenantTables.columnOf(null)).isEmpty();
    }

    @Test
    void 门槛常量不被改动_网关与下游共用同一个头名() {
        assertThat(TenantTables.HEADER).isEqualTo("X-Tenant-Id");
    }
}
