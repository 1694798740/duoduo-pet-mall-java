package com.duoduo.fulfilment.support;

import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.support.Couriers.CourierInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-05 · 模拟面单与快递公司白名单。
 */
class SimulatedLogisticsProviderTest {

    private final SimulatedLogisticsProvider provider =
            new SimulatedLogisticsProvider(new SnowflakeIdGenerator(1, 1));

    @Test
    void 快递公司_默认顺丰_大小写不敏感_未知返回null() {
        assertThat(Couriers.resolve(null).code()).isEqualTo("SF");
        assertThat(Couriers.resolve("  ").code()).isEqualTo("SF");
        assertThat(Couriers.resolve("yto").code()).isEqualTo("YTO");
        assertThat(Couriers.resolve("EMS").name()).isEqualTo("中国邮政");
        assertThat(Couriers.resolve("FAKE")).isNull();
        assertThat(Couriers.all()).extracting(CourierInfo::code)
                .containsExactly("SF", "YTO", "ZTO", "STO", "YD", "JD", "EMS");
    }

    @Test
    void 模拟运单号_快递前缀加12位数字且不重复() {
        LogisticsProvider.Waybill first = provider.create(
                new LogisticsProvider.WaybillRequest("ORD-1", Couriers.resolve("SF")));
        LogisticsProvider.Waybill second = provider.create(
                new LogisticsProvider.WaybillRequest("ORD-2", Couriers.resolve("SF")));

        assertThat(first.mode()).isEqualTo("SIMULATED");
        assertThat(first.waybillNo()).matches("SF\\d{12}");
        assertThat(second.waybillNo()).matches("SF\\d{12}");
        assertThat(second.waybillNo()).isNotEqualTo(first.waybillNo());
    }
}
