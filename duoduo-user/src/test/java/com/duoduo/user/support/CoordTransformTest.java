package com.duoduo.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐标转换的确定性单测。
 *
 * <p>断言用的都是**实数**：先由高德 geocode 把地名解析成 GCJ-02 坐标，
 * 再反解出 WGS-84，作为「浏览器会给的值」。转换后必须回到 GCJ-02 附近，
 * 且偏移量落在已知区间（500~600 米）—— 这正是修掉「偏差过大」的关键证据。
 */
class CoordTransformTest {

    @Test
    @DisplayName("WGS-84 → GCJ-02：北京天安门（100 米内还原）")
    void tiananmen() {
        // 天安门 GCJ-02 由高德 geocode 得到：116.397463, 39.909187
        // 其 WGS-84 反解值：116.391222, 39.907786
        double[] gcj = CoordTransform.wgs84ToGcj02(116.391222, 39.907786);
        double diff = CoordTransform.distanceMeters(gcj[0], gcj[1], 116.397463, 39.909187);
        assertTrue(diff < 100, "预期还原到 100 米内，实际 " + diff + " 米");
    }

    @Test
    @DisplayName("WGS-84 → GCJ-02：深圳花园坪（100 米内还原）")
    void shenzhen() {
        // GCJ-02: 114.231199, 22.706634；其 WGS-84: 114.226340, 22.709461
        double[] gcj = CoordTransform.wgs84ToGcj02(114.226340, 22.709461);
        double diff = CoordTransform.distanceMeters(gcj[0], gcj[1], 114.231199, 22.706634);
        assertTrue(diff < 100, "预期还原到 100 米内，实际 " + diff + " 米");
    }

    @Test
    @DisplayName("偏移量级：国内同一点两种坐标差值应在 300~800 米（说明这一步不可省）")
    void offsetMagnitude() {
        double[] gcj = CoordTransform.wgs84ToGcj02(114.226340, 22.709461);
        double offset = CoordTransform.distanceMeters(114.226340, 22.709461, gcj[0], gcj[1]);
        assertTrue(offset > 300 && offset < 800, "沪深一带偏移应 300~800 米，实际 " + offset + " 米");
    }

    @Test
    @DisplayName("境外坐标原样返回（不做加密偏移）")
    void overseas() {
        double[] gcj = CoordTransform.wgs84ToGcj02(139.6917, 35.6895); // 东京
        assertEquals(139.6917, gcj[0], 1e-9);
        assertEquals(35.6895, gcj[1], 1e-9);
    }

    @Test
    @DisplayName("正反转换可逆（误差 < 1 米）")
    void roundTrip() {
        double[] wgs = CoordTransform.gcj02ToWgs84(120.1551, 30.2741);
        double[] back = CoordTransform.wgs84ToGcj02(wgs[0], wgs[1]);
        double diff = CoordTransform.distanceMeters(back[0], back[1], 120.1551, 30.2741);
        assertTrue(diff < 1, "往返误差应 < 1 米，实际 " + diff + " 米");
    }
}
