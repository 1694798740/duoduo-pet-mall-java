package com.duoduo.trade.support;

import com.duoduo.common.web.CsvExport;
import com.duoduo.trade.dto.OrderDtos.OrderItemView;
import com.duoduo.trade.dto.OrderDtos.OrderView;

import java.util.List;

/**
 * 全平台订单导出的列定义（同步流式导出与异步任务共用）。
 *
 * <p><b>为什么必须只有一份</b>：同步接口与异步任务如果各写一份表头 / 列顺序，
 * 运营下载到的两份文件会在「第几列是实付」这种地方悄悄错位 ——
 * 而财务报表是按列位置读的，错位不会报错，只会算错钱。
 */
public final class OrderCsv {

    private OrderCsv() {
    }

    /** 运营视角：含完整收货信息（脱敏是给公开页面用的，运营排障需要真值） */
    public static final List<String> HEADERS = List.of(
            "订单号", "店铺", "订单类型", "状态", "商品件数", "商品总额(元)", "运费(元)", "优惠(元)", "实付(元)",
            "收货人", "收货电话", "收货地址", "下单时间", "支付时间", "发货时间", "完成时间", "关闭原因");

    public static List<String> row(OrderView order) {
        int itemCount = order.items() == null ? 0
                : order.items().stream().mapToInt(OrderItemView::quantity).sum();
        return List.of(
                text(order.orderNo()),
                text(order.storeName()),
                text(order.orderType()),
                text(order.statusLabel()),
                String.valueOf(itemCount),
                CsvExport.yuan(order.totalAmount()),
                CsvExport.yuan(order.freightAmount()),
                CsvExport.yuan(order.discountAmount()),
                CsvExport.yuan(order.payAmount()),
                text(order.receiverName()),
                text(order.receiverPhone()),
                text(order.receiverAddress()),
                CsvExport.beijingTime(order.createdAt()),
                CsvExport.beijingTime(order.paidAt()),
                CsvExport.beijingTime(order.shippedAt()),
                CsvExport.beijingTime(order.finishedAt()),
                text(order.closeReason()));
    }

    // ---------------- 买家侧（矩阵 · 我的订单） ----------------

    /**
     * 买家视角的表头：**不含店铺内部字段与关闭原因**，手机号 / 地址脱敏。
     *
     * <p>为什么本人数据也要脱敏：导出文件最常见的去向是发给客服 / 代收人 / 群里核对，
     * 页面里本人看得到完整信息就够了；文件一旦转手，完整住址就成了别人的数据。
     * 脱敏规则与展示层一致（手机号保首 3 末 4，地址保前 6 位）。
     */
    public static final List<String> BUYER_HEADERS = List.of(
            "订单号", "店铺", "订单类型", "状态", "商品件数", "商品总额(元)", "运费(元)", "优惠(元)", "实付(元)",
            "收货人", "收货电话(脱敏)", "收货地址(脱敏)", "下单时间", "支付时间", "发货时间", "完成时间");

    public static List<String> buyerRow(OrderView order) {
        int itemCount = order.items() == null ? 0
                : order.items().stream().mapToInt(OrderItemView::quantity).sum();
        return List.of(
                text(order.orderNo()),
                text(order.storeName()),
                text(order.orderType()),
                text(order.statusLabel()),
                String.valueOf(itemCount),
                CsvExport.yuan(order.totalAmount()),
                CsvExport.yuan(order.freightAmount()),
                CsvExport.yuan(order.discountAmount()),
                CsvExport.yuan(order.payAmount()),
                text(order.receiverName()),
                maskPhone(order.receiverPhone()),
                maskAddress(order.receiverAddress()),
                CsvExport.beijingTime(order.createdAt()),
                CsvExport.beijingTime(order.paidAt()),
                CsvExport.beijingTime(order.shippedAt()),
                CsvExport.beijingTime(order.finishedAt()));
    }

    /** 手机号脱敏：138****1234（长度不足或非手机号时统一打码，不原样输出） */
    public static String maskPhone(String phone) {
        String value = text(phone).trim();
        if (value.length() < 7) {
            return value.isEmpty() ? "" : "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    /** 地址脱敏：保前 6 位（省市区），其余全部隐藏 —— 不保留门牌号尾部，避免反推 */
    public static String maskAddress(String address) {
        String value = text(address).trim();
        if (value.length() <= 6) {
            return value.isEmpty() ? "" : "****";
        }
        return value.substring(0, 6) + "****";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
