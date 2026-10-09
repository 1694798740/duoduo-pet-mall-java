package com.duoduo.common.security;

import com.duoduo.common.context.LoginUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E17 · 权限版本在令牌里的往返与兼容性。
 *
 * <h2>这组断言真正防的是什么</h2>
 * 版本号是"网关据此判断这枚令牌是不是旧权限"的唯一依据。三件事任意一件错，
 * 表现都是<b>静默失效</b>（版本校验悄悄不生效，权限变更回到 15 分钟窗口）：
 * <ol>
 *   <li>签发时没把版本写进令牌 → 网关照"老令牌"处理，放行；</li>
 *   <li>解析时读不出（声明名对不上 / 类型转换失败）→ 同上；</li>
 *   <li>把"没有声明"当成版本 0 → 反而会把所有老令牌判成过期，等于上线当天全站掉线。</li>
 * </ol>
 * 第 1、2 条只能靠"签发后立刻解析回来比对"来钉；第 3 条靠显式断言 {@code null}。
 */
class JwtTokenServicePermVersionTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final long USER_ID = 710000000000000001L;

    private final JwtTokenService jwt =
            new JwtTokenService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));

    private static LoginUser user(String role) {
        return new LoginUser(USER_ID, "13900000001", role, true);
    }

    @Test
    void 带版本的令牌能原样读回() {
        String token = jwt.createAccessToken(user("OPERATOR"), null, 7L);

        var payload = jwt.parse(token, JwtTokenService.TYPE_ACCESS);

        assertThat(payload.valid()).isTrue();
        assertThat(payload.permVersion()).isEqualTo(7L);
        assertThat(payload.role()).isEqualTo("OPERATOR");
    }

    @Test
    void 版本与入口可以共存_互不覆盖() {
        // 两个声明都走 Map 组装，最容易出的错是"后写的把前写的覆盖了"
        String token = jwt.createAccessToken(user("ADMIN"), "admin", 12L);

        var payload = jwt.parse(token, JwtTokenService.TYPE_ACCESS);

        assertThat(payload.entry()).isEqualTo("admin");
        assertThat(payload.permVersion()).isEqualTo(12L);
    }

    @Test
    void 不声明版本的令牌读回为null_而不是0() {
        // 这是兼容窗口的全部依据：null = "不参与版本校验"（放行），
        // 若被当成 0，所有老令牌都会因为"0 != 当前版本"被判过期 —— 加一个字段踢掉全部在线用户
        var payload = jwt.parse(jwt.createAccessToken(user("USER")), JwtTokenService.TYPE_ACCESS);

        assertThat(payload.valid()).isTrue();
        assertThat(payload.permVersion()).isNull();
    }

    @Test
    void 版本0也要能读回_不能被当成没有声明() {
        // 新注册账号就是 0。若实现里用 "permVersion != null" 之外的方式（比如 > 0）判定，
        // 新账号的令牌会被当成老令牌 —— 从它被改角色的那一刻起，校验对它就不再生效
        var payload = jwt.parse(jwt.createAccessToken(user("USER"), null, 0L), JwtTokenService.TYPE_ACCESS);

        assertThat(payload.permVersion()).isNotNull();
        assertThat(payload.permVersion()).isZero();
    }

    @Test
    void 大数值版本不被截断() {
        // 声明走 JSON 数字通道：小整数与长整数的反序列化类型不同（Integer / Long），
        // 这里用超过 int 范围的值钉住"取 Number 再转 long"这条路径
        long big = 5_000_000_000L;
        var payload = jwt.parse(jwt.createAccessToken(user("ADMIN"), null, big), JwtTokenService.TYPE_ACCESS);

        assertThat(payload.permVersion()).isEqualTo(big);
    }

    @Test
    void 刷新令牌不参与版本校验_刻意不带该声明() {
        // 刷新令牌若也带（并被校验），那么"版本已过期"时连刷新都做不了 ——
        // 客户端会陷入死循环：401 → 刷新 → 401。刷新必须永远能拿到"当前最新版本"的新令牌
        var payload = jwt.parse(jwt.createRefreshToken(USER_ID, "jti-1", "admin"), JwtTokenService.TYPE_REFRESH);

        assertThat(payload.valid()).isTrue();
        assertThat(payload.permVersion()).isNull();
    }

    @Test
    void 无效令牌的载荷里也没有版本() {
        var payload = jwt.parse("not-a-real-token", JwtTokenService.TYPE_ACCESS);

        assertThat(payload.valid()).isFalse();
        assertThat(payload.permVersion()).isNull();
    }
}
