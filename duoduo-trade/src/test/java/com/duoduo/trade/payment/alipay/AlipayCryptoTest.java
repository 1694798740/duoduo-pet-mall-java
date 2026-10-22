package com.duoduo.trade.payment.alipay;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付宝 RSA2 签名/验签单元测试（2026-10-22）。
 *
 * <p>用自生成的 RSA 密钥对代替真实支付宝密钥：
 * 签名规则与密钥长度无关，2048 位测试密钥即可完整验证「签名→验签」闭环，
 * 以及改动任意字段、删除签名、换公钥都必须验签失败这三条反向边界。
 */
class AlipayCryptoTest {

    private static String privateKeyPem;
    private static String publicKeyPem;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKeyPem = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        publicKeyPem = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private static Map<String, String> notifyParams() {
        Map<String, String> params = new HashMap<>();
        params.put("app_id", "2021000100000000");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("out_trade_no", "PAY20261022001");
        params.put("trade_no", "2026102222001400001000001");
        params.put("total_amount", "158.00");
        params.put("gmt_payment", "2026-10-22 18:00:00");
        // 故意乱序 + 带空值 + 带 sign_type：拼串必须剔除空值、按字典序排
        params.put("charset", "utf-8");
        params.put("foo_empty", "");
        params.put("sign_type", "RSA2");
        return params;
    }

    @Test
    void signThenVerifyRoundTrip() {
        Map<String, String> params = notifyParams();
        params.put("sign", AlipayCrypto.sign(params, privateKeyPem));
        assertTrue(AlipayCrypto.verify(params, publicKeyPem), "正常签名必须验签通过");
    }

    @Test
    void tamperedFieldMustFail() {
        Map<String, String> params = notifyParams();
        params.put("sign", AlipayCrypto.sign(params, privateKeyPem));
        params.put("total_amount", "1.00"); // 篡改金额：最经典的回调攻击
        assertFalse(AlipayCrypto.verify(params, publicKeyPem), "任何字段被篡改都必须验签失败");
    }

    @Test
    void missingSignMustFail() {
        Map<String, String> params = notifyParams();
        assertFalse(AlipayCrypto.verify(params, publicKeyPem), "缺少 sign 必须验签失败");
    }

    @Test
    void wrongPublicKeyMustFail() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String otherKey = Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        Map<String, String> params = notifyParams();
        params.put("sign", AlipayCrypto.sign(params, privateKeyPem));
        assertNotEquals(otherKey, publicKeyPem);
        assertFalse(AlipayCrypto.verify(params, otherKey), "用别的公钥必须验签失败");
    }

    @Test
    void signContentExcludesSignAndEmptyValues() {
        Map<String, String> params = notifyParams();
        String content = AlipayCrypto.signContent(params);
        assertFalse(content.contains("sign_type="), "sign_type 不参与签名");
        assertFalse(content.contains("foo_empty="), "空值不参与签名");
        assertTrue(content.startsWith("app_id=2021000100000000"), "必须按字典序排列（app_id 最前）");
        assertEquals(-1, content.indexOf("\n"), "签名文本不允许换行");
    }

    @Test
    void pemWrapperTolerated() {
        // 平台展示的公钥可能带 BEGIN/END 头：剥头逻辑必须兼容
        String wrapped = "-----BEGIN PUBLIC KEY-----\n" + publicKeyPem + "\n-----END PUBLIC KEY-----";
        Map<String, String> params = notifyParams();
        params.put("sign", AlipayCrypto.sign(params, privateKeyPem));
        assertTrue(AlipayCrypto.verify(params, wrapped), "带 PEM 头的公钥必须也能验签");
    }
}
