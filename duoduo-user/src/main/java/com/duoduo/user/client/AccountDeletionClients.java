package com.duoduo.user.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * C7 · 账号注销编排用的五个域客户端（聚合壳）。
 *
 * <p>为什么单独包一层而不是在 {@code AccountDeletionService} 里注入五个接口：
 * 注销执行是「五域必须全部成功才算完成」的一次编排，把五个调用点收拢到一个
 * 具名对象里，{@code executeOne} 读起来才是「五域清理」一件事，
 * 而不是五行各自独立的 HTTP 调用；将来加第六个域也只改这一处。
 *
 * <p>用字段注入而非构造注入是刻意的：这个类是纯聚合壳，没有可选依赖问题，
 * 也没有循环依赖风险（五个 client 接口都是 Feign 代理，不反向依赖本类）。
 */
@Component
public class AccountDeletionClients {

    @Autowired
    private TradePersonalDataClient trade;
    @Autowired
    private AfterSalePersonalDataClient afterSale;
    @Autowired
    private CouponPersonalDataClient coupon;
    @Autowired
    private SocialPersonalDataClient social;
    @Autowired
    private ListingPersonalDataClient listing;

    public TradePersonalDataClient trade() {
        return trade;
    }

    public AfterSalePersonalDataClient afterSale() {
        return afterSale;
    }

    public CouponPersonalDataClient coupon() {
        return coupon;
    }

    public SocialPersonalDataClient social() {
        return social;
    }

    public ListingPersonalDataClient listing() {
        return listing;
    }
}
