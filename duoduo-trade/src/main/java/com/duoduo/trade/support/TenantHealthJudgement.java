package com.duoduo.trade.support;

import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthRow;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * E24 · 租户健康度的**判据层**（纯函数，无 Spring、无 IO）。
 *
 * <h2>为什么判据要抽出来</h2>
 * 「什么算不健康」是一条业务口径，不是一次查询。把它写成纯函数有三个直接好处：
 * <ol>
 *   <li>单测能直接钉住每个阈值 —— 否则只能靠搭一套数据去"跑出来看"，而那种测试
 *       在阈值改错时往往照样绿（因为断言也是照着错的口径写的）；</li>
 *   <li>阈值只有一处定义，前端不再抄一份（本项目已经因为"两端各判一次"栽过多次）；</li>
 *   <li>它回答了面板上最容易被质疑的那个问题：<b>"你凭什么说这家店有风险"</b>——
 *       每一档都指得出一条可读的规则。</li>
 * </ol>
 *
 * <h2>★ 四态而不是三态：为什么必须有 {@code NO_DATA}</h2>
 * 这是本类最重要的一个决定。「这个窗口里一单都没成交」与「成交了 100 单、一单售后都没有」
 * 在数值上都等于 0，但结论完全相反：前者是**没有可判断的数据**，后者是**健康**。
 * 只有把两者分开，运维才不会把"这家店还没开始做生意"当成"这家店没问题"。
 * （本项目在 S3 对账里吃过同一个亏：把"没跑成"记成"干净"，报表一片绿而事实未知。）
 *
 * <h2>★ 为什么 4xx 不参与判级</h2>
 * 面板同时给出 5xx 与 4xx 两个计数，但**档位只看 5xx**。理由：
 * <ul>
 *   <li>4xx 里绝大多数是**预期的**——令牌过期（401）、看了不属于自己的单（404）、
 *       参数写错（400）。商家端与运营端每天都在产生这种响应；</li>
 *   <li>5xx 才是**无歧义的平台/服务故障**，是"接口错误率"这个词在运维语境下的本义。</li>
 * </ul>
 * 把 4xx 混进错误率会得到一个"永远在 0.5%~5% 之间、谁也说不清正不正常的数"。
 * 4xx 仍然照常展示：它是排查商家端集成问题的重要线索（比如某店突然全是 404，
 * 说明它调了一个已经不存在的端点），只是不作为判级依据。
 *
 * <h2>阈值取值（每一个都要能说出理由）</h2>
 * <ul>
 *   <li><b>结算异常 &gt; 0 直接 RISK</b>：欠款未收回、超期仍托管、已付款却缺托管单，
 *       这三类都是**资金事实已经错位**，没有"轻微"一说。一笔也算风险。</li>
 *   <li><b>售后率 ≥ 20% 且成交 ≥ 5 单</b>：20% 是电商里"显著偏离常态"的量级
 *       （宠物类目正常售后率在个位数）；5 单是样本下限 ——
 *       成交 1 单、售后 1 单也是 100%，拿它报警等于制造噪音，会让人很快无视整个面板。</li>
 *   <li><b>接口错误率 ≥ 1% 且调用 ≥ 10 次</b>：1% 的 5xx 已经是"这家店的集成明显有问题"；
 *       10 次是同样的样本下限（比售后单数的 5 更严一点：一次调用很便宜，
 *       "刚接入的店头几次全失败"是常态，不该一上来就报风险）。
 *       0.2%~1% 之间记为 WATCH（有苗头，先观察）。</li>
 * </ul>
 *
 * <h2>★ 样本不足时"不给档位"，但计数照常展示（这不是掩盖）</h2>
 * 成交 4 单里出 4 笔售后（100%）、或调用 5 次全失败（100%），按上面的规则都**不会**被判高风险。
 * 这是刻意的：这些数字的绝对量太小，任何比例都没有统计意义，
 * 而一个"1 次失败就变红"的面板会在几天内被运维彻底无视 —— 那比漏报更糟。
 * 代价是这些租户会落到 {@code OK}，因此面板必须**同时展示原始计数**
 * （{@code afterSaleCount} / {@code apiServerError} 都是响应里的独立字段），
 * 人一眼能看到"成交 4 单售后 4 笔"，只是系统不替他下结论。
 */
public final class TenantHealthJudgement {

    private TenantHealthJudgement() {
    }

    /** 判级档位。顺序即排序权重（越靠前越需要人看）。 */
    public enum Level {
        /** 有资金事实错位，或接口 5xx 率超线 —— 需要人处理 */
        RISK("风险"),
        /** 有偏高的迹象（售后率 / 少量 5xx），先观察 */
        WATCH("关注"),
        /** 窗口内既没有成交也没有接口调用 —— **没有可判断的数据**，不等于健康 */
        NO_DATA("无数据"),
        /** 数据齐全且各项正常 */
        OK("正常");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** 排序权重：越大越靠前（前端与后端用同一个顺序，不各排一次） */
        public int rank() {
            return values().length - ordinal();
        }

        /** 声明清单，供"新增档位忘了配文案/排序"的守卫断言使用 */
        public static final Set<Level> ALL =
                Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values())));

        /** 字符串转档位；未知返回 null（调用方决定是"忽略"还是"报错"） */
        public static Level of(String raw) {
            if (raw == null) {
                return null;
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            for (Level level : values()) {
                if (level.name().equals(v)) {
                    return level;
                }
            }
            return null;
        }
    }

    // ---------------- 展示排序 ----------------

    /**
     * 面板行的展示顺序：**档位（风险优先）→ 下单数降序 → 租户 id 升序**。
     *
     * <h2>★ 为什么放在判据层而不是 Service 里</h2>
     * 「谁最需要人看」与判级是同一个口径问题；放在这里它才能被单测钉住。
     * E24 实测踩过一个很典型的坑：比较器写成
     * {@code Comparator.comparingInt(rank).thenComparing(...)} —— 表面上与
     * 「rank 越大越靠前」的注释相符，实际 {@code comparingInt} 是**升序**，
     * 于是「正常」排在了最前面、「风险」沉到了底部。
     * 这个错在数据量少时只表现为「乱序 1 处」，极容易被当成偶发放过去。
     *
     * <h2>为什么用 {@link #displayRank}（越小越靠前）而不是 {@code rank().reversed()}</h2>
     * {@code reversed()} 的**作用域**是这里最容易写错的地方：写在链中间只反转前一段，
     * 写在链尾会反转整条链（连 tie-breaker 一起翻）。干脆把「越小越靠前」做成一个显式的键，
     * 比较器只用升序，这类错误就无处可藏。
     *
     * <h2>未知档位排最前（刻意的失效方向）</h2>
     * 未知档位只出现在「后端加了档位、调用方还没同步」的时候。把它埋在最后，
     * 结果是新出现的（可能是更严重的）档位被当成尾部的正常项忽略掉；
     * 排在首位至少会被人看见。
     *
     * <h2>第三键 tenantId 不是装饰</h2>
     * 没有它，同档位同单数的两家店在两次请求之间可能换序，于是被 {@code limit} 截断的
     * 永远是随机一批，「还有 N 家未展示」里的 N 家会飘。
     */
    public static final Comparator<TenantHealthRow> DISPLAY_ORDER =
            Comparator.comparingInt((TenantHealthRow r) -> displayRank(r.level()))
                    .thenComparing(Comparator.comparingLong(TenantHealthRow::orderCount).reversed())
                    .thenComparingLong(TenantHealthRow::tenantId);

    /**
     * 展示排序键：**越小越靠前**。
     * 未知档位返回 {@link Integer#MIN_VALUE}（理由见 {@link #DISPLAY_ORDER}）。
     */
    public static int displayRank(String level) {
        Level parsed = Level.of(level);
        return parsed == null ? Integer.MIN_VALUE : -parsed.rank();
    }

    /** 售后率预警线（百分比） */
    public static final double AFTER_SALE_WATCH_PERCENT = 20.0;
    /** 售后率的样本下限（成交单数） */
    public static final long AFTER_SALE_MIN_BASE = 5;
    /** 接口错误率风险线（百分比，5xx / 总调用） */
    public static final double API_ERROR_RISK_PERCENT = 1.0;
    /** 接口错误率关注线（百分比） */
    public static final double API_ERROR_WATCH_PERCENT = 0.2;
    /** 接口调用的样本下限 */
    public static final long API_MIN_BASE = 10;

    /**
     * 超期托管的判定天数。
     *
     * <p>与 {@code SettlementMapper#staleCustodyBySeller} 的 SQL 条件**必须一致**（那里由调用方
     * 传 {@code now - 本值}）。放在这里而不是散在服务里，是因为"30 天"是一个**判据**：
     * 它会被写进面板说明、被运维质疑、被调整，只允许有一处定义。
     */
    public static final int STALE_CUSTODY_DAYS = 30;

    /**
     * 面板允许的最大窗口天数。
     *
     * <p><b>★ 它必须 ≤ {@code TenantApiMetrics.KEEP_DAYS}（40）</b>，否则接口指标会读到
     * 已经过期的键 —— 空结果与"那天没调用"完全一样，错误率会安静地变准不了。
     * 单测里有一条断言钉住这个关系（跨模块的约束只能靠测试守，编译器管不到）。
     * 取 30 而不是 40：常用的巡检窗口是"近 7 / 近 14 / 近 30 天"，
     * 而多留的 10 天 TTL 余量正好覆盖"月末跑 30 天窗口时，最早那天还有 10 天寿命"。
     */
    public static final int MAX_DAYS = 30;

    /** 面板默认窗口天数 */
    public static final int DEFAULT_DAYS = 30;

    /** 租户行数默认上限 */
    public static final int DEFAULT_LIMIT = 50;

    /** 租户行数硬上限：再大就不是"看一眼"而是导出了，应该走别的通道 */
    public static final int MAX_LIMIT = 200;

    /**
     * 百分比：分母为 0 时返回 null。
     *
     * <p>返回 null 而不是 0.0 是本类的核心约定（见类注释）：前者渲染成「—」，
     * 后者渲染成「0.00%」，两者在"这家店有没有问题"这个问题上结论相反。
     * 也刻意不抛异常 —— 分母为 0 在健康度面板里是**常态**（大量店铺没有成交），
     * 抛异常会让面板对着一张空库直接 500。
     */
    public static Double percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return (double) numerator / denominator * 100.0;
    }

    /**
     * 百分比格式化：两位小数 + 百分号；入参为 null 时返回 null。
     *
     * <p>与 {@code AdminOpsService#percent} 的格式一致（{@code %.2f%%}）——
     * 两个后台页面上的比例必须长得一样，否则运营会以为口径不同。
     */
    public static String percentText(Double percent) {
        return percent == null ? null : String.format("%.2f%%", percent);
    }

    /** 售后率（百分比，可能为 null）＝ 售后立案数 / 成交单数 */
    public static Double afterSaleRate(long afterSaleCount, long paidCount) {
        return percent(afterSaleCount, paidCount);
    }

    /** 接口错误率（百分比，可能为 null）＝ 5xx / 总调用数 */
    public static Double apiErrorRate(long serverError, long total) {
        return percent(serverError, total);
    }

    /**
     * 判级。
     *
     * <p>判定顺序即优先级，**不能重排**：先看资金硬异常，再看接口硬故障，
     * 最后才是"倾向性"指标。反过来会出现"售后率 25% 把一笔未收回欠款盖过去"。
     *
     * <p>样本下限只做一件事：<b>不因小样本报警</b>，而不是"不给结论"。
     * 成交 2 单、售后 1 单时既不算 WATCH，也不该凭空造一个"样本不足"档 ——
     * 那个档会让面板上大多数新店都是灰的，反而失去信息量。
     * 它落到 {@link Level#OK}，同时面板照常展示原始计数（见类注释最后一段）。
     *
     * @param paidCount               窗口内成交单数
     * @param settlementAbnormalCount 结算异常合计（欠款 + 超期托管 + 缺托管单）
     * @param afterSaleCount          窗口内售后立案数
     * @param apiServerError          窗口内 5xx
     * @param apiTotal               窗口内接口调用总数
     */
    public static Level level(long paidCount,
                              long settlementAbnormalCount,
                              long afterSaleCount,
                              long apiServerError,
                              long apiTotal) {
        // ① 资金事实错位：一笔也算风险
        if (settlementAbnormalCount > 0) {
            return Level.RISK;
        }
        Double apiRate = apiErrorRate(apiServerError, apiTotal);
        if (apiRate != null && apiTotal >= API_MIN_BASE && apiRate >= API_ERROR_RISK_PERCENT) {
            return Level.RISK;
        }
        // ② 倾向性指标：达到样本下限才判
        Double saleRate = afterSaleRate(afterSaleCount, paidCount);
        if (saleRate != null && paidCount >= AFTER_SALE_MIN_BASE && saleRate >= AFTER_SALE_WATCH_PERCENT) {
            return Level.WATCH;
        }
        if (apiRate != null && apiTotal >= API_MIN_BASE && apiRate >= API_ERROR_WATCH_PERCENT) {
            return Level.WATCH;
        }
        // ③ 两个维度都没有任何样本 → 无法判断，不是"健康"
        if (paidCount <= 0 && apiTotal <= 0) {
            return Level.NO_DATA;
        }
        return Level.OK;
    }

    /** 结算异常合计：三类存量异常之和（每类都是"一笔也算"的量级，直接相加） */
    public static long settlementAbnormalCount(long debtCount, long staleCustodyCount, long missingSettlementCount) {
        return debtCount + staleCustodyCount + missingSettlementCount;
    }
}
