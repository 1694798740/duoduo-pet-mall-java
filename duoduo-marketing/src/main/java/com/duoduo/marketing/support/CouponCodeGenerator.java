package com.duoduo.marketing.support;

import java.security.SecureRandom;

/**
 * 券码生成器。
 *
 * <p><b>为什么券码不用雪花 ID</b>：雪花 ID 是 18 位 BIGINT，超过 JS 的
 * {@code Number.MAX_SAFE_INTEGER}，{@code JSON.parse} 会把它静默改写成另一个数。
 * 前端拿这个被改写的数去拼 URL，打到的是一张不存在的券，
 * 而报错信息看起来是「券不存在」——排查方向完全被带偏（INV-16，售后单踩过同一个坑）。
 * 随机字符串天然规避：它不是数字，不参与任何数值运算。
 *
 * <p><b>为什么不用 UUID</b>：32 位太长，券码是要给用户看、给客服念的。
 * 16 位分成 4 组在 UI 上可读，且去掉了 {@code 0/O/1/I} 这组易混字符 ——
 * 券码最常见的输入场景是「客服电话里报给你」，易混字符会直接变成输错。
 */
public final class CouponCodeGenerator {

    /** 去掉 0/O/1/I 的 32 字符表：大小写混合既可读又不至于太长 */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 14;
    private static final String PREFIX = "CP";

    private static final SecureRandom RANDOM = new SecureRandom();

    private CouponCodeGenerator() {
    }

    /** 生成形如 {@code CP7KQ3M2X9P4RT6V} 的券码（16 位） */
    public static String next() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + RANDOM_LENGTH);
        sb.append(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
