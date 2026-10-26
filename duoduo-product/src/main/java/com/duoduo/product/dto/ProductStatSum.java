package com.duoduo.product.dto;

/**
 * 商品曝光 / 点击窗口汇总（A-17，Mapper 结果映射用）。
 *
 * <p>刻意不用 record：MyBatis 的自动映射走 setter，record 需要额外的构造器映射配置；
 * 这是一个只服务于一条 SQL 的搬运对象，用普通 Bean 更直白。
 */
public class ProductStatSum {

    private Long spuId;
    private long exposure;
    private long clicks;

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public long getExposure() {
        return exposure;
    }

    public void setExposure(long exposure) {
        this.exposure = exposure;
    }

    public long getClicks() {
        return clicks;
    }

    public void setClicks(long clicks) {
        this.clicks = clicks;
    }
}
