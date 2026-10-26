package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillCreateRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillView;
import com.duoduo.fulfilment.service.WaybillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电子面单（B-05，卖家 / 运营）。
 *
 * <p>路径放在履约域自己的 {@code /api/logistics} 命名空间下，与轨迹查询一致：
 * 订单主数据在交易域，面单与轨迹事实在履约域，前端本来就是两次调用。
 *
 * <p>申请后不会自动发货：面单只是「运单号已就绪」，发不发货由卖家在交易域确认。
 * 这样卖家可以先申请面单、打印/核对，再点发货 —— 与真实电商的操作顺序一致。
 */
@RestController
@RequestMapping("/api/logistics/waybills")
public class WaybillController {

    private final WaybillService waybillService;

    public WaybillController(WaybillService waybillService) {
        this.waybillService = waybillService;
    }

    /**
     * 申请电子面单（幂等）：同一订单重复申请返回同一张。
     *
     * <p>准入与状态门禁在服务层：只有该订单卖家 / 平台运营、且订单处于待发货或已发货时可用。
     * 本地没有快递鸟/菜鸟凭据，返回 {@code simulated=true} 并在运单号上使用模拟渠道。
     */
    @PostMapping
    public ApiResponse<WaybillView> create(@Valid @RequestBody WaybillCreateRequest request) {
        return ApiResponse.ok(waybillService.create(request));
    }
}
