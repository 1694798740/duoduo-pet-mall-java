package com.duoduo.social.support;

import java.util.Set;

/**
 * 小程序页面白名单（第五十五轮）：通知 {@code jump_page} 的真实性依据。
 *
 * <p>为什么通知落地页要白名单，而不是「存什么就跳什么」：
 * <ol>
 *   <li><b>拼错是最可能的故障</b>：内部推送由交易域 / 履约域拼路径，
 *       写成 {@code /pages/order-detail/index} 这种错法编译器不会报错、日志也不会报错，
 *       只会在用户点击时表现为「点了没反应 / 跳到空白页」，且没有任何服务端痕迹；</li>
 *   <li><b>它是外来的字符串</b>：{@code jump_page} 最终会喂给小程序的
 *       {@code wx.navigateTo}。不校验就等于开放「让小程序跳任意页」的能力，
 *       未来若路径支持传参，还可能变成注入点。</li>
 * </ol>
 *
 * <p>清单来源：{@code miniprogram/app.json} 的 {@code pages} 数组（本文件必须与之保持一致；
 * 新增页面时同步这里，静态自检 {@code scripts/check-miniprogram.js} 会比对两处）。
 *
 * <p>只校验<b>路径段</b>，query 不参与判断（{@code /pages/chat/chat?peerId=123} 通过，
 * 因为 path 在白名单内）—— 具体参数是否合法由目标页面自己兜底，通知不该替页面做参数校验。
 */
public final class MiniProgramPages {

    /** app.json pages 全量清单（tab 页 + 推进式页面） */
    private static final Set<String> PAGES = Set.of(
            "/pages/index/index",
            "/pages/category/category",
            "/pages/cart/cart",
            "/pages/orders/orders",
            "/pages/profile/profile",
            "/pages/product/product",
            "/pages/seckill/seckill",
            "/pages/coupons/coupons",
            "/pages/address/address",
            "/pages/order-detail/order-detail",
            "/pages/checkout/checkout",
            "/pages/pay/pay",
            "/pages/after-sales/after-sales",
            "/pages/after-sale/after-sale",
            "/pages/ai/ai",
            "/pages/share-circle/share-circle",
            "/pages/notification/notification",
            "/pages/share-detail/share-detail",
            "/pages/user-profile/user-profile",
            "/pages/follow-list/follow-list",
            "/pages/chat-list/chat-list",
            "/pages/chat/chat",
            "/pages/login/login",
            "/pages/flea-market/flea-market",
            "/pages/flea-detail/flea-detail",
            "/pages/flea-publish/flea-publish",
            "/pages/flea-mine/flea-mine",
            "/pages/realname/realname"
    );

    private MiniProgramPages() {
    }

    /** 落地页是否合法：空串视为「不跳转」（合法）；非空必须命中页面清单 */
    public static boolean isValid(String jumpPage) {
        if (jumpPage == null || jumpPage.isBlank()) {
            return true;
        }
        return PAGES.contains(pathOf(jumpPage));
    }

    /** 取路径段（去掉 {@code ?query} 与结尾多余空白），供比对与错误提示使用 */
    public static String pathOf(String jumpPage) {
        if (jumpPage == null) {
            return "";
        }
        String trimmed = jumpPage.trim();
        int q = trimmed.indexOf('?');
        return q < 0 ? trimmed : trimmed.substring(0, q);
    }

    /** 页面清单快照（自测脚本 / 错误提示用） */
    public static Set<String> all() {
        return PAGES;
    }
}
