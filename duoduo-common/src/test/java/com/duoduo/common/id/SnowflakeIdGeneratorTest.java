package com.duoduo.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 雪花 ID 单测（第五十八轮 · P2-10，公共域第一批单测）。
 *
 * <h2>为什么第一条断言是「超出 JS 安全整数」</h2>
 * 这是整个前端「ID 必须按字符串流转」约定的<b>根因</b>，也是最容易被忘掉的一条：
 * 雪花 ID 是 18~19 位十进制，超过 JS Number 的 53 位有效精度 ——
 * {@code Number('710000000000000001') === 710000000000000000}，
 * 服务端按错 id 查库 → 404「用户不存在」。后端天然用 long，永远测不到这类缺陷，
 * 所以在这里把「数值大小」钉死：将来谁把位数调小到 JS 能安全承载的范围，
 * 这条会转红，提醒他重新评估那批字符串化代码是否还需要（也别急着删）。
 *
 * <h2>为什么同时断言单调与唯一</h2>
 * 趋势递增是选雪花而非 UUID 的唯一理由（对 MySQL 聚簇索引友好）。
 * 一旦位宽分配被改错导致不递增，现象是「插入性能慢慢变差」，
 * 没人会联想到 ID 生成器 —— 只能靠断言守住。
 */
class SnowflakeIdGeneratorTest {

    /** JS Number 能精确表示的最大整数 */
    private static final long JS_MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);

    @Test
    void 生成的id超出JS安全整数范围_必须按字符串流转() {
        long id = generator.nextId();

        assertThat(id).isGreaterThan(JS_MAX_SAFE_INTEGER);
        assertThat(String.valueOf(id)).hasSizeGreaterThanOrEqualTo(18);
        // 精度损失的现场：在这个量级上，相邻的两个整数已经无法用双精度区分
        // （ulp > 1 意味着「+1」在 double 里会被抹平），这正是 JS Number 存不下雪花 id 的原因
        assertThat(Math.ulp((double) id)).isGreaterThan(1.0);
    }

    @Test
    void 同毫秒内连续生成仍然唯一() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(5000);
    }

    @Test
    void 趋势递增() {
        long previous = generator.nextId();
        for (int i = 0; i < 1000; i++) {
            long current = generator.nextId();
            assertThat(current).isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    void 可以反解出生成时间() {
        long before = System.currentTimeMillis();
        long id = generator.nextId();
        long after = System.currentTimeMillis();

        long extracted = SnowflakeIdGenerator.extractTimestamp(id);
        assertThat(extracted).isBetween(before - 5, after + 5);
    }

    @Test
    void 不同节点编号不会产出相同id() {
        SnowflakeIdGenerator other = new SnowflakeIdGenerator(2, 3);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ids.add(generator.nextId());
            ids.add(other.nextId());
        }
        assertThat(ids).hasSize(400);
    }

    @Test
    void 节点编号越界时拒绝构造() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SnowflakeIdGenerator(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
