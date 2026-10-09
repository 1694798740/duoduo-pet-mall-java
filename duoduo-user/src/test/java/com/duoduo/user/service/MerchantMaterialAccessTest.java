package com.duoduo.user.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.entity.AuditLogEntity;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.mapper.AuditLogMapper;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.mapper.MerchantMemberMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.MerchantMaterialStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料读取的**授权与留痕**单测（E12）。
 *
 * <h2>守什么</h2>
 * 私有通道的全部价值就在这一层：网关只保证"登录了"，**"是不是该看这份材料"**
 * 必须在这里判定。三个口径钉死：店主可读、运营可读、**其他任何登录用户 403**；
 * 且每次成功读取都要留痕（谁 / 哪家店 / 哪份材料）—— 材料泄露的排查入口就是审计表。
 */
class MerchantMaterialAccessTest {

    private static final long OWNER_ID = 710000000000000001L;
    private static final long STRANGER_ID = 710000000000000002L;
    private static final long OPERATOR_ID = 710000000000000201L;
    private static final long MERCHANT_ID = 710000000000000101L;
    private static final String STORED = "0a0a0a0a-1111-2222-3333-444444444444.jpg";

    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantMemberMapper memberMapper = mock(MerchantMemberMapper.class);
    private final MerchantMaterialMapper materialMapper = mock(MerchantMaterialMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MerchantMaterialStorage storage = mock(MerchantMaterialStorage.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

    private final MerchantOnboardingService service =
            new MerchantOnboardingService(merchantMapper, memberMapper, materialMapper,
                    userMapper, idGenerator, objectMapper, storage, auditLogMapper,
                    mock(MerchantViolationService.class));

    @BeforeEach
    void setUp() {
        MerchantEntity entity = new MerchantEntity();
        entity.setId(MERCHANT_ID);
        entity.setOwnerUserId(OWNER_ID);
        entity.setStatus("SUBMITTED");
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(entity);
        when(storage.read(MERCHANT_ID, STORED))
                .thenReturn(new MerchantMaterialStorage.ReadResult(null, "image/jpeg"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAs(long userId, String role) {
        UserContext.set(new LoginUser(userId, "1390000000" + (userId % 10), role, true));
    }

    @Test
    void 店主本人可读并留下审计() {
        loginAs(OWNER_ID, "USER");

        service.readMaterial(OWNER_ID, MERCHANT_ID, STORED);

        ArgumentCaptor<AuditLogEntity> cap = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogMapper).insert(cap.capture());
        assertThat(cap.getValue().getActorId()).isEqualTo(OWNER_ID);
        assertThat(cap.getValue().getTargetId()).isEqualTo(MERCHANT_ID);
        assertThat(cap.getValue().getDetail()).isEqualTo(STORED);
    }

    @Test
    void 运营可读_审核职责需要() {
        loginAs(OPERATOR_ID, "OPERATOR");

        assertThat(service.readMaterial(OPERATOR_ID, MERCHANT_ID, STORED)).isNotNull();
    }

    @Test
    void 管理员可读() {
        loginAs(710000000000000301L, "ADMIN");
        assertThat(service.readMaterial(710000000000000301L, MERCHANT_ID, STORED)).isNotNull();
    }

    @Test
    void 无关登录用户读取_403() {
        // 403 不是 404：文件确实存在，拒绝的原因是"你不该看" ——
        // 写成 404 会引导攻击者以为猜错了文件名继续枚举
        loginAs(STRANGER_ID, "USER");

        assertThatThrownBy(() -> service.readMaterial(STRANGER_ID, MERCHANT_ID, STORED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权查看");
    }

    @Test
    void 留痕失败不阻断读取_但审计口子留在日志() {
        // 审计写入炸掉时读取仍要成功（可用性优先），否则一张读不出的图会卡住整个审核
        loginAs(OWNER_ID, "USER");
        when(auditLogMapper.insert(any(AuditLogEntity.class))).thenThrow(new RuntimeException("db down"));

        assertThat(service.readMaterial(OWNER_ID, MERCHANT_ID, STORED)).isNotNull();
    }

    @Test
    void 未登录读取_直接拒绝() {
        // 网关已拦未登录流量；这里防御的是"服务被直连"的纵深场景
        assertThatThrownBy(() -> service.readMaterial(null, MERCHANT_ID, STORED))
                .isInstanceOf(Exception.class);
    }
}
