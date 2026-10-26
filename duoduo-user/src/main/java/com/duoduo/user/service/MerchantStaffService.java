package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.audit.AuditTrail;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.MerchantDtos.MembershipInfo;
import com.duoduo.user.dto.MerchantDtos.StaffAdminView;
import com.duoduo.user.dto.MerchantDtos.StaffView;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMemberEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMemberMapper;
import com.duoduo.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商家子账号（店员）体系（E13）。
 *
 * <h2>语义</h2>
 * 店主邀请**已注册用户**成为本店店员（{@code role=STAFF}）；店员以**店铺主体**
 * （店主 userId）的身份访问订单 / 商品等经营接口；平台侧可跨租户总览并停用。
 * 成员身份与「角色」解耦：店员 role 仍是 USER —— 邀请不升角色、停用不改角色，
 * 权限完全由 {@code t_merchant_member} 的 status 驱动，因此**停用即时生效**
 * （这正是成员身份不进令牌快照的原因：令牌 15 分钟，停用不能等它过期）。
 *
 * <h2>三条边界</h2>
 * <ol>
 *   <li><b>一人一店</b>（uk_user 唯一键兜底）：兼职/跨店是将来有真实需求再放宽的事；</li>
 *   <li><b>OWNER 不可停用</b>：店主被自己的店员停用等于店铺被劫持，接口层直接拒绝；</li>
 *   <li><b>店员不可管理成员</b>：邀请 / 停用是 OWNER-only —— 否则一个店员可以拉进
 *       无限个"同事"，成员名单就不再是店主可控的范围。</li>
 * </ol>
 */
@Service
public class MerchantStaffService {

    private static final Logger log = LoggerFactory.getLogger(MerchantStaffService.class);

    private final MerchantMapper merchantMapper;
    private final MerchantMemberMapper memberMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdGenerator idGenerator;

    public MerchantStaffService(MerchantMapper merchantMapper, MerchantMemberMapper memberMapper,
                                UserMapper userMapper, SnowflakeIdGenerator idGenerator) {
        this.merchantMapper = merchantMapper;
        this.memberMapper = memberMapper;
        this.userMapper = userMapper;
        this.idGenerator = idGenerator;
    }

    // ---------------- 店主侧 ----------------

    /** 邀请店员：按手机号邀请已注册用户（status=ACTIVE 即生效，无需对方确认 —— v1 口径） */
    @Transactional
    public StaffView invite(Long ownerId, String phone) {
        MerchantEntity store = requireOwnedStore(ownerId);
        UserEntity target = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getPhone, phone).last("LIMIT 1"));
        if (target == null) {
            throw BizException.notFound("该手机号尚未注册");
        }
        if (target.getId().equals(ownerId)) {
            throw BizException.businessRule("不能邀请自己");
        }
        if ("BANNED".equals(target.getStatus())) {
            throw BizException.businessRule("该账号已被封禁，无法邀请");
        }
        if (findMembership(target.getId()) != null) {
            throw BizException.businessRule("该用户已属于其他商家，一个账号只能属于一家店");
        }
        MerchantMemberEntity member = new MerchantMemberEntity();
        member.setId(idGenerator.nextId());
        member.setMerchantId(store.getId());
        member.setUserId(target.getId());
        member.setRole("STAFF");
        member.setStatus("ACTIVE");
        member.setInvitedBy(ownerId);
        member.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        member.setUpdatedAt(member.getCreatedAt());
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("该用户已属于其他商家（并发邀请），请刷新确认");
        }
        // E18 · 留痕交给 AuditInterceptor（端点带 @Audited），这里只补注解采集不到的三件事：
        // 目标是谁、属于哪个租户、人读说明。租户 = 店铺主体 userId（与 t_spu.seller_id 同一个键）。
        AuditTrail.target(target.getId(), "STAFF");
        AuditTrail.tenant(store.getOwnerUserId());
        AuditTrail.note(store.getName() + " 邀请 " + mask(target.getPhone()));
        log.info("店员邀请成功 merchantId={} staff={} owner={}", store.getId(), target.getId(), ownerId);
        return toView(member, target);
    }

    /** 本店成员列表（含店主本人，OWNER 标出） */
    public List<StaffView> listForOwner(Long ownerId) {
        MerchantEntity store = requireOwnedStore(ownerId);
        List<MerchantMemberEntity> members = memberMapper.selectList(
                new LambdaQueryWrapper<MerchantMemberEntity>()
                        .eq(MerchantMemberEntity::getMerchantId, store.getId())
                        .orderByAsc(MerchantMemberEntity::getRole)
                        .orderByDesc(MerchantMemberEntity::getCreatedAt));
        return toViews(members);
    }

    /** 店主停用 / 启用店员 */
    @Transactional
    public StaffView setStaffStatus(Long ownerId, long memberId, boolean enable) {
        MerchantEntity store = requireOwnedStore(ownerId);
        MerchantMemberEntity member = memberMapper.selectById(memberId);
        if (member == null || !member.getMerchantId().equals(store.getId())) {
            throw BizException.notFound("店员");
        }
        if ("OWNER".equals(member.getRole())) {
            throw BizException.businessRule("不能停用店主账号");
        }
        String target = enable ? "ACTIVE" : "DISABLED";
        if (!target.equals(member.getStatus())) {
            member.setStatus(target);
            member.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            memberMapper.updateById(member);
            AuditTrail.target(member.getUserId(), "STAFF");
            AuditTrail.tenant(store.getOwnerUserId());
            AuditTrail.change(member.getRole() + ":" + (enable ? "DISABLED" : "ACTIVE"),
                    member.getRole() + ":" + target);
            AuditTrail.note("店 " + store.getName());
        } else {
            // 状态本来就一致 = 幂等确认，不构成事件（与 E18 之前同一口径）
            AuditTrail.skip("店员状态已是 " + target + "，无实际变更");
        }
        return toView(member, userMapper.selectById(member.getUserId()));
    }

    // ---------------- 平台侧（跨租户） ----------------

    /**
     * 平台侧成员总览。
     *
     * @param keyword 店铺名 / 手机号 / 真实姓名（包含匹配）
     */
    public PageResult<StaffAdminView> adminPage(int page, int pageSize, String keyword) {
        LambdaQueryWrapper<MerchantMemberEntity> wrapper = new LambdaQueryWrapper<MerchantMemberEntity>()
                .orderByDesc(MerchantMemberEntity::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("(merchant_id IN (SELECT id FROM t_merchant WHERE name LIKE {0})"
                    + " OR user_id IN (SELECT id FROM t_user WHERE phone LIKE {1} OR real_name LIKE {1}))",
                    like, like);
        }
        Page<MerchantMemberEntity> p = memberMapper.selectPage(new Page<>(Math.max(1, page), pageSize), wrapper);
        // 注意：关键字筛出 0 条时不能直接 selectBatchIds(空集合) —— 会生成 `IN ( )`，
        // MyBatis-Plus 的 JSqlParser 解析直接抛异常，接口变成 500。空集合走空 Map。
        Set<Long> merchantIds = p.getRecords().stream()
                .map(MerchantMemberEntity::getMerchantId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> userIds = p.getRecords().stream()
                .map(MerchantMemberEntity::getUserId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, MerchantEntity> stores = merchantIds.isEmpty() ? Map.of()
                : merchantMapper.selectBatchIds(merchantIds).stream()
                        .collect(Collectors.toMap(MerchantEntity::getId, m -> m));
        Map<Long, UserEntity> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(UserEntity::getId, u -> u));
        List<StaffAdminView> items = p.getRecords().stream().map(m -> {
            MerchantEntity store = stores.get(m.getMerchantId());
            UserEntity u = users.get(m.getUserId());
            return new StaffAdminView(
                    String.valueOf(m.getId()), String.valueOf(m.getMerchantId()),
                    store == null ? "—" : store.getName(),
                    String.valueOf(m.getUserId()),
                    u == null ? "—" : mask(u.getPhone()),
                    m.getRole(), m.getStatus(),
                    m.getCreatedAt() == null ? null : m.getCreatedAt().toString());
        }).toList();
        return PageResult.of(items, p.getTotal(), page, pageSize);
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<StaffAdminView> adminPage(int page, int pageSize) {
        return adminPage(page, pageSize, null);
    }

    /** 平台侧停用 / 启用（跨租户）：风控场景由平台直接处置，事后审计 */
    @Transactional
    public StaffAdminView adminSetStatus(Long adminId, long memberId, boolean enable) {
        MerchantMemberEntity member = memberMapper.selectById(memberId);
        if (member == null) {
            throw BizException.notFound("店员");
        }
        if ("OWNER".equals(member.getRole())) {
            throw BizException.businessRule("不能停用店主账号（如需处置店铺请走封禁流程）");
        }
        MerchantEntity store = merchantMapper.selectById(member.getMerchantId());
        String target = enable ? "ACTIVE" : "DISABLED";
        if (!target.equals(member.getStatus())) {
            member.setStatus(target);
            member.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            memberMapper.updateById(member);
            AuditTrail.target(member.getUserId(), "STAFF");
            // 平台处置也要落租户：否则「这家店的成员被谁动过」在按租户检索时查不出来，
            // 而那恰恰是平台处置最需要被追溯的场景。
            AuditTrail.tenant(store == null ? 0L : store.getOwnerUserId() == null ? 0L : store.getOwnerUserId());
            AuditTrail.change(member.getRole() + ":" + (enable ? "DISABLED" : "ACTIVE"),
                    member.getRole() + ":" + target);
            AuditTrail.note("平台处置 merchant=" + member.getMerchantId());
        } else {
            AuditTrail.skip("店员状态已是 " + target + "，无实际变更");
        }
        UserEntity u = userMapper.selectById(member.getUserId());
        return new StaffAdminView(String.valueOf(member.getId()), String.valueOf(member.getMerchantId()),
                store == null ? "—" : store.getName(), String.valueOf(member.getUserId()),
                u == null ? "—" : mask(u.getPhone()), member.getRole(), member.getStatus(),
                member.getCreatedAt() == null ? null : member.getCreatedAt().toString());
    }

    // ---------------- 内部（交易/商品域归属判定用） ----------------

    /**
     * 成员身份查询（内部端点下发）：返回该用户**当前生效**的 STAFF 身份；
     * 店主 / 无店 / 已停用 → null —— 停用即查不到，这正是"停用即时生效"的实现机制。
     */
    public MembershipInfo membershipOf(Long userId) {
        MerchantMemberEntity member = findMembership(userId);
        if (member == null || !"ACTIVE".equals(member.getStatus())) {
            return null;
        }
        MerchantEntity store = merchantMapper.selectById(member.getMerchantId());
        if (store == null || !"APPROVED".equals(store.getStatus())) {
            return null;
        }
        return new MembershipInfo(String.valueOf(store.getId()),
                String.valueOf(store.getOwnerUserId()), member.getRole(), member.getStatus());
    }

    /**
     * 本店在职成员（C-04 工单转交候选人，内部端点下发）。
     *
     * <p>为什么单独开一个内部读方法，而不是复用店主管理页的 {@link #listForOwner}：
     * 后者带手机号（脱敏）与邀请信息，属于店主管理视图；工单转交只需要
     * 「userId / 昵称 / 头像 / 角色」。跨域按调用方真正需要的字段裁剪。
     *
     * <p>店铺尚未入驻（自营客服账号）时返回空列表，由调用方退化为「只有当前账号」。
     */
    public List<com.duoduo.user.dto.MerchantDtos.TicketMemberInfo> activeMembersOf(Long ownerUserId) {
        if (ownerUserId == null) {
            return List.of();
        }
        MerchantEntity store = merchantMapper.selectOne(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getOwnerUserId, ownerUserId).last("LIMIT 1"));
        if (store == null) {
            return List.of();
        }
        List<MerchantMemberEntity> members = memberMapper.selectList(
                new LambdaQueryWrapper<MerchantMemberEntity>()
                        .eq(MerchantMemberEntity::getMerchantId, store.getId())
                        .eq(MerchantMemberEntity::getStatus, "ACTIVE")
                        .orderByAsc(MerchantMemberEntity::getRole)
                        .orderByAsc(MerchantMemberEntity::getCreatedAt));
        Map<Long, UserEntity> users = members.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(members.stream().map(MerchantMemberEntity::getUserId)
                        .distinct().toList()).stream().collect(Collectors.toMap(UserEntity::getId, u -> u));
        // 店主可能没有 OWNER 成员行（早期入驻直接写 t_merchant.owner_user_id）——
        // 工单候选人必须始终包含店主，否则「转交给店主」会被误判成非本店成员。
        List<com.duoduo.user.dto.MerchantDtos.TicketMemberInfo> result = new java.util.ArrayList<>();
        UserEntity owner = userMapper.selectById(store.getOwnerUserId());
        result.add(new com.duoduo.user.dto.MerchantDtos.TicketMemberInfo(
                String.valueOf(store.getOwnerUserId()),
                owner == null || owner.getNickname() == null ? "店主" : owner.getNickname(),
                owner == null ? null : owner.getAvatar(),
                "OWNER"));
        for (MerchantMemberEntity m : members) {
            if (m.getUserId().equals(store.getOwnerUserId())) {
                continue;
            }
            UserEntity user = users.get(m.getUserId());
            result.add(new com.duoduo.user.dto.MerchantDtos.TicketMemberInfo(
                    String.valueOf(m.getUserId()),
                    user == null || user.getNickname() == null ? "成员" + m.getUserId() : user.getNickname(),
                    user == null ? null : user.getAvatar(),
                    m.getRole()));
        }
        return result;
    }

    // ---------------- 内部 ----------------

    private MerchantEntity requireOwnedStore(Long ownerId) {
        // 店员调用成员管理 → 403（而不是 404）：他知道这家店存在，缺的是权限
        MerchantMemberEntity self = findMembership(ownerId);
        if (self != null && "STAFF".equals(self.getRole())) {
            throw BizException.forbidden("店员账号不可管理成员，请联系店主");
        }
        MerchantEntity store = merchantMapper.selectOne(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getOwnerUserId, ownerId).last("LIMIT 1"));
        if (store == null) {
            throw BizException.notFound("店铺（请先完成入驻）");
        }
        return store;
    }

    private MerchantMemberEntity findMembership(Long userId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<MerchantMemberEntity>()
                .eq(MerchantMemberEntity::getUserId, userId).last("LIMIT 1"));
    }

    private List<StaffView> toViews(List<MerchantMemberEntity> members) {
        Map<Long, UserEntity> users = members.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(members.stream().map(MerchantMemberEntity::getUserId).distinct().toList())
                        .stream().collect(Collectors.toMap(UserEntity::getId, u -> u));
        return members.stream()
                .map(m -> toView(m, users.get(m.getUserId())))
                .toList();
    }

    private StaffView toView(MerchantMemberEntity member, UserEntity user) {
        return new StaffView(
                String.valueOf(member.getId()),
                String.valueOf(member.getUserId()),
                user == null ? "—" : mask(user.getPhone()),
                user == null ? "—" : user.getNickname(),
                member.getRole(), member.getStatus(),
                member.getInvitedBy() == null ? null : String.valueOf(member.getInvitedBy()),
                member.getCreatedAt() == null ? null : member.getCreatedAt().toString());
    }


    private static String mask(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
