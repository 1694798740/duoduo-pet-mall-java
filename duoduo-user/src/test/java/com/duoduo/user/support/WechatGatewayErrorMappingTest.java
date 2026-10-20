package com.duoduo.user.support;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信错误码分类的确定性单测（2026-10-20 绑微信被踢下线事故）。
 *
 * <p>背景：此前所有微信侧失败都抛 401 {@code UNAUTHORIZED}，小程序据此判定
 * 「登录态失效」→ 清空本地会话 → 用户一绑定就被登出。这里把分类钉死：
 * <ul>
 *   <li>AppSecret / AppID / IP 白名单类问题 = <b>服务端配置错</b>（503，配置错误）；</li>
 *   <li>code 过期/已用/无效 = <b>请求参数问题</b>（422，凭据无效）；</li>
 *   <li>两者都<b>不是</b> UNAUTHORIZED。</li>
 * </ul>
 */
class WechatGatewayErrorMappingTest {

    @Test
    @DisplayName("invalid appsecret → 503 配置错误（不是 401）")
    void invalidAppSecret() {
        BizException e = WechatGateway.toException(40125, "invalid appsecret, rid: 6ad714ec");
        assertEquals(ErrorCode.WECHAT_CONFIG_ERROR, e.getErrorCode());
        assertEquals(503, e.getErrorCode().httpStatusValue());
        assertTrue(e.getMessage().contains("AppSecret"));
    }

    @Test
    @DisplayName("invalid code（同码 40125 但语义不同）→ 400 凭据无效")
    void invalidCode() {
        BizException e = WechatGateway.toException(40125, "invalid code, rid: xx");
        assertEquals(ErrorCode.WECHAT_CODE_INVALID, e.getErrorCode());
        assertEquals(400, e.getErrorCode().httpStatusValue());
    }

    @Test
    @DisplayName("code 已被使用 40163 / 无效 40029 → 400")
    void codeUsedOrInvalid() {
        assertEquals(ErrorCode.WECHAT_CODE_INVALID, WechatGateway.toException(40163, "code been used").getErrorCode());
        assertEquals(ErrorCode.WECHAT_CODE_INVALID, WechatGateway.toException(40029, "invalid code").getErrorCode());
    }

    @Test
    @DisplayName("AppID 无效 40013 / IP 不在白名单 40164 → 503 配置错误")
    void appidOrIpProblems() {
        assertEquals(ErrorCode.WECHAT_CONFIG_ERROR, WechatGateway.toException(40013, "invalid appid").getErrorCode());
        assertEquals(ErrorCode.WECHAT_CONFIG_ERROR,
                WechatGateway.toException(40164, "invalid ip, not in whitelist").getErrorCode());
    }

    @Test
    @DisplayName("任何分类都不得产生 401 UNAUTHORIZED（否则端侧会清登录态）")
    void neverUnauthorized() {
        int[][] cases = {{40125, 0}, {40029, 0}, {40163, 0}, {40013, 0}, {40164, 0}, {-1, 0}};
        String[] msgs = {"invalid appsecret", "invalid code", "code been used", "invalid appid", "invalid ip", "unknown"};
        for (int i = 0; i < cases.length; i++) {
            ErrorCode code = WechatGateway.toException(cases[i][0], msgs[i]).getErrorCode();
            assertTrue(code != ErrorCode.UNAUTHORIZED,
                    "errcode=" + cases[i][0] + " 被映射成 UNAUTHORIZED，会导致绑定失败即登出");
        }
    }
}
