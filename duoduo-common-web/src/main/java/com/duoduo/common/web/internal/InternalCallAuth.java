package com.duoduo.common.web.internal;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间调用凭据：签发（出向 Feign）与校验（入向内部接口）。
 *
 * <p><b>为什么改掉原来的「明文共享密钥」</b>（第五十八轮 · P0-3）：
 * 老写法把 {@code duoduo.internal.token} 原样放进 {@code X-Internal-Token} 请求头。
 * 它有三个绕不过去的问题：
 * <ul>
 *   <li><b>密钥在链路上裸奔</b>：任意一处访问日志 / 抓包 / 代理缓冲都会留下它，
 *       拿到的人可以永久冒充任意服务 —— 日志里的一行就是全站内部接口的钥匙。</li>
 *   <li><b>无时效</b>：泄露一次永远有效，只能靠「轮换 + 全站重启」止血。</li>
 *   <li><b>无调用方身份</b>：只有一个「自己人 / 不是自己人」的布尔值，
 *       无法回答「这次调用到底是谁发的」，也就无法单独吊销某一个调用方。</li>
 * </ul>
 *
 * <p><b>改造后的形态</b>：请求头里带的是<b>签名</b>而不是密钥本身 ——
 * <pre>
 *   X-Internal-Token: dd1.&lt;caller&gt;.&lt;tsSeconds&gt;.&lt;nonce&gt;.&lt;签名&gt;
 *   签名 = Base64Url( HMAC-SHA256( 密钥(caller), "dd1\ncaller\nts\nnonce\nMETHOD\npath" ) )
 * </pre>
 * 于是：链路上不再出现密钥；签名只在时间窗内有效；每个调用方有独立密钥，
 * 换掉一个调用方的密钥不影响其它调用方；nonce 让同一个签名只能用一次。
 *
 * <p><b>密钥怎么来的</b>：默认是 <b>派生密钥</b> —— {@code 密钥(caller) = HMAC(根密钥, caller)}。
 * 这样各域共用同一个根密钥（配置与容器编排一行都不用改），但落到每一次调用上的实际签名密钥
 * 是按调用方身份分开的：<b>用 trade 的密钥签出来的头，放到 fulfilment 名下校验一定不通过</b>，
 * 一条被观察到的请求头无法被挪用到别的身份上。
 * 想进一步收窄时，给 {@code duoduo.internal.caller-secrets} 配
 * {@code duoduo-trade=xxx,duoduo-fulfilment=yyy}：表里有的调用方用显式密钥，
 * 于是「一密通全站」变成「每个调用方一把钥匙，可单独轮换」。
 *
 * <p><b>仍然不是生产终态</b>：共享根密钥意味着拿到根密钥的人仍能冒充任意调用方，
 * 真正的服务身份认证要上 mTLS / 服务网格授权（已登记为待办）。本类做的是
 * 把「裸奔 + 永久有效 + 无身份」变成「签名 + 有时效 + 有身份且可单独吊销」，成本为零新增依赖。
 *
 * <p><b>失败关闭</b>：任何一步对不上（格式 / 过期 / 身份未知 / 签名不符 / nonce 重放）一律拒绝，
 * 不写「没配就放行」那类兜底（INV-11）。
 */
public final class InternalCallAuth {

    public static final String HEADER = "X-Internal-Token";

    /** 版本号：格式变更时递增，让新旧格式可以明确区分（而不是靠猜） */
    private static final String VERSION = "dd1";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    /** nonce 去重表上限：只用于单实例兜底，超限按 LRU 淘汰（过期签名本来也会被时间窗拦掉） */
    private static final int NONCE_CACHE_MAX = 10_000;

    private final String rootSecret;
    private final String selfCaller;
    private final long skewSeconds;
    private final Map<String, String> callerSecrets;
    private final Object nonceLock = new Object();
    private final Map<String, Boolean> seenNonces = new LinkedHashMap<>(512, 0.75f, true);

    public InternalCallAuth(String rootSecret, String selfCaller, long skewSeconds,
                            Map<String, String> callerSecrets) {
        this.rootSecret = rootSecret == null ? "" : rootSecret;
        // 身份归一为小写：避免「同一个服务名大小写不同」被当成两个调用方（那是隐蔽的不一致）
        this.selfCaller = selfCaller == null || selfCaller.isBlank()
                ? "unknown" : selfCaller.toLowerCase(Locale.ROOT);
        this.skewSeconds = Math.max(1L, skewSeconds);
        this.callerSecrets = callerSecrets == null ? Map.of() : Collections.unmodifiableMap(callerSecrets);
    }

    /** 本服务的调用方身份（出向签名时使用） */
    public String caller() {
        return selfCaller;
    }

    /**
     * 签发一次调用的凭据。
     *
     * <p><b>每次调用都必须重新签发</b>：nonce 是一次性的，复用同一个请求头会被判为重放。
     * Feign 拦截器是按次执行的，天然满足；手写请求头的地方（脚本 / 探针）也要按次生成。
     *
     * @param method HTTP 方法（GET / POST …）
     * @param path   请求路径，<b>不含查询串</b>（Feign 侧用 {@code template.path()}，
     *               服务端用 {@code request.getRequestURI()}，两侧口径一致）
     */
    public String sign(String method, String path) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String secret = secretFor(selfCaller);
        String sig = signature(secret, selfCaller, ts, nonce, safe(method), safe(path));
        return VERSION + "." + selfCaller + "." + ts + "." + nonce + "." + sig;
    }

    /**
     * 校验一次调用。
     *
     * @param header 请求头原文（可能为 null）
     * @param method HTTP 方法
     * @param path   请求路径（不含查询串）
     */
    public VerifyResult verify(String header, String method, String path) {
        if (header == null || header.isBlank()) {
            return VerifyResult.deny("missing");
        }
        // 限制成 5 段：签名里不含 '.'（Base64Url 字母表没有 '.'），所以按 '.' 切分是安全的
        String[] parts = header.split("\\.", 5);
        if (parts.length != 5 || !VERSION.equals(parts[0])) {
            return VerifyResult.deny("format");
        }
        String caller = parts[1];
        String ts = parts[2];
        String nonce = parts[3];
        String sig = parts[4];
        if (caller.isBlank() || nonce.isBlank()) {
            return VerifyResult.deny("format");
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return VerifyResult.deny("format");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - issuedAt) > skewSeconds) {
            return VerifyResult.deny("expired");
        }
        String secret = secretFor(caller);
        if (secret.isBlank()) {
            // 根密钥为空时派生结果为空 —— 绝不能让「两边都空」变成校验通过
            return VerifyResult.deny("no-secret");
        }
        String expected;
        try {
            expected = signature(secret, caller, ts, nonce, safe(method), safe(path));
        } catch (IllegalStateException e) {
            return VerifyResult.deny("mac-error");
        }
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8))) {
            return VerifyResult.deny("bad-signature");
        }
        if (!claimNonce(caller, nonce)) {
            return VerifyResult.deny("replayed");
        }
        return VerifyResult.ok(caller);
    }

    /**
     * 解析 {@code duoduo.internal.caller-secrets} 配置：{@code duoduo-trade=xxx,duoduo-fulfilment=yyy}。
     * 空串返回空表（= 全部走派生密钥）。
     */
    public static Map<String, String> parseCallerSecrets(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String item : raw.split(",")) {
            String pair = item.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue;
            }
            parsed.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return parsed;
    }

    /**
     * 调用方密钥：显式配置优先，否则从根密钥派生（见类注释）。
     *
     * <p>包内可见是为了让单测能直接钉住「<b>每个调用方一把钥匙</b>」这条性质：
     * 只断言「换个身份签名就失效」是不够的 —— 那条被「签名串里本来就有 caller 字段」
     * 兜住了，就算派生时漏掉 caller 也照样通过（反向验证实测如此）。
     * 真正的价值在于：拿到某一个调用方密钥的人，无法冒充其它调用方。
     */
    String secretForCaller(String caller) {
        return secretFor(caller);
    }

    private String secretFor(String caller) {
        String explicit = callerSecrets.get(caller);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return derive(rootSecret, caller);
    }

    private static String derive(String root, String caller) {
        if (root.isBlank()) {
            return "";
        }
        return hmac(root, VERSION + "|key|" + caller.toLowerCase(Locale.ROOT));
    }

    private static String signature(String secret, String caller, String ts, String nonce,
                                    String method, String path) {
        return hmac(secret, String.join("\n", VERSION, caller, ts, nonce, method, path));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用，服务间调用凭据无法签发", e);
        }
    }

    /** nonce 一次性：抢到才算通过，重复即判重放（单实例内有效，多实例需换共享存储） */
    private boolean claimNonce(String caller, String nonce) {
        String key = caller + "|" + nonce;
        synchronized (nonceLock) {
            if (seenNonces.putIfAbsent(key, Boolean.TRUE) != null) {
                return false;
            }
            if (seenNonces.size() > NONCE_CACHE_MAX) {
                // LRU 由 LinkedHashMap 的 access-order 保证：这里淘汰最久未用的那一个
                String eldest = seenNonces.keySet().iterator().next();
                seenNonces.remove(eldest);
            }
            return true;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 校验结果。{@code reason} 只用于服务端日志，不下发给调用方
     * （返回给调用方的永远是同一个 403 文案 —— 细节本身就是情报）。
     */
    public record VerifyResult(boolean ok, String reason) {

        public static VerifyResult ok(String caller) {
            return new VerifyResult(true, caller);
        }

        public static VerifyResult deny(String reason) {
            return new VerifyResult(false, reason);
        }
    }
}
