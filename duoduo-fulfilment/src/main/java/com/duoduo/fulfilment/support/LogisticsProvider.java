package com.duoduo.fulfilment.support;

import com.duoduo.fulfilment.support.Couriers.CourierInfo;

/**
 * 电子面单渠道适配层（B-05）。
 *
 * <p><b>为什么先定义接口而不是直接写模拟实现</b>：真实渠道（快递鸟 / 菜鸟）的报文、
 * 签名、重试与错误码都不同，但业务侧只关心三件事 —— 快递公司、运单号、是否模拟。
 * 把这三件事收在一个接口里，将来接真实渠道时只新增一个实现并切换配置，
 * 订单、轨迹、前端都不需要改。
 *
 * <p>当前只有 {@link SimulatedLogisticsProvider}：本地没有商户凭据，
 * 强行调用真实接口无法验证，反而会把「网络不通」伪装成业务失败。
 * 模拟面单的接口形态、轨迹节点与真实渠道完全一致，因此接真实渠道是替换实现，不是重做功能。
 */
public interface LogisticsProvider {

    /** SIMULATED / EXTERNAL */
    String mode();

    /**
     * 生成一张面单。
     *
     * @param request 订单号与快递公司（已由服务层校验并归一）
     */
    Waybill create(WaybillRequest request);

    record WaybillRequest(String orderNo, CourierInfo courier) {
    }

    record Waybill(String waybillNo, String mode) {
    }
}
