package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.SaveDraftRequest;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMaterialEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.AuditLogMapper;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.mapper.MerchantMemberMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.LicenseExpiry;
import com.duoduo.user.support.MerchantMaterialStorage;
import com.duoduo.user.support.MerchantStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资质有效期与续期单测（E15）。
 *
 * <h2>这组测试守的是什么</h2>
 * E15 的危害全是「静默」的：提醒没发出去，商家不会知道（到期才知道，然后投诉）；
 * 自动停业多停了一天，商家只会看到「店突然没了」而查不出原因；
 * 续期把状态回错成 APPROVED，等于免审放行一张可能造假的证照。
 * 这里把每条路径的**状态落点**与**去重依据**都钉在断言里，
 * 而不是只断言「没抛异常」。
 *
 * <p>另外两条刻意保留的设计也在此加锁：<b>NULL = 长期有效</b>（不是过期）、
 * <b>续期提交不通过（走完整审核）</b>。
 */
class MerchantLicenseRenewalTest {

    private static final long USER_ID = 710000000000000001L;
    private static final long MERCHANT_ID = 710000000000000101L;
    private static final long OPERATOR_ID = 710000000000000201L;

    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantMemberMapper memberMapper = mock(MerchantMemberMapper.class);
    private final MerchantMaterialMapper materialMapper = mock(MerchantMaterialMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final MerchantMaterialStorage materialStorage = mock(MerchantMaterialStorage.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

    private final MerchantOnboardingService service =
            new MerchantOnboardingService(merchantMapper, memberMapper, materialMapper,
                    userMapper, idGenerator, new ObjectMapper(), materialStorage, auditLogMapper);

    /** service 每次 selectList 都返回这一份材料行，用例可以改它模拟「商家填了哪些有效期」 */
    private final List<MerchantMaterialEntity> materialRows = new ArrayList<>();

    @BeforeAll
    static void initMybatisTableInfo() {
        // LambdaUpdateWrapper 在**运行期**靠 TableInfo 缓存把方法引用翻译成列名，
        // 纯 Mockito 单测里没有 Spring 容器去初始化它 —— 不预热就会在
        // `.set(MerchantEntity::getExpireNotifyStage, null)` 那一行抛
        // 「MybatisPlus can not find lambda cache for this entity」。
        // 这个错在单测里看着像产品缺陷，其实是测试环境缺一步（E14 已在商品域踩过）。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MerchantEntity.class);
    }

    @BeforeEach
    void setUp() {
        when(idGenerator.nextId()).thenReturn(710000000000000301L);
        UserEntity owner = new UserEntity();
        owner.setId(USER_ID);
        owner.setPhone("13900000001");
        owner.setRole("SELLER");
        owner.setStatus("ACTIVE");
        owner.setRealName("张三");
        when(userMapper.selectById(USER_ID)).thenReturn(owner);
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(materialMapper.selectList(any())).thenAnswer(inv -> materialRows);
        when(materialMapper.selectOne(any())).thenAnswer(inv ->
                materialRows.isEmpty() ? null : materialRows.get(0));
        when(materialMapper.selectById(anyLong())).thenAnswer(inv ->
                materialRows.isEmpty() ? null : materialRows.get(0));
    }

    // ---------------- 派生店铺到期日 ----------------

    @Test
    @DisplayName("保存材料后派生店铺到期日 = 多份材料里最早的那个（不是最近的那个）")
    void derivedExpiryTakesEarliest() {
        merchant(MerchantStatus.APPROVED);
        // 营业执照 2027-01-01、行业许可证 2026-08-01：店铺层面必须按 8-01 判定，
        // 否则那张先过期的许可证会让店铺在「账面上还没到期」的时候违规经营
        materialRow("BUSINESS_LICENSE", LocalDateTime.of(2027, 1, 1, 23, 59, 59));
        materialRow("INDUSTRY_PERMIT", LocalDateTime.of(2026, 8, 1, 23, 59, 59));

        ApplicationView view = service.ensureApplication(USER_ID);

        assertThat(view.licenseExpireAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 23, 59, 59));
    }

    @Test
    @DisplayName("★ 一份带有效期的材料都没有 → 店铺无到期概念（NULL），不是「今天到期」")
    void noExpiryMeansNoConceptOfExpiry() {
        merchant(MerchantStatus.APPROVED);
        materialRow("BUSINESS_LICENSE", null);

        ApplicationView view = service.ensureApplication(USER_ID);

        assertThat(view.licenseExpireAt()).isNull();
        assertThat(view.licenseStage()).isEqualTo("NONE");
        // 剩余天数回 null 而不是 0：0 在界面上会被渲染成「今天到期」，
        // 这才是「长期有效」与「即将失效」真正会被混淆的地方
        assertThat(view.licenseRemainingDays()).isNull();
    }

    @Test
    @DisplayName("写入有效期：空串 = 改为长期有效（显式可撤回，不是「未填」）")
    void blankExpiryMeansLongLived() {
        merchant(MerchantStatus.DRAFT);
        materialRow("BUSINESS_LICENSE", LocalDateTime.of(2026, 8, 1, 23, 59, 59));

        ApplicationView view = service.saveDraft(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", "")));

        assertThat(materialRows.get(0).getExpireAt()).isNull();
        assertThat(view.licenseExpireAt()).isNull();
    }

    @Test
    @DisplayName("有效期只给到日 → 落到当日 23:59:59（到期日当天仍有效）")
    void dateOnlyExpiryLandsAtEndOfDay() {
        merchant(MerchantStatus.DRAFT);
        materialRow("BUSINESS_LICENSE", null);

        ApplicationView view = service.saveDraft(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", "2026-12-31")));

        assertThat(materialRows.get(0).getExpireAt())
                .isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        assertThat(view.materialExpiry()).containsEntry("BUSINESS_LICENSE", "2026-12-31");
    }

    @Test
    @DisplayName("有效期格式非法 → 400 校验错误（不静默当成长效）")
    void invalidExpiryIsRejected() {
        merchant(MerchantStatus.DRAFT);
        materialRow("BUSINESS_LICENSE", null);

        assertThatThrownBy(() -> service.saveDraft(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", "2026年12月31日"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("营业执照");
    }

    @Test
    @DisplayName("材料尚未上传时填有效期 → 跳过而不是报错（前端可能一次提交里两项都有）")
    void expiryWithoutMaterialIsSkipped() {
        merchant(MerchantStatus.DRAFT);

        ApplicationView view = service.saveDraft(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", "2026-12-31")));

        assertThat(view.licenseExpireAt()).isNull();
    }

    @Test
    @DisplayName("视图带出档位：剩余 3 天 = T7，剩余 20 天 = T30")
    void viewCarriesStage() {
        merchant(MerchantStatus.APPROVED);
        materialRow("BUSINESS_LICENSE", endOfDayAfterDays(3));
        assertThat(service.ensureApplication(USER_ID).licenseStage()).isEqualTo("T7");
        assertThat(service.ensureApplication(USER_ID).licenseRemainingDays()).isEqualTo(3);

        materialRows.get(0).setExpireAt(endOfDayAfterDays(20));
        assertThat(service.ensureApplication(USER_ID).licenseStage()).isEqualTo("T30");
    }

    // ---------------- 续期 ----------------

    @Test
    @DisplayName("★ 续期：APPROVED 临期店铺提交新证 → 回 SUBMITTED 重走审核，并清空提醒档位")
    void renewalFromApprovedGoesBackToReview() {
        MerchantEntity entity = merchant(MerchantStatus.APPROVED);
        entity.setExpireNotifyStage("T7");
        materialRow("BUSINESS_LICENSE", endOfDayAfterDays(3));

        ApplicationView view = service.renewal(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", isoDateAfterDays(400))));

        // 续期不是自动通过 —— 必须回到 SUBMITTED 让人再过一眼
        assertThat(view.status()).isEqualTo("SUBMITTED");
        assertThat(view.appliedAt()).isNotNull();
        // 新周期从零开始：档位清空，否则下一个有效期期间不会再提醒
        assertThat(entity.getExpireNotifyStage()).isNull();
        assertThat(entity.getLicenseExpireAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("★ 续期：SUSPENDED（已过期被停业）补齐新证后可重新送审，停业原因清除")
    void renewalFromSuspendedClearsReason() {
        MerchantEntity entity = merchant(MerchantStatus.SUSPENDED);
        entity.setSuspendedReason("经营资质已过期（2026-06-30），店铺已停业");
        entity.setLicenseExpireAt(LocalDateTime.of(2026, 6, 30, 23, 59, 59));
        materialRow("BUSINESS_LICENSE", LocalDateTime.of(2026, 6, 30, 23, 59, 59));

        ApplicationView view = service.renewal(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", isoDateAfterDays(500))));

        assertThat(view.status()).isEqualTo("SUBMITTED");
        assertThat(entity.getSuspendedReason()).isNull();
        assertThat(entity.getLicenseExpireAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("续期提交的有效期仍在过去 → 422（否则走完一圈审核回来照样停业）")
    void renewalWithPastExpiryIsRejected() {
        merchant(MerchantStatus.SUSPENDED);
        materialRow("BUSINESS_LICENSE", LocalDateTime.of(2026, 1, 1, 23, 59, 59));

        assertThatThrownBy(() -> service.renewal(USER_ID, new SaveDraftRequest(
                null, null, Map.of("BUSINESS_LICENSE", "2025-12-31"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仍在过去");
    }

    @Test
    @DisplayName("续期时全部材料都是长期有效 → 放行（earliest 为 NULL 不等于「过期」）")
    void renewalWithNoExpiryIsAllowed() {
        merchant(MerchantStatus.SUSPENDED);
        materialRow("BUSINESS_LICENSE", null);

        assertThat(service.renewal(USER_ID, new SaveDraftRequest(null, null, null)).status())
                .isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("审核中（SUBMITTED / REVIEWING）不能续期：送审样本中途换掉，结论就与材料脱钩")
    void renewalRejectedWhileUnderReview() {
        for (MerchantStatus s : List.of(MerchantStatus.SUBMITTED, MerchantStatus.REVIEWING)) {
            merchant(s);
            assertThatThrownBy(() -> service.renewal(USER_ID, new SaveDraftRequest(null, null, null)))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不能发起资质续期");
        }
    }

    @Test
    @DisplayName("草稿 / 已驳回不能走续期入口（还没开张，该走提交那条路）")
    void renewalRejectedForDraftAndRejected() {
        for (MerchantStatus s : List.of(MerchantStatus.DRAFT, MerchantStatus.REJECTED)) {
            merchant(s);
            assertThatThrownBy(() -> service.renewal(USER_ID, new SaveDraftRequest(null, null, null)))
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("续期请求体可以为空（什么都不改，只是把店重新送一次审）")
    void renewalWithNullBodyIsSafe() {
        merchant(MerchantStatus.SUSPENDED);
        materialRow("BUSINESS_LICENSE", null);

        assertThat(service.renewal(USER_ID, null).status()).isEqualTo("SUBMITTED");
    }

    // ---------------- 审核通过时重置提醒周期 ----------------

    @Test
    @DisplayName("★ 审核通过清空提醒档位：否则续期后这个有效期期间再也不会提醒")
    void approveResetsNotifyStage() {
        MerchantEntity entity = merchant(MerchantStatus.REVIEWING);
        entity.setExpireNotifyStage("T7");
        entity.setSuspendedReason("经营资质已过期");
        materialRow("BUSINESS_LICENSE", endOfDayAfterDays(500));

        service.approve(MERCHANT_ID, OPERATOR_ID);

        assertThat(entity.getExpireNotifyStage()).isNull();
        assertThat(entity.getSuspendedReason()).isNull();
    }

    /**
     * ★ 本组最值钱的一条：清空动作必须落到 <b>UPDATE 语句</b> 上。
     *
     * <h2>为什么非要断言 SQL</h2>
     * 上面那两条只断言内存对象 —— 而内存对象永远是 null（`setXxx(null)` 一调就是 null），
     * 所以**无论实现对不对都恒绿**。真实缺陷正是这样溜过去的：MyBatis-Plus 默认更新策略是
     * {@code NOT_NULL}，`updateById` 会<b>跳过</b> null 字段，于是「档位清零 / 停业原因清除 /
     * 驳回原因过时清理」三条承诺在库里全都不成立，而接口返回 200、状态看着也对。
     * 这组断言把「有没有真的写进 UPDATE」钉死，实现一旦退回 `updateById` 立刻红。
     */
    @Test
    @DisplayName("★ 清空可空列必须真的写进 UPDATE（退回 updateById 会静默失效）")
    void clearingNullColumnsMustReachSql() {
        MerchantEntity entity = merchant(MerchantStatus.REVIEWING);
        entity.setExpireNotifyStage("T7");
        entity.setSuspendedReason("经营资质已过期");
        materialRow("BUSINESS_LICENSE", endOfDayAfterDays(500));

        service.approve(MERCHANT_ID, OPERATOR_ID);

        String sql = capturedClearSetClause();
        assertThat(sql)
                .contains("expire_notify_stage")
                .contains("suspended_reason")
                .contains("reject_reason");
    }

    @Test
    @DisplayName("★ 续期提交同样要把档位与停业原因写进 UPDATE")
    void renewalAlsoClearsColumnsInSql() {
        MerchantEntity entity = merchant(MerchantStatus.SUSPENDED);
        entity.setExpireNotifyStage("EXPIRED");
        entity.setSuspendedReason("经营资质已过期（2026-06-30），店铺已停业");
        materialRow("BUSINESS_LICENSE", LocalDateTime.now(ZoneOffset.UTC).plusDays(500));

        service.renewal(USER_ID, null);

        assertThat(capturedClearSetClause())
                .contains("expire_notify_stage")
                .contains("suspended_reason");
    }

    @Test
    @DisplayName("★ 重新提交（重提）要把过时的驳回原因写进 UPDATE")
    void submitClearsStaleRejectReasonInSql() {
        MerchantEntity entity = merchant(MerchantStatus.REJECTED);
        entity.setRejectReason("[{\"scope\":\"GENERAL\",\"code\":\"OTHER\",\"note\":\"店名不符\"}]");
        for (String type : List.of("BUSINESS_LICENSE", "LEGAL_ID_FRONT", "LEGAL_ID_BACK",
                "BANK_ACCOUNT", "OPERATION_COMMITMENT", "INDUSTRY_PERMIT")) {
            materialRow(type, null);
        }

        service.submit(USER_ID);

        assertThat(capturedClearSetClause()).contains("reject_reason");
    }

    /**
     * 取出「清空可空列」那次 wrapper 更新的 <b>SET 子句</b>。
     *
     * <p>★ 必须用 {@code getSqlSet()} 而不是 {@code getSqlSegment()}：
     * 后者的名字听起来像「整段 SQL」，实际只返回 <b>WHERE</b> 部分
     * （实测拿到的是 {@code "(id = #{ew.paramNameValuePairs.MPGENVAL5})"}）。
     * 用它来断言列名会**永远失败**（还以为是实现没写），或者更糟 ——
     * 断言写成「不含某列」时会**永远通过**，又是一个不会红的断言。
     */
    private String capturedClearSetClause() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<MerchantEntity>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(merchantMapper, atLeastOnce()).update(isNull(), captor.capture());
        return captor.getValue().getSqlSet();
    }

    // ---------------- 夹具 ----------------

    private MerchantEntity merchant(MerchantStatus status) {
        MerchantEntity entity = new MerchantEntity();
        entity.setId(MERCHANT_ID);
        entity.setOwnerUserId(USER_ID);
        entity.setName("豆豆宠物用品店");
        entity.setStatus(status.name());
        entity.setIsolationLevel("STANDARD");
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(entity);
        when(merchantMapper.selectOne(any())).thenReturn(entity);
        return entity;
    }

    private void materialRow(String type, LocalDateTime expireAt) {
        MerchantMaterialEntity row = new MerchantMaterialEntity();
        row.setId(710000000000000401L);
        row.setMerchantId(MERCHANT_ID);
        row.setMaterialType(type);
        row.setFileUrl("/api/merchant-materials/" + MERCHANT_ID + "/" + type.toLowerCase() + ".jpg");
        row.setExpireAt(expireAt);
        materialRows.add(row);
    }

    /** 服务端落库口径：某日后一天的 23:59:59 */
    private static LocalDateTime endOfDayAfterDays(int days) {
        return LocalDateTime.now(ZoneOffset.UTC).plusDays(days).toLocalDate().atTime(23, 59, 59);
    }

    private static String isoDateAfterDays(int days) {
        return LocalDateTime.now(ZoneOffset.UTC).plusDays(days).toLocalDate().toString();
    }
}
