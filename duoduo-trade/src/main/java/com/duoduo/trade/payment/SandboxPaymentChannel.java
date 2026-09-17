package com.duoduo.trade.payment;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱渠道：按**真实渠道的形态**实现完整收款流程。
 *
 * <p>它扮演两边的角色：既是"渠道服务器"（生成二维码、发回调、做签名），
 * 也是我们这边的渠道实现（验签、解析）。这样做不是偷懒 ——
 * 而是因为真实渠道**最难写对、也最该先写对**的那一段就是：
 * <b>回调验签 → 防重放 → 幂等入账 → 金额核对</b>。
 * 沙箱把它完整跑通之后，换真实渠道只需要替换"怎么生成二维码"和"怎么验签"两处。
 *
 * <h3>协议（沙箱自定，与真实渠道的差异仅在于签名算法与字段名）</h3>
 * <pre>
 *   POST /api/payments/callback/sandbox
 *   Header: X-Pay-Timestamp: 1789630000
 *           X-Pay-Signature: HMAC-SHA256(secret, canonical) 的十六进制
 *   Body:   {"payNo":"...","channelTradeNo":"...","amount":39700,"status":"SUCCESS","timestamp":"1789630000"}
 *
 *   canonical = payNo + "|" + channelTradeNo + "|" + amount + "|" + status + "|" + timestamp
 * </pre>
 *
 * <p>三条安全约束，任何一条不满足都拒绝：
 * <ol>
 *   <li><b>验签</b>：签名不对 → 401，绝不"验不过就放过"；</li>
 *   <li><b>防重放</b>：时间戳超出 {@code MAX_SKEW_SECONDS} 窗口 → 拒绝
 *       （否则一条旧回调可以被无限次重投）；</li>
 *   <li><b>时间戳一致性</b>：报文里的 timestamp 必须等于请求头里的，
 *       避免"用 A 报文的头 + B 报文的体"这种拼接攻击。</li>
 * </ol>
 *
 * <p>调用方（{@code PaymentService}）还会再核对一遍<b>回调金额与支付单金额是否相等</b>：
 * 签名只证明"这条回调来自渠道"，不证明"金额没被改过"。
 *
 * <p>生产接入真实渠道时，本类会被替换（或并列新增），
 * 而 {@code OrderService} / {@code SettlementService} / 回调处理流程<b>一行都不用改</b>。
 */
@Component
public class SandboxPaymentChannel implements PaymentChannel {

    private static final Logger log = LoggerFactory.getLogger(SandboxPaymentChannel.class);

    public static final String CODE = "SANDBOX";
    /** 签名请求头 */
    public static final String HEADER_SIGNATURE = "X-Pay-Signature";
    public static final String HEADER_TIMESTAMP = "X-Pay-Timestamp";
    /** 时间戳允许的偏移窗口（秒）：超出即视为重放 */
    private static final long MAX_SKEW_SECONDS = 300;
    private static final String ALGORITHM = "HmacSHA256";

    private final String cashierUrl;
    private final String callbackSecret;
    private final int expireMinutes;
    private final ObjectMapper objectMapper;

    public SandboxPaymentChannel(
            @Value("${duoduo.pay.sandbox.cashier-url:http://127.0.0.1:8080/sandbox/pay}") String cashierUrl,
            @Value("${duoduo.pay.sandbox.callback-secret:duoduo-sandbox-pay-secret}") String callbackSecret,
            @Value("${duoduo.pay.sandbox.expire-minutes:15}") int expireMinutes,
            ObjectMapper objectMapper) {
        this.cashierUrl = cashierUrl;
        this.callbackSecret = callbackSecret;
        this.expireMinutes = expireMinutes;
        this.objectMapper = objectMapper;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean instantSuccess() {
        return false;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        // 真实渠道这里返回的是渠道给的二维码串（微信 Native 形如 weixin://wxpay/bizpayurl?pr=xxx），
        // 沙箱返回的是我们自己的收银台地址 —— 形态一致：都是"一个交给前端渲染成二维码的字符串"
        String codeUrl = cashierUrl.endsWith("/")
                ? cashierUrl + command.payNo()
                : cashierUrl + "/" + command.payNo();
        LocalDateTime expireAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(Math.max(1, expireMinutes));
        log.info("沙箱预下单：支付单 {}，金额 {} 分，二维码内容 {}，{} 分钟后过期",
                command.payNo(), command.amount(), codeUrl, expireMinutes);
        return new PrepayResult(codeUrl, expireAt);
    }

    @Override
    public CallbackPayload verifyCallback(String rawBody, Map<String, String> headers) {
        String timestamp = header(headers, HEADER_TIMESTAMP);
        String signature = header(headers, HEADER_SIGNATURE);
        if (timestamp == null || signature == null) {
            throw signatureInvalid("缺少签名请求头（" + HEADER_SIGNATURE + " / " + HEADER_TIMESTAMP + "）");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw signatureInvalid("时间戳格式不正确");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - ts);
        if (skew > MAX_SKEW_SECONDS) {
            throw signatureInvalid("回调时间戳超出允许窗口（" + skew + " 秒 > " + MAX_SKEW_SECONDS + " 秒），疑似重放");
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw signatureInvalid("回调报文无法解析为 JSON");
        }
        String payNo = text(node, "payNo");
        String channelTradeNo = text(node, "channelTradeNo");
        String status = text(node, "status");
        String bodyTimestamp = text(node, "timestamp");
        if (payNo == null || channelTradeNo == null || status == null || bodyTimestamp == null) {
            throw signatureInvalid("回调报文缺少必要字段（payNo / channelTradeNo / status / timestamp）");
        }
        // 头部与报文的时间戳必须一致：否则可以用「A 报文的签名头 + B 报文的体」拼出一条通过验签的假回调
        if (!bodyTimestamp.trim().equals(timestamp.trim())) {
            throw signatureInvalid("请求头与报文中的时间戳不一致");
        }
        if (!node.hasNonNull("amount")) {
            throw signatureInvalid("回调报文缺少金额字段");
        }
        long amount = node.get("amount").asLong();

        String canonical = canonical(payNo, channelTradeNo, amount, status, timestamp);
        String expected = sign(canonical);
        if (!constantTimeEquals(expected, signature.trim())) {
            throw signatureInvalid("签名不匹配");
        }
        return new CallbackPayload(payNo, channelTradeNo, amount, status.trim().toUpperCase(),
                LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 沙箱收银台调用的「渠道侧签名」：构造一条与真实渠道回调**格式完全相同**的报文与请求头。
     *
     * <p>现实里这一步发生在渠道服务器上，我们看不到；沙箱里由我们自己扮演，
     * 所以签名逻辑与 {@link #verifyCallback} 用的是同一份密钥与同一份规则 ——
     * 验签路径因此是**真的被使用**，而不是摆着好看的死代码。
     */
    public Map<String, String> buildSignedCallback(String payNo, String channelTradeNo, long amount) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String status = "SUCCESS";
        String canonical = canonical(payNo, channelTradeNo, amount, status, timestamp);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payNo", payNo);
        body.put("channelTradeNo", channelTradeNo);
        body.put("amount", amount);
        body.put("status", status);
        body.put("timestamp", timestamp);
        Map<String, String> result = new LinkedHashMap<>();
        try {
            result.put("body", objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "沙箱回调报文构造失败", e);
        }
        result.put(HEADER_TIMESTAMP, timestamp);
        result.put(HEADER_SIGNATURE, sign(canonical));
        return result;
    }

    /** 签名串：字段顺序与分隔符都是契约的一部分，改动等于不兼容升级 */
    private static String canonical(String payNo, String channelTradeNo, long amount, String status, String timestamp) {
        return payNo + "|" + channelTradeNo + "|" + amount + "|" + status + "|" + timestamp;
    }

    /** 计算 HMAC-SHA256 十六进制签名。密钥来自环境变量，生产必须覆盖默认值 */
    private String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(callbackSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "支付回调签名计算失败", e);
        }
    }

    /**
     * 定长比较，避免按字节提前返回造成的计时侧信道。
     * 虽然沙箱场景下这个风险很低，但真实渠道的实现必须这么写 —— 这里就把正确写法立起来。
     */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String value = headers.get(name);
        if (value == null) {
            // HTTP 头大小写不敏感，不同容器/Servlet 实现给的键大小写可能不同，这里做一次兼容查找
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BizException signatureInvalid(String reason) {
        return new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "支付回调校验失败：" + reason);
    }
}
