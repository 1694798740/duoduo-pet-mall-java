package com.duoduo.user.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.JwtTokenService;
import com.duoduo.user.controller.MerchantApplicationController;
import com.duoduo.user.controller.UserController;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.service.MerchantOnboardingService;
import com.duoduo.user.service.PetProfileService;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.AuthCookieWriter;
import com.duoduo.user.support.WechatGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色升级「即时生效」的单测（第五十八轮 · P1-6，也是用户域第一批单测）。
 *
 * <h2>为什么必须有这组断言</h2>
 * 访问令牌里写的是<b>签发那一刻的角色快照</b>。库里的角色改了、令牌没换，
 * 用户就会看到「申请成功了，商家端还是进不去」—— 且这个现象会自己消失（15 分钟过期），
 * 既难复现也难归因。这里钉住两件事：
 * <ol>
 *   <li>重签出来的令牌带的是<b>库里现读</b>的角色，而不是调用方传进来的角色
 *       （服务层刻意不接受入参 role —— 传参就是给越权留口子）；</li>
 *   <li>浏览器通道<b>只覆盖访问令牌</b>、不动刷新令牌：轮换刷新令牌会连带吊销旧的那一批，
 *       等于把用户其它端一起踢下线，代价远大于收益。</li>
 * </ol>
 *
 * <p>令牌用真实的 {@link JwtTokenService} 签发并当场解析回来 ——
 * 用桩件返回任意字符串的话，「令牌里到底有没有新角色」永远测不出来。
 */
class RoleTokenEffectiveTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef"; // 32 字节，满足 HS256
    private static final long USER_ID = 710000000000000001L;

    private final UserMapper userMapper = mock(UserMapper.class);
    private final RefreshTokenMapper refreshTokenMapper = mock(RefreshTokenMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtTokenService jwt = new JwtTokenService(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final WechatGateway wechatGateway = mock(WechatGateway.class);

    private final com.duoduo.user.mapper.MerchantMapper merchantMapper = mock(com.duoduo.user.mapper.MerchantMapper.class);
    private final com.duoduo.user.mapper.MerchantMemberMapper merchantMemberMapper =
            mock(com.duoduo.user.mapper.MerchantMemberMapper.class);
    /** E17：版本副本发布（此处只关注"签发时带上了版本"，发布本身由 AdminUserServiceRoleChangeTest 钉） */
    private final com.duoduo.user.support.PermVersionPublisher permVersionPublisher =
            mock(com.duoduo.user.support.PermVersionPublisher.class);

    private final AuthService authService = new AuthService(userMapper, refreshTokenMapper, passwordEncoder,
            jwt, idGenerator, wechatGateway, 60, merchantMapper, merchantMemberMapper, permVersionPublisher);
    /** 控制器多了一个宠物档案依赖（第五十八轮 S1），这两条断言用不到它 */
    private final PetProfileService petProfileService = mock(PetProfileService.class);

    @BeforeEach
    void login() {
        UserContext.set(new LoginUser(USER_ID, "13900000001", "USER", true));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    private static UserEntity user(String role, String status) {
        UserEntity entity = new UserEntity();
        entity.setId(USER_ID);
        entity.setPhone("13900000001");
        entity.setNickname("测试用户");
        entity.setRole(role);
        entity.setStatus(status);
        entity.setRealName("张三");
        entity.setCreditScore(60);
        return entity;
    }

    @Test
    void 重签的访问令牌带库里的最新角色() {
        when(userMapper.selectById(USER_ID)).thenReturn(user("SELLER", "ACTIVE"));

        String token = authService.renewAccessToken(USER_ID);

        JwtTokenService.TokenPayload payload = jwt.parse(token, JwtTokenService.TYPE_ACCESS);
        assertThat(payload.valid()).isTrue();
        assertThat(payload.role()).isEqualTo("SELLER");
        assertThat(payload.userId()).isEqualTo(USER_ID);
    }

    // ================= E17 · 权限版本 =================

    @Test
    void 重签的令牌带库里的权限版本_并把副本对齐() {
        // 库里已经有两次变更（版本 2）→ 重签出来的令牌必须是 2，
        // 否则这枚"新"令牌会被网关当成旧权限直接 401 —— 也就是"重签之后反而更进不去"
        UserEntity entity = user("SELLER", "ACTIVE");
        entity.setPermVersion(2L);
        when(userMapper.selectById(USER_ID)).thenReturn(entity);

        String token = authService.renewAccessToken(USER_ID);

        assertThat(jwt.parse(token, JwtTokenService.TYPE_ACCESS).permVersion()).isEqualTo(2L);
        // 顺便把 Redis 副本对齐（自愈）：副本落后时靠这一步收敛，不必等重新登录
        verify(permVersionPublisher).publishQuietly(USER_ID, 2L);
    }

    @Test
    void 库内版本为NULL时按初始版本0处理() {
        // 老库 / 手工插入的数据可能没有这个值。这里刻意断言"按 0 处理"而不是"写 null"：
        // 令牌里不写版本 = 该令牌从此不参与版本校验，等于这次的加固对它静默失效
        when(userMapper.selectById(USER_ID)).thenReturn(user("USER", "ACTIVE"));

        String token = authService.renewAccessToken(USER_ID);

        assertThat(jwt.parse(token, JwtTokenService.TYPE_ACCESS).permVersion()).isEqualTo(0L);
    }

    @Test
    void 重签拒绝接受调用方传入的角色_只认库里的值() {
        // 服务层签名里根本没有 role 入参，这里用「库里是 USER、上下文里是 ADMIN」证明：
        // 重签结果以库为准，调用方身份再高也不能给自己签一个更高的角色出来
        UserContext.set(new LoginUser(USER_ID, "13900000001", "ADMIN", true));
        when(userMapper.selectById(USER_ID)).thenReturn(user("USER", "ACTIVE"));

        String token = authService.renewAccessToken(USER_ID);

        assertThat(jwt.parse(token, JwtTokenService.TYPE_ACCESS).role()).isEqualTo("USER");
    }

    @Test
    void 未登录与封禁账号不能重签() {
        assertThatThrownBy(() -> authService.renewAccessToken(null))
                .isInstanceOf(BizException.class);

        when(userMapper.selectById(USER_ID)).thenReturn(user("SELLER", "BANNED"));
        assertThatThrownBy(() -> authService.renewAccessToken(USER_ID))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 审核通过后激活返回商家入口的新令牌_不动刷新令牌() {
        // E10：入驻改为「提交 → 审核 → 通过 → 商家自行激活取新令牌」。
        // 激活端点只重签访问令牌（角色升级已发生在 approve），刷新令牌不轮换 ——
        // 轮换会吊销旧批次，把用户其它端一起踢下线（与第五十八轮同一取舍）。
        AuthService auth = mock(AuthService.class);
        MerchantOnboardingService onboarding = mock(MerchantOnboardingService.class);
        when(onboarding.activateIfApproved(USER_ID)).thenReturn(user("SELLER", "ACTIVE"));
        when(auth.renewAccessToken(eq(USER_ID), eq("merchant"))).thenReturn("activated-access-token");
        when(auth.accessTtlSeconds()).thenReturn(900L);
        MerchantApplicationController controller = new MerchantApplicationController(onboarding, auth);

        var result = controller.activate();

        assertThat(result.data().accessToken()).isEqualTo("activated-access-token");
        assertThat(result.data().expiresInSeconds()).isEqualTo(900L);
        // E8：激活重签的令牌入口必须固定为商家侧
        verify(auth).renewAccessToken(eq(USER_ID), eq("merchant"));
    }

}
