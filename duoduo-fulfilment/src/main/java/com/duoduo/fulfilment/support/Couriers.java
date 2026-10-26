package com.duoduo.fulfilment.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快递公司编码与中文名（B-05，单一事实来源）。
 *
 * <p>编码用于生成运单号前缀、与未来快递鸟/菜鸟对接；中文名用于买卖双方展示。
 * 卖家不能自创编码：自创编码会让「同一条轨迹在买家页叫顺丰、卖家页叫 SF」，
 * 也会让将来的真实渠道查不到这家快递。
 */
public final class Couriers {

    public record CourierInfo(String code, String name) {
    }

    public static final String DEFAULT_CODE = "SF";

    private static final Map<String, CourierInfo> ALL = new LinkedHashMap<>();

    static {
        register("SF", "顺丰速运");
        register("YTO", "圆通速递");
        register("ZTO", "中通快递");
        register("STO", "申通快递");
        register("YD", "韵达快递");
        register("JD", "京东物流");
        register("EMS", "中国邮政");
    }

    private Couriers() {
    }

    private static void register(String code, String name) {
        ALL.put(code, new CourierInfo(code, name));
    }

    /**
     * 解析快递公司：空 = 默认顺丰；不认识 = {@code null}（由调用方报 400）。
     *
     * <p>大小写不敏感：卖家从文档里复制 {@code yto} 进来不该被拒。
     */
    public static CourierInfo resolve(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return ALL.get(DEFAULT_CODE);
        }
        return ALL.get(normalized);
    }

    public static List<CourierInfo> all() {
        return List.copyOf(ALL.values());
    }
}
