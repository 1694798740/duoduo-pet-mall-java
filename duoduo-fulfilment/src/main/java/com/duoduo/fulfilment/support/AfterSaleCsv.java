package com.duoduo.fulfilment.support;

import com.duoduo.common.web.CsvExport;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;

import java.util.List;

/**
 * 售后导出的列定义（2026-10-28 · 导入导出第一批）。
 *
 * <p>卖家侧与运营侧导出的是同一份事实、同一组列，差别只在可见范围 ——
 * 两处各写一份列定义，迟早出现「运营导出的金额单位是元、商家导出的是分」这种事故。
 */
public final class AfterSaleCsv {

    private AfterSaleCsv() {
    }

    public static final List<String> HEADERS = List.of(
            "售后单号", "订单号", "类型", "状态", "退款金额(元)", "买家ID", "卖家ID",
            "申请原因", "卖家处理", "裁定结果", "裁定说明", "申请时间", "更新时间");

    public static List<String> row(AfterSaleView view) {
        return List.of(
                text(view.id()),
                text(view.orderNo()),
                text(view.typeLabel()),
                text(view.statusLabel()),
                view.refundAmount() == null ? "" : CsvExport.yuan(view.refundAmount()),
                view.buyerId() == null ? "" : String.valueOf(view.buyerId()),
                view.sellerId() == null ? "" : String.valueOf(view.sellerId()),
                text(view.reason()),
                text(view.sellerReply()),
                text(view.arbitrateResult()),
                text(view.arbitrateNote()),
                CsvExport.beijingTime(view.createdAt()),
                CsvExport.beijingTime(view.updatedAt()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
