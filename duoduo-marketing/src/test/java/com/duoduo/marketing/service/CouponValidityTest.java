package com.duoduo.marketing.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * R60 券放宽：有效期改为按<b>自然日</b>判定。
 *
 * <p>放宽前是秒级比较：一张到期日写「今天」的券，若 expire_at 落在今天 08:00，
 * 早上 9 点就已经不能用 —— 卡包上明明写着今天到期。这两条断言把「到期日当天整天可用」
 * 这个新口径钉住，防止有人日后改回精确比较。
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CouponValidityTest {

    @Test
    void 到期日当天仍然可用() {
        LocalDateTime expireAt = LocalDateTime.of(2026, 10, 1, 8, 0, 0);
        assertFalse(CouponService.expiredByDay(expireAt, LocalDateTime.of(2026, 10, 1, 9, 0, 0)),
                "到期日当天必须还能用（放宽前这里就已经过期了）");
        assertFalse(CouponService.expiredByDay(expireAt, LocalDateTime.of(2026, 10, 1, 23, 59, 59)));
    }

    @Test
    void 到期日的次日零点起失效() {
        LocalDateTime expireAt = LocalDateTime.of(2026, 10, 1, 8, 0, 0);
        assertTrue(CouponService.expiredByDay(expireAt, LocalDateTime.of(2026, 10, 2, 0, 0, 0)));
    }

    @Test
    void 已过去很久的券依然判为过期() {
        assertTrue(CouponService.expiredByDay(LocalDateTime.of(2026, 9, 1, 0, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0, 0)));
    }

    @Test
    void 反向验证_若改回秒级比较则到期日当天上午就过期() {
        // 这条断言说明放宽到底放宽了什么：秒级口径下，同一张券在到期日 09:00 已经不可用。
        LocalDateTime expireAt = LocalDateTime.of(2026, 10, 1, 8, 0, 0);
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 9, 0, 0);
        assertTrue(expireAt.isBefore(now), "秒级比较下该券已过期 —— 这正是被放宽掉的行为");
        assertFalse(CouponService.expiredByDay(expireAt, now), "按自然日判定则仍可用");
    }
}
