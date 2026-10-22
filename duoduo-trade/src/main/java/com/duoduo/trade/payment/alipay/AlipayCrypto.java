package com.duoduo.trade.payment.alipay;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 支付宝 RSA2 签名与验签（2026-10-22）。
 *
 * <h2>为什么手写而不是引 alipay-sdk</h2>
 * 支付宝开放接口的签名规则只有一条：把参数按 key 的字典序排成
 * {@code k1=v1&k2=v2&…}（剔除 {@code sign} 与 {@code sign_type} 两个 key 与空值），
 * 用 SHA256withRSA 对这段文本签名。预下单、回调验签、退款验签用的都是同一条规则 ——
 * 自己实现不到 60 行，还省去 SDK 对 httpclient / commons-logging 的一串传递依赖。
 *
 * <h2>密钥格式</h2>
 * 平台给出的是 PKCS#8 的应用私钥与 PKCS#8 的支付宝公钥（都是去头去尾的 base64 串）。
 * 这里接受"裸 base64"或完整 PEM 两种输入，内部统一剥掉头尾再解码 ——
 * 配置的人不需要纠结粘贴的是哪一种。
 */
public final class AlipayCrypto {

    private AlipayCrypto() {
    }

    /**
     * 按支付宝规则拼接待签名文本：字典序、剔除 sign 与空值。
     *
     * <p>★ 实测结论（2026-10-22 沙箱联调，网关错误页会展示它构造的验签串）：
     * <b>sign_type 参与签名</b>（只剔除 sign 与空值）——与部分旧文档「剔除 sign_type」
     * 的说法不同。以网关实际构造的验签串为准：包含 sign_type 才能通过，
     * 异步通知验签同理（sign_type 也参与）。
     */
    public static String signContent(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .filter(e -> !"sign".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    /** 用应用私钥对参数签名，返回 base64 的 sign 值 */
    public static String sign(Map<String, String> params, String privateKeyPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(privateKeyPem));
            signature.update(signContent(params).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR, "支付宝签名失败：" + e.getMessage());
        }
    }

    /**
     * 验签：用支付宝公钥校验回调 / 同步返回的签名。
     * 注意回调参数里的 sign 值是 URL 编码过的，调用方必须先完成 URL 解码再传入。
     */
    public static boolean verify(Map<String, String> params, String alipayPublicKeyPem) {
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey(alipayPublicKeyPem));
            signature.update(signContent(params).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            // 验签异常（编码错 / 公钥错）一律按验签失败处理，绝不能"异常就放过"
            return false;
        }
    }

    /**
     * 回调验签的一个易错点：支付宝对 {@code biz_content} 里的中文值做了一次签名后编码，
     * 但 {@code notify} 回调的各字段值本身就是解码后的原文 —— 直接用即可；
     * 而 {@code return_url} 同步跳转带回来的参数是 URL 编码的，需先解码。
     * 本方法用于后者。
     */
    public static Map<String, String> urlDecodeParams(Map<String, String[]> form) {
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String[]> e : form.entrySet()) {
            String[] values = e.getValue();
            if (values != null && values.length > 0) {
                out.put(e.getKey(), java.net.URLDecoder.decode(values[0], StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static PrivateKey privateKey(String pem) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stripPem(pem));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR,
                    "支付宝应用私钥不可用（格式应为 PKCS#8 base64）：" + e.getMessage());
        }
    }

    static PublicKey publicKey(String pem) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stripPem(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_ERROR,
                    "支付宝公钥不可用（格式应为 X.509 base64）：" + e.getMessage());
        }
    }

    /** 兼容「裸 base64」与「-----BEGIN…-----」两种粘贴格式 */
    private static String stripPem(String pem) {
        return pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    }
}
