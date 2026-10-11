package com.duoduo.trade.support;

import java.util.Arrays;

/**
 * 对账范围（S3）。
 *
 * <p><b>为什么钱与库存分成两个范围，而不是一轮全做完</b>：
 * 两者的**取数依赖不同** —— 钱只要交易域自己的库；库存要跨域读商品服务。
 * 合成一轮时，商品服务抖一下会让整个对账报 FAILED，而钱其实算得好好的，
 * 于是「钱对不对」这个每日期望得到答案的问题被一个无关故障拖成未知。
 * 分开之后各自独立成败，「钱是干净的、库存没跑成」是一个能说清楚的结论。
 */
public enum ReconScope {

    /** 钱：托管单 / 订单 / 欠款之间的账 */
    MONEY("钱"),
    /** 库存：冻结台账 / 订单明细 / 商品域原始库存之间的量 */
    STOCK("库存");

    private final String label;

    ReconScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 未知取值返回 {@code null}（调用方负责报 400），刻意不抛异常：脏数据不该把接口打挂 */
    public static ReconScope of(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}
