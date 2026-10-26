package com.duoduo.social.support.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.MerchantMemberBrief;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.mapper.SupportSessionMapper;
import com.duoduo.social.mapper.SupportTicketLogMapper;
import com.duoduo.social.support.dto.SupportDtos.TicketTransferRequest;
import com.duoduo.social.support.dto.SupportDtos.TicketView;
import com.duoduo.social.support.entity.SupportSessionEntity;
import com.duoduo.social.support.entity.SupportTicketLogEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-04 · 客服工单认领 / 转交 / 释放 / 审计。
 *
 * <p>守四条：并发认领只有一个成功；处理人不是访问控制（全店仍可见）；
 * 转交目标必须是在职成员；每次成功动作同事务写一条处理记录。
 */
class SupportTicketServiceTest {

    private final SupportSessionMapper sessionMapper = mock(SupportSessionMapper.class);
    private final SupportTicketLogMapper logMapper = mock(SupportTicketLogMapper.class);
    private final UserClient userClient = mock(UserClient.class);
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1);
    private final SupportTicketService service =
            new SupportTicketService(sessionMapper, logMapper, userClient, idGenerator);

    @BeforeEach
    void defaults() {
        when(logMapper.listBySession(anyLong())).thenReturn(List.of());
        when(userClient.merchantMembers(anyLong())).thenReturn(ApiResponse.ok(List.of(
                new MerchantMemberBrief("101", "店主", null, "OWNER"),
                new MerchantMemberBrief("102", "店员", null, "STAFF"))));
        when(userClient.briefs(anyString())).thenReturn(ApiResponse.ok(List.of(
                new UserBrief(101L, "店主", null),
                new UserBrief(102L, "店员", null))));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private static LoginUser staff(long id) {
        return new LoginUser(id, "13800000000", "SELLER", false);
    }

    private static SupportSessionEntity session(Long assignee) {
        SupportSessionEntity s = new SupportSessionEntity();
        s.setId(900L);
        s.setSellerId(101L);
        s.setUserId(201L);
        s.setStatus("OPEN");
        s.setAssigneeId(assignee);
        s.setAssignedAt(assignee == null ? null : LocalDateTime.now());
        return s;
    }

    @Test
    void 认领成功_写入处理记录且视图带处理人() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(null));
        when(sessionMapper.claimTicket(eq(900L), eq(101L), eq(102L), any())).thenReturn(1);

        TicketView view = service.claim(101L, 900L);

        assertThat(view.assigneeId()).isEqualTo("102");
        assertThat(view.assigneeName()).isEqualTo("店员");
        ArgumentCaptor<SupportTicketLogEntity> captor = ArgumentCaptor.forClass(SupportTicketLogEntity.class);
        verify(logMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("CLAIM");
        assertThat(captor.getValue().getActorId()).isEqualTo(102L);
        assertThat(captor.getValue().getActorRole()).isEqualTo("STAFF");
        assertThat(captor.getValue().getFromAssignee()).isNull();
        assertThat(captor.getValue().getToAssignee()).isEqualTo(102L);
    }

    @Test
    void 重复认领_幂等且不重复记日志() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(102L));

        TicketView view = service.claim(101L, 900L);

        assertThat(view.assigneeId()).isEqualTo("102");
        verify(sessionMapper, never()).claimTicket(anyLong(), anyLong(), anyLong(), any());
        verify(logMapper, never()).insert(any());
    }

    @Test
    void 并发认领失败_409且提示被谁认领() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(null));
        when(sessionMapper.claimTicket(eq(900L), eq(101L), eq(102L), any())).thenReturn(0);
        // 第一次读 null，更新失败后重读发现已被店主认领
        when(sessionMapper.selectById(900L)).thenReturn(session(null), session(101L));

        assertThatThrownBy(() -> service.claim(101L, 900L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("认领");
        verify(logMapper, never()).insert(any());
    }

    @Test
    void 已关闭会话不能认领() {
        UserContext.set(staff(102L));
        SupportSessionEntity closed = session(null);
        closed.setStatus("CLOSED");
        when(sessionMapper.selectById(900L)).thenReturn(closed);

        assertThatThrownBy(() -> service.claim(101L, 900L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话已结束");
    }

    @Test
    void 转交_非当前处理人且非店主_403() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(101L));

        assertThatThrownBy(() -> service.transfer(101L, 900L, new TicketTransferRequest(102L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有当前处理人或店主");
        verify(sessionMapper, never()).transferTicket(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void 转交成功_记录from与to() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(102L));
        when(sessionMapper.transferTicket(eq(900L), eq(101L), eq(102L), eq(101L), any())).thenReturn(1);

        TicketView view = service.transfer(101L, 900L, new TicketTransferRequest(101L, "店长接手"));

        assertThat(view.assigneeId()).isEqualTo("101");
        ArgumentCaptor<SupportTicketLogEntity> captor = ArgumentCaptor.forClass(SupportTicketLogEntity.class);
        verify(logMapper).insert(captor.capture());
        SupportTicketLogEntity log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("TRANSFER");
        assertThat(log.getFromAssignee()).isEqualTo(102L);
        assertThat(log.getToAssignee()).isEqualTo(101L);
        assertThat(log.getNote()).isEqualTo("店长接手");
    }

    @Test
    void 转交给非本店成员_400且不写库() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(102L));

        assertThatThrownBy(() -> service.transfer(101L, 900L, new TicketTransferRequest(999L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是本店在职成员");
        verify(sessionMapper, never()).transferTicket(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void 释放成功_处理人清空并记录RELEASE() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(102L));
        when(sessionMapper.releaseTicket(eq(900L), eq(101L), eq(102L), any())).thenReturn(1);

        TicketView view = service.release(101L, 900L);

        assertThat(view.assigneeId()).isNull();
        ArgumentCaptor<SupportTicketLogEntity> captor = ArgumentCaptor.forClass(SupportTicketLogEntity.class);
        verify(logMapper).insert(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("RELEASE");
        assertThat(captor.getValue().getFromAssignee()).isEqualTo(102L);
        assertThat(captor.getValue().getToAssignee()).isNull();
    }

    @Test
    void 跨店查看工单_403() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(null));

        assertThatThrownBy(() -> service.view(202L, 900L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于当前店铺");
    }

    @Test
    void 工单视图_返回成员名单与处理记录() {
        UserContext.set(staff(102L));
        when(sessionMapper.selectById(900L)).thenReturn(session(102L));
        SupportTicketLogEntity log = new SupportTicketLogEntity();
        log.setId(1L);
        log.setSessionId(900L);
        log.setAction("CLAIM");
        log.setActorId(102L);
        log.setActorRole("STAFF");
        log.setToAssignee(102L);
        log.setCreatedAt(LocalDateTime.now());
        when(logMapper.listBySession(900L)).thenReturn(List.of(log));

        TicketView view = service.view(101L, 900L);

        assertThat(view.members()).hasSize(2);
        assertThat(view.members()).extracting(m -> m.role()).containsExactly("OWNER", "STAFF");
        assertThat(view.logs()).hasSize(1);
        assertThat(view.logs().get(0).actionLabel()).isEqualTo("认领");
        assertThat(view.logs().get(0).toAssigneeName()).isEqualTo("店员");
    }
}
