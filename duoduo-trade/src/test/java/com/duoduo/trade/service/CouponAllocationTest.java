package com.duoduo.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * R60 券放宽：抵扣额在多个子单之间按商品金额比例分摊。
 *
 * <p>分摊是「放宽适用范围」之后最容易算错钱的一步：总和必须严格等于券面额
 * （少一分用户就会发现「说好减 30 只减了 29」），且任何一单的分摊都不能超过它自己的商品额
 * （否则那一单应付为负，退款时会出现退得比付得多）。
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CouponAllocationTest {

    private static Map<Long, Long> groups(long... amounts) {
        Map<Long, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < amounts.length; i++) {
            map.put(1000L + i, amounts[i]);
        }
        return map;
    }

    private static long sum(Map<Long, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void 分摊后各单之和严格等于券面额() {
        Map<Long, Long> g = groups(200L, 100L, 57L);
        assertEquals(30L, sum(OrderService.allocateCouponDiscount(g, 30L)));
    }

    @Test
    void 按商品金额比例分摊() {
        Map<Long, Long> g = groups(200L, 100L);
        Map<Long, Long> alloc = OrderService.allocateCouponDiscount(g, 30L);
        assertEquals(20L, alloc.get(1000L));
        assertEquals(10L, alloc.get(1001L));
    }

    @Test
    void 除不尽的余数给金额最大的那一单() {
        // 100/100/101 共 301，券 10 分：按比例取整各 3 分（共 9），余 1 分必须给最大单
        Map<Long, Long> g = groups(100L, 100L, 101L);
        Map<Long, Long> alloc = OrderService.allocateCouponDiscount(g, 10L);
        assertEquals(10L, sum(alloc));
        assertEquals(4L, alloc.get(1002L), "余数应落在金额最大的那一单");
    }

    @Test
    void 任何一单的分摊都不超过它自己的商品额() {
        Map<Long, Long> g = groups(5L, 1000L);
        Map<Long, Long> alloc = OrderService.allocateCouponDiscount(g, 300L);
        for (Map.Entry<Long, Long> entry : alloc.entrySet()) {
            assertTrue(entry.getValue() <= g.get(entry.getKey()),
                    "子单 " + entry.getKey() + " 的分摊 " + entry.getValue() + " 超过了它自己的商品额");
        }
    }

    @Test
    void 券面额为零时各单都是零() {
        Map<Long, Long> alloc = OrderService.allocateCouponDiscount(groups(100L, 200L), 0L);
        assertEquals(0L, sum(alloc));
    }

    @Test
    void 没有拆单时抵扣全额落在这一单() {
        Map<Long, Long> alloc = OrderService.allocateCouponDiscount(groups(250L), 30L);
        assertEquals(30L, alloc.get(1000L));
    }

    @Test
    void 券归属取金额最大的那一单且并列时取最先拆出的() {
        assertEquals(1001L, OrderService.largestGroup(groups(100L, 300L, 200L)));
        assertEquals(1000L, OrderService.largestGroup(groups(300L, 300L, 100L)),
                "并列时必须确定取最先拆出的那单，否则券归属会随 Map 迭代顺序漂移");
    }

    @Test
    void 反向验证_丢掉余数会导致分摊总和小于券面额() {
        // 这是本测试存在的理由：按比例取整后不补余数，用户就会少减钱且查不出原因。
        Map<Long, Long> g = groups(100L, 100L, 101L);
        long naive = 0L;
        long total = sum(g);
        for (Long amount : g.values()) {
            naive += 10L * amount / total;
        }
        assertFalse(naive == 10L, "按比例取整天然会丢余数 —— 必须有补余数的那一步");
    }
}
