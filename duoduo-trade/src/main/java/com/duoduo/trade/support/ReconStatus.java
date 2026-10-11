package com.duoduo.trade.support;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 对账轮次状态（S3）。
 *
 * <p><b>三态，其中 {@link #FAILED} 是本功能最重要的一个取值。</b>
 * 对账只有两种结局在直觉上是"正常的"：干净、或者查出了差异。
 * 但还有第三种 —— <b>根本没查成</b>（商品服务读不到、SQL 超时、容器在重启）。
 *
 * <p>如果这一种被记成 {@link #CLEAN}，报表会一片绿而事实未知：
 * 「昨天对账全绿」这句话会变成一句没有依据的断言，而它恰恰是运维唯一会看的那一行。
 * 因此 {@code FAILED} 必须能与 {@code CLEAN} 分开显示，且**不允许**被折叠成 0 差异。
 */
public enum ReconStatus {

    /** 跑完了，一条差异都没有 */
    CLEAN("干净"),
    /** 跑完了，有差异 */
    DIRTY("有差异"),
    /** **没跑成** —— 未知，不等于干净 */
    FAILED("未完成");

    private final String label;

    ReconStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ReconStatus of(String code) {
        return Arrays.stream(values())
                .filter(s -> s.name().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** 由差异条数推导（只在"确实跑完了"的前提下用；失败时调用方必须显式给 FAILED） */
    public static ReconStatus byDiff(int diffCount) {
        return diffCount > 0 ? DIRTY : CLEAN;
    }

    public static Set<ReconStatus> all() {
        return new LinkedHashSet<>(Arrays.asList(values()));
    }
}
