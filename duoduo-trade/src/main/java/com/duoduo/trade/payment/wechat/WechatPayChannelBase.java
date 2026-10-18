package com.duoduo.trade.payment.wechat;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付渠道实现（2026-10-18）。
 *
 * <p>三种产品形态共用这一套代码，仅 URL 与请求体不同：
 * <ul>
 *   <li><b>NATIVE</b>（{@code WECHAT_NATIVE}）：网页端扫码支付，返回 {@code code_url} 交给前端渲染二维码；</li>
 *   <li><b>JSAPI</b>（{@code WECHAT_JSAPI}）：小程序 / 公众号内支付，返回 {@code prepay_id} 交给 {@code wx.requestPayment}；</li>
 *   <li><b>H5</b>（{@code WECHAT_H5}）：手机非微信浏览器，返回 {@code h5_url}，前端跳转即可。</li>
 * </ul>
 *
 * <p>三者返回的东西不同，但落点一致：{@link PrepayResult#codeUrl()} 始终表示
 * 「前端能直接使用的支付入口」（二维码内容 / 跳转 URL），JSAPI 额外给出 {@code prepayId}。
 *
 * <h2>与沙箱的关系</h2>
 * 沙箱（{@code SANDBOX}）与 MOCK<b>原样保留</b>：它们的实现类、配置、收银台页面都未删除。
 * 切换只靠配置 —— {@code duoduo.pay.wechat.enabled} 决定微信渠道是否注册，
 * {@code duoduo.pay.default-channel} 决定前端不传渠道时的默认值。
 *
 * <h2>钱到没到只以回调为准</h2>
 * {@code prepay} 只负责"向微信登记这笔支付"，<b>不会</b>把订单置为已付款；
 * 入账仍由 {@code POST /api/payments/callback/wechat_*} 完成 —— 与沙箱完全同一条路径。
 */
public abstract class WechatPayChannelBase implements PaymentChannel {

    private static final Logger log = LoggerFactory.getLogger(WechatPayChannelBase.class);

    /** 微信支付 API 域名（生产固定值，沙箱环境不可用于真实收款） */
    private static final String API_HOST = "https://api.mch.weixin.qq.com";
    /** 回调时间戳允许的偏移窗口（秒）：超出即视为重放 */
    private static final long MAX_SKEW_SECONDS = 300;

    private static final String HEADER_TIMESTAMP = "wechatpay-timestamp";
    private static final String HEADER_NONCE = "wechatpay-nonce";
    private static final String HEADER_SIGNATURE = "wechatpay-signature";
    private static final String HEADER_SERIAL = "wechatpay-serial";

    protected enum PayType { NATIVE, JSAPI, H5 }

    private final WechatPayConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    protected WechatPayChannelBase(WechatPayConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        config.assertComplete();
    }

    /** 具体产品形态，由子类决定 */
    protected abstract PayType payType();

    @Override
    public boolean instantSuccess() {
        return false;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        config.assertComplete();
        String path = switch (payType()) {
            case NATIVE -> "/v3/pay/transactions/native";
            case JSAPI -> "/v3/pay/transactions/jsapi";
            case H5 -> "/v3/pay/transactions/h5";
        };
        ObjectNode body = objectMapper.createObjectNode();
        body.put("appid", config.appid());
        body.put("mchid", config.mchid());
        body.put("description", command.subject());
        body.put("out_trade_no", command.payNo());
        body.put("time_expire", expireRfc3339());
        body.put("notify_url", config.notifyUrl());
        ObjectNode amount = body.putObject("amount");
        amount.put("total", command.amount());
        amount.put("currency", "CNY");

        if (payType() == PayType.JSAPI) {
            if (command.openId() == null || command.openId().isBlank()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "JSAPI 支付缺少 openid：请先完成微信登录");
            }
            body.putObject("payer").put("openid", command.openId());
        }
        if (payType() == PayType.H5) {
            if (command.clientIp() == null || command.clientIp().isBlank()) {
                // 微信要求 H5 必填 payer_client_ip；没有就用假 IP 蒙过去，只会被风控判为异常交易
                throw new BizException(ErrorCode.VALIDATION_ERROR, "H5 支付缺少客户端 IP（clientIp）");
            }
            ObjectNode scene = body.putObject("scene_info");
            scene.put("payer_client_ip", command.clientIp());
            ObjectNode h5 = scene.putObject("h5_info");
            h5.put("type", "Wap");
            h5.put("wap_url", config.h5SceneWapUrl());
            h5.put("wap_name", config.h5SceneWapName());
        }

        JsonNode resp = post(path, body.toString());
        String codeUrl = null;
        String prepayId = null;
        if (payType() == PayType.NATIVE) {
            codeUrl = text(resp, "code_url");
        } else if (payType() == PayType.JSAPI) {
            prepayId = text(resp, "prepay_id");
            // JSAPI 没有可扫的码；前端需要的是 prepay_id，codeUrl 留空由其自行组装调起参数
            codeUrl = "wechatpay://jsapi/" + command.payNo();
        } else {
            codeUrl = text(resp, "h5_url");
        }
        log.info("微信{}预下单成功：payNo={} amount={}分", payType(), command.payNo(), command.amount());
        return new PrepayResult(codeUrl, prepayId,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(config.expireMinutes()));
    }

    @Override
    public CallbackPayload verifyCallback(String rawBody, Map<String, String> headers) {
        String timestamp = header(headers, HEADER_TIMESTAMP);
        String nonce = header(headers, HEADER_NONCE);
        String signature = header(headers, HEADER_SIGNATURE);
        String serial = header(headers, HEADER_SERIAL);
        if (timestamp == null || nonce == null || signature == null) {
            throw signatureInvalid("缺少微信支付回调签名头（Wechatpay-Timestamp / Nonce / Signature）");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw signatureInvalid("回调时间戳格式不正确");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - ts);
        if (skew > MAX_SKEW_SECONDS) {
            throw signatureInvalid("回调时间戳超出允许窗口（" + skew + " 秒 > " + MAX_SKEW_SECONDS + " 秒），疑似重放");
        }
        if (serial != null && !serial.isBlank()
                && !config.merchantSerialNo().isBlank()
                && !serial.trim().equalsIgnoreCase(config.merchantSerialNo())) {
            // 平台证书轮换后序列号会变；这里只记日志不阻断 —— 阻断会让整批回调失败，
            // 正确性由下面的签名验证兜住（签名用的是平台证书公钥）
            log.warn("回调证书序列号与配置不一致（回调 {} / 配置 {}），若持续出现请更新 platform-cert-pem",
                    serial, config.merchantSerialNo());
        }
        if (!WechatPayCrypto.verify(timestamp, nonce, rawBody, signature, config.platformCertPem())) {
            throw signatureInvalid("微信支付回调验签失败");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw signatureInvalid("回调报文不是合法 JSON");
        }
        JsonNode resource = root.get("resource");
        if (resource == null) {
            throw signatureInvalid("回调报文缺少 resource 节点");
        }
        String plain = WechatPayCrypto.decryptResource(config.apiV3Key(),
                text(resource, "nonce"), text(resource, "ciphertext"), text(resource, "associated_data"));
        JsonNode data;
        try {
            data = objectMapper.readTree(plain);
        } catch (Exception e) {
            throw signatureInvalid("回调 resource 解密后不是合法 JSON");
        }
        String payNo = text(data, "out_trade_no");
        String tradeNo = text(data, "transaction_id");
        String tradeState = text(data, "trade_state");
        JsonNode amountNode = data.get("amount");
        long amount = amountNode == null ? 0L : amountNode.path("total").asLong(0L);
        if (payNo == null || tradeState == null) {
            throw signatureInvalid("回调缺少 out_trade_no / trade_state");
        }
        LocalDateTime paidAt = parsePaidAt(text(data, "success_time"));
        log.info("微信支付回调验签通过：payNo={} tradeNo={} state={} amount={}分", payNo, tradeNo, tradeState, amount);
        return new CallbackPayload(payNo, tradeNo, amount, tradeState, paidAt);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        config.assertComplete();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("out_trade_no", command.payNo());
        body.put("out_refund_no", command.refundNo());
        ObjectNode amount = body.putObject("amount");
        amount.put("refund", command.amount());
        amount.put("total", command.totalAmount());
        amount.put("currency", "CNY");
        if (command.reason() != null && !command.reason().isBlank()) {
            body.put("reason", command.reason());
        }
        JsonNode resp = post("/v3/refund/domestic/refunds", body.toString());
        String status = text(resp, "status");
        log.info("微信退款受理：refundNo={} payNo={} amount={}分 status={}",
                command.refundNo(), command.payNo(), command.amount(), status);
        // SUCCESS / CLOSED 为终态；PROCESSING 需等待退款回调或主动查单
        boolean done = "SUCCESS".equals(status) || "CLOSED".equals(status);
        return new RefundResult(command.refundNo(), text(resp, "refund_id"), status == null ? "" : status, done);
    }

    // ---------------- HTTP ----------------

    private JsonNode post(String path, String bodyJson) {
        long ts = Instant.now().getEpochSecond();
        String nonce = randomNonce();
        String authorization = WechatPayCrypto.authorization("POST", path, ts, nonce, bodyJson,
                config.privateKeyPem(), config.merchantSerialNo());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_HOST + path))
                    .header("Authorization", authorization)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "duoduo-pet-mall")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // 微信的错误体里 message 才是人话；原样带上便于排查（不含任何密钥）
                throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR,
                        "微信支付接口返回 " + status + "：" + response.body());
            }
            String respBody = response.body();
            return respBody == null || respBody.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(respBody);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR, "调用微信支付接口失败：" + e.getMessage());
        }
    }

    // ---------------- 小工具 ----------------

    private static String header(Map<String, String> headers, String lowerName) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(lowerName);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(lowerName)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static LocalDateTime parsePaidAt(String successTime) {
        if (successTime == null || successTime.isBlank()) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(successTime, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    private String expireRfc3339() {
        return OffsetDateTime.now().plusMinutes(config.expireMinutes())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String randomNonce() {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        return Base64.getEncoder().withoutPadding().encodeToString(buf).replaceAll("[^A-Za-z0-9]", "");
    }

    private static BizException signatureInvalid(String message) {
        return new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, message);
    }
}
