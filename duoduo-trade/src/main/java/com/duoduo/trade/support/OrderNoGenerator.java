package com.duoduo.trade.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 订单号生成器。
 *
 * <p>刻意**不用**雪花 ID 做订单号：雪花 ID 的规律性会暴露业务量
 * （看到两个订单号就能估出这段时间成交了多少单）。所以：
 * <ul>
 *   <li>数据库主键 / 分片键 → 雪花 ID（内部使用，不对外）</li>
 *   <li>对外展示的订单号 → 时间戳 + 随机数 + 校验位</li>
 * </ul>
 *
 * <p>格式：{@code yyyyMMddHHmmss}(14) + 随机 6 位 + 校验位 1 位 = 21 位。
 * 校验位是「各位数字之和 mod 10」，用于客服口头核对订单号时发现输错，
 * 不承担安全职责。唯一性由数据库唯一索引 {@code uk_order_no} 最终兜底。
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int RANDOM_BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        String body = LocalDateTime.now(ZoneOffset.UTC).format(FORMATTER)
                + String.format("%06d", random.nextInt(RANDOM_BOUND));
        return body + checkDigit(body);
    }

    /** 校验位：各位数字之和 mod 10 */
    static char checkDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            sum += body.charAt(i) - '0';
        }
        return (char) ('0' + (sum % 10));
    }
}
