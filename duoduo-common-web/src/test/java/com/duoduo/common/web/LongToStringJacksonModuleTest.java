package com.duoduo.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LongToStringJacksonModule} 的口径回归。
 *
 * <h2>为什么必须补这一个测试</h2>
 * 这个模块有一条**只靠肉眼无法确认**的规则：{@code Long} 默认转字符串，
 * 但字段名命中 {@code NUMERIC_LONG_FIELDS} 时保持数字。而「字段名白名单」
 * 是靠 {@code BeanSerializerModifier.changeProperties} 实现的，源码注释里
 * 一直写着「**record 组件不流经 changeProperties，白名单对其失效**」——
 * 也就是说按注释推理，record 里的金额会被字符串化。
 *
 * <p>但集成冒烟（{@code scripts/id-precision-smoke.ps1}，56/0）里那些
 * 「金额必须是 JSON 数字」的断言，对象恰恰是 record
 * （{@code OrderView.payAmount} / {@code TemplateView.thresholdAmount} …），
 * 而且**全绿**。两处结论互相矛盾，而这个矛盾的方向很危险：
 * <ul>
 *   <li>若注释对 → 全站有一批 record 的金额正在以字符串下发，前端
 *       {@code +} 会变成字符串拼接、{@code ===} 恒 false；</li>
 *   <li>若冒烟对 → 不该照着注释去给每个 record 字段补注解（会误导后来者），
 *       而且「漏标方向是安全的」这条设计前提才成立。</li>
 * </ul>
 * 这个级别的结论不能靠读注释或读日志推理出来 —— 直接把两种形态各序列化一次，
 * 看它到底吐出什么。
 *
 * <p>本测试**同时钉住**普通类与 record 两种形态，因为两者走的是 Jackson 的不同分支；
 * 只测其中一种，另一种的回归就无人看守。
 */
class LongToStringJacksonModuleTest {

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new LongToStringJacksonModule());
        return m;
    }

    // ---------- 被测形态 ----------

    /** 普通 POJO：{@code changeProperties} 肯定会被调用的一条路径。 */
    public static class PojoView {
        private final long payAmount;   // 命中白名单（金额）
        private final long someNumber;  // 未命中白名单
        private final Long spuId;       // 标识

        PojoView(long payAmount, long someNumber, Long spuId) {
            this.payAmount = payAmount;
            this.someNumber = someNumber;
            this.spuId = spuId;
        }

        public long getPayAmount() {
            return payAmount;
        }

        public long getSomeNumber() {
            return someNumber;
        }

        public Long getSpuId() {
            return spuId;
        }
    }

    /** record：源码注释声称「不流经 changeProperties」的那条路径。 */
    public record RecordView(long payAmount, long someNumber, Long spuId) {
    }

    /** record + 显式标注（既有 34 处的写法）：无论机制如何都必须是数字。 */
    public record AnnotatedRecordView(
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = LongToStringJacksonModule.NumericLongSerializer.class) long payAmount) {
    }

    private static final long AMOUNT = 12900L;
    private static final Long SPU_ID = 361084524781768704L;

    // ---------- 1. 普通类 ----------

    @Test
    @DisplayName("普通类：金额类字段名保持数字、标识仍转字符串")
    void pojoFollowsFieldNameWhitelist() throws Exception {
        String json = mapper().writeValueAsString(new PojoView(AMOUNT, 7L, SPU_ID));

        assertThat(json).contains("\"payAmount\":" + AMOUNT);
        assertThat(json).doesNotContain("\"payAmount\":\"");
        // 标识必须带引号且**逐字符不变**（末位不能被舍入）
        assertThat(json).contains("\"spuId\":\"" + SPU_ID + "\"");
        // 未命中白名单的 long 走默认策略 = 当标识处理 → 字符串
        assertThat(json).contains("\"someNumber\":\"7\"");
    }

    // ---------- 2. record ----------

    /**
     * ★ 本条是**结论性**断言，用来终止「record 到底走不走白名单」这场争论。
     *
     * <p>如果它红了，说明源码注释是对的、集成冒烟的绿是别的原因造成的，
     * 那就应当：① 给所有下发到前端的 record 金额/计数字段补注解；
     * ② 在冒烟里补一条能稳定拦住这类回归的断言。
     *
     * <p>如果它绿了，说明**字段名白名单对 record 同样生效**，
     * 源码里那句「record 必须逐个标注」的注释就该改掉 ——
     * 否则后来者会照着它做无用的补标注，并在真正的漏标发生时失去警觉。
     */
    @Test
    @DisplayName("★ record：与普通类行为一致（金额按字段名保持数字、标识转字符串）")
    void recordFollowsFieldNameWhitelist() throws Exception {
        String json = mapper().writeValueAsString(new RecordView(AMOUNT, 7L, SPU_ID));

        assertThat(json).contains("\"payAmount\":" + AMOUNT);
        assertThat(json).doesNotContain("\"payAmount\":\"");
        assertThat(json).contains("\"spuId\":\"" + SPU_ID + "\"");
        assertThat(json).contains("\"someNumber\":\"7\"");
    }

    // ---------- 3. 显式标注是「保险丝」，两种形态下都必须生效 ----------

    @Test
    @DisplayName("显式标注 NumericLongSerializer：普通类与 record 下都是数字")
    void explicitAnnotationWinsInBothShapes() throws Exception {
        String recordJson = mapper().writeValueAsString(new AnnotatedRecordView(AMOUNT));
        assertThat(recordJson).contains("\"payAmount\":" + AMOUNT);
        assertThat(recordJson).doesNotContain("\"payAmount\":\"");
    }

    // ---------- 4. 越界防护：白名单之外的金额字段名会被字符串化 ----------

    /**
     * 这不是「缺陷」，而是设计上**被明说的限制**：白名单按字段名精确匹配，
     * 所以一个承载金额但名字不在清单里的字段（例如新写的 {@code extraFee}）
     * 会被当作标识转成字符串，且**不报任何错**。
     *
     * <p>钉住它的意义在于：将来有人往白名单里加字段，这条会一起变；而如果他
     * 「顺手把白名单改成按类型一刀切」，这条也会红 —— 提醒他那会把全站 id 一起放开。
     */
    @Test
    @DisplayName("未登记名字的 long 一律按标识转字符串（漏标方向是安全的，但要知道它会发生）")
    void unlistedNameIsStringified() throws Exception {
        String json = mapper().writeValueAsString(new RecordView(AMOUNT, 7L, SPU_ID));
        assertThat(json).contains("\"someNumber\":\"7\"");
    }
}
