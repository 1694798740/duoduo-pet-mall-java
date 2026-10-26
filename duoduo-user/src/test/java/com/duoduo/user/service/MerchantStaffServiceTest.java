package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.seller.StaffPermissions;
import com.duoduo.user.dto.MerchantDtos.MembershipInfo;
import com.duoduo.user.dto.MerchantDtos.StaffView;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMemberEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMemberMapper;
import com.duoduo.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-04 · 店员权限配置与下发单测。
 *
 * <p>守三件事：
 * <ol>
 *   <li>写路径拒绝未知键 / 缺字段 / 改店主 —— 这三类都不能被"宽容"成一次成功的配置；</li>
 *   <li>序列化顺序稳定（枚举声明顺序），审计 diff 才可比；</li>
 *   <li>下发口径：历史 {@code NULL} 按全量、空串按「仅查看」。</li>
 * </ol>
 */
class MerchantStaffServiceTest {

    private static final long MERCHANT_ID = 810000000000000101L;
    private static final long OWNER_ID = 810000000000000001L;
    private static final long STAFF_USER_ID = 810000000000000002L;
    private static final long MEMBER_ID = 810000000000000201L;

    private final MerchantMapper merchantMapper = mock(MerchantMapper.class);
    private final MerchantMemberMapper memberMapper = mock(MerchantMemberMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);

    private final MerchantStaffService service = new MerchantStaffService(
            merchantMapper, memberMapper, userMapper, idGenerator);

    @BeforeAll
    static void initMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, MerchantEntity.class);
        TableInfoHelper.initTableInfo(assistant, MerchantMemberEntity.class);
    }

    private static MerchantEntity store() {
        MerchantEntity store = new MerchantEntity();
        store.setId(MERCHANT_ID);
        store.setOwnerUserId(OWNER_ID);
        store.setName("权限夹具店");
        store.setStatus("APPROVED");
        return store;
    }

    private static MerchantMemberEntity member(String role, String permissions) {
        MerchantMemberEntity member = new MerchantMemberEntity();
        member.setId(MEMBER_ID);
        member.setMerchantId(MERCHANT_ID);
        member.setUserId(STAFF_USER_ID);
        member.setRole(role);
        member.setStatus("ACTIVE");
        member.setPermissions(permissions);
        return member;
    }

    /** 店主身份（不是店员）+ 本店。 */
    private void stubOwnerStore() {
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(merchantMapper.selectOne(any())).thenReturn(store());
    }

    @Test
    @DisplayName("权限更新成功：去重、按枚举顺序落库，并下发同一份清单")
    void updatePermissionsNormalizesAndPersists() {
        stubOwnerStore();
        MerchantMemberEntity member = member("STAFF", null);
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);

        StaffView view = service.updatePermissions(OWNER_ID, MEMBER_ID,
                List.of("ORDER_SHIP", "PRODUCT_EDIT", "ORDER_SHIP"));

        assertThat(member.getPermissions()).isEqualTo("PRODUCT_EDIT,ORDER_SHIP");
        assertThat(view.permissions()).containsExactly("PRODUCT_EDIT", "ORDER_SHIP");
        verify(memberMapper).updateById(member);
    }

    @Test
    @DisplayName("未知权限键 → 422，且不写库")
    void unknownPermissionRejected() {
        stubOwnerStore();
        MerchantMemberEntity member = member("STAFF", null);
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);

        assertThatThrownBy(() -> service.updatePermissions(OWNER_ID, MEMBER_ID, List.of("PRODUCT_EDITT")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知权限");
        verify(memberMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("缺少权限字段（null）→ 拒绝，不能解释成清空")
    void missingPermissionsRejected() {
        stubOwnerStore();
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("STAFF", null));

        assertThatThrownBy(() -> service.updatePermissions(OWNER_ID, MEMBER_ID, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请提交权限清单");
        verify(memberMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("店主行不可配置权限")
    void ownerCannotBeConfigured() {
        stubOwnerStore();
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member("OWNER", null));

        assertThatThrownBy(() -> service.updatePermissions(OWNER_ID, MEMBER_ID, List.of("ORDER_SHIP")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店主权限为全量");
    }

    @Test
    @DisplayName("跨店成员 → 404，不泄露存在性")
    void crossStoreMemberNotFound() {
        stubOwnerStore();
        MerchantMemberEntity other = member("STAFF", null);
        other.setMerchantId(MERCHANT_ID + 1);
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(other);

        assertThatThrownBy(() -> service.updatePermissions(OWNER_ID, MEMBER_ID, List.of("ORDER_SHIP")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店员");
    }

    @Test
    @DisplayName("空数组 = 仅查看；序列化为空串")
    void emptyPermissionsMeansReadOnly() {
        stubOwnerStore();
        MerchantMemberEntity member = member("STAFF", StaffPermissions.serialize(StaffPermissions.all()));
        when(memberMapper.selectById(MEMBER_ID)).thenReturn(member);

        StaffView view = service.updatePermissions(OWNER_ID, MEMBER_ID, List.of());

        assertThat(member.getPermissions()).isEmpty();
        assertThat(view.permissions()).isEmpty();
    }

    @Test
    @DisplayName("权限未变化 → 幂等确认，不写库")
    void unchangedPermissionsAreIdempotent() {
        stubOwnerStore();
        when(memberMapper.selectById(MEMBER_ID))
                .thenReturn(member("STAFF", "ORDER_SHIP,PRODUCT_EDIT"));

        StaffView view = service.updatePermissions(OWNER_ID, MEMBER_ID,
                List.of("PRODUCT_EDIT", "ORDER_SHIP"));

        assertThat(view.permissions()).containsExactly("PRODUCT_EDIT", "ORDER_SHIP");
        verify(memberMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("下发历史 NULL → 全量；空串 → 仅查看")
    void membershipUsesLegacyNullAsAll() {
        when(memberMapper.selectOne(any())).thenReturn(member("STAFF", null));
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(store());

        MembershipInfo info = service.membershipOf(STAFF_USER_ID);

        assertThat(info.permissions()).containsExactlyElementsOf(StaffPermissions.all());
        assertThat(info.hasPermission(com.duoduo.common.seller.StaffPermission.ORDER_SHIP)).isTrue();
    }

    @Test
    @DisplayName("下发空串 → 仅查看（任何操作权限都没有）")
    void membershipEmptyPermissions() {
        when(memberMapper.selectOne(any())).thenReturn(member("STAFF", ""));
        when(merchantMapper.selectById(MERCHANT_ID)).thenReturn(store());

        MembershipInfo info = service.membershipOf(STAFF_USER_ID);

        assertThat(info.permissions()).isEmpty();
        assertThat(info.hasPermission(com.duoduo.common.seller.StaffPermission.ORDER_SHIP)).isFalse();
    }
}
