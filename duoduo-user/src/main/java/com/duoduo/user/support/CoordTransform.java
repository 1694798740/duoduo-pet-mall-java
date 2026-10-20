package com.duoduo.user.support;

/**
 * 坐标系转换（WGS-84 ↔ GCJ-02）。
 *
 * <h2>为什么必须有这个类</h2>
 * <p>浏览器 {@code navigator.geolocation} 返回的是 **WGS-84**（GPS 原始坐标），
 * 而高德地图全系列接口使用的是 **GCJ-02**（国测局加密坐标）。两者在中国境内
 * 存在系统性偏移 —— 实测（2026-10-20）：
 * <pre>
 *   深圳「花园坪C区一巷8号」：GCJ-02 → 畲吓花园坪小区；把 WGS-84 直接传 → 龙城公园（相距 589 米）
 *   北京「天安门」        ：GCJ-02 → 天安门；      把 WGS-84 直接传 → 西长安街（相距 555 米）
 * </pre>
 * 也就是说「偏差 50 米以上」不是接口数据不准，而是**坐标系用错**：
 * 把 WGS-84 当成 GCJ-02 传给高德，解析出来的必然是错位点的地址。
 *
 * <h2>两端的差异（务必分清）</h2>
 * <ul>
 *   <li><b>网页端</b>：{@code navigator.geolocation} → WGS-84，**必须先转换**；</li>
 *   <li><b>微信小程序</b>：{@code wx.getLocation({type:'gcj02'})} → 已是 GCJ-02，**不能再转**，
 *       否则会二次偏移（往反方向再偏 500 米）。</li>
 * </ul>
 * 因此接口设计了 {@code coord} 参数，由调用方声明自己给的是哪种坐标。
 *
 * <h2>实现口径</h2>
 * <p>采用业界通行的公开换算公式（Krasovsky 1940 椭球 + 偏移多项式）。
 * 该公式与高德瓦片底图的对齐误差在 **1~2 米**量级，远小于 GPS 自身精度，
 * 足够地址回填使用。境外坐标（不在中国范围内）原样返回。
 */
public final class CoordTransform {

    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private CoordTransform() {
    }

    /** WGS-84 → GCJ-02（网页端浏览器坐标进后端时使用） */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    /**
     * GCJ-02 → WGS-84（迭代反解）。
     * 只在测试与排查时使用；线上链路不需要反向转换。
     */
    public static double[] gcj02ToWgs84(double lng, double lat) {
        double wLng = lng;
        double wLat = lat;
        for (int i = 0; i < 10; i++) {
            double[] g = wgs84ToGcj02(wLng, wLat);
            wLng += lng - g[0];
            wLat += lat - g[1];
        }
        return new double[]{wLng, wLat};
    }

    /** 两点球面距离（米），用于把「偏移」量化成人能理解的数字 */
    public static double distanceMeters(double lng1, double lat1, double lng2, double lat2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static boolean outOfChina(double lng, double lat) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }
}
