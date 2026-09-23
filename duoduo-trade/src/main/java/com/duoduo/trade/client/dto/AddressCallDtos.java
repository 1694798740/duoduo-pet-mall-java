package com.duoduo.trade.client.dto;

/**
 * 交易域 → 用户域的调用契约（A8 · 收货地址）。
 *
 * <p>与 {@link CouponCallDtos} 同一约定：在交易域<b>再定义一份</b>，
 * 不引入 {@code duoduo-user} 的 jar —— 编译期耦合会让任何一方改字段都要同时发版另一方。
 * 代价是字段要同步改两处，因此这里刻意只保留下单真正需要的三样：
 * 收货人、电话、拼好的完整地址。
 *
 * <p>地址簿里的标签、省市字段、是否默认等「管理信息」一概不复制过来 ——
 * 交易域拿到它们也没有用途，只会白白扩大两边需要保持同步的字段面。
 */
public final class AddressCallDtos {

    private AddressCallDtos() {
    }

    /**
     * 下单用的收货信息快照（用户域返回）。
     *
     * <p>{@code fullAddress} 是用户域<b>拼好的完整地址文本</b>，
     * 交易域直接照抄进 {@code t_order.receiver_address}，不再自己拼一遍 ——
     * 拼接规则（如直辖市去重）只应有一处定义，否则「地址簿里看到的」与「订单里存的」
     * 迟早会出现不一致，而这类差异在售后纠纷里是有意义的证据。
     */
    public record ReceiverSnapshot(
            Long id,
            String receiverName,
            String receiverPhone,
            String fullAddress
    ) {
    }
}
