package com.duoduo.user.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.RejectReason;
import com.duoduo.user.dto.MerchantDtos.SaveDraftRequest;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMaterialEntity;
import com.duoduo.user.entity.MerchantMemberEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.mapper.MerchantMemberMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.MerchantStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入驻状态机全路径单测（E10 交付项：「单测覆盖全路径」）。
 *
 * <h2>这组测试守的是什么</h2>
 * 入驻状态机是<b>钱与身份的门</b>：REJECTED→SUBMITTED 必须通（否则被驳回的商家
 * 只能重新注册账号绕审核）、SUBMITTED 之后材料必须冻结（否则边审边换图）、
 * approve 必须幂等（运营双击不该开两家店）、reject 必须带结构化原因
 * （E11 审核台与 E15 续期都要按它工作）。每一条规则在正常流程里都"看起来没问题"，
 * 只有把 16 条路径逐一钉死，将来谁改动状态流转时才会立刻红。
 */
class MerchantOnboardingStateTest {

    private static final long USER_ID = 710000000000000001L;
    private static final long MERCHANT_ID = 710000000000000101L;
    private static final long OPERATOR_ID = 710000000000000201L;

    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantMemberMapper memberMapper = mock(MerchantMemberMapper.class);
    private final MerchantMaterialMapper materialMapper = mock(MerchantMaterialMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MerchantOnboardingService service =
            new MerchantOnboardingService(merchantMapper, memberMapper, materialMapper,
                    userMapper, idGenerator, objectMapper);

    /** setUp 与各用例共享的 owner 实例（service 经 userMapper.selectById 读到的就是它） */
    private UserEntity ownerUser;

    @BeforeEach
    void setUp() {
        when(idGenerator.nextId()).thenReturn(710000000000000301L);
        ownerUser = realNamedUser();
        when(userMapper.selectById(USER_ID)).thenReturn(ownerUser);
        // 默认：查无成员（approve 开店路径）
        when(memberMapper.selectOne(any())).thenReturn(null);
    }

    // ---------------- 提交 ----------------

    @Test
    void 提交_草稿材料齐全且实名_进入SUBMITTED() {
        mockMerchant(MerchantStatus.DRAFT, "豆豆宠物用品店");
        mockMaterials(allSix());

        ApplicationView view = service.submit(USER_ID);

        assertThat(view.status()).isEqualTo("SUBMITTED");
        assertThat(view.rejectReasons()).isEmpty();
    }

    @Test
    void 提交_重复提交幂等_不产生第二次副作用() {
        MerchantEntity submitted = mockMerchant(MerchantStatus.SUBMITTED, "豆豆宠物用品店");
        submitted.setAppliedAt(LocalDateTime.of(2026, 10, 8, 0, 0));
        mockMaterials(allSix());

        ApplicationView again = service.submit(USER_ID);

        assertThat(again.status()).isEqualTo("SUBMITTED");
        // 幂等的可观察证据：appliedAt 保持首次提交的值（若被刷新，说明重复提交产生了副作用）
        assertThat(again.appliedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 0, 0));
    }

    @Test
    void 提交_材料缺一类_422并指出缺什么() {
        mockMerchant(MerchantStatus.DRAFT, "豆豆宠物用品店");
        Map<MerchantMaterialTypeKey, String> five = allSix();
        five.remove(new MerchantMaterialTypeKey("BANK_ACCOUNT"));
        mockMaterials(five);

        assertThatThrownBy(() -> service.submit(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对公银行账户证明");
    }

    @Test
    void 提交_未实名_422() {
        when(userMapper.selectById(USER_ID)).thenReturn(unrealNamedUser());
        mockMerchant(MerchantStatus.DRAFT, "豆豆宠物用品店");
        mockMaterials(allSix());

        assertThatThrownBy(() -> service.submit(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("实名");
    }

    @Test
    void 提交_店名仍是占位_422() {
        mockMerchant(MerchantStatus.DRAFT, "待填写-" + USER_ID);
        mockMaterials(allSix());

        assertThatThrownBy(() -> service.submit(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺名称");
    }

    @Test
    void 提交_已通过_422() {
        mockMerchant(MerchantStatus.APPROVED, "豆豆宠物用品店");
        mockMaterials(allSix());

        assertThatThrownBy(() -> service.submit(USER_ID))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 保存草稿 ----------------

    @Test
    void 保存草稿_审核中不可改材料_422() {
        mockMerchant(MerchantStatus.REVIEWING, "豆豆宠物用品店");

        assertThatThrownBy(() -> service.saveDraft(USER_ID,
                new SaveDraftRequest("新店名", Map.of("BUSINESS_LICENSE", "/uploads/x.jpg"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void 保存草稿_店名撞唯一键_409() {
        mockMerchant(MerchantStatus.DRAFT, "旧店名");
        when(merchantMapper.updateById(any())).thenThrow(new DuplicateKeyException("uk_name"));

        assertThatThrownBy(() -> service.saveDraft(USER_ID,
                new SaveDraftRequest("别人家的店", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺名已被使用");
    }

    // ---------------- 审核 ----------------

    @Test
    void 开始审核_SUBMITTED进入REVIEWING_重复开始幂等() {
        MerchantEntity entity = mockMerchant(MerchantStatus.SUBMITTED, "豆豆宠物用品店");
        mockMaterials(allSix());
        assertThat(service.startReview(MERCHANT_ID, OPERATOR_ID).status()).isEqualTo("REVIEWING");

        // 幂等：已 REVIEWING 再开始一次，返回现状不报错
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(entity);
        assertThat(service.startReview(MERCHANT_ID, OPERATOR_ID).status()).isEqualTo("REVIEWING");
    }

    @Test
    void 通过并开店_写OWNER成员并把角色升为SELLER() {
        mockMerchant(MerchantStatus.REVIEWING, "豆豆宠物用品店");
        mockMaterials(allSix());

        ApplicationView view = service.approve(MERCHANT_ID, OPERATOR_ID);

        assertThat(view.status()).isEqualTo("APPROVED");
        org.mockito.Mockito.verify(memberMapper).insert(any(MerchantMemberEntity.class));
        // 角色升在 setUp 里那个 owner 实例上（service 从 userMapper 读到的就是它）
        assertThat(ownerUser.getRole()).isEqualTo("SELLER");
    }

    @Test
    void 通过_重复通过幂等() {
        mockMerchant(MerchantStatus.APPROVED, "豆豆宠物用品店");
        mockMaterials(allSix());

        assertThat(service.approve(MERCHANT_ID, OPERATOR_ID).status()).isEqualTo("APPROVED");
    }

    @Test
    void 通过_跳过开始审核从SUBMITTED直接通过_422() {
        mockMerchant(MerchantStatus.SUBMITTED, "豆豆宠物用品店");
        mockMaterials(allSix());

        // 状态机刻意要求先 start-review：UI 上"一键通过"内部先调开始审核，
        // 但接口层不允许越过 REVIEWING —— 否则「审核中」这个状态永远没人用，
        // 将来接入风控/复核就没有挂载点
        assertThatThrownBy(() -> service.approve(MERCHANT_ID, OPERATOR_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审核中");
    }

    // ---------------- 驳回 ----------------

    @Test
    void 驳回_结构化原因写入并可在视图中读回() {
        MerchantEntity entity = mockMerchant(MerchantStatus.REVIEWING, "豆豆宠物用品店");
        mockMaterials(allSix());
        List<RejectReason> reasons = List.of(
                new RejectReason("BUSINESS_LICENSE", "MATERIAL_ILLEGIBLE", "营业执照照片模糊，请重新上传"),
                new RejectReason("STORE_NAME", "INFO_MISMATCH", "店名与执照名称不一致"));

        ApplicationView view = service.reject(MERCHANT_ID, OPERATOR_ID, reasons);

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.rejectReasons()).hasSize(2);
        assertThat(view.rejectReasons().get(0).code()).isEqualTo("MATERIAL_ILLEGIBLE");
        assertThat(entity.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void 驳回_空原因_422() {
        mockMerchant(MerchantStatus.REVIEWING, "豆豆宠物用品店");

        assertThatThrownBy(() -> service.reject(MERCHANT_ID, OPERATOR_ID, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("结构化原因");
    }

    @Test
    void 驳回_已驳回时幂等_允许更新原因() {
        MerchantEntity entity = mockMerchant(MerchantStatus.REJECTED, "豆豆宠物用品店");
        entity.setRejectReason("[{\"scope\":\"GENERAL\",\"code\":\"OLD\",\"note\":\"旧\"}]");
        mockMaterials(allSix());

        ApplicationView view = service.reject(MERCHANT_ID, OPERATOR_ID,
                List.of(new RejectReason("GENERAL", "NEW", "补充说明")));

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.rejectReasons().get(0).code()).isEqualTo("NEW");
    }

    @Test
    void 驳回_已通过的申请_422() {
        mockMerchant(MerchantStatus.APPROVED, "豆豆宠物用品店");

        assertThatThrownBy(() -> service.reject(MERCHANT_ID, OPERATOR_ID,
                List.of(new RejectReason("GENERAL", "X", "y"))))
                .isInstanceOf(BizException.class);
    }

    // ---------------- 激活 ----------------

    @Test
    void 激活_未通过审核_422() {
        mockMerchant(MerchantStatus.SUBMITTED, "豆豆宠物用品店");

        assertThatThrownBy(() -> service.activateIfApproved(USER_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚未通过");
    }

    @Test
    void 激活_已通过_放行并交回用户实体() {
        mockMerchant(MerchantStatus.APPROVED, "豆豆宠物用品店");

        // activate 只负责「确认已通过」这一道闸；角色升级发生在 approve（见通过并开店用例），
        // 两者职责分开 —— activate 不应隐式再升一次角色
        assertThat(service.activateIfApproved(USER_ID).getId()).isEqualTo(USER_ID);
    }

    // ---------------- 夹具 ----------------

    private MerchantEntity mockMerchant(MerchantStatus status, String name) {
        MerchantEntity entity = new MerchantEntity();
        entity.setId(MERCHANT_ID);
        entity.setOwnerUserId(USER_ID);
        entity.setName(name);
        entity.setStatus(status.name());
        entity.setIsolationLevel("STANDARD");
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(entity);
        when(merchantMapper.selectOne(any())).thenReturn(entity);
        return entity;
    }

    /** 材料查询的键就是服务层 loadMaterials 的键（MerchantMaterialType），包一层便于按名删类 */
    private record MerchantMaterialTypeKey(String name) {
    }

    private Map<MerchantMaterialTypeKey, String> allSix() {
        Map<MerchantMaterialTypeKey, String> m = new HashMap<>();
        for (String t : new String[]{"BUSINESS_LICENSE", "LEGAL_ID_FRONT", "LEGAL_ID_BACK",
                "BANK_ACCOUNT", "OPERATION_COMMITMENT", "INDUSTRY_PERMIT"}) {
            m.put(new MerchantMaterialTypeKey(t), "/uploads/" + t.toLowerCase() + ".jpg");
        }
        return m;
    }

    private void mockMaterials(Map<MerchantMaterialTypeKey, String> materials) {
        List<MerchantMaterialEntity> rows = materials.entrySet().stream().map(e -> {
            MerchantMaterialEntity row = new MerchantMaterialEntity();
            row.setId(710000000000000401L);
            row.setMerchantId(MERCHANT_ID);
            row.setMaterialType(e.getKey().name());
            row.setFileUrl(e.getValue());
            return row;
        }).toList();
        when(materialMapper.selectList(any())).thenReturn(rows);
        when(materialMapper.selectOne(any())).thenReturn(rows.isEmpty() ? null : rows.get(0));
        when(materialMapper.selectById(anyLong())).thenReturn(rows.isEmpty() ? null : rows.get(0));
    }

    private UserEntity realNamedUser() {
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setPhone("13900000001");
        u.setRole("USER");
        u.setStatus("ACTIVE");
        // isRealNameVerified 是派生值：realName 非空即视为已实名（见 UserEntity）
        u.setRealName("张三");
        return u;
    }

    private UserEntity unrealNamedUser() {
        UserEntity u = realNamedUser();
        u.setRealName(null);
        return u;
    }
}
