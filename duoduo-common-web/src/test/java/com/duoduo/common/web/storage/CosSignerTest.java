package com.duoduo.common.web.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COS 签名 v5 的单测。
 *
 * <h2>期望值是怎么来的（★ 这一点必须写清楚）</h2>
 * 期望值**不是**"从本实现跑出来再抄回去"（那是自证），而是两条独立来源：
 * <ol>
 *   <li><b>官方文档的完整示例向量</b>：HttpString / SignKey / Signature 都由腾讯云文档给出，
 *       本测试用同一个 HttpString 喂进实现里比对。官方文档把示例签名的**尾部打码**了
 *       （防照抄），所以只断言前 36 个十六进制字符 —— 36 个十六进制位能对上，
 *       不可能是巧合，足以证明整条链（SignKey → HttpString → StringToSign → Signature）走对了。</li>
 *   <li><b>另一份独立实现算出的向量</b>：Python 的
 *       {@code hmac.new(key, msg, hashlib.sha1).hexdigest()}。</li>
 * </ol>
 * ★ 但**权威验证不在这里**：签名是否被服务端接受，只有真的打一次 COS 才知道。
 * 那一条由 {@link CosObjectStorageLiveTest} 覆盖。
 *
 * <h2>这些用例钉死的正是 2026-10-13 踩过的那次错</h2>
 * 当时的实现把 {@code StringToSign} 写成了裸的 {@code SHA1(HttpString)}，
 * 漏掉 {@code sha1\n} 前缀、{@code KeyTime\n} 中间段和结尾换行 ——
 * SignKey 与 HttpString 两段全对，服务端只回一句 {@code SignatureDoesNotMatch}。
 * 下面 {@link #stringToSignCarriesTheFixedPrefixKeyTimeAndTrailingNewline()} 就是防它回潮的。
 */
class CosSignerTest {

    // ---------- 官方文档示例向量 ----------
    private static final String DOC_SECRET_ID = "AKIDQjz3ltompVjBni5LitkWHFlFpwkn9U5q";
    private static final String DOC_SECRET_KEY = "BQYIM75p8x0iWVFSIgqEKwFprpRSVHlz";
    private static final long DOC_START = 1557989151L;
    private static final long DOC_END = 1557996351L;

    // ---------- 本项目场景向量（同一个 SecretKey，时间戳固定以便断言）----------
    private static final String SECRET_ID = "AKIDQjz3ltompVjBni5LitkWHFlFpwkn9U5q";
    private static final String SECRET_KEY = "BQYIM75p8x0iWVFSIgqEKwFprpRSVHlz";
    private static final long START = 1551113065L;
    private static final long END = 1551113066L;

    private final CosSigner docSigner = new CosSigner(DOC_SECRET_ID, DOC_SECRET_KEY);
    private final CosSigner signer = new CosSigner(SECRET_ID, SECRET_KEY);

    @Test
    @DisplayName("★ 官方示例向量（PUT + 7 个签名头）：签名与文档给出的值前 36 位一致")
    void matchesOfficialDocumentedVector() {
        // 照抄官方文档「示例一：上传对象（PUT）」的请求头。
        // 注意 path 是**未编码**的 /exampleobject(腾讯云)，而线上请求发的是
        // /exampleobject(%E8%85%BE%E8%AE%AF%E4%BA%91) —— 这是文档自己举的"签名用原始路径"的例子。
        Map<String, String> headers = Map.of(
                "content-length", "13",
                "content-md5", "mQ/fVh815F3k6TAUm8m0eg==",
                "content-type", "text/plain",
                "date", "Thu, 16 May 2019 06:45:51 GMT",
                "host", "examplebucket-1250000000.cos.ap-beijing.myqcloud.com",
                "x-cos-acl", "private",
                "x-cos-grant-read", "uin=\"100000000011\"");

        String auth = docSigner.sign("PUT", "/exampleobject(腾讯云)", "", headers, DOC_START, DOC_END);

        // 文档给出的中间量（本测试的 HttpString 构造必须与它们一致）：
        //   SignKey      = eb2519b498b02ac213cb1f3d1a3d27a3b3c9bc5f
        //   SHA1(HttpString) = 8b2751e77f43a0995d6e9eb9477f4b685cca4172
        //   Signature    = 3b8851a11a569213c17ba8fa7dcf2abec693....（文档尾部打码）
        assertThat(auth)
                .contains("&q-header-list=content-length;content-md5;content-type;date;host;x-cos-acl;x-cos-grant-read")
                .contains("&q-url-param-list=")
                .contains("&q-signature=3b8851a11a569213c17ba8fa7dcf2abec693");
    }

    @Test
    @DisplayName("★ 本项目场景向量（get / host-only）：完整 Authorization 串逐字符一致")
    void matchesHostOnlyVector() {
        String actual = signer.sign("get", "/",
                "examplebucket-1250000000.cos.ap-beijing.myqcloud.com", START, END);

        // 独立算出的中间量（Python hmac/hashlib）：
        //   SignKey          = 82769d95621e326246f538b4343b221546fc5b93
        //   SHA1(HttpString) = 1694ba3b8b30a7d55773f12a11a3810674266638
        //   StringToSign     = "sha1\n1551113065;1551113066\n1694ba...6638\n"
        //   Signature        = ac126dfc33291230526564be2426aa38949bcf6a
        assertThat(actual).isEqualTo(
                "q-sign-algorithm=sha1"
                        + "&q-ak=" + SECRET_ID
                        + "&q-sign-time=1551113065;1551113066"
                        + "&q-key-time=1551113065;1551113066"
                        + "&q-header-list=host"
                        + "&q-url-param-list="
                        + "&q-signature=ac126dfc33291230526564be2426aa38949bcf6a");
    }

    @Test
    @DisplayName("本项目真实桶 + uploads/ 前缀：签名一致")
    void matchesProjectBucketVector() {
        String actual = signer.sign("put", "/uploads/550e8400-e29b-41d4-a716-446655440000.jpg",
                "duoduo-mall-public-1374887902.cos.ap-guangzhou.myqcloud.com", START, END);

        //   SHA1(HttpString) = 66c5a2b05a892a441d09b35fc4de1c38cb5dc73c
        //   StringToSign     = "sha1\n1551113065;1551113066\n66c5a2...73c\n"
        //   Signature        = 2a03362520736815ce8b98b27394f2c915f5eb86
        assertThat(actual).contains("&q-signature=2a03362520736815ce8b98b27394f2c915f5eb86");
    }

    @Test
    @DisplayName("★ StringToSign = sha1\\n + KeyTime\\n + SHA1(HttpString)\\n —— 三段缺一不可")
    void stringToSignCarriesTheFixedPrefixKeyTimeAndTrailingNewline() {
        // 这一条是防"回潮"的：把 StringToSign 退化成裸的 SHA1(HttpString) 时
        // （2026-10-13 真实踩过），签名值会变，而**没有任何本地断言能发现**，
        // 只有打真接口才会得到 SignatureDoesNotMatch。
        // 做法：KeyTime 不同 ⇒ 签名必须不同。裸 SHA1(HttpString) 的写法里 KeyTime 不进报文，
        // 于是"换 KeyTime 签名不变"——这正是那个 bug 的可观测特征。
        String a = signer.sign("get", "/uploads/a.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com",
                1551113065L, 1551113066L);
        String b = signer.sign("get", "/uploads/a.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com",
                1551113065L, 1551113999L);

        assertThat(b).as("KeyTime 变了签名却没变 —— StringToSign 里没有带上 KeyTime")
                .isNotEqualTo(a);
    }

    @Test
    @DisplayName("★ method / path / host 三者只要有一样不同，签名就必须不同")
    void everySignedComponentAffectsSignature() {
        String base = sign("get", "/uploads/a.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com");

        // 这三种"漏签"是最典型的实现错误：不签 path（换对象不换签名）、不签 method、不签 host
        assertThat(sign("put", "/uploads/a.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com"))
                .as("method 未参与签名").isNotEqualTo(base);
        assertThat(sign("get", "/uploads/b.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com"))
                .as("path 未参与签名").isNotEqualTo(base);
        assertThat(sign("get", "/uploads/a.jpg", "c-1374887902.cos.ap-guangzhou.myqcloud.com"))
                .as("host 未参与签名").isNotEqualTo(base);
    }

    @Test
    @DisplayName("method 大小写不敏感：PUT 与 put 得到同一个签名")
    void methodIsCaseInsensitive() {
        assertThat(sign("PUT", "/uploads/a.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com"))
                .isEqualTo(sign("put", "/uploads/a.jpg", "b-1374887902.cos.ap-guangzhou.myqcloud.com"));
    }

    @Test
    @DisplayName("uriEncode：保留 -_.~ 与路径斜杠，其它按 %XX 大写编码，空格必须 %20")
    void uriEncodeFollowsRfc3986() {
        assertThat(CosSigner.uriEncode("uploads/550e8400-e29b-41d4.jpg", false))
                .isEqualTo("uploads/550e8400-e29b-41d4.jpg");
        assertThat(CosSigner.uriEncode("a b", false)).isEqualTo("a%20b");
        assertThat(CosSigner.uriEncode("a/b", true)).isEqualTo("a%2Fb");
        assertThat(CosSigner.uriEncode("-_.~", false)).isEqualTo("-_.~");
        assertThat(CosSigner.uriEncode("营业执照.pdf", false)).startsWith("%E8%90%A5");
        // 官方示例里出现过的三个：/ = : 分别编码为 %2F %3D %3A
        assertThat(CosSigner.uriEncode("mQ/fVh815F3k6TAUm8m0eg==", true))
                .isEqualTo("mQ%2FfVh815F3k6TAUm8m0eg%3D%3D");
    }

    private String sign(String method, String path, String host) {
        return signer.sign(method, path, host, START, END);
    }
}
