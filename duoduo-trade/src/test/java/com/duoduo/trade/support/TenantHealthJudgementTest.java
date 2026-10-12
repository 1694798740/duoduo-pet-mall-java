package com.duoduo.trade.support;

import com.duoduo.common.metrics.TenantApiMetrics;
import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthRow;
import com.duoduo.trade.support.TenantHealthJudgement.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E24 · 租户健康度判据的单测。
 *
 * <h2>这些断言在守什么</h2>
 * 判级是本面板唯一会被"当成结论"的东西 —— 运维看一眼 RISK 就去查账。
 * 阈值写错不会有任何报错：面板照样出数、页面照样好看，只是**结论错了**。
 * 因此这里把每条阈值的两侧都钉住（刚好达标 / 差一点不达标），
 * 并把"NO_DATA 必须与 OK 分得开"这条三态纪律单独写一条用例。
 */
class TenantHealthJudgementTest {

    // ---------------- 三态：无数据 ≠ 健康 ----------------

    @Test
    void 没有样本判无数据_不能判成健康() {
        // "这窗口一单没成交、也没调过接口"与"成交 100 单都没问题"数值上都等于 0，
        // 但结论相反：前者没有可判断的数据。写成同一个 OK 会让新店看起来和健康店一样。
        assertThat(TenantHealthJudgement.level(0, 0, 0, 0, 0)).isEqualTo(Level.NO_DATA);
        // 有成交 → 才谈得上 OK
        assertThat(TenantHealthJudgement.level(10, 0, 0, 0, 0)).isEqualTo(Level.OK);
        // 没成交但有接口调用 → 也是"有动静"，判 OK 而不是 NO_DATA
        assertThat(TenantHealthJudgement.level(0, 0, 0, 0, 30)).isEqualTo(Level.OK);
    }

    // ---------------- 资金硬异常 ----------------

    @Test
    void 结算异常一笔也算风险() {
        // 未收回欠款 / 超期托管 / 缺托管单：都是资金事实已错位，没有"轻微"一说
        assertThat(TenantHealthJudgement.level(100, 1, 0, 0, 500)).isEqualTo(Level.RISK);
        // 而且它压过"售后率很低"这类倾向性指标
        assertThat(TenantHealthJudgement.level(100, 1, 0, 0, 500)).isEqualTo(Level.RISK);
        assertThat(TenantHealthJudgement.settlementAbnormalCount(1, 2, 3)).isEqualTo(6);
    }

    // ---------------- 售后率 ----------------

    @Test
    void 售后率达到两成且样本够才判关注() {
        // 20/100 = 20.0%，刚好达标（>= 而非 >，边界要能被断言）
        assertThat(TenantHealthJudgement.afterSaleRate(20, 100)).isEqualTo(20.0);
        assertThat(TenantHealthJudgement.level(100, 0, 20, 0, 0)).isEqualTo(Level.WATCH);
        // 19/100 = 19.0%，差一点
        assertThat(TenantHealthJudgement.level(100, 0, 19, 0, 0)).isEqualTo(Level.OK);
    }

    @Test
    void 小样本不报警_一单成交一单售后也是全量但那是噪音() {
        // 样本下限 5：成交 4 单、售后 4 单 = 100%，仍然不判 WATCH
        assertThat(TenantHealthJudgement.afterSaleRate(4, 4)).isEqualTo(100.0);
        assertThat(TenantHealthJudgement.level(4, 0, 4, 0, 0)).isEqualTo(Level.OK);
        // 成交 5 单、售后 5 单 → 到样本下限，判 WATCH
        assertThat(TenantHealthJudgement.level(5, 0, 5, 0, 0)).isEqualTo(Level.WATCH);
    }

    // ---------------- 接口错误率（只看 5xx） ----------------

    @Test
    void 接口错误率只看5xx_并且要有样本下限() {
        // 1% 刚好达标（100 次里 1 次 5xx）
        assertThat(TenantHealthJudgement.apiErrorRate(1, 100)).isEqualTo(1.0);
        assertThat(TenantHealthJudgement.level(0, 0, 0, 1, 100)).isEqualTo(Level.RISK);
        // 样本不足（调用数 < 10）时，即便 100% 全是 5xx 也不给档位 ——
        // 刚接入的店头几次调用全失败是常态，不该一上来就报风险
        assertThat(TenantHealthJudgement.apiErrorRate(5, 5)).isEqualTo(100.0);
        assertThat(TenantHealthJudgement.level(0, 0, 0, 5, 5)).isEqualTo(Level.OK);
        // 调用数刚好到下限（10 次里 10 次 5xx）→ 判风险
        assertThat(TenantHealthJudgement.level(0, 0, 0, 10, 10)).isEqualTo(Level.RISK);
    }

    @Test
    void 接口错误率在关注线与风险线之间判关注() {
        // 0.2% ~ 1% 之间：有苗头，先观察
        assertThat(TenantHealthJudgement.level(0, 0, 0, 1, 200)).isEqualTo(Level.WATCH); // 0.5%
        // 0.15% 未达关注线
        assertThat(TenantHealthJudgement.level(0, 0, 0, 3, 2000)).isEqualTo(Level.OK);
    }

    @Test
    void 客户端错误不参与判级_但比例仍然算得出来() {
        // 面板给出 client_error 计数供排查商家端集成，但判级只看 server_error。
        // 这里断言的是"通道分开"这件事本身：入参里根本没有 clientError 这一项。
        // 若将来有人把 4xx 混进 level() 的签名，这条用例会连同源码一起被改掉 ——
        // 那正是我们想要的"改动必须显式"。
        assertThat(TenantHealthJudgement.level(100, 0, 0, 0, 100)).isEqualTo(Level.OK);
    }

    // ---------------- 比例：无样本返回 null，不给 0% ----------------

    @Test
    void 分母为零不给0百分比_给null() {
        // 0.00% 与"—"在"这家店有没有问题"这个问题上结论相反
        assertThat(TenantHealthJudgement.percent(0, 0)).isNull();
        assertThat(TenantHealthJudgement.percent(5, 0)).isNull();
        assertThat(TenantHealthJudgement.percent(0, 10)).isZero();
        assertThat(TenantHealthJudgement.percentText(null)).isNull();
        assertThat(TenantHealthJudgement.percentText(12.345)).isEqualTo("12.35%");
        // 格式与 AdminOpsService#percent 一致：两个后台页面上的比例必须长得一样
        assertThat(TenantHealthJudgement.percentText(TenantHealthJudgement.percent(1, 3)))
                .isEqualTo("33.33%");
    }

    // ---------------- 声明清单守卫 ----------------

    @Test
    void 档位清单与排序权重必须齐全_新增档位忘了配文案会红() {
        // 每个档位都要有中文标签（前端直接渲染 label，不再抄一份映射）
        for (Level level : Level.values()) {
            assertThat(level.label()).isNotBlank();
            assertThat(level.rank()).isGreaterThan(0);
        }
        // 声明清单必须覆盖全部枚举值：漏一个等于"这个档位永远不会被列表展示"
        assertThat(Level.ALL).containsExactlyInAnyOrder(Level.values());
        // 顺序即排序权重：RISK 必须排在 WATCH 之前，WATCH 必须排在 OK 之前
        assertThat(Level.RISK.rank()).isGreaterThan(Level.WATCH.rank());
        assertThat(Level.WATCH.rank()).isGreaterThan(Level.NO_DATA.rank());
        assertThat(Level.NO_DATA.rank()).isGreaterThan(Level.OK.rank());
        // 字符串解析大小写容错；未知返回 null（调用方决定处置）
        assertThat(Level.of("risk")).isEqualTo(Level.RISK);
        assertThat(Level.of(" WATCH ")).isEqualTo(Level.WATCH);
        assertThat(Level.of("UNKNOWN")).isNull();
        assertThat(Level.of(null)).isNull();
    }

    // ---------------- 跨模块约束：窗口上限 vs 键存活期 ----------------

    @Test
    void 窗口上限不得超过接口指标的键存活期() {
        // ★ 这条约束编译器管不到（两个常量在不同模块），只能靠测试守。
        // 一旦 MAX_DAYS > KEEP_DAYS，超期那几天的键已经不存在，
        // HGETALL 读到空 Map 与"那天该租户一次都没调用"完全一样 →
        // 接口错误率会**安静地变小**，面板上没有任何异常。
        assertThat(TenantHealthJudgement.MAX_DAYS).isLessThanOrEqualTo(TenantApiMetrics.KEEP_DAYS);
        // 阈值本身也要在合理量级：负数或 0 会让所有租户都判 WATCH/RISK
        assertThat(TenantHealthJudgement.AFTER_SALE_WATCH_PERCENT).isGreaterThan(0);
        assertThat(TenantHealthJudgement.API_ERROR_RISK_PERCENT)
                .isGreaterThan(TenantHealthJudgement.API_ERROR_WATCH_PERCENT);
        assertThat(TenantHealthJudgement.AFTER_SALE_MIN_BASE).isGreaterThan(0);
        assertThat(TenantHealthJudgement.API_MIN_BASE).isGreaterThan(0);
        assertThat(TenantHealthJudgement.STALE_CUSTODY_DAYS).isGreaterThan(0);
    }

    // ---------------- 展示排序 ----------------

    /** 只填排序用得上的三个字段，其余留 0 / null（排序不读它们）。 */
    private static TenantHealthRow row(String tenantId, String level, long orderCount) {
        return new TenantHealthRow(Long.parseLong(tenantId), null, orderCount,
                0, 0, 0, 0, 0, 0, null,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, null, level);
    }

    @Test
    void 展示排序_风险优先且同级按下单数降序再按租户号升序() {
        // ★ 这条用例守的是比较器的**方向与作用域**，不是一个数字。
        //   实测踩过：写「rank 越大越靠前」的注释，代码却是 `comparingInt`（升序），
        //   于是「正常」排最前、「风险」沉底 —— 而数据少时只表现为「乱序 1 处」，
        //   极易被当成偶发。`reversed()` 写在链尾会反转整条链（连 tie-breaker 一起翻），
        //   所以这里把三个键的顺序一起断言，写法改错时至少有两条会红。
        List<TenantHealthRow> rows = new ArrayList<>(List.of(
                row("1", "OK", 100),
                row("2", "RISK", 1),
                row("3", "NO_DATA", 50),
                row("4", "WATCH", 10),
                row("5", "RISK", 9),
                row("6", "WATCH", 30)));

        rows.sort(TenantHealthJudgement.DISPLAY_ORDER);

        assertThat(rows).extracting(TenantHealthRow::level)
                .containsExactly("RISK", "RISK", "WATCH", "WATCH", "NO_DATA", "OK");
        // 同级：下单数降序（9 在 1 前、30 在 10 前）
        assertThat(rows).extracting(TenantHealthRow::tenantId)
                .containsExactly(5L, 2L, 6L, 4L, 3L, 1L);
    }

    @Test
    void 展示排序_同档同单数时按租户号升序_截断才是稳定的() {
        // 没有第三个键，被 limit 截断的永远是随机一批，「还有 N 家未展示」里的 N 家会飘。
        List<TenantHealthRow> rows = new ArrayList<>(List.of(
                row("300", "OK", 7), row("100", "OK", 7), row("200", "OK", 7)));
        rows.sort(TenantHealthJudgement.DISPLAY_ORDER);
        assertThat(rows).extracting(TenantHealthRow::tenantId)
                .containsExactly(100L, 200L, 300L);
    }

    @Test
    void 展示排序_未知档位排在最前_不被埋在正常项里() {
        // 未知档位只出现在「后端加了档位、调用方还没同步」时。
        // 排在最后 = 新出现的（可能是更严重的）档位被当成尾部正常项忽略；
        // 排在最前 = 至少会被人看见。这是刻意的失效方向选择。
        assertThat(TenantHealthJudgement.displayRank("CRITICAL_FUTURE"))
                .isLessThan(TenantHealthJudgement.displayRank("RISK"));
        assertThat(TenantHealthJudgement.displayRank(null))
                .isLessThan(TenantHealthJudgement.displayRank("RISK"));
    }

    @Test
    void 展示排序键_四态严格单调() {
        assertThat(TenantHealthJudgement.displayRank("RISK"))
                .isLessThan(TenantHealthJudgement.displayRank("WATCH"));
        assertThat(TenantHealthJudgement.displayRank("WATCH"))
                .isLessThan(TenantHealthJudgement.displayRank("NO_DATA"));
        assertThat(TenantHealthJudgement.displayRank("NO_DATA"))
                .isLessThan(TenantHealthJudgement.displayRank("OK"));
    }
}
