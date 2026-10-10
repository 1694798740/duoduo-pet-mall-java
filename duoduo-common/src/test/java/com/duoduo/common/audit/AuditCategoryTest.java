package com.duoduo.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计类目判据（E18）。
 *
 * <h2>这组断言守的是什么</h2>
 * {@link AuditCategory#immutable()} 是「这条记录将来能不能被清理」的**唯一判据**
 * （总纲 E18 原文：资金与权限类不可删改）。它一旦写反，后果是单向的：
 * 把 SECURITY/FUNDS 判成可删，等于某天清理脚本会把追责材料删掉，且**不可逆**。
 * 反过来多留几年只是占点磁盘。所以这里对两个方向分别断言，而不是只测「SECURITY 是 true」。
 *
 * <h2>为什么单独测「未知值折叠到哪一侧」</h2>
 * 解析失败的走向是这类代码最容易写反、也最难在运行中发现的地方：
 * 返回 OTHER 会让一条读不懂的记录**自动获得可删除资格**。
 * 因此断言必须钉住「未知 → SECURITY」这个具体字面量，
 * 而不是「未知 → 某个不可删改的档」—— 后者在将来新增档位时会静默放宽。
 */
class AuditCategoryTest {

    @Test
    @DisplayName("不可删改档恰好是 SECURITY 与 FUNDS 两个")
    void 不可删改档恰好是两个() {
        assertThat(AuditCategory.SECURITY.immutable()).isTrue();
        assertThat(AuditCategory.FUNDS.immutable()).isTrue();

        assertThat(AuditCategory.TENANT.immutable()).isFalse();
        assertThat(AuditCategory.CONTENT.immutable()).isFalse();
        assertThat(AuditCategory.OTHER.immutable()).isFalse();
    }

    @Test
    @DisplayName("全部枚举逐个核对：只有 SECURITY/FUNDS 不可删改")
    void 全部枚举逐个核对() {
        // 遍历而不是逐个列举：将来新增档位时，本用例会逼着作者回答「它属于哪一侧」，
        // 而不是让新档位默默继承某个默认判据
        for (AuditCategory category : AuditCategory.values()) {
            boolean expected = category == AuditCategory.SECURITY || category == AuditCategory.FUNDS;
            assertThat(category.immutable())
                    .as("类目 %s 的不可删改判据", category)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("未知 / 空白一律折叠为 SECURITY（最严档），而不是 OTHER")
    void 未知值折叠为最严档() {
        // ★ 关键点：必须断开「未知 → OTHER」这条路。
        //   只要写的是 isEqualTo(SECURITY)，把它改成 OTHER 就一定会红
        assertThat(AuditCategory.parse(null)).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditCategory.parse("")).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditCategory.parse("   ")).isEqualTo(AuditCategory.SECURITY);
        assertThat(AuditCategory.parse("NO_SUCH_CATEGORY")).isEqualTo(AuditCategory.SECURITY);
        // 折叠结果必须落在「不可删改」这一侧 —— 上一条断言只钉了字面量，
        // 这一条钉住的是「折叠的**方向**是对的」
        assertThat(AuditCategory.parse("NO_SUCH_CATEGORY").immutable()).isTrue();
    }

    @Test
    @DisplayName("正常值大小写与首尾空格都要能解析")
    void 正常值宽容解析() {
        assertThat(AuditCategory.parse("FUNDS")).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditCategory.parse("funds")).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditCategory.parse("  Funds  ")).isEqualTo(AuditCategory.FUNDS);
        assertThat(AuditCategory.parse("other")).isEqualTo(AuditCategory.OTHER);
    }

    @Test
    @DisplayName("解析不抛异常：调用方来自注解反射与数据库读取两条路径")
    void 解析不抛异常() {
        // 两条调用路径都不该因为一个读不懂的值 500
        assertThat(AuditCategory.parse("\u0000乱码")).isNotNull();
    }
}
