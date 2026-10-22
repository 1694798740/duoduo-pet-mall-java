package com.duoduo.trade.payment.alipay;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝「电脑网站支付」渠道（2026-10-22，Web 端）。
 *
 * <h2>产品形态</h2>
 * {@code alipay.trade.page.pay}：预下单不调 HTTP 接口，而是把签名后的参数拼在
 * <b>支付宝收银台 URL</b> 上交给前端，前端整页跳转（或二维码扫码）进入支付宝付款；
 * 支付结果由支付宝 {@code notify_url} 异步 POST 表单回调 —— 与微信 Native 的
 * 「预下单拿 code_url + 服务端回调入账」形态完全同构，所以能装进同一个
 * {@link PaymentChannel} 骨架：{@code codeUrl} = 收银台 URL。
 *
 * <h2>钱到没到只以回调为准</h2>
 * 预下单只是生成跳转链接，订单入账仍由 {@code POST /api/payments/callback/ALIPAY_PC}
 * 完成（网关白名单 {@code /api/payments/callback/**} 已覆盖）。
 * 回调是 {@code application/x-www-form-urlencoded} 表单，验签用支付宝公钥 RSA2；
 * 验签失败必须抛错，重放防护依赖「支付单状态 CAS + 金额核对」而不是时间戳
 * （支付宝 notify 无时间戳头，幂等由库内支付单状态机兜住）。
 *
 * <h2>沙箱 / 生产</h2>
 * 代码零差别，只换 {@code duoduo.pay.alipay.gateway} 与密钥：
 * 沙箱 {@code openapi-sandbox.dl.alipaydev.com}，生产 {@code openapi.alipay.com}。
 */
@Component
@ConditionalOnProperty(prefix = "duoduo.pay.alipay", name = "enabled", havingValue = "true")
public class AlipayPagePayChannel implements PaymentChannel {

    public static final String CODE = "ALIPAY_PC";

    private static final Logger log = LoggerFactory.getLogger(AlipayPagePayChannel.class);

    private final AlipayPayConfig config;
    private final ObjectMapper objectMapper;

    public AlipayPagePayChannel(AlipayPayConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        config.assertComplete();
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
        config.assertComplete();
        Map<String, String> params = baseParams();
        params.put("method", "alipay.trade.page.pay");
        params.put("return_url", config.returnUrl());
        params.put("notify_url", config.notifyUrl());
        // biz_content 单独作为 JSON：这是支付宝的约定，金额单位是「元」（两位小数字符串），
        // 而不是微信的「分」—— 单位换算错一位就是 100 倍差异，写在这里做显式换算。
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.payNo());
        biz.put("total_amount", yuan(command.amount()));
        biz.put("subject", command.subject());
        biz.put("product_code", "FAST_INSTANT_TRADE_PAY");
        params.put("biz_content", toJson(biz));
        params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("sign", AlipayCrypto.sign(params, config.privateKeyPem()));

        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        String cashierUrl = config.gateway() + "?" + query;
        log.info("支付宝电脑网站支付预下单完成：payNo={} amount={}分", command.payNo(), command.amount());
        return new PrepayResult(cashierUrl, null, LocalDateTime.now().plusMinutes(config.expireMinutes()));
    }

    @Override
    public CallbackPayload verifyCallback(String rawBody, Map<String, String> headers) {
        TreeMap<String, String> form = new TreeMap<>();
        for (String pair : rawBody.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, eq));
            String value = urlDecode(pair.substring(eq + 1));
            form.merge(key, value, (a, b) -> b); // 同名多值取最后一个，与 servlet 参数语义一致
        }
        if (!AlipayCrypto.verify(form, config.alipayPublicKeyPem())) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "支付宝回调验签失败");
        }
        String payNo = form.get("out_trade_no");
        String tradeNo = form.get("trade_no");
        String tradeStatus = form.get("trade_status");
        if (payNo == null || tradeStatus == null) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "回调缺少 out_trade_no / trade_status");
        }
        // 只有这两态表示钱已到账；WAIT_BUYER_PAY 等状态一律按"未成功"拒绝入账，
        // 让上层状态机保持 PENDING，等真正的成功回调再来
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            log.info("支付宝回调非成功状态，不入账：payNo={} status={}", payNo, tradeStatus);
            return new CallbackPayload(payNo, tradeNo == null ? "" : tradeNo, 0L, "PENDING", null);
        }
        long amountFen;
        try {
            amountFen = new BigDecimal(form.getOrDefault("total_amount", "0"))
                    .movePointRight(2).longValueExact();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                    "回调金额不是合法金额：" + form.get("total_amount"));
        }
        LocalDateTime paidAt = parsePaidAt(form.get("gmt_payment"));
        log.info("支付宝回调验签通过：payNo={} tradeNo={} amount={}分", payNo, tradeNo, amountFen);
        return new CallbackPayload(payNo, tradeNo == null ? "" : tradeNo, amountFen, "SUCCESS", paidAt);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        config.assertComplete();
        Map<String, String> params = baseParams();
        params.put("method", "alipay.trade.refund");
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.payNo());
        biz.put("refund_amount", yuan(command.amount()));
        biz.put("out_request_no", command.refundNo());
        if (command.reason() != null && !command.reason().isBlank()) {
            biz.put("refund_reason", command.reason());
        }
        params.put("biz_content", toJson(biz));
        params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("sign", AlipayCrypto.sign(params, config.privateKeyPem()));

        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        // 退款是同步返回 JSON 的接口（GET 网关），不走回调
        com.fasterxml.jackson.databind.JsonNode resp = httpGetJson(config.gateway() + "?" + query);
        String code = resp.path("alipay_trade_refund_response").path("code").asText("");
        boolean ok = "10000".equals(code);
        String fundChange = resp.path("alipay_trade_refund_response").path("fund_change").asText("");
        log.info("支付宝退款：refundNo={} payNo={} code={} fundChange={}",
                command.refundNo(), command.payNo(), code, fundChange);
        // fund_change=Y 表示本次产生了真实退款资金流；同步接口成功即终态
        return new RefundResult(command.refundNo(),
                resp.path("alipay_trade_refund_response").path("trade_no").asText(""),
                ok ? ("Y".equals(fundChange) ? "SUCCESS" : "SUCCESS") : "FAILED", ok);
    }

    // ---------------- 小工具 ----------------

    private Map<String, String> baseParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", config.appId());
        params.put("format", "JSON");
        params.put("charset", "utf-8");
        params.put("sign_type", "RSA2");
        params.put("version", "1.0");
        return params;
    }

    /** 分 → 元（两位小数），唯一允许出现金额单位换算的地方 */
    private static String yuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).toPlainString();
    }

    private String toJson(Object node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR, "biz_content 序列化失败：" + e.getMessage());
        }
    }

    private com.fasterxml.jackson.databind.JsonNode httpGetJson(String url) {
        try {
            var response = java.net.http.HttpClient.newHttpClient()
                    .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR, "调用支付宝网关失败：" + e.getMessage());
        }
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static LocalDateTime parsePaidAt(String gmtPayment) {
        if (gmtPayment == null || gmtPayment.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(gmtPayment, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
