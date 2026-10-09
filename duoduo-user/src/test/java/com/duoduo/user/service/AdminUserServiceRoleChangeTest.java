package com.duoduo.user.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos.RoleChangeResult;
import com.duoduo.user.entity.AuditLogEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.AuditLogMapper;
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
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final RefreshTokenMapper refreshTokenMapper = mock(RefreshTokenMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final PermVersionPublisher permVersionPublisher = mock(PermVersionPublisher.class);

    private final AdminUserService service = new AdminUserService(userMapper, auditLogMapper,
            refreshTokenMapper, idGenerator, permVersionPublisher);

    @BeforeEach
    void loginAsAdmin() {
        UserContext.set(new LoginUser(ADMIN_ID, "13900000000", "ADMIN", true));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
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

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).isEqualTo("USER -> SELLER");
        assertThat(captor.getValue().getActorId()).isEqualTo(ADMIN_ID);
    }
}
