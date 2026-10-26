package com.duoduo.fulfilment.support;

import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.support.Couriers.CourierInfo;
import org.springframework.stereotype.Component;

/**
 * 模拟电子面单渠道（B-05）。
 *
 * <p><b>运单号怎么生成</b>：{@code 快递编码 + 12 位数字}。数字来自雪花 id 的低 12 位 ——
 * 雪花在同一毫秒内单调递增，取低位足以保证同一进程内不重复；跨实例由数据库
 * {@code uk_waybill_no} 唯一键兜底（真撞了会走「回读已有面单」路径，不会出两张）。
 *
 * <p><b>为什么不是随机数</b>：随机数无法解释、也无法在日志里对应回订单；
 * 雪花低位至少是单调的，排查时能按号段判断先后。
 */
@Component
public class SimulatedLogisticsProvider implements LogisticsProvider {

    public static final String MODE = "SIMULATED";

    private final SnowflakeIdGenerator idGenerator;

    public SimulatedLogisticsProvider(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public Waybill create(WaybillRequest request) {
        CourierInfo courier = request.courier();
        long digits = Math.floorMod(idGenerator.nextId(), 1_000_000_000_000L);
        String waybillNo = courier.code() + String.format("%012d", digits);
        return new Waybill(waybillNo, MODE);
    }
}
