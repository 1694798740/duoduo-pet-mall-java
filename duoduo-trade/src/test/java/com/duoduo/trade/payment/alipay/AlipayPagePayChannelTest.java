package com.duoduo.trade.payment.alipay;

import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付宝电脑网站支付渠道单测（2026-10-22，按官方 Skill 集成校验要点编写）。
 *
 * <p>用自生成的 RSA 密钥对模拟「沙箱返回的应用密钥对 + 支付宝公钥」：
 * 私钥用于服务端签名（等价 appPrivateKey，PKCS#8），
 * 同一对的公钥作为「支付宝公钥」用于回调验签。
 */
class AlipayPagePayChannelTest {

    private static AlipayPagePayChannel channel;
    private static String privateKeyPem;
    private static String alipayPublicKeyPem;

    private static final String APP_ID = "9021000123456789";

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKeyPem = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        alipayPublicKeyPem = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        AlipayPayConfig config = new AlipayPayConfig(
                true, APP_ID, privateKeyPem, alipayPublicKeyPem,
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do",
                "https://callback.example.com/api/payments/callback/ALIPAY_PC",
                "http://localhost:8088/orders", 15);
        channel = new AlipayPagePayChannel(config, new ObjectMapper());
    }

    /** 从预下单返回的 form HTML 里解析出各 hidden 字段（模拟浏览器提交前的解码） */
    private static Map<String, String> formFields(String formHtml) {
        TreeMap<String, String> fields = new TreeMap<>();
        Matcher input = Pattern.compile(
                "<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\"/>").matcher(formHtml);
        while (input.find()) {
            fields.put(htmlUnescape(input.group(1)), htmlUnescape(input.group(2)));
        }
        return fields;
    }

    private static String htmlUnescape(String v) {
        return v.replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    /** 把字段按 application/x-www-form-urlencoded 编码成回调报文 */
    private static String urlEncoded(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    @Test
    void prepayReturnsPostFormNotUrl() {
        com.duoduo.trade.payment.PaymentChannel.PrepayResult result = channel.prepay(new com.duoduo.trade.payment.PaymentChannel.PrepayCommand(
                "PAY20261022001", "ORD20261022001", 15800L, "多多宠物商店商品"));
        String form = result.codeUrl();
        // Skill 规范：页面跳转类 API 必须是「表单 + hidden inputs」，不能是拼参 URL
        assertTrue(form.startsWith("<form"), "codeUrl 必须是完整 form HTML");
        assertTrue(form.contains("method=\"post\""), "必须是 POST 表单");
        assertTrue(form.contains("action=\"https://openapi-sandbox.dl.alipaydev.com/gateway.do\""),
                "表单 action 必须是网关地址本身，不带查询串");
        assertTrue(form.contains("name=\"biz_content\""), "biz_content 必须作为 hidden 字段");
        assertTrue(form.contains("FAST_INSTANT_TRADE_PAY"), "product_code 固定 FAST_INSTANT_TRADE_PAY");
        assertTrue(formFields(form).get("biz_content").contains("158.00"), "金额必须是「元」两位小数");
        assertTrue(!form.contains("<script"), "不应自带自动提交脚本（由前端控制时机）");
    }

    @Test
    void prepaySignatureVerifiesAgainstFields() {
        com.duoduo.trade.payment.PaymentChannel.PrepayResult result = channel.prepay(new com.duoduo.trade.payment.PaymentChannel.PrepayCommand(
                "PAY20261022002", "ORD20261022002", 100L, "商品"));
        Map<String, String> fields = formFields(result.codeUrl());
        // 用支付宝"公钥"（本项目测试里即同一密钥对的公钥）验签，模拟网关侧校验
        assertTrue(AlipayCrypto.verify(fields, alipayPublicKeyPem), "表单字段必须能通过 RSA2 验签");
        assertEquals(APP_ID, fields.get("app_id"));
        assertEquals("alipay.trade.page.pay", fields.get("method"));
        // 时间戳必须是 yyyy-MM-dd HH:mm:ss（空格分隔，非 ISO）
        assertTrue(fields.get("timestamp").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "时间戳格式必须是 yyyy-MM-dd HH:mm:ss");
    }

    @Test
    void callbackVerifiesAndParsesSuccess() {
        // 模拟支付宝服务器：用"支付宝私钥"签名一份成功通知（测试里同一密钥对）
        TreeMap<String, String> notify = new TreeMap<>();
        notify.put("app_id", APP_ID);
        notify.put("trade_status", "TRADE_SUCCESS");
        notify.put("out_trade_no", "PAY20261022003");
        notify.put("trade_no", "2026102222001400001000001");
        notify.put("total_amount", "158.00");
        notify.put("gmt_payment", "2026-10-22 19:00:00");
        notify.put("sign_type", "RSA2");
        notify.put("sign", AlipayCrypto.sign(notify, privateKeyPem));

        com.duoduo.trade.payment.PaymentChannel.CallbackPayload payload =
                channel.verifyCallback(urlEncoded(notify), Map.of());
        assertNotNull(payload);
        assertEquals("SUCCESS", payload.status());
        assertEquals("PAY20261022003", payload.payNo());
        assertEquals(15800L, payload.amount());
        assertEquals("2026102222001400001000001", payload.channelTradeNo());
    }

    @Test
    void callbackRejectsForeignAppId() {
        TreeMap<String, String> notify = new TreeMap<>();
        notify.put("app_id", "2088000000000000"); // 别人家的 app_id
        notify.put("trade_status", "TRADE_SUCCESS");
        notify.put("out_trade_no", "PAY20261022004");
        notify.put("total_amount", "1.00");
        notify.put("sign_type", "RSA2");
        notify.put("sign", AlipayCrypto.sign(notify, privateKeyPem));
        // 签名本身合法，但 app_id 不是本应用的：必须拒绝（官方规范强制核对项）
        assertThrows(BizException.class, () -> channel.verifyCallback(urlEncoded(notify), Map.of()));
    }

    @Test
    void callbackRejectsTamperedAmount() {
        TreeMap<String, String> notify = new TreeMap<>();
        notify.put("app_id", APP_ID);
        notify.put("trade_status", "TRADE_SUCCESS");
        notify.put("out_trade_no", "PAY20261022005");
        notify.put("total_amount", "0.01");
        notify.put("sign_type", "RSA2");
        notify.put("sign", AlipayCrypto.sign(notify, privateKeyPem));
        String body = urlEncoded(notify).replace("total_amount=0.01", "total_amount=158.00");
        assertThrows(BizException.class, () -> channel.verifyCallback(body, Map.of()));
    }

    @Test
    void nonSuccessTradeStatusIsNotSuccess() {
        TreeMap<String, String> notify = new TreeMap<>();
        notify.put("app_id", APP_ID);
        notify.put("trade_status", "WAIT_BUYER_PAY");
        notify.put("out_trade_no", "PAY20261022006");
        notify.put("total_amount", "158.00");
        notify.put("sign_type", "RSA2");
        notify.put("sign", AlipayCrypto.sign(notify, privateKeyPem));
        com.duoduo.trade.payment.PaymentChannel.CallbackPayload payload =
                channel.verifyCallback(urlEncoded(notify), Map.of());
        assertEquals("PENDING", payload.status());
    }
}
