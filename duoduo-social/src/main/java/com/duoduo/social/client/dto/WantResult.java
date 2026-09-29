package com.duoduo.social.client.dto;

/**
 * 「想要」上报结果跨域契约副本（与商品域 {@code ProductDtos.WantResult} 对齐，第五十四轮）。
 *
 * <p>与 {@link ListingCardBrief} 同款做法：跨服务契约在消费端留一份副本，
 * 避免「商品域改字段、社交域静默反序列化失败」这种只有联调时才发现的问题。
 *
 * <p>{@code counted}：{@code true} = 该买家首次咨询，计数已 +1；{@code false} = 已在册未计数。
 * 本域<b>不</b>据此改变任何用户可见行为（消息照发）——
 * 它只是把「到底计没计」变成一个明确可断言的返回值，供联调与自动化测试使用。
 */
public record WantResult(
        boolean counted
) {
}
