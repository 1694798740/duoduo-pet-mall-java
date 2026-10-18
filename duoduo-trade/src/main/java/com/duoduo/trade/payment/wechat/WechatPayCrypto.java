package com.duoduo.trade.payment.wechat;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 微信支付 API v3 的签名 / 验签 / 解密（2026-10-18）。
 *
 * <h2>为什么自己实现而不用官方 SDK</h2>
 * 本项目构建是<b>离线</b>的（{@code mvn -o}），引入新依赖需要先联网拉取；
 * 而 v3 的三件事用 JDK 自带能力即可完成：
 *   · 签名：{@code SHA256withRSA} + PKCS#8 私钥；
 *   · 验签：平台证书里的 RSA 公钥；
 *   · 解密：{@code AES/GCM/NoPadding}（APIv3 密钥）。
 * 零新依赖，也就没有新的供应链风险；这三段逻辑都能用固定向量写成单测（见 WechatPayCryptoTest）。
 *
 * <h2>两个绝不能做错的细节</h2>
 * <ol>
 *   <li><b>签名串每行都要以 \n 结尾，包括最后一行</b>（报文主体后面也要有 \n）；</li>
 *   <li><b>回调验签必须用原始报文体</b>，不能用反序列化后再拼回的字符串 —— 顺序/空白差异会让验签必然失败。</li>
 * </ol>
 */
public final class WechatPayCrypto {

    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String AUTH_PREFIX = "WECHATPAY2-SHA256-RSA2048 ";
    private static final int GCM_TAG_BITS = 128;

    private WechatPayCrypto() {
    }

    /** 请求签名：返回 Authorization 头的完整值 */
    public static String authorization(String method, String urlPath, long timestamp, String nonce,
                                       String body, String privateKeyPem, String merchantSerialNo) {
        String message = method + "\n" + urlPath + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        String signature = rsaSign(message, privateKeyPem);
        return AUTH_PREFIX + "mchid=\"" + mchidOf(merchantSerialNo) + "\","
                + "nonce_str=\"" + nonce + "\","
                + "signature=\"" + signature + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + merchantSerialNo + "\"";
    }

    private static String mchidOf(String serialNo) {
        return serialNo;
    }

    /** 验签：微信回调的验签名串 = 时间戳\n随机串\n报文主体\n */
    public static boolean verify(String timestamp, String nonce, String body, String signatureBase64,
                                 String platformCertPem) {
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        try {
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(publicKeyFromCertPem(platformCertPem));
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (java.security.SignatureException | IllegalArgumentException e) {
            // 签名本身不合法（字节长度不对 / 被篡改）：这是"验签没通过"，不是系统异常
            return false;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "回调验签过程异常：" + e.getMessage());
        }
    }

    /**
     * 解密回调 resource：AES-256-GCM。
     *
     * @param apiV3Key  32 字节的 APIv3 密钥
     * @param nonce     resource.nonce
     * @param ciphertext resource.ciphertext（Base64）
     * @param associatedData resource.associated_data
     */
    public static String decryptResource(String apiV3Key, String nonce, String ciphertext, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec key = new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
            cipher.updateAAD((associatedData == null ? "" : associatedData).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                    "回调报文解密失败（APIv3 密钥不匹配或报文被篡改）：" + e.getMessage());
        }
    }

    // ---------------- 内部实现 ----------------

    private static String rsaSign(String message, String privateKeyPem) {
        try {
            Signature signer = Signature.getInstance(SIGN_ALGORITHM);
            signer.initSign(privateKeyFromPem(privateKeyPem));
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "请求签名失败：" + e.getMessage());
        }
    }

    static PrivateKey privateKeyFromPem(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "商户私钥解析失败：" + e.getMessage());
        }
    }

    static PublicKey publicKeyFromCertPem(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")
                    .replaceAll("\\s", "");
            X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            return cert.getPublicKey();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID, "微信支付平台证书解析失败：" + e.getMessage());
        }
    }
}
