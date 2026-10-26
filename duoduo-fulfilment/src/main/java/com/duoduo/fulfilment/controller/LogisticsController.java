package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.fulfilment.dto.LogisticsDtos.CourierView;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackListView;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillView;
import com.duoduo.fulfilment.service.LogisticsService;
import com.duoduo.fulfilment.service.WaybillService;
import com.duoduo.fulfilment.support.Couriers;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 物流轨迹查询（买家 / 卖家 / 运营）。
 *
 * <p><b>为什么路径不放在 {@code /api/orders/{orderNo}/tracks}</b>：
 * 网关把 {@code /api/orders/**} 整段路由给交易域，而这条接口的数据在履约域。
 * 若照 {@code 07} 篇草案把路径挂在 {@code /api/orders} 下，就必须在网关里
 * 为 {@code /api/orders/*&#47;tracks} 单独插一条优先级更高的路由 ——
 * 那正是历史上造成过「新增端点不报错、只 404」的那类脆弱写法（README 里已记录过一次）。
 * 放在履约域自己的 {@code /api/logistics} 命名空间下，路由一段就够，且归属一眼可见。
 * 前端本来就是「订单详情 + 轨迹」两次调用，路径前缀不同不影响使用。
 *
 * <p>前缀 {@code /api/logistics/**} <b>不进网关白名单</b>：轨迹能反映「谁买了什么、寄到哪个城市」，
 * 属当事人信息，不登录就拿不到 {@code X-User-*}，服务端也就无从判断资格。
 *
 * <p>控制器只做参数绑定与包响应，准入判断全在服务层。
 */
@RestController
@RequestMapping("/api/logistics")
public class LogisticsController {

    private final LogisticsService logisticsService;
    private final WaybillService waybillService;

    public LogisticsController(LogisticsService logisticsService, WaybillService waybillService) {
        this.logisticsService = logisticsService;
        this.waybillService = waybillService;
    }

    /**
     * 支持的快递公司（B-05）：前端下拉直接消费，中文名由服务端给出，不在端上维护第二份映射。
     */
    @GetMapping("/couriers")
    public ApiResponse<List<CourierView>> couriers() {
        UserContext.requireUserId();
        return ApiResponse.ok(Couriers.all().stream()
                .map(c -> new CourierView(c.code(), c.name()))
                .toList());
    }

    /**
     * 某订单的物流轨迹（按发生时间正序）。
     *
     * <p>仅有轨迹的当事人（买家本人 / 该单卖家）与运营可见；
     * 订单还没发货、或已发货但推送未到时返回<b>空列表</b>而非错误 ——
     * 「暂无轨迹」是正常状态，用错误码表达会被前端渲染成故障。
     */
    @GetMapping("/orders/{orderNo}/tracks")
    public ApiResponse<TrackListView> tracks(@PathVariable String orderNo) {
        return ApiResponse.ok(logisticsService.listForParty(UserContext.requireUserId(), orderNo));
    }

    /**
     * 订单当前电子面单（B-05）：买家本人 / 该单卖家 / 运营可见；没有面单返回 404。
     * 前端在订单详情页据此展示「电子面单」区块。
     */
    @GetMapping("/orders/{orderNo}/waybill")
    public ApiResponse<WaybillView> waybill(@PathVariable String orderNo) {
        return ApiResponse.ok(waybillService.forParty(orderNo));
    }
}
