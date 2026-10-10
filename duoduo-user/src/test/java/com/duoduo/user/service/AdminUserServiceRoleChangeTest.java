package com.duoduo.user.service;

import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos.RoleChangeResult;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.PermVersionPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运营改角色的生效语义单测（第五十八轮 · P1-6 建立，E17 重写口径）。
 *
 * <h2>E17 把口径从"吊销刷新令牌"换成了"权限版本"</h2>
 * 旧口径只能保证「不能静默续期」：旧访问令牌仍带旧角色活到自然过期（≤15 分钟），
 * 降级场景下这 15 分钟是实打实的越权窗口。新口径是版本号精确收口 ——
 * 旧令牌<b>下一个请求</b>就被网关判过期。因此本组断言从"必须吊销"改成：
 * <ol>
 *   <li>变更时必须让版本 +1，并把新版本广播出去（否则网关照旧放行旧令牌）；</li>
 *   <li><b>广播成功时不吊销刷新令牌</b> —— 这正是"无需重新登录"的实现方式，
 *       改回去（顺手加一句吊销）会让用户被迫重登，是极易被当成"更安全"而误加的一行；</li>
 *   <li><b>广播失败时必须退回吊销</b> —— 宁可让人重登一次，
 *       也不能出现"运营界面写着权限已收回、实际旧令牌还能用"的假象。</li>
 * </ol>
 *
 * <h2>为什么升 / 降两条路径都要覆盖</h2>
 * 两条路径共用同一段逻辑，但业务含义相反：升级不即时生效只是「不方便」，
 * 降级不即时生效是「权限收不回来」。只测其中一条，另一条被改坏时测试仍全绿。
 */
class AdminUserServiceRoleChangeTest {

    private static final long ADMIN_ID = 710000000000000001L;
    private static final long TARGET_ID = 710000000000000002L;

    private final UserMapper userMapper = mock(UserMapper.class);
    private final RefreshTokenMapper refreshTokenMapper = mock(RefreshTokenMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final PermVersionPublisher permVersionPublisher = mock(PermVersionPublisher.class);

    private final AdminUserService service = new AdminUserService(userMapper,
            refreshTokenMapper, idGenerator, permVersionPublisher);

    @BeforeEach
    void loginAsAdmin() {
        UserContext.set(new LoginUser(ADMIN_ID, "13900000000", "ADMIN", true));
        // E18 起留痕由拦截器统一做；单测里没有拦截器，所以显式开一个上下文来承接
        // 业务侧补充的 target / 前后值（不开上下文时这些调用是静默 no-op，
        // 那样断言「前后值写对了没」会永远通过 —— 一个典型的假绿）
        AuditTrail.start(AuditAction.USER_ROLE_CHANGE);
    }

    @AfterEach
    void logout() {
        UserContext.clear();
        AuditTrail.clear();
    }

    private static UserEntity target(String role) {
        UserEntity entity = new UserEntity();
        entity.setId(TARGET_ID);
        entity.setPhone("13900000002");
        entity.setNickname("被改的人");
        entity.setRole(role);
        entity.setStatus("ACTIVE");
        entity.setPermVersion(4L);
        return entity;
    }

    /**
     * 造出「变更前读一次、变更后回读一次」的桩。
     *
     * <p>两次读都必须给出**与用例名相符**的角色：夹具与用例语义不一致时，
     * 断言看起来仍然会绿，但被守住的性质已经不是名字上写的那件事了。
     */
    private void afterBump(String oldRole, String newRole) {
        UserEntity bumped = target(newRole);
        bumped.setPermVersion(5L);
        when(userMapper.selectById(TARGET_ID)).thenReturn(target(oldRole), bumped);
    }

    @Test
    void 降权后版本自增并广播_不吊销刷新令牌() {
        afterBump("OPERATOR", "USER");
        when(permVersionPublisher.publish(TARGET_ID, 5L)).thenReturn(true);

        RoleChangeResult result = service.changeRole(TARGET_ID, "USER");

        // 版本必须 +1 且广播出去：这是"旧令牌下一个请求即失效"的唯一实现方式
        verify(permVersionPublisher).publish(TARGET_ID, 5L);
        // ★ 广播成功时**刻意不吊销**刷新令牌 —— 吊销了就等于强制重登，
        //   而 E17 要的正是"刷新一次即换到新角色、不必重新登录"
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
        assertThat(result.user().role()).isEqualTo("USER");
        assertThat(result.notice()).contains("下一个请求即生效");
    }

    @Test
    void 广播失败时退回吊销刷新令牌_不留假象() {
        afterBump("OPERATOR", "USER");
        when(permVersionPublisher.publish(TARGET_ID, 5L)).thenReturn(false);
        when(refreshTokenMapper.revokeAllByUser(TARGET_ID)).thenReturn(2);

        RoleChangeResult result = service.changeRole(TARGET_ID, "USER");

        // Redis 不可用时网关照旧读不到新版本 → 旧令牌还能用。
        // 这时必须退回"吊销刷新令牌"这条老路，并且把结果如实写进文案 ——
        // 运营需要知道这次变更生效到了哪一步，而不是看到一句"已生效"
        verify(refreshTokenMapper).revokeAllByUser(TARGET_ID);
        assertThat(result.notice()).contains("重新登录");
        assertThat(result.notice()).doesNotContain("下一个请求即生效");
    }

    @Test
    void 提权同样版本自增_保证角色立刻生效() {
        afterBump("USER", "SELLER");
        when(permVersionPublisher.publish(TARGET_ID, 5L)).thenReturn(true);

        service.changeRole(TARGET_ID, "SELLER");

        verify(permVersionPublisher).publish(TARGET_ID, 5L);
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
    }

    @Test
    void 自增交给数据库而不是读出来加一() {
        // 注：本用例只关心变更语句的形状，新旧角色用降权路径
        // 读出来 +1 再写回在并发改同一用户时会丢失更新（两人各读 v、都写 v+1）。
        // 这里钉住"变更语句里确实带了 perm_version 自增的 SQL"，而不是靠回读值反推 ——
        // 回读值看起来一样，丢失更新的缺陷只在并发下出现
        afterBump("OPERATOR", "USER");
        when(permVersionPublisher.publish(TARGET_ID, 5L)).thenReturn(true);

        service.changeRole(TARGET_ID, "USER");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<UserEntity>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(userMapper).update(any(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("perm_version = perm_version + 1");
    }

    @Test
    void 目标已是该角色时不写库也不广播() {
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("SELLER"));

        RoleChangeResult result = service.changeRole(TARGET_ID, "SELLER");

        verify(userMapper, never()).updateById(any());
        verify(userMapper, never()).update(any(), any());
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
        // 幂等：没有变更就不该动版本，否则每次"重复点一下"都会把别人的在线会话判过期
        verify(permVersionPublisher, never()).publish(any(), any());
        assertThat(result.notice()).contains("未做变更");
        // ★ E18 起留痕由拦截器统一做，所以"无事实变化"这件事必须**显式抑制** ——
        //   不抑制的话，重复确认会在审计表里堆成事件，把真正的变更淹没掉。
        //   这里断言的是「抑制被声明了」，而不是「没写审计」：后者在单测里必然成立
        //   （没有拦截器就没有写库路径），断言它等于什么都没测。
        assertThat(AuditTrail.current()).isNotNull();
        assertThat(AuditTrail.current().skipped())
                .as("幂等确认必须显式声明抑制，否则会写成一条审计记录")
                .isTrue();
        assertThat(AuditTrail.current().skipReason()).isNotBlank();
    }

    @Test
    void 降级ADMIN时先数管理员_最后一个不让降() {
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("ADMIN"));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(TARGET_ID, "USER"))
                .isInstanceOf(BizException.class);
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
        verify(permVersionPublisher, never()).publish(any(), any());
    }

    @Test
    void 不能给自己降权() {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(target("ADMIN"));
        when(userMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.changeRole(ADMIN_ID, "USER"))
                .isInstanceOf(BizException.class);
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
    }

    @Test
    void 角色变更必须留审计() {
        afterBump("USER", "SELLER");
        when(permVersionPublisher.publish(TARGET_ID, 5L)).thenReturn(true);

        service.changeRole(TARGET_ID, "SELLER");

        // E18：服务层不再自己 insert 审计表，改为「补充拦截器采集不到的三件事」。
        // 断言因此落在 AuditTrail 上 —— 若把这几行删掉（回归到"只靠注解"），
        // 本用例会红；而删掉它们不会影响任何业务行为，属于极易被顺手移除的代码。
        AuditTrail trail = AuditTrail.current();
        assertThat(trail).isNotNull();
        assertThat(trail.targetId()).as("目标用户必须写进审计").isEqualTo(TARGET_ID);
        assertThat(trail.targetType()).isEqualTo("USER");
        assertThat(trail.beforeValue()).isEqualTo("USER");
        assertThat(trail.afterValue()).isEqualTo("SELLER");
        assertThat(trail.detail()).as("人读摘要应能直接看出改了哪个角色").isEqualTo("USER -> SELLER");
        assertThat(trail.tenantId())
                .as("★ 角色是平台级概念，租户必须为 0；填了 actor 的 userId 会在审计页误显示成「某家店的动作」")
                .isZero();
        assertThat(trail.skipped()).as("发生了真实变更，绝不能被抑制").isFalse();
    }

    @Test
    void 广播失败也要留审计_前后值不因分支不同而缺失() {
        afterBump("OPERATOR", "USER");
        when(permVersionPublisher.publish(TARGET_ID, 5L)).thenReturn(false);
        when(refreshTokenMapper.revokeAllByUser(TARGET_ID)).thenReturn(1);

        service.changeRole(TARGET_ID, "USER");

        AuditTrail trail = AuditTrail.current();
        assertThat(trail.beforeValue()).isEqualTo("OPERATOR");
        assertThat(trail.afterValue()).isEqualTo("USER");
        // 广播失败只影响「生效路径」（是否需要重登），**不影响**这次变更本身要不要留痕 ——
        // 把留痕放在广播成功分支里，会让 Redis 一挂就出现一段"查不到谁改的"空窗
        assertThat(trail.skipped()).isFalse();
    }
}
