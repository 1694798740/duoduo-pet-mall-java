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
 * 运营改角色的生效语义单测（第五十八轮 · P1-6）。
 *
 * <h2>为什么「改完角色必须吊销刷新令牌」要单独钉住</h2>
 * 只改库里的角色，对方手上的访问令牌还是旧角色，而且<b>还能靠刷新令牌静默续期</b>——
 * 降级场景下这等于「已经被收回的权限可以继续用」，比「升级不生效」危险得多。
 * 吊销全部刷新令牌后，旧访问令牌最多活到自然过期（15 分钟），且无法续期。
 *
 * <h2>为什么升 / 降两条路径都要覆盖</h2>
 * 两条路径共用同一句吊销，但业务含义相反：升级不即时生效只是「不方便」，
 * 降级不即时生效是「权限收不回来」。只测其中一条，另一条被改坏时测试仍全绿。
 */
class AdminUserServiceRoleChangeTest {

    private static final long ADMIN_ID = 710000000000000001L;
    private static final long TARGET_ID = 710000000000000002L;

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final RefreshTokenMapper refreshTokenMapper = mock(RefreshTokenMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);

    private final AdminUserService service = new AdminUserService(userMapper, auditLogMapper,
            refreshTokenMapper, idGenerator);

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
        return entity;
    }

    @Test
    void 降权后吊销全部刷新令牌() {
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("OPERATOR"));
        when(userMapper.selectCount(any())).thenReturn(2L);
        when(refreshTokenMapper.revokeAllByUser(TARGET_ID)).thenReturn(3);

        RoleChangeResult result = service.changeRole(TARGET_ID, "USER");

        verify(refreshTokenMapper).revokeAllByUser(TARGET_ID);
        assertThat(result.user().role()).isEqualTo("USER");
        assertThat(result.notice()).contains("重新登录");
    }

    @Test
    void 提权同样吊销_保证角色立刻生效() {
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("USER"));
        when(refreshTokenMapper.revokeAllByUser(TARGET_ID)).thenReturn(1);

        service.changeRole(TARGET_ID, "SELLER");

        verify(refreshTokenMapper).revokeAllByUser(TARGET_ID);
    }

    @Test
    void 目标已是该角色时不写库也不吊销() {
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("SELLER"));

        RoleChangeResult result = service.changeRole(TARGET_ID, "SELLER");

        verify(userMapper, never()).updateById(any());
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
        assertThat(result.notice()).contains("未做变更");
    }

    @Test
    void 降级ADMIN时先数管理员_最后一个不让降() {
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("ADMIN"));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(TARGET_ID, "USER"))
                .isInstanceOf(BizException.class);
        verify(refreshTokenMapper, never()).revokeAllByUser(any());
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
        when(userMapper.selectById(TARGET_ID)).thenReturn(target("USER"));
        when(refreshTokenMapper.revokeAllByUser(TARGET_ID)).thenReturn(1);

        service.changeRole(TARGET_ID, "SELLER");

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).isEqualTo("USER -> SELLER");
        assertThat(captor.getValue().getActorId()).isEqualTo(ADMIN_ID);
    }
}
