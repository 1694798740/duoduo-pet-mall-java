package com.duoduo.trade.dto;

import com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 交易域 · 卖家中心 DTO。
 *
 * <p>金额一律整数「分」；时间一律 ISO8601 UTC 字符串。
 * 所有汇总口径（佣金、可得、托管中）都由服务端算好下发 —— 前端不做减法，
 * 理由同 {@link SettlementView}：佣金口径一旦在多处实现，改费率必然漏改。
 */
public final class SellerDtos {

    private SellerDtos() {
    }

    /**
     * 卖家发货请求：物流信息可选。
     *
     * <p>当前订单表没有物流字段，也不为此加列 —— 单号随状态日志留痕
     * （{@code t_order_status_log.reason}），履约纠纷时能追溯即可。
     * 等物流域真正落地（含轨迹回传）再独立建表，那时这里换存储不改接口。
     */
    public record ShipRequest(
            @Size(max = 50, message = "物流公司名称过长") String logisticsCompany,
            @Size(max = 64, message = "物流单号过长") String logisticsNo
    ) {

        /** 拼进状态日志的原因文案；未填物流时回落到默认文案 */
        public String describe() {
            String company = logisticsCompany == null ? "" : logisticsCompany.trim();
            String no = logisticsNo == null ? "" : logisticsNo.trim();
            if (company.isEmpty() && no.isEmpty()) {
                return "卖家发货";
            }
            if (company.isEmpty()) {
                return "卖家发货（单号 " + no + "）";
            }
            return no.isEmpty() ? "卖家发货（" + company + "）" : "卖家发货（" + company + " " + no + "）";
        }
    }

    /**
     * 商家端概览。
     *
     * <p>{@code platformScope} 为 true 表示可见范围里额外包含平台自营
     * （{@code seller_id = 0}）—— 目前商品域 SPU 没有商家字段，
     * 所有自营销量都挂在平台账号下，运营 / 管理员需要能看到并代为履约。
     * 第三方商家接入后应回收这个口子，只保留运营的「代运营」用途。
     */
    public record SellerSummary(
            Long sellerId,
            String role,
            boolean platformScope,
            /** 待付款（买家还没付钱，卖家无需动作） */
            long pendingPaymentCount,
            /** 待发货（已付款，卖家该发货了） */
            long pendingShipCount,
            /** 已发货、在途 */
            long shippedCount,
            /** 已完成（款项已放款） */
            long completedCount,
            /** 已退款 */
            long refundedCount,
            /** 已关闭（超时未付 / 用户取消） */
            long closedCount,
            long totalOrderCount,
            /** 托管中：买家实付总额 */
            long custodyAmount,
            /** 托管中：卖家可得（已扣平台佣金） */
            long custodySellerAmount,
            /** 已放款：买家实付总额 */
            long releasedAmount,
            /** 已放款：卖家实收（已扣平台佣金） */
            long releasedSellerAmount,
            /** 已退回买家 */
            long refundedAmount,
            String generatedAt
    ) {
    }

    /**
     * 结算对账结果：分页明细 + <b>全量</b>汇总。
     *
     * <p>汇总刻意不受分页影响：分页只是「一次看不完」，
     * 而「我一共托管多少钱、已到账多少」是对账问题的答案，
     * 让前端拿当前页的数字相加只会得到一个随页码变化的假答案。
     *
     * <p>★ 每个「量」字段都要显式标注 {@code @JsonSerialize}：
     * {@code LongToStringJacksonModule} 的字段名白名单只对普通 POJO 生效，
     * **record 组件不流经那条路径**，不标注就会以字符串下发（前端声明的是 number）。
     * 详见 {@code SettlementDtos} 的类注释。
     */
    public record SettlementPage(
            List<SettlementView> items,
            @JsonSerialize(using = NumericLongSerializer.class) long total,
            int page,
            int pageSize,
            @JsonSerialize(using = NumericLongSerializer.class) long custodyAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long custodyFee,
            @JsonSerialize(using = NumericLongSerializer.class) long custodySellerAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long releasedAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long releasedFee,
            @JsonSerialize(using = NumericLongSerializer.class) long releasedSellerAmount,
            @JsonSerialize(using = NumericLongSerializer.class) long refundedAmount
    ) {
    }
}
