package com.duoduo.trade.support;

import com.duoduo.trade.dto.AnalyticsDtos.TrendPoint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 趋势序列的「补零」口径。
 *
 * <p><b>为什么必须有这一步</b>：聚合查询只返回**有数据的那几天**。直接拿去画折线，
 * 横轴会变成「把所有有单的日子等距排列」—— 于是「10-01 与 10-05 之间空了 3 天」
 * 在图上看起来和「连续两天」一样宽，趋势的形状是假的；而某天真的没有订单时，
 * 折线会直接跳过它，读图的人会以为是数据缺失而不是「那天没生意」。
 *
 * <p>抽成一处是因为商家端与平台后台都要用：两份实现迟早在某次口径调整后只改一处，
 * 然后两个页面给出不同的横轴。
 */
public final class TrendSeries {

    private TrendSeries() {
    }

    /**
     * 把稀疏的聚合结果补成**连续**的日序列。
     *
     * @param rows 聚合结果，每行需含 {@code d}（日期）/{@code cnt}（订单数）/ {@code gmv}（成交额）
     * @param from 起始日（含）
     * @param days 天数
     * @return 长度恒等于 {@code days} 的连续序列；无数据的日期补 0
     */
    public static List<TrendPoint> fill(Collection<Map<String, Object>> rows, LocalDate from, int days) {
        Map<String, TrendPoint> byDate = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String date = String.valueOf(row.get("d"));
            byDate.put(date, new TrendPoint(date, lng(row, "cnt"), lng(row, "gmv")));
        }
        List<TrendPoint> series = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            String date = from.plusDays(i).toString();
            series.add(byDate.getOrDefault(date, new TrendPoint(date, 0L, 0L)));
        }
        return series;
    }

    private static long lng(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
