package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.seller.StaffPermissions;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.MerchantDtos.MembershipInfo;
import com.duoduo.user.dto.MerchantDtos.StaffAdminView;
import com.duoduo.user.dto.MerchantDtos.StaffImportRow;
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
 *   <li><b>店员不可管理成员</b>：邀请 / 停用 / 权限配置是 OWNER-only —— 否则一个店员可以拉进
 *       无限个"同事"，成员名单就不再是店主可控的范围。</li>
 * </ol>
 *
 * <p>F-04：店员的**操作范围**由 {@code t_merchant_member.permissions} 决定，
 * 与「身份是否生效」正交 —— 停用仍然即时生效，权限收窄同样即时生效（下游每次现查，不缓存）。
 */
@Service
public class MerchantStaffService {

    private static final Logger log = LoggerFactory.getLogger(MerchantStaffService.class);

    /** 同步导出的行数上限（矩阵统一口径：超出应转异步任务） */
    private static final int MAX_EXPORT_ROWS = 50_000;

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
        // F-04：新邀请默认全量（与 F-04 之前的行为一致），店主随后按需收窄。
        // 历史行 permissions=NULL 也按全量解释（见 StaffPermissions.effective）。
        member.setPermissions(StaffPermissions.serialize(StaffPermissions.all()));
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

    /**
     * 员工批量导入（2026-10-28 · 导入导出第一批）。
     *
     * <p>逐行调用与单条邀请**完全同一段逻辑**（{@link #invite}），单行失败只记录原因、
     * 不影响其它行 —— 一份导入表里通常总有几个手机号尚未注册，中止整批等于让人重新挑一遍。
     *
     * <p>权限列可空（= 默认全量）；非空时先规范化再邀请 ——
     * 若先邀请再校验，非法权限会留下一个「已入店但权限没配上」的成员，
     * 而结果里那一行还显示失败，店主会以为没加上，实际却加上了。
     *
     * <p>不整批包事务：逐行独立提交，与批量发货 / 批量审核同一口径。
     */
    public BatchDtos.Result importStaff(Long ownerId, List<StaffImportRow> rows) {
        if (rows == null || rows.isEmpty()) {
            throw BizException.validation("导入内容不能为空");
        }
        if (rows.size() > 200) {
            throw BizException.validation("单次最多导入 200 行");
        }
        List<BatchDtos.Item> items = new java.util.ArrayList<>(rows.size());
        int success = 0;
        for (StaffImportRow row : rows) {
            String key = row == null || row.rowNo() == null ? "?" : String.valueOf(row.rowNo());
            try {
                if (row == null) {
                    throw BizException.validation("空行");
                }
                String phone = row.phone() == null ? "" : row.phone().trim();
                if (phone.isEmpty()) {
                    throw BizException.validation("手机号不能为空");
                }
                List<String> normalized = null;
                if (row.permissions() != null && !row.permissions().isEmpty()) {
                    try {
                        normalized = StaffPermissions.normalize(row.permissions());
                    } catch (IllegalArgumentException ex) {
                        throw BizException.validation(ex.getMessage());
                    }
                }
                StaffView invited = invite(ownerId, phone);
                if (normalized != null) {
                    updatePermissions(ownerId, Long.parseLong(invited.memberId()), normalized);
                }
                success++;
                items.add(new BatchDtos.Item(invited.memberId(), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(key, false, ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("员工导入单行失败 rowNo={} phone={}", key, row == null ? null : row.phone(), ex);
                items.add(new BatchDtos.Item(key, false, "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return new BatchDtos.Result(rows.size(), success, rows.size() - success, items, List.of());
    }

    /**
     * 员工批量启用 / 停用（2026-10-28）。
     *
     * <p>逐条走与单条相同的归属校验与状态机：非本店成员 / 店主本人都会逐条失败并带回原因。
     * 上限 100 条，与其它批量入口一致。
     */
    public BatchDtos.Result setStaffStatusBatch(Long ownerId, List<String> memberIds, boolean enable) {
        if (memberIds == null || memberIds.isEmpty()) {
            throw BizException.validation("memberIds 不能为空");
        }
        java.util.LinkedHashSet<Long> distinct = new java.util.LinkedHashSet<>();
        for (String raw : memberIds) {
            if (raw == null || raw.isBlank()) {
                throw BizException.validation("memberIds 不能包含空值");
            }
            try {
                distinct.add(Long.valueOf(raw.trim()));
            } catch (NumberFormatException ex) {
                throw BizException.validation("memberIds 含非法主键：" + raw);
            }
        }
        if (distinct.size() > 100) {
            throw BizException.validation("单次最多批量操作 100 名成员");
        }
        List<BatchDtos.Item> items = new java.util.ArrayList<>(distinct.size());
        int success = 0;
        for (Long memberId : distinct) {
            try {
                setStaffStatus(ownerId, memberId, enable);
                success++;
                items.add(new BatchDtos.Item(String.valueOf(memberId), true, null, null));
            } catch (BizException ex) {
                items.add(new BatchDtos.Item(String.valueOf(memberId), false,
                        ex.getErrorCode().name(), ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("员工批量{}单条失败 memberId={}", enable ? "启用" : "停用", memberId, ex);
                items.add(new BatchDtos.Item(String.valueOf(memberId), false,
                        "INTERNAL_ERROR", "系统繁忙，请稍后重试"));
            }
        }
        return new BatchDtos.Result(distinct.size(), success, distinct.size() - success, items, List.of());
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

    /**
     * F-04 · 店主配置店员操作权限（字段 / 操作级）。
     *
     * <p>只有 STAFF 成员行有权限清单：店主恒为全量、平台角色不受限 ——
     * 对 OWNER 行改权限既没有语义，也会让「店主把自己锁死」成为可能。
     *
     * <p>空数组是合法输入，表示「仅查看」；缺少字段则拒绝（不能把"请求写漏了"
     * 解释成"把权限清空"）。每次变更留痕，审计里能回答「谁在什么时候放开了什么」。
     */
    @Transactional
    public StaffView updatePermissions(Long ownerId, long memberId, List<String> permissions) {
        MerchantEntity store = requireOwnedStore(ownerId);
        MerchantMemberEntity member = memberMapper.selectById(memberId);
        if (member == null || !member.getMerchantId().equals(store.getId())) {
            throw BizException.notFound("店员");
        }
        if ("OWNER".equals(member.getRole())) {
            throw BizException.businessRule("店主权限为全量，不可修改");
        }
        if (permissions == null) {
            throw BizException.validation("请提交权限清单；空数组表示仅查看");
        }
        List<String> normalized;
        try {
            normalized = StaffPermissions.normalize(permissions);
        } catch (IllegalArgumentException ex) {
            throw BizException.validation(ex.getMessage());
        }
        List<String> before = StaffPermissions.effective(member.getPermissions());
        if (before.equals(normalized)) {
            // 幂等确认：不写库、不构成事件（与停用/启用同一口径）
            AuditTrail.skip("店员权限未变化");
            return toView(member, userMapper.selectById(member.getUserId()));
        }
        member.setPermissions(StaffPermissions.serialize(normalized));
        member.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        memberMapper.updateById(member);
        AuditTrail.target(member.getUserId(), "STAFF");
        AuditTrail.tenant(store.getOwnerUserId());
        AuditTrail.change(StaffPermissions.labelOf(before), StaffPermissions.labelOf(normalized));
        AuditTrail.note(store.getName() + " · 店员权限配置");
        log.info("店员权限已更新 merchantId={} staff={} owner={} permissions={}",
                store.getId(), member.getUserId(), ownerId, member.getPermissions());
        return toView(member, userMapper.selectById(member.getUserId()));
    }

    // ---------------- 平台侧（跨租户） ----------------

    /**
     * 平台侧成员总览。
     *
     * @param keyword 店铺名 / 手机号 / 真实姓名（包含匹配）
     */
    public PageResult<StaffAdminView> adminPage(int page, int pageSize, String keyword) {
        Page<MerchantMemberEntity> p = memberMapper.selectPage(new Page<>(Math.max(1, page), pageSize),
                adminWrapper(keyword));
        return PageResult.of(toAdminViews(p.getRecords()), p.getTotal(), page, pageSize);
    }

    /** 平台成员导出（矩阵第二批）：与列表同一关键字筛选，单次上限 5 万行 */
    public List<StaffAdminView> exportAdmin(String keyword) {
        LambdaQueryWrapper<MerchantMemberEntity> wrapper = adminWrapper(keyword);
        wrapper.last("LIMIT " + MAX_EXPORT_ROWS);
        return toAdminViews(memberMapper.selectList(wrapper));
    }

    private LambdaQueryWrapper<MerchantMemberEntity> adminWrapper(String keyword) {
        LambdaQueryWrapper<MerchantMemberEntity> wrapper = new LambdaQueryWrapper<MerchantMemberEntity>()
                .orderByDesc(MerchantMemberEntity::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("(merchant_id IN (SELECT id FROM t_merchant WHERE name LIKE {0})"
                    + " OR user_id IN (SELECT id FROM t_user WHERE phone LIKE {1} OR real_name LIKE {1}))",
                    like, like);
        }
        return wrapper;
    }

    private List<StaffAdminView> toAdminViews(List<MerchantMemberEntity> records) {
        // 注意：关键字筛出 0 条时不能直接 selectBatchIds(空集合) —— 会生成 `IN ( )`，
        // MyBatis-Plus 的 JSqlParser 解析直接抛异常，接口变成 500。空集合走空 Map。
        Set<Long> merchantIds = records.stream()
                .map(MerchantMemberEntity::getMerchantId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> userIds = records.stream()
                .map(MerchantMemberEntity::getUserId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, MerchantEntity> stores = merchantIds.isEmpty() ? Map.of()
                : merchantMapper.selectBatchIds(merchantIds).stream()
                        .collect(Collectors.toMap(MerchantEntity::getId, m -> m));
        Map<Long, UserEntity> users = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(UserEntity::getId, u -> u));
        return records.stream().map(m -> {
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
                String.valueOf(store.getOwnerUserId()), member.getRole(), member.getStatus(),
                StaffPermissions.effective(member.getPermissions()));
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
                "OWNER".equals(member.getRole())
                        ? StaffPermissions.all()
                        : StaffPermissions.effective(member.getPermissions()),
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
