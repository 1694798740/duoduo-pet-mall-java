package com.duoduo.trade.payment.alipay;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.JsonNode;
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
 * 支付宝「电脑网站支付」渠道（2026-10-22，Web 端；按支付宝官方接入规范校准）。
 *
 * <h2>产品形态（pageExecute POST 等价实现）</h2>
 * {@code alipay.trade.page.pay} 是<b>页面跳转类 API</b>：官方 SDK 用
 * {@code pageExecute(request, "POST")} 生成一段<b>自动提交的 HTML 表单</b>，
 * 由前端渲染并提交到网关 —— <b>禁止拼 URL 直接跳转</b>（大参数场景只会显示参数、无法跳转支付宝）。
 * 本项目为避免引入 alipay-sdk 的传递依赖，按 SDK 的行为自实现了等价的表单生成：
 * 服务端签名后返回完整 form HTML（hidden inputs + 提交按钮），前端注入 DOM 后提交。
 * 签名内容规则与 SDK 完全一致：字典序拼串、剔除 sign/sign_type/空值、SHA256withRSA（RSA2）。
 *
 * <h2>规范要点（来自支付宝官方接入文档）</h2>
 * <ul>
 *   <li>时间戳格式必须 {@code yyyy-MM-dd HH:mm:ss}（非 ISO）；</li>
 *   <li>{@code product_code} 固定 {@code FAST_INSTANT_TRADE_PAY}；</li>
 *   <li>金额单位是「元」（两位小数），不是微信的「分」；</li>
 *   <li>支付结果以异步通知 / 查询接口为准，同步跳转（return_url）不可信；</li>
 *   <li>异步通知必须验签，并核对 {@code app_id / out_trade_no / total_amount}；</li>
 *   <li>未收到异步通知时必须调用 {@code alipay.trade.query} 兜底（见 {@link #queryStatus}）。</li>
 * </ul>
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
        params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        // biz_content 单独作为 JSON；金额单位是「元」（两位小数），分→元换算只发生在这里
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", command.payNo());
        biz.put("total_amount", yuan(command.amount()));
        biz.put("subject", command.subject());
        biz.put("product_code", "FAST_INSTANT_TRADE_PAY");
        params.put("biz_content", toJson(biz));
        params.put("sign", AlipayCrypto.sign(params, config.privateKeyPem()));

        // 与官方 SDK pageExecute(request, "POST") 生成的表单完全同构：
        // 公共参数（含 charset）放在 **action 的 URL 查询串**，body 里只有 biz_content ——
        // 网关强制要求 charset 位于查询串，全部塞进 POST body 会报 invalid-signature
        // （错误说明原文："请确认 charset 参数放在了 URL 查询字符串中"）。
        // 刻意不带 <script> 自动提交脚本：前端（React）负责注入 DOM 并控制提交时机。
        String query = params.entrySet().stream()
                .filter(e -> !"biz_content".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        StringBuilder form = new StringBuilder();
        form.append("<form name=\"punchout_form\" method=\"post\" action=\"")
                .append(htmlEscape(config.gateway()))
                .append("?").append(htmlEscape(query))
                .append("\" accept-charset=\"utf-8\">");
        form.append("<input type=\"hidden\" name=\"biz_content\" value=\"")
                .append(htmlEscape(params.get("biz_content"))).append("\"/>");
        form.append("<input type=\"submit\" value=\"立即支付\" style=\"display:none\">");
        form.append("</form>");
        log.info("支付宝电脑网站支付预下单完成（表单形态）：payNo={} amount={}分", command.payNo(), command.amount());
        return new PrepayResult(form.toString(), null, LocalDateTime.now().plusMinutes(config.expireMinutes()));
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
        // 官方规范：验签通过后必须核对 app_id / out_trade_no / total_amount 是否为本应用订单
        String appId = form.get("app_id");
        if (appId == null || !config.appId().equals(appId)) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                    "回调 app_id 与配置不符（回调 " + appId + "）");
        }
        String payNo = form.get("out_trade_no");
        String tradeNo = form.get("trade_no");
        String tradeStatus = form.get("trade_status");
        if (payNo == null || tradeStatus == null) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "回调缺少 out_trade_no / trade_status");
        }
        // 只有 TRADE_SUCCESS / TRADE_FINISHED 表示钱已到账；其余状态按"未成功"返回，
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

    /**
     * 主动查询渠道侧交易状态（{@code alipay.trade.query}，同步返回 JSON）。
     *
     * <p>官方规范：未收到异步通知时必须用查询接口兜底。补偿调度对 PENDING 支付单
     * 轮询本方法；渠道侧查无此单（{@code ACQ.TRADE_NOT_EXIST}）返回 null 跳过。
     */
    @Override
    public CallbackPayload queryStatus(String payNo) {
        config.assertComplete();
        Map<String, String> params = baseParams();
        params.put("method", "alipay.trade.query");
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", payNo);
        params.put("biz_content", toJson(biz));
        params.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("sign", AlipayCrypto.sign(params, config.privateKeyPem()));

        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        JsonNode resp = httpGetJson(config.gateway() + "?" + query);
        JsonNode body = resp.path("alipay_trade_query_response");
        String code = body.path("code").asText("");
        if ("40004".equals(code) && "ACQ.TRADE_NOT_EXIST".equals(body.path("sub_code").asText(""))) {
            return null; // 渠道侧还没有这笔单（用户还没到收银台），交给下一轮
        }
        if (!"10000".equals(code)) {
            log.warn("支付宝查询接口返回异常：payNo={} code={} msg={}", payNo, code, body.path("msg").asText(""));
            return null;
        }
        String tradeStatus = body.path("trade_status").asText("");
        String tradeNo = body.path("trade_no").asText("");
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            return new CallbackPayload(payNo, tradeNo, 0L, "PENDING", null);
        }
        long amountFen;
        try {
            amountFen = new BigDecimal(body.path("total_amount").asText("0"))
                    .movePointRight(2).longValueExact();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR,
                    "查询返回金额不是合法金额：" + body.path("total_amount").asText());
        }
        LocalDateTime paidAt = parsePaidAt(body.path("gmt_payment").asText(""));
        log.info("支付宝查询确认支付成功：payNo={} tradeNo={} amount={}分", payNo, tradeNo, amountFen);
        return new CallbackPayload(payNo, tradeNo, amountFen, "SUCCESS", paidAt);
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
        // 退款是同步返回 JSON 的接口（exec 语义），不走回调
        JsonNode resp = httpGetJson(config.gateway() + "?" + query);
        JsonNode body = resp.path("alipay_trade_refund_response");
        String code = body.path("code").asText("");
        boolean ok = "10000".equals(code);
        String fundChange = body.path("fund_change").asText("");
        log.info("支付宝退款：refundNo={} payNo={} code={} fundChange={}",
                command.refundNo(), command.payNo(), code, fundChange);
        // 官方规范：fund_change=Y 表示本次产生了真实退款资金流；同步接口成功即终态
        return new RefundResult(command.refundNo(), body.path("trade_no").asText(""),
                ok ? "SUCCESS" : "FAILED", ok);
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

    private JsonNode httpGetJson(String url) {
        try {
            var response = java.net.http.HttpClient.newHttpClient()
                    .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR, "调用支付宝网关失败：" + e.getMessage());
        }
    }

    /** 表单值 HTML 转义：签名后的参数含 base64 的 +/=，必须防属性注入 */
    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
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
