package com.duoduo.common.web.storage;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯云 COS 实现（S9）—— **零第三方依赖**，只用 JDK 21 自带的
 * {@link HttpClient} 与 {@link CosSigner} 的 HMAC-SHA1 签名。
 *
 * <p>为什么不用 SDK：见 {@link CosSigner} 类注释（离线构建 + 项目内仓库里没有任何对象存储 SDK）。
 *
 * <h2>访问方式：虚拟主机风格</h2>
 * {@code https://<bucket>.cos.<region>.myqcloud.com/<key>}。
 * 选它而不是路径风格（{@code /<bucket>/<key>}）的理由：签名只需覆盖 {@code /<key>} 这一段，
 * path 与 key 一对一，少一层容易拼错的映射。★ 注意：桶名里**不能出现点号**，
 * 否则 TLS 证书通配会失配 —— 本项目的两个桶名都只有连字符，符合要求。
 *
 * <h2>出错时的可诊断性（这一块是刻意的）</h2>
 * COS 的失败体是 XML（{@code <Error><Code>AccessDenied</Code>...}），而 HTTP 状态码很笼统
 * （403 既可能是密钥不对、也可能是策略没授那个 action）。所以这里把 {@code Code}/{@code Message}
 * 抠出来拼进异常消息 —— 否则排障只能看到"上传失败"。
 * 三类最常见的 Code 及含义：
 * <ul>
 *   <li>{@code SignatureDoesNotMatch} —— 密钥错，或签名的 method/path/host 与实际请求不一致；</li>
 *   <li>{@code AccessDenied} —— CAM 策略没授这个 action（例如策略里漏了 {@code cos:HeadObject}）；</li>
 *   <li>{@code NoSuchBucket} —— 桶名或地域写错。</li>
 * </ul>
 */
public class CosObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(CosObjectStorage.class);

    /** 签名提前量：容忍本机时钟比服务端慢 */
    private static final long SKEW_BACK_SECONDS = 60;
    /** 签名有效期：单次请求足够；短一点可以降低签名被复用的窗口 */
    private static final long SIGN_TTL_SECONDS = 900;

    private static final Pattern ERR_CODE = Pattern.compile("<Code>([^<]*)</Code>");
    private static final Pattern ERR_MSG = Pattern.compile("<Message>([^<]*)</Message>");

    private final String bucket;
    private final String host;
    private final CosSigner signer;
    private final HttpClient http;
    private final String role;

    /**
     * @param role      用途标识（{@code public} / {@code private}），只用于日志与报错措辞
     * @param bucket    桶名（含 APPID 后缀）
     * @param region    地域，如 {@code ap-guangzhou}
     * @param secretId  子账号 SecretId
     * @param secretKey 子账号 SecretKey
     */
    public CosObjectStorage(String role, String bucket, String region,
                            String secretId, String secretKey) {
        this.role = role;
        this.bucket = bucket;
        this.host = bucket + ".cos." + region + ".myqcloud.com";
        this.signer = new CosSigner(secretId, secretKey);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // 不跟随跳转：COS 的 3xx 往往意味着桶名/地域拼错，
                // 跟随会把一个配置错误伪装成"偶发失败"
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        log.info("对象存储后端 = COS（{}-bucket={} host={}）", role, bucket, host);
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (content == null) {
            throw BizException.validation("要写入的内容为空");
        }
        HttpRequest.Builder b = request("PUT", key)
                .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
        HttpResponse<String> resp = send(b.build(), HttpResponse.BodyHandlers.ofString());
        requireOk(resp.statusCode(), resp.body(), "写入", key);
    }

    @Override
    public byte[] get(String key) {
        HttpResponse<byte[]> resp = send(request("GET", key).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() == 404) {
            return null;
        }
        requireOk(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8), "读取", key);
        return resp.body();
    }

    @Override
    public boolean exists(String key) {
        HttpResponse<Void> resp = send(
                request("HEAD", key).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        if (resp.statusCode() == 404) {
            return false;
        }
        requireOk(resp.statusCode(), "", "探测", key);
        return true;
    }

    @Override
    public void delete(String key) {
        HttpResponse<String> resp = send(request("DELETE", key).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        // COS 的 DELETE 是幂等的：对象不存在也返回 204。这里同样把 404 当成功，
        // 保证清理脚本可以重复执行（可重跑是备份/迁移类脚本的硬要求）。
        if (resp.statusCode() == 404) {
            return;
        }
        requireOk(resp.statusCode(), resp.body(), "删除", key);
    }

    @Override
    public String backend() {
        return "cos";
    }

    /** 桶的默认访问域名（{@code <bucket>.cos.<region>.myqcloud.com}），供配置自检与日志使用。 */
    public String host() {
        return host;
    }

    private HttpRequest.Builder request(String method, String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw BizException.validation("非法的存储 key");
        }
        // ★ 两个 path 刻意分开：**发出去的是编码后的，签名用的是原始 key**
        //   （官方示例里线上是 /exampleobject(%E8%85%BE%E8%AE%AF%E4%BA%91)，
        //     而 HttpString 里写 /exampleobject(腾讯云)）。
        //   本项目的 key 是 UUID / 数字，编码前后一致，两种写法等价；
        //   分开写是为了将来出现非 ASCII key 时不会悄悄错。
        String wirePath = "/" + CosSigner.uriEncode(key, false);
        String signPath = "/" + key;
        long now = Instant.now().getEpochSecond();
        return HttpRequest.newBuilder(URI.create("https://" + host + wirePath))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization",
                        signer.sign(method, signPath, host, now - SKEW_BACK_SECONDS, now + SIGN_TTL_SECONDS));
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL, "对象存储请求被中断", e);
        } catch (Exception e) {
            // 网络层失败（DNS / 连不通 / TLS）与权限失败要能分辨：这里统一带上桶与 host
            throw new BizException(ErrorCode.INTERNAL,
                    "对象存储不可达（bucket=" + bucket + " host=" + host + "）：" + e.getMessage(), e);
        }
    }

    private void requireOk(int status, String body, String action, String key) {
        if (status >= 200 && status < 300) {
            return;
        }
        String code = extract(ERR_CODE, body);
        String msg = extract(ERR_MSG, body);
        String detail = (code.isEmpty() ? "" : " code=" + code) + (msg.isEmpty() ? "" : " msg=" + msg);
        // 只记 key（服务端生成的 UUID 形态），不记请求体，也不记签名头
        log.error("COS {}失败 status={} {}{} backend=cos bucket={}",
                action, status, detail, " key=" + key, bucket);
        // ★ 欠费停服（HTTP 451 UnavailableForLegalReasons）单独点名：
        //   这是**账号欠费**，不是网络抖动 —— "请稍后重试"只会让用户白白重试，
        //   读和写会一起 451（已存图片同样打不开）。必须把"去充值"说在明面上，
        //   并且提示语要能直接落到排障动作上（控制台看欠费通知）。
        if (status == 451 || (msg != null && msg.contains("arrears"))) {
            throw new BizException(ErrorCode.DEGRADED,
                    "图片存储服务（腾讯云 COS）因账户欠费已被停用，上传与已存图片暂时都无法访问；"
                            + "请在腾讯云控制台充值恢复（HTTP 451）");
        }
        throw new BizException(ErrorCode.INTERNAL,
                "对象存储" + action + "失败（HTTP " + status + detail + "）");
    }

    private static String extract(Pattern p, String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : "";
    }
}
