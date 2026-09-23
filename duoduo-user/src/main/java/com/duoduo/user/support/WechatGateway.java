package com.duoduo.user.support;

import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A13 · 微信 code2Session 客户端（小程序登录凭据 → openid）。
 *
 * <p>三种模式，优先级从高到低：
 * <ol>
 *   <li><b>mock</b>：{@code duoduo.wechat.mock-enabled=true} 且 code 形如 {@code mock:<openid>} ——
 *       不调微信服务器直接回 openid，供本地 / CI 冒烟。该开关是**显式配置项**，
 *       生产环境绝不能开（等于把「任意 openid 登录任意账号」的门焊死敞开）。</li>
 *   <li><b>real</b>：appid / secret 均已配置 → 调
 *       {@code https://api.weixin.qq.com/sns/jscode2session}，errcode != 0 视为凭据无效（401）。</li>
 *   <li><b>未配置</b>：appid 或 secret 缺失且非 mock 请求 → 503 {@code DEGRADED}
 *       （「依赖服务不可用，已降级」—— AppID 是外部依赖，没有它微信登录这条腿就是瘸的，
 *       如实返回降级而不是伪装成成功或 500）。</li>
 * </ol>
 *
 * <p>安全：secret 只从环境变量读取，不落盘、不写日志；日志只记 openid 前 4 位 + 长度。
 */
@Component
public class WechatGateway {

    private static final Logger log = LoggerFactory.getLogger(WechatGateway.class);
    private static final String JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String MOCK_PREFIX = "mock:";

    private final String appid;
    private final String secret;
    private final boolean mockEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WechatGateway(@Value("${duoduo.wechat.appid:}") String appid,
                         @Value("${duoduo.wechat.secret:}") String secret,
                         @Value("${duoduo.wechat.mock-enabled:false}") boolean mockEnabled) {
        this.appid = appid == null ? "" : appid.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.mockEnabled = mockEnabled;
    }

    /** 是否已具备真实调用条件（appid + secret 齐备） */
    public boolean isConfigured() {
        return !appid.isEmpty() && !secret.isEmpty();
    }

    /**
     * 用 js_code 换 openid。失败一律抛 BizException（400/401/503），绝不返回 null。
     */
    public String codeToOpenid(String code) {
        // ── mock 模式：显式开启 + 指定前缀，缺一不可 ─────────────────────────
        if (mockEnabled && code != null && code.startsWith(MOCK_PREFIX)) {
            String openid = code.substring(MOCK_PREFIX.length()).trim();
            if (openid.isEmpty()) {
                throw BizException.validation("mock code 缺少 openid 部分");
            }
            log.info("微信登录走 mock 通道 openidPrefix={} len={}", openid.substring(0, Math.min(4, openid.length())), openid.length());
            return openid;
        }

        // ── 真实模式 ────────────────────────────────────────────────────────
        if (!isConfigured()) {
            // AppID/secret 是外部依赖凭据，缺了就是「这条登录腿不可用」，如实降级（503 DEGRADED）。
            // 语义上不能用 422：那会把「服务端配置缺失」错怪到调用方头上。
            throw new BizException(com.duoduo.common.enums.ErrorCode.DEGRADED,
                    "微信登录未配置（缺少 AppID/Secret），请改用手机号登录");
        }
        return exchangeReal(code);
    }

    /**
     * 真实调用 jscode2session。仅当 isConfigured() 时才会被走到。
     */
    public String exchangeReal(String code) {
        String url = JSCODE2SESSION_URL
                + "?appid=" + URLEncoder.encode(appid, StandardCharsets.UTF_8)
                + "&secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)
                + "&js_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                + "&grant_type=authorization_code";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            if (node.hasNonNull("errcode") && node.get("errcode").asInt() != 0) {
                // 微信侧拒绝（code 过期 / 已用 / appid 不匹配）。errcode 含义对用户无意义，转人话。
                log.warn("jscode2session 被微信拒绝 errcode={}", node.get("errcode").asInt());
                throw BizException.unauthorized("微信登录凭据无效或已过期，请重新拉起登录");
            }
            String openid = node.path("openid").asText("");
            if (openid.isEmpty()) {
                log.warn("jscode2session 响应缺少 openid");
                throw BizException.businessRule("微信登录响应异常，请稍后重试");
            }
            log.info("jscode2session 成功 openidPrefix={} len={}",
                    openid.substring(0, Math.min(4, openid.length())), openid.length());
            return openid;
        } catch (java.io.IOException | java.lang.InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("调用微信 jscode2session 失败: {}", e.getClass().getSimpleName());
            throw degraded("微信服务暂时不可用，请稍后重试");
        }
    }

    private BizException degraded(String message) {
        return new BizException(com.duoduo.common.enums.ErrorCode.DEGRADED, message);
    }
}
