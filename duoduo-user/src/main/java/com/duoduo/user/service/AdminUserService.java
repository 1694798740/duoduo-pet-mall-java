package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.common.security.perm.RolePermissionMatrix;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos.AdminUserView;
import com.duoduo.user.dto.UserDtos.RoleChangeResult;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.PermVersionPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营侧账号管理（A7）。
 *
 * <p>权限模型是<b>两层</b>的，对应 PRD 的角色矩阵：
 * <ul>
 *   <li>进后台看列表：OPERATOR / ADMIN（{@link OperatorGuard#requireOperator()}）——
 *       客服与运营要能查用户、核对状态；</li>
 *   <li>改角色：**仅 ADMIN** —— 角色就是权限本身，让 OPERATOR 能发角色
 *       等于把权限体系整个交了出去（提权链必须有人守门）。</li>
 * </ul>
 *
 * <p>三条硬规则（验收口径）：
 * <ol>
 *   <li>不能给自己降权 —— 防手滑把自己锁在门外，只能由另一个 ADMIN 互相制衡；</li>
 *   <li>不能降级最后一个 ADMIN —— 平台永远至少留一名超级管理员，
 *       否则所有后台能力随最后一次降级一起陪葬；</li>
 *   <li>每次变更写 {@code t_audit_log}（谁给谁从什么改成什么）——
 *       权限变更是审计的第一等公民，出事时这是唯一能回答「当时谁干的」的证据。</li>
 * </ol>
 *
 * <p>生效语义（E17 起）：访问令牌里的角色是<b>签发时的快照</b>，但令牌同时带一个权限版本；
 * 改角色会让版本 +1 并广播给网关，因此被改用户手上的旧令牌
 * <b>在下一个请求就被判过期（401）</b>，客户端刷新一次即换到新角色 ——
 * 既不用等 15 分钟自然过期，也不需要重新登录。服务层把这个结果拼进返回文案，
 * 让运营界面能原样展示（验收②）。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /**
     * 角色白名单（E16 起改为引用矩阵的 {@code KNOWN_ROLES}）。
     *
     * <p>原先这里独立硬编码一份 {@code Set.of("USER","SELLER","OPERATOR","ADMIN")}，
     * 与 {@code RolePermissionMatrix} 各持一份角色清单 —— 加角色时必然漏一处。
     * 现在「系统角色有哪些」只有矩阵一个来源。
     */
    private static final Set<String> ALLOWED_ROLES = RolePermissionMatrix.KNOWN_ROLES;

    /** 权能从高到低（用于「给自己降权」判定）：数值越小权能越高 */
    private static final Map<String, Integer> ROLE_RANK =
            Map.of("ADMIN", 0, "OPERATOR", 1, "SELLER", 2, "USER", 3);


    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final PermVersionPublisher permVersionPublisher;

    public AdminUserService(UserMapper userMapper,
                            RefreshTokenMapper refreshTokenMapper,
                            SnowflakeIdGenerator idGenerator,
                            PermVersionPublisher permVersionPublisher) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.idGenerator = idGenerator;
        this.permVersionPublisher = permVersionPublisher;
    }

    /**
     * 分页查询用户（运营视角，脱敏）。
     *
     * <p>{@code role} 精确过滤；{@code keyword} 同时模糊匹配手机号与昵称
     * （运营排查的入口要么是「对方报的手机号」要么是「对方报的昵称」，一次查全）。
     */
    public PageResult<AdminUserView> listUsers(String role, String keyword, int page, int pageSize) {
        OperatorGuard.requireOperator();

        String roleFilter = StringUtils.hasText(role) ? role.trim().toUpperCase() : null;
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        LambdaQueryWrapper<UserEntity> wrapper = Wrappers.<UserEntity>lambdaQuery()
                .eq(StringUtils.hasText(roleFilter), UserEntity::getRole, roleFilter)
                .and(kw != null,
                        w -> w.like(UserEntity::getPhone, kw).or().like(UserEntity::getNickname, kw))
                .orderByDesc(UserEntity::getCreatedAt);

        Page<UserEntity> result = userMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<AdminUserView> items = result.getRecords().stream().map(AdminUserService::toAdminView).toList();
        return PageResult.of(items, result.getTotal(), page, pageSize);
    }

    /**
     * 变更用户角色（仅 ADMIN）。
     *
     * <p>幂等：目标用户已是目标角色时直接成功返回、不写库、不留审计
     * （「重复确认同一状态」不构成事件，写成事件反而稀释审计信噪比）。
     */
    @Transactional
    public RoleChangeResult changeRole(Long targetUserId, String newRoleRaw) {
        // 第一层门：进后台；第二层门：改角色需权限点 user:role:manage（矩阵里仅 ADMIN）——
        // 两句独立报错，运营看到 403 时能分清是「没进后台」还是「进了但级别不够」。
        // E16 起不再写 actor.isAdmin()：判权只认权限点，角色名比较是上一代的写法。
        LoginUser actor = OperatorGuard.requireOperator();
        if (!actor.hasPerm(Permission.USER_ROLE_MANAGE)) {
            throw BizException.forbidden("变更用户角色仅限超级管理员操作");
        }

        String newRole = newRoleRaw == null ? "" : newRoleRaw.trim().toUpperCase();
        if (!ALLOWED_ROLES.contains(newRole)) {
            throw BizException.businessRule("角色只能是 USER / SELLER / OPERATOR / ADMIN");
        }

        UserEntity target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw BizException.notFound("用户");
        }

        String oldRole = target.getRole() == null ? "USER" : target.getRole().toUpperCase();
        if (oldRole.equals(newRole)) {
            // 幂等：目标角色与当前一致，原样返回，不产生审计事件。
            // E18 起留痕由拦截器统一做，所以这里要**显式抑制** —— 否则「重复确认同一状态」
            // 会变成一条记录，把审计信噪比稀释掉（这也是 E18 之前的行为）。
            AuditTrail.skip("目标已是该角色，无实际变更");
            return new RoleChangeResult(toAdminView(target), "该用户已是 " + newRole + " 角色，未做变更");
        }

        // 不能给自己降权：权能上自己永远要保持在当前档位，降权必须由另一个 ADMIN 执行
        if (targetUserId.equals(actor.userId()) && ROLE_RANK.get(newRole) > ROLE_RANK.get(oldRole)) {
            throw BizException.businessRule("不能给自己降权，请由其他管理员操作");
        }

        // 最后一个 ADMIN 不可降级：先数后改，count 在同一事务里随行锁串行化，
        // 并发降级最后两名 ADMIN 时后到者会看到 count 已减一而收到 422
        if ("ADMIN".equals(oldRole) && !"ADMIN".equals(newRole)) {
            Long adminCount = userMapper.selectCount(
                    Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getRole, "ADMIN"));
            if (adminCount == null || adminCount <= 1) {
                throw BizException.businessRule(
                        "平台至少保留一名超级管理员，无法降级最后一个 ADMIN");
            }
        }

        target.setRole(newRole);
        target.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(target);

        /*
         * E17 · 权限版本 +1，把新版本广播给网关。
         *
         * 为什么不再"一律吊销刷新令牌"：那是 E17 之前的止血办法 —— 只能保证
         * **不能静默续期**，旧访问令牌仍可带旧角色活到自然过期（≤15 分钟）。
         * 降级场景下这 15 分钟是实打实的越权窗口。现在改为版本号精确收口：
         * 旧令牌下一个请求即 401，客户端刷新一次就换到新角色，**不需要重新登录**。
         *
         * 为什么"自增"要写进 SQL：读出来 +1 再写回，在并发改同一用户时会有丢失更新 ——
         * 两个人各读到 v，都写 v+1，最终只加了一次。自增交给数据库，一次语句就是一次原子的。
         */
        userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .eq(UserEntity::getId, targetUserId)
                .setSql("perm_version = perm_version + 1"));

        // 回读新版本：不自己算 old+1，避免与上面的原子自增产生第二份口径
        UserEntity refreshed = userMapper.selectById(targetUserId);
        Long newVersion = refreshed == null ? target.getPermVersion() : refreshed.getPermVersion();
        target.setPermVersion(newVersion);

        /*
         * 广播失败必须退回老路（吊销刷新令牌）。
         * 不退回的话：网关照旧读不到新版本 → 继续放行旧令牌 → 运营界面上写着"权限已收回"、
         * 实际上还能用。宁可让被改的人重新登录一次，也不能留下"看起来收回了"的假象。
         * 两个响应文案因此是**有意**分开的：它是运营判断"这次变更到底生效到哪一步"的唯一线索。
         */
        boolean broadcast = permVersionPublisher.publish(targetUserId, newVersion);

        // 吊销刷新令牌：**仅在广播失败时**作为兜底（见上）
        int revoked = broadcast ? 0 : refreshTokenMapper.revokeAllByUser(targetUserId);

        // E18 · 补充审计上下文（目标 + 前后值）。采集与落库由 AuditInterceptor 统一做：
        // 一个动作只能有一条记录，所以这里不再自己写审计表。
        // 租户一律 0：角色是平台级概念，不属于任何店铺 —— 这里若填了 actor 的 userId，
        // 审计页会把它误显示成「某家店的动作」。
        AuditTrail.target(target.getId(), "USER");
        AuditTrail.change(oldRole, newRole);

        log.info("角色变更 actorId={} targetId={} {} -> {}，permVersion={}，副本广播={}，吊销刷新令牌 {} 条",
                actor.userId(), targetUserId, oldRole, newRole, newVersion, broadcast, revoked);

        String notice = broadcast
                ? "角色已更新为 " + newRole + "；该用户在线会话将在下一个请求即生效（无需重新登录）"
                : "角色已更新为 " + newRole + "；权限版本副本同步失败（Redis 不可用），已吊销该用户的刷新令牌（"
                + revoked + " 条），其在线会话将重新登录后生效";
        return new RoleChangeResult(toAdminView(target), notice);
    }

    /** 运营视图（脱敏）：不给实名姓名、身份证掩码、完整手机号，理由见 DTO 注释 */
    private static AdminUserView toAdminView(UserEntity entity) {
        return new AdminUserView(
                entity.getId(),
                maskPhone(entity.getPhone()),
                entity.getNickname(),
                entity.getRole(),
                entity.getStatus(),
                entity.isRealNameVerified(),
                entity.getCreditScore(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString()
        );
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
