package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleArbitrateRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;
import com.duoduo.fulfilment.service.AfterSaleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台（运营）售后仲裁工作台。
 *
 * <p>与买家 / 卖家侧的接口分开：可见范围不同（这里是全平台）、权限不同（运营准入）、
 * 且未来运营侧要加的动作（批量裁定、导出、备注）不该混进用户端接口。
 * 准入由服务层的 {@code OperatorGuard.requireOperator()} 把关。
 */
@RestController
@RequestMapping("/api/admin/aftersales")
public class AdminAfterSaleController {

    private final AfterSaleService afterSaleService;

    public AdminAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    /** 全平台售后列表（按状态过滤，分页） */
    @GetMapping
    public ApiResponse<PageResult<AfterSaleView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(afterSaleService.listAll(status, page, pageSize));
    }

    /** 裁定：APPROVE_REFUND（支持退款，会真的执行退款）/ REJECT（驳回），**必须填裁定说明** */
    @PostMapping("/{id}/arbitrate")
    public ApiResponse<AfterSaleView> arbitrate(@PathVariable Long id,
                                               @Valid @RequestBody AfterSaleArbitrateRequest request) {
        return ApiResponse.ok(afterSaleService.arbitrate(id, request));
    }
}
