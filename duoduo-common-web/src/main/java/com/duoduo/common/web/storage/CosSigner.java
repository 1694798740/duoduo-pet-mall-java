package com.duoduo.common.web.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 腾讯云 COS 请求签名（COS 签名 v5，{@code q-sign-algorithm=sha1}）—— 纯 JDK 实现。
 *
 * <h2>为什么自己写而不引 SDK</h2>
 * 本项目的 Maven 用的是**项目内本地仓库**（{@code maven/settings.xml} 里的
 * {@code <localRepository>.../.m2repo</localRepository>}）且构建走 {@code -o}（离线）。
 * 实测该仓库里 <b>{@code com.qcloud:cos_api}（官方 SDK）、{@code io.minio:minio}、
 * {@code software.amazon.awssdk:s3} 一个都没有</b> —— 离线状态下引不进来。
 *
 * <p>而 COS 的签名算法是**公开且自包含**的：两次 HMAC-SHA1 + 一次 SHA1，
 * JDK 自带 {@link Mac} 与 {@link MessageDigest} 就够了（约 60 行）。
 * 这与本项目已有的自研签名风格一致（{@code common-web/InternalCallAuth} 用 HMAC-SHA256）。
 *
 * <h2>算法（官方文档「请求签名」章节，逐步照做）</h2>
 * <pre>
 *   KeyTime      = "&lt;startSec&gt;;&lt;endSec&gt;"
 *   SignKey      = HexLower( HMAC_SHA1( key = SecretKey, data = KeyTime ) )
 *   HttpString   = method.toLowerCase() + "\n" + path + "\n" + params + "\n" + headers + "\n"
 *   StringToSign = "sha1" + "\n" + KeyTime + "\n" + HexLower( SHA1( HttpString ) ) + "\n"
 *   Signature    = HexLower( HMAC_SHA1( key = SignKey, data = StringToSign ) )
 * </pre>
 * 其中 {@code headers} 形如 {@code host=<urlencode(host)>}，且**必须与实际请求头发送的值一致**；
 * {@code q-header-list} 里列出的头才是被校验的，其余头（例如 {@code Content-Type}）
 * 可以照常发送但不参与校验。
 *
 * <h2>★★ 四个容易写错、且错了很难查的点（前两个是本类真踩过的）</h2>
 * <ol>
 *   <li><b>{@code StringToSign} 不是"SHA1(HttpString)"就完了</b> ——
 *       它是 {@code sha1\n<KeyTime>\n<SHA1(HttpString)>\n}：<b>带固定前缀 {@code sha1}、
 *       带中间段的 KeyTime、且结尾还有一个换行</b>。
 *       2026-10-13 实测：漏掉这三段时服务端统一回 {@code SignatureDoesNotMatch}
 *       （只说"签名无效"，不告诉你差在哪），本类的单测与官方示例向量对齐后才定位到。
 *       这是个**看着很像、差一点才对**的错误：HttpString 与 SignKey 两段全对，
 *       只有最后一步进 HMAC 的报文拼错了。</li>
 *   <li><b>{@code UriPathname} 用的是"未编码"的路径</b>：官方示例里线上请求是
 *       {@code /exampleobject(%E8%85%BE%E8%AE%AF%E4%BA%91)}，而 HttpString 里写的是
 *       {@code /exampleobject(腾讯云)}。本项目的 key 全是 UUID / 数字，编码前后一致，
 *       但签名一律按**原始 key** 计算、发请求时才编码（见 {@link CosObjectStorage}）。</li>
 *   <li><b>SignKey 是"十六进制字符串"，且以它的 ASCII 字节作为第二次 HMAC 的 key</b>，
 *       不是把原始 SecretKey 直接用第二次；</li>
 *   <li><b>HttpString 末尾有一个换行</b>（4 段 + 结尾 {@code \n}），少这一个字节签名必错。</li>
 * </ol>
 * 因此 {@link CosSigner#sign} 的入参必须是**实际要发出去的那一份** method/path/host ——
 * 签名值与被签内容分离是本类唯一的正确用法。
 */
public final class CosSigner {

    private static final String HMAC_SHA1 = "HmacSHA1";

    private final String secretId;
    private final String secretKey;

    public CosSigner(String secretId, String secretKey) {
        this.secretId = secretId;
        this.secretKey = secretKey;
    }

    /**
     * 生成 {@code Authorization} 头的值。
     *
     * @param method  HTTP 方法（大小写不敏感，内部转小写）
     * @param path    请求路径，**以 {@code /} 开头、不含域名与 query，且必须是"未编码"形式**
     *                （官方示例：线上发 {@code /exampleobject(%E8%85%BE%E8%AE%AF%E4%BA%91)}，
     *                签名里写 {@code /exampleobject(腾讯云)}）
     * @param host    实际发送的 Host（虚拟主机风格：{@code <bucket>.cos.<region>.myqcloud.com}）
     * @param startSec 签名生效时间（秒级 Unix 时间戳）
     * @param endSec   签名过期时间（秒级 Unix 时间戳）
     */
    public String sign(String method, String path, String host, long startSec, long endSec) {
        return sign(method, path, "", Map.of("host", host), startSec, endSec);
    }

    /**
     * 通用签名：可指定参与签名的任意请求头与 URL 参数。
     *
     * <p>生产路径（{@link CosObjectStorage}）只签 {@code host} 就够了 —— 其余头
     * （如 {@code Content-Type}）不签也能正常发送，只是不受签名保护。
     * 这个重载存在的主要理由是**单测能逐字复现官方文档的示例向量**：
     * 只有能把官方那个带 7 个头的 HttpString 原样喂进来，才谈得上"算法与文档一致"。
     *
     * @param params 已按字典序拼好的查询串（如 {@code a=1&b=2}）；无则空串
     * @param headers 参与签名的头（key 大小写不敏感，内部转小写并按字典序排序）
     */
    String sign(String method, String path, String params,
                Map<String, String> headers, long startSec, long endSec) {
        String keyTime = startSec + ";" + endSec;
        String signKey = hmacSha1Hex(secretKey.getBytes(StandardCharsets.UTF_8),
                keyTime.getBytes(StandardCharsets.UTF_8));

        List<String> names = new ArrayList<>(headers.size());
        for (String n : headers.keySet()) {
            names.add(n.toLowerCase());
        }
        Collections.sort(names);

        StringBuilder headerStr = new StringBuilder();
        for (String n : names) {
            if (headerStr.length() > 0) {
                headerStr.append('&');
            }
            headerStr.append(uriEncode(n, true)).append('=')
                    .append(uriEncode(headers.get(n), true));
        }

        String httpString = method.toLowerCase() + "\n"
                + path + "\n"
                + params + "\n"
                + headerStr + "\n";

        // ★★ 这三段（前缀 "sha1"、中间段 KeyTime、结尾换行）一个都不能少。
        //    实测漏掉它们时服务端只回一句 SignatureDoesNotMatch —— 见类注释第 1 条。
        String stringToSign = "sha1\n" + keyTime + "\n"
                + sha1Hex(httpString.getBytes(StandardCharsets.UTF_8)) + "\n";
        String signature = hmacSha1Hex(signKey.getBytes(StandardCharsets.UTF_8),
                stringToSign.getBytes(StandardCharsets.UTF_8));

        return "q-sign-algorithm=sha1"
                + "&q-ak=" + secretId
                + "&q-sign-time=" + keyTime
                + "&q-key-time=" + keyTime
                + "&q-header-list=" + String.join(";", names)
                + "&q-url-param-list=" + paramListOf(params)
                + "&q-signature=" + signature;
    }

    /** 从已按字典序拼好的查询串里取参数名列表（{@code a=1&b=2} → {@code a;b}）。 */
    private static String paramListOf(String params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : params.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            int eq = pair.indexOf('=');
            sb.append(eq < 0 ? pair : pair.substring(0, eq));
        }
        return sb.toString();
    }

    private static String hmacSha1Hex(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(key, HMAC_SHA1));
            return toHex(mac.doFinal(data));
        } catch (Exception e) {
            // 密钥长度非法 / 算法缺失属于环境问题，直接抛 —— 不要吞掉后返回一个"看起来像签名"的串
            throw new IllegalStateException("COS 签名失败", e);
        }
    }

    private static String sha1Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA1 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * RFC 3986 编码（COS 签名要求）：保留 {@code A-Za-z0-9-_.~}，
     * 其余按 {@code %XX} 大写十六进制编码；空格必须是 {@code %20} 而不是 {@code +}。
     *
     * @param encodeSlash 路径中的 {@code /} 是否编码（作为 path 时传 false）
     */
    static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder(value.length() * 2);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                sb.append(c);
            } else if (c == '/' && !encodeSlash) {
                sb.append('/');
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
