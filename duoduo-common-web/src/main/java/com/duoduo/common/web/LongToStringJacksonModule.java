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
            // 2026-10-19：秒杀价。此前漏列 ⇒ 以字符串 "100" 下发，而前端 types 声明为 number：
            // 全站商品卡要拿它跟原价做比较、算折扣、参与 formatPrice 运算，字符串会静默出错。
            "seckillPrice",
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
            // A-17：商品曝光 / 点击是**被运算的量**（点击率 = 点击/曝光，端侧还要累加），
            // 漏列会让它们以字符串下发，`+` 退化成拼接、`===` 与 `>` 全部失真。
            "exposure", "clicks", "totalExposure", "totalClicks",
            "pendingPaymentCount", "pendingShipCount", "shippedCount",
            "completedCount", "refundedCount", "closedCount", "totalOrderCount",
            // 第四十四轮补齐：分享圈互动计数（信息流展示 + 前端 toggle 后直接回填比较）
            "likeCount", "favoriteCount", "count",
            // 第五十六轮补齐：分享圈评论数 / 评论回复数 / 话题被使用次数。
            // 这三个字段是 R56 新增的，**漏列的后果当场就被探针抓到了**：
            // `commentCount` / `shareCount` / `replyCount` 以字符串下发，
            // 前端 `count > 0` 之类的比较退化成字典序（"10" < "9"），
            // 而「话题按使用次数排序」更是直接排错。规律很稳定 ——
            // **新增任何「前端会拿去比大小 / 加减」的计数字段，必须同时加进本集合**。
            "commentCount", "replyCount", "shareCount",
            "closeMinutes", "expireMinutes", "durationMinutes", "expiresInSeconds",
            // 第七十六轮补齐：E15 资质剩余天数。这是**度量**不是标识 ——
            // 前端 types.ts 早已声明为 number，界面要显示「还剩 N 天」、将来还要按它排序
            // 与判断紧急程度。漏列的后果与上面 commentCount 同型：以字符串下发，
            // `remaining > 7` 退化成字典序比较（"10" > "7" 为 false，即"只剩 10 天"被当成不紧急）。
            "licenseRemainingDays",
            // 第十七轮补齐：**分页总数**。此前漏列导致 `PageResult.total` 以字符串下发，
            // 于是 `smoke.ps1` 的 `$p.total -ge 3` 在 PowerShell 里退化成**字典序比较**
            // （"15" -ge "3" 为 False）而恒失败 —— 一个类型错误伪装成业务失败。
            "total",
            // 第六十六轮补齐：图表切片 `AnalyticsDtos.Slice(name, long value)`。
            // 图表数值一律是「会被运算的量」—— ECharts 要拿它算最大值、占比、柱高，
            // 字符串下去直接画不出图（实测：`"value":"144"` 会被当成类目名而非数值）。
            //
            // ⚠️ `value` 是个很泛的名字，与「漏标方向是安全的」这条默认相反：
            // 将来若真出现一个**标识**字段叫 `value`（雪花 id），它会被这里误伤为数字。
            // 届时请把那个字段改名（如 `xxxId`），**不要**把本行删掉 ——
            // 删掉会让所有图表一起失效。全仓当前仅此一处 Long 名为 value
            // （其余同名项都是方法参数，与序列化无关）。
            "value",
            // —— 时间戳（epoch 毫秒，前端要 new Date(ts) 参与运算）——
            "timestamp", "createdAtMs", "updatedAtMs", "expireAtMs",
            // E24（2026-10-12）租户健康度面板：**每个字段都是前端要排序 / 比大小 / 相除的量**，
            // 一个都不能漏。漏了不会报错，只会以字符串下发 ——
            // `debtOutstandingAmount` 变成 `"12900"` 时前端 `+` 会退化成拼接、
            // `>=` 退化成字典序（"9" > "100"），而页面上数字看着"差不多是对的"。
            // ★ 特别注意 `debtOutstandingAmount` / `staleCustodyAmount`：
            //   白名单是**按字段名精确匹配**的，`amount` 命中不了 `xxxAmount` ——
            //   这正是铁律里那条「语义是金额但名字没进白名单 → 静默变字符串」的典型场景。
            "paidCount",
            "afterSaleCount", "afterSaleRefundedCount", "afterSaleOpenCount", "afterSaleAdverseCount",
            "debtOutstandingCount", "debtOutstandingAmount",
            "staleCustodyCount", "staleCustodyAmount",
            "missingSettlementCount", "settlementAbnormalCount",
            "apiTotal", "apiServerError", "apiClientError",
            "tenantCount", "riskyTenants", "watchTenants"
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
     * <p>用途：把某个字段**强制**按 JSON 数字输出，常用于「字段名不在白名单里、
     * 但语义上确实是要参与运算的量」这种个别情况。
     *
     * <p><b>★ 不再是为了 record 而加</b>：本类此前写的是「白名单修饰器对 record 不生效，
     * 因此 record 的计数/金额字段必须逐个标注」。该结论**在 2026-10-11 被实测推翻**
     * （见 {@code LongToStringJacksonModuleTest}，4/4 通过：同一个序列化器下，
     * record 与普通类的行为**完全一致** —— 字段名命中白名单即数字、否则转字符串）。
     * 所以给 record 加注解是**可选的保险丝**，不是必需的补偿；
     * 新增金额/计数类字段的正确做法是**把它加进 {@link #NUMERIC_LONG_FIELDS}**
     * （按名字生效，对两种形态都管用），而不是给某个 record 单独打补丁。
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
     * <p><b>适用范围（2026-10-11 实测更正）</b>：本机制对**普通 POJO 与 {@code record}
     * 同样生效**。
     *
     * <p>这里原先写着「本机制只对普通 POJO 生效 —— record 的属性由
     * {@code POJOPropertiesCollector} 的 record 分支直接构造，不流经
     * {@code changeProperties}，白名单对其失效」。该说法**已被实验推翻**：
     * {@code LongToStringJacksonModuleTest} 把「普通类」与「record」两种形态用同一个
     * ObjectMapper 各序列化一次，四类断言全过 —— 两者的行为完全一致
     * （命中白名单的 {@code payAmount} 是数字、未命中的 {@code someNumber} 与标识
     * {@code spuId} 是字符串）。集成侧也有旁证：{@code scripts/id-precision-smoke.ps1}
     * 里那些「金额必须是 JSON 数字」的断言，被测对象恰恰是 record
     * （{@code OrderView.payAmount} / {@code TemplateView.thresholdAmount}），全绿。
     *
     * <p><b>为什么这条注释必须改掉</b>：按旧说法，后来者会得出「全站一批 record 的金额
     * 正在以字符串下发」的错误结论（转而做大批无用的补标注），并且**在真正的漏标发生时
     * 失去警觉** —— 真正会漏的是「语义是金额、但**名字不在白名单里**」的新字段
     * （例如新写的 {@code extraFee}），它与「哪个类承载它」无关。
     * 判据始终是**字段名**，一视同仁。
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
