package com.duoduo.common.web;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/**
 * 把 64 位**标识**序列化为 JSON 字符串、同时保留**金额 / 计数**为数字的 Jackson 模块。
 *
 * <h2>问题（T-39）</h2>
 * 本项目的数据库主键是雪花 ID，形如 {@code 361084524781768704} —— 18~19 位十进制数字。
 * 而浏览器 JavaScript 的 {@code number} 是 IEEE-754 双精度浮点，**安全整数上限只有
 * {@code 2^53 - 1 = 9007199254740991}（16 位）**。超过这个范围，值会被静默舍入：
 *
 * <pre>
 *   Number('361084524781768704') === 361084524781768700   // 末位 704 → 700，且不报错
 * </pre>
 *
 * 后果不是"显示得不好看"，而是**功能性错误**：前端把这个已损坏的值拼进 URL，
 * 服务端按 id 查不到记录，接口返回 404。本项目已在真实浏览器自测中复现过两次
 * （A3 售后单、A8 收货地址，均表现为「点按钮 → XX 不存在」）。
 *
 * <h2>为什么不能简单地"所有 Long 都转字符串"</h2>
 * 本项目金额字段（{@code price} / {@code payAmount} / {@code discountAmount} …）
 * 的 Java 类型也是 {@code Long}（共 63 处）。若一刀切，金额会变成字符串
 * {@code "12900"}，前端 {@code formatPrice(price)} 里的 {@code price / 100}
 * 会静默算出 {@code NaN}，把页面金额全打成 "¥NaN" —— 用一个更大的 bug 换掉一个小 bug。
 *
 * <p>实际的量级也支持区分处理：全站金额最大约 219900 分（2199 元），
 * 距 16 位安全上限差 10 个数量级；**只有标识才可能超限**（雪花 ID 现在就是 18 位）。
 * 因此判据是**字段语义**，不是 Java 类型。
 *
 * <h2>本模块的规则</h2>
 * <ul>
 *   <li>{@code Long} / {@code long} / {@code BigInteger} 字段：
 *       <b>默认转字符串</b>（因为绝大多数 Long 字段是 id 或 id 外键）；</li>
 *   <li>但若字段名命中 {@link #NUMERIC_LONG_FIELDS}（金额、计数、时间戳类），
 *       <b>保持数字</b>。</li>
 * </ul>
 *
 * <p>这样"漏标"的方向是安全的：新加一个 id 字段**不需要做任何事**就受保护；
 * 万一将来新加了一个"大到超限的 Long 计价字段"（例如把金额单位换成厘），
 * 那需要的是一个显式的决定 —— 往白名单里加一行，而不是悄悄失效。
 *
 * <h2>反序列化不受影响</h2>
 * JSON 字符串 {@code "123"} 仍能正常转成 Long 字段；{@code @PathVariable Long}
 * 由 Spring 的转换器处理，与 Jackson 无关。因此入参无需任何改动。
 */
public class LongToStringJacksonModule extends SimpleModule {

    /**
     * 需要**保持 JSON 数字**的 Long 字段名（按字段名精确匹配，大小写不敏感）。
     *
     * <p>判据：这些字段承载的是"可以被运算的量"（钱、件数、时长），
     * 而不是"用来指代某个实体的标识"。标识绝不该参与算术 ——
     * 对 id 做加法本身就是一个 bug。
     *
     * <p>若新增的此类字段没有列在这里，它会被当成标识转成字符串。
     * 加字段时请顺手对照：**这个值前端会拿去做加减/乘除/比大小吗？**
     * 会 → 加进本集合。
     */
    private static final Set<String> NUMERIC_LONG_FIELDS = Set.copyOf(java.util.Arrays.asList(
            // —— 金额（单位：分）——
            "price", "originalPrice", "minPrice", "maxPrice", "marketPrice",
            "amount", "totalAmount", "payAmount", "unitAmount", "itemAmount",
            "discountAmount", "thresholdAmount", "refundAmount",
            "freight", "freightAmount", "shippingFee",
            "settleAmount", "settlementAmount", "sellerAmount", "platformAmount",
            "balance", "paidAmount", "receivableAmount", "backfillAmount",
            // 第十七轮补齐：这些金额字段此前**漏列**，被当成标识转成了字符串
            // （前端 `types.ts` 早已把它们声明为 number → 类型与实况不符）。
            // 在 JS 里 `"12900" / 100` 有隐式转换所以"看着正常"，但 `+` 会变成字符串拼接、
            // `===` 恒 false、`.toFixed()` 直接抛错 —— 属 T-40 同一类缺陷，一并根治。
            "gmv", "totalPayAmount", "orderAmount", "selectedAmount", "bestDiscountAmount",
            "custodyAmount", "custodyFee", "custodySellerAmount",
            "releasedAmount", "releasedFee", "releasedSellerAmount", "refundedAmount",
            "platformFee",
            // —— 计数 / 度量 ——
            "stock", "frozenStock", "availableStock",
            "claimedCount", "totalCount", "remainingCount", "usedCount",
            "salesCount", "viewCount", "ratingCount", "imageCount", "appendCount",
            "itemCount", "totalQuantity", "selectedCount", "orderCount",
            "pendingPaymentCount", "pendingShipCount", "shippedCount",
            "completedCount", "refundedCount", "closedCount", "totalOrderCount",
            // 第四十四轮补齐：分享圈互动计数（信息流展示 + 前端 toggle 后直接回填比较）
            "likeCount", "favoriteCount", "count",
            "closeMinutes", "expireMinutes", "durationMinutes", "expiresInSeconds",
            // 第十七轮补齐：**分页总数**。此前漏列导致 `PageResult.total` 以字符串下发，
            // 于是 `smoke.ps1` 的 `$p.total -ge 3` 在 PowerShell 里退化成**字典序比较**
            // （"15" -ge "3" 为 False）而恒失败 —— 一个类型错误伪装成业务失败。
            "total",
            // —— 时间戳（epoch 毫秒，前端要 new Date(ts) 参与运算）——
            "timestamp", "createdAtMs", "updatedAtMs", "expireAtMs"
    ));

    public LongToStringJacksonModule() {
        super("duoduo-long-to-string");
        addSerializer(Long.class, new IdToStringSerializer());
        addSerializer(Long.TYPE, new IdToStringSerializer());
        addSerializer(BigInteger.class, new IdToStringSerializer());
        // 用 BeanSerializerModifier 按**字段名**逐个决定，而不是按类型一刀切
        setSerializerModifier(new NumericFieldPreservingModifier());
    }

    /** 默认策略：Long 一律按字符串输出（标识安全） */
    private static final class IdToStringSerializer extends JsonSerializer<Object> {        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.toString());
        }
    }

    /**
     * 显式「按数字输出」的 Long 序列化器（第五十轮新增）。
     *
     * <p>用途：Java {@code record} 组件的计数字段（如 {@code UnreadCount.count}）——
     * 白名单修饰器对 record 不生效（见 {@code NumericFieldPreservingModifier} 局限说明），
     * 需要在字段上直接标注本序列化器，让计数保持 JSON 数字，与
     * {@code PageResult.total} 等普通 POJO 字段的类型口径一致。
     *
     * <pre>
     * public record UnreadCount(
     *     &#64;JsonSerialize(using = LongToStringJacksonModule.NumericLongSerializer.class)
     *     long count) {}
     * </pre>
     */
    public static final class NumericLongSerializer extends JsonSerializer<Object> {
        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            if (value instanceof BigInteger big) {
                gen.writeNumber(big);
            } else {
                gen.writeNumber(((Number) value).longValue());
            }
        }
    }

    /**
     * 把命中 {@link #NUMERIC_LONG_FIELDS} 的 Long 字段改回"按数字输出"。
     *
     * <p>Jackson 的序列化器按 Java 类型注册在全局，无法在中途按字段回退；
     * 因此这里用 {@code BeanSerializerModifier} 在建序列化器阶段
     * 把该类字段的 writer **替换成标准版**（{@code BeanPropertyWriter} 默认行为
     * 即写数字），从而覆盖掉全局的 ToString 序列化器。
     *
     * <p><b>已知局限（第五十轮补记）</b>：本机制只对**普通 POJO** 生效 ——
     * Java {@code record} 的属性由 {@code POJOPropertiesCollector} 的 record 分支
     * 直接构造，不流经 {@code changeProperties}，白名单对其失效，record 组件里的
     * {@code long} 会被当标识转成字符串（如 {@code UnreadCount.count} 下发 {@code "3"}，
     * 而同一接口族的 {@code PageResult.total} 是数字 {@code 3} —— 同语义两种类型）。
     * 因此 record 组件里的计数 / 金额字段必须显式标注
     * {@code @JsonSerialize(using = NumericLongSerializer.class)}。
     */
    private static final class NumericFieldPreservingModifier extends BeanSerializerModifier {

        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                        BeanDescription beanDesc,
                                                        List<BeanPropertyWriter> beanProperties) {
            for (int i = 0; i < beanProperties.size(); i++) {
                BeanPropertyWriter writer = beanProperties.get(i);
                Class<?> type = writer.getType().getRawClass();
                boolean isLongLike = type == Long.class || type == Long.TYPE || type == BigInteger.class;
                if (isLongLike && isNumericField(writer.getName())) {
                    // 用不带自定义序列化器的 writer 覆盖 → 回落为默认的数值输出
                    beanProperties.set(i, new NumericLongPropertyWriter(writer));
                }
            }
            return beanProperties;
        }

        private static boolean isNumericField(String name) {
            if (NUMERIC_LONG_FIELDS.contains(name)) {
                return true;
            }
            // 容忍大小写与常见前缀（如 skuPrice / discountAmount 已直接命中，这里兜一层）
            for (String candidate : NUMERIC_LONG_FIELDS) {
                if (candidate.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 与普通 {@code BeanPropertyWriter} 的唯一差别：序列化时用**默认数值输出**，
     * 绕过挂在 {@code Long.class} 上的 ToString 序列化器。
     */
    private static final class NumericLongPropertyWriter extends BeanPropertyWriter {

        NumericLongPropertyWriter(BeanPropertyWriter base) {
            super(base);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov)
                throws Exception {
            Object value = get(bean);
            if (value == null) {
                if (_nullSerializer != null) {
                    gen.writeFieldName(_name);
                    _nullSerializer.serialize(null, gen, prov);
                }
                return;
            }
            gen.writeFieldName(_name);
            if (value instanceof BigInteger big) {
                gen.writeNumber(big);
            } else {
                gen.writeNumber(((Number) value).longValue());
            }
        }
    }
}
