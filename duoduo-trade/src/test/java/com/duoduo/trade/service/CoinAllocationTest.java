package com.duoduo.trade.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R-P1 · 多多币分摊口径的纯函数测试。
 *
 * <p>这三个静态方法是「预览显示抵多少」与「下单实际抵多少」的共同源头，
 * 任何一条边界算错都会直接变成对不上账的金额，因此逐条钉死：
 * 单笔上限 50%、按拆单顺序贪心、余额耗尽后剩余单全为 0。
 */
class CoinAllocationTest {

    private static Map<Long, Long> pays(long... values) {
        LinkedHashMap<Long, Long> map = new LinkedHashMap<>();
        long id = 1L;
        for (long v : values) {
            map.put(id++, v);
        }
        return map;
    }

    @Test
    void 上限为券后金额的一半且向下取整() {
        assertEquals(5000L, OrderService.coinCapOf(10000L), "券后 100 元最多抵 50 元");
        assertEquals(50L, OrderService.coinCapOf(101L), "101 分的另一半是 50 分（向下取整）");
        assertEquals(0L, OrderService.coinCapOf(1L), "1 分钱连半分都凑不出，不可用币");
        assertEquals(0L, OrderService.coinCapOf(0L));
        assertEquals(0L, OrderService.coinCapOf(-5L), "负数防御性归零");
    }

    @Test
    void 余额充足时各单抵到各自上限() {
        // 两单券后 100 元 / 60 元：上限 50 元 + 30 元 = 80 元；余额 100 币够抵满
        Map<Long, Long> out = OrderService.allocateCoins(pays(10000L, 6000L), 10000L);
        assertEquals(5000L, out.get(1L));
        assertEquals(3000L, out.get(2L));
    }

    @Test
    void 余额不足时按拆单顺序贪心() {
        // 上限合计 80 元，余额只有 55 币：第一单抵满 50，第二单只剩 5
        Map<Long, Long> out = OrderService.allocateCoins(pays(10000L, 6000L), 5500L);
        assertEquals(5000L, out.get(1L));
        assertEquals(500L, out.get(2L));
    }

    @Test
    void 余额耗尽后剩余单全为零且不出现负数() {
        Map<Long, Long> out = OrderService.allocateCoins(pays(10000L, 6000L, 8000L), 5000L);
        assertEquals(5000L, out.get(1L));
        assertEquals(0L, out.get(2L));
        assertEquals(0L, out.get(3L));
    }

    @Test
    void 零余额与零余额单都不产生抵扣() {
        Map<Long, Long> out = OrderService.allocateCoins(pays(10000L, 0L), 0L);
        assertEquals(0L, out.get(1L));
        assertEquals(0L, out.get(2L));
        // 键必须完整保留（调用方按键取数，缺键会 NPE）
        assertEquals(2, out.size());
    }

    @Test
    void 分摊结果永不越过单笔上限() {
        // 余额远大于上限：各单只能抵到自己的一半，绝不能多
        Map<Long, Long> out = OrderService.allocateCoins(pays(2000L, 400L), 999_999L);
        assertEquals(1000L, out.get(1L));
        assertEquals(200L, out.get(2L));
    }
}
