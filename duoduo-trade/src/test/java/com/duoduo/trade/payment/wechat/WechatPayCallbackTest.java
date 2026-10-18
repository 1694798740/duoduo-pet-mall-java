package com.duoduo.trade.payment.wechat;

import com.duoduo.common.exception.BizException;
import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信支付回调的验签 / 解密（2026-10-18）。
 *
 * <p>这一段是<b>收钱链路的最后一道门</b>，失效方式是"验签没验住，假回调也入账"——
 * 没有任何报错、日志里只是一笔正常成功的订单，所以对三个方向都钉死：
 * 正确签名能过、改一个字节必须拒、时间戳超窗必须拒。
 *
 * <p>证书与私钥是<b>仓库内自签的测试素材</b>（{@code wechat-test-*.pem}），
 * 只用于本测试，与真实商户凭据无关；真实凭据一律由环境变量注入，不入库。
 */
class WechatPayCallbackTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef"; // 32 字节
    private static final String SERIAL = "TEST-SERIAL-0001";
    private static final String NOTIFY_URL = "https://notify.example.com/api/payments/callback/wechat_native";

    private static String pem(String name) {
        try {
            var res = new ClassPathResource(name);
            return new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取测试证书失败：" + name, e);
        }
    }

    private static final String PRIVATE_KEY = pem("wechat-test-key-pkcs8.pem");
    private static final String PLATFORM_CERT = pem("wechat-test-cert.pem");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestChannel channel() {
        WechatPayConfig config = new WechatPayConfig(true, "wxappid", "1230000109", API_V3_KEY,
                PRIVATE_KEY, SERIAL, PLATFORM_CERT, NOTIFY_URL, "", "多多宠物商店", 15);
        return new TestChannel(config, objectMapper);
    }

    /** 只为了能实例化抽象类：真实注册的三个子类分别是 NATIVE / JSAPI / H5 */
    static class TestChannel extends WechatPayChannelBase {
        TestChannel(WechatPayConfig config, ObjectMapper om) {
            super(config, om);
        }

        @Override
        public String code() {
            return "WECHAT_NATIVE";
        }

        @Override
        protected PayType payType() {
            return PayType.NATIVE;
        }
    }

    /** 构造一份"微信发来的"回调：resource 用 APIv3 密钥加密，头部用商户私钥签名 */
    private Map.Entry<String, Map<String, String>> buildCallback(String payNo, long amount, String tradeState,
                                                                 long timestamp, boolean tamperSignature) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("out_trade_no", payNo);
            data.put("transaction_id", "4200001234567890");
            data.put("trade_state", tradeState);
            data.put("success_time", "2026-10-18T23:00:00+08:00");
            ObjectNode amountNode = data.putObject("amount");
            amountNode.put("total", amount);
            amountNode.put("currency", "CNY");
            String plain = data.toString();

            String nonce = "abcdefghijkl";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            // 微信的 resource 带 associated_data，加密时必须 updateAAD，否则解密端 Tag 校验必然失败
            cipher.updateAAD("transaction".getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            ObjectNode resource = objectMapper.createObjectNode();
            resource.put("algorithm", "AEAD_AES_256_GCM");
            resource.put("nonce", nonce);
            resource.put("ciphertext", Base64.getEncoder().encodeToString(ct));
            resource.put("associated_data", "transaction");
            ObjectNode root = objectMapper.createObjectNode();
            root.put("id", "EV-20261018");
            root.put("event_type", "TRANSACTION.SUCCESS");
            root.set("resource", resource);
            String body = root.toString();

            String nonceHeader = "NONCE123456";
            String message = timestamp + "\n" + nonceHeader + "\n" + body + "\n";
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(WechatPayCrypto.privateKeyFromPem(PRIVATE_KEY));
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            if (tamperSignature) {
                signature = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Wechatpay-Timestamp", String.valueOf(timestamp));
            headers.put("Wechatpay-Nonce", nonceHeader);
            headers.put("Wechatpay-Signature", signature);
            headers.put("Wechatpay-Serial", SERIAL);
            return Map.entry(body, headers);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("签名正确 + 报文未被篡改：解析出支付单号、流水号、金额与状态")
    void 正常回调可通过验签() {
        var cb = buildCallback("PAY-20261018001", 39700L, "SUCCESS", Instant.now().getEpochSecond(), false);
        PaymentChannel.CallbackPayload payload = channel().verifyCallback(cb.getKey(), cb.getValue());
        assertThat(payload.payNo()).isEqualTo("PAY-20261018001");
        assertThat(payload.channelTradeNo()).isEqualTo("4200001234567890");
        assertThat(payload.amount()).isEqualTo(39700L);
        assertThat(payload.status()).isEqualTo("SUCCESS");
        assertThat(payload.paidAt()).isNotNull();
    }

    @Test
    @DisplayName("签名不合法必须拒绝 —— 绝不能验不过就放过")
    void 签名错误被拒绝() {
        var cb = buildCallback("PAY-20261018002", 100L, "SUCCESS", Instant.now().getEpochSecond(), true);
        assertThatThrownBy(() -> channel().verifyCallback(cb.getKey(), cb.getValue()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("验签失败");
    }

    @Test
    @DisplayName("时间戳超出 5 分钟窗口：按重放拒绝")
    void 时间戳超窗被拒绝() {
        long old = Instant.now().getEpochSecond() - 3600;
        var cb = buildCallback("PAY-20261018003", 100L, "SUCCESS", old, false);
        assertThatThrownBy(() -> channel().verifyCallback(cb.getKey(), cb.getValue()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("疑似重放");
    }

    @Test
    @DisplayName("缺少签名头：明确拒绝并点名缺哪个头")
    void 缺少签名头被拒绝() {
        var cb = buildCallback("PAY-20261018004", 100L, "SUCCESS", Instant.now().getEpochSecond(), false);
        Map<String, String> headers = new HashMap<>(cb.getValue());
        headers.remove("Wechatpay-Signature");
        assertThatThrownBy(() -> channel().verifyCallback(cb.getKey(), headers))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("签名头");
    }

    @Test
    @DisplayName("非 SUCCESS 的支付状态原样透出（由调用方决定如何处理，渠道不擅自当成成功）")
    void 非成功状态原样透出() {
        var cb = buildCallback("PAY-20261018005", 100L, "USERPAYING", Instant.now().getEpochSecond(), false);
        PaymentChannel.CallbackPayload payload = channel().verifyCallback(cb.getKey(), cb.getValue());
        assertThat(payload.status()).isEqualTo("USERPAYING");
    }

    @Test
    @DisplayName("APIv3 密钥不匹配时解密失败，必须抛错而不是返回空数据")
    void 密钥不匹配时解密失败() {
        WechatPayConfig bad = new WechatPayConfig(true, "wxappid", "1230000109",
                "ffffffffffffffffffffffffffffffff", PRIVATE_KEY, SERIAL, PLATFORM_CERT, NOTIFY_URL, "", "多多", 15);
        var cb = buildCallback("PAY-20261018006", 100L, "SUCCESS", Instant.now().getEpochSecond(), false);
        assertThatThrownBy(() -> new TestChannel(bad, objectMapper).verifyCallback(cb.getKey(), cb.getValue()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    @DisplayName("配置缺项或回调地址非 https：启动自检直接失败（fail closed，绝不悄悄走模拟渠道）")
    void 配置不完整启动失败() {
        assertThatThrownBy(() -> new WechatPayConfig(true, "", "1230000109", API_V3_KEY,
                PRIVATE_KEY, SERIAL, PLATFORM_CERT, NOTIFY_URL, "", "多多", 15).assertComplete())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appid");
        assertThatThrownBy(() -> new WechatPayConfig(true, "wxappid", "1230000109", API_V3_KEY,
                PRIVATE_KEY, SERIAL, PLATFORM_CERT, "http://example.com/notify", "", "多多", 15).assertComplete())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("未启用时不校验配置（沙箱 / MOCK 场景不受影响）")
    void 未启用时不校验() {
        new WechatPayConfig(false, "", "", "", "", "", "", "", "", "多多", 15).assertComplete();
    }
}
