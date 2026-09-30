package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos.AdminUserView;
import com.duoduo.user.dto.UserDtos.RoleChangeResult;
import com.duoduo.user.entity.AuditLogEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.AuditLogMapper;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.UserMapper;
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
 * <p>生效语义：访问令牌里的角色是<b>签发时的快照</b>，被改用户重新登录后才拿到新角色。
 * 服务层把这一点拼进返回的提示文案，让运营界面能原样展示（验收②）。
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /** 角色白名单：与 {@code LoginUser} 的判定口径一致，库里统一大写存储 */
    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "SELLER", "OPERATOR", "ADMIN");

    /** 权能从高到低（用于「给自己降权」判定）：数值越小权能越高 */
    private static final Map<String, Integer> ROLE_RANK =
            Map.of("ADMIN", 0, "OPERATOR", 1, "SELLER", 2, "USER", 3);

    private static final String ACTION_ROLE_CHANGE = "USER_ROLE_CHANGE";

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final SnowflakeIdGenerator idGenerator;

    public AdminUserService(UserMapper userMapper,
                            AuditLogMapper auditLogMapper,
                            RefreshTokenMapper refreshTokenMapper,
                            SnowflakeIdGenerator idGenerator) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.idGenerator = idGenerator;
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
        // 第一层门：进后台；第二层门：改角色仅限 ADMIN —— 两句独立报错，
        // 运营看到 403 时能分清是「没进后台」还是「进了但级别不够」
        LoginUser actor = OperatorGuard.requireOperator();
        if (!actor.isAdmin()) {
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
            // 幂等：目标角色与当前一致，原样返回，不产生审计事件
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

        // 吊销目标用户**所有**刷新令牌（第五十八轮 P1-6）：
        //   访问令牌里带角色快照，改完库里的角色，对方手上的旧令牌仍是旧角色 ——
        //   降级场景下这意味着「已经被收回的权限还能继续用」，比升级不生效危险得多。
        //   吊销刷新令牌后旧访问令牌最多活到自然过期，且无法静默续期，等于强制重登。
        int revoked = refreshTokenMapper.revokeAllByUser(targetUserId);

        writeAudit(actor, target, oldRole, newRole);

        log.info("角色变更 actorId={} targetId={} {} -> {}，吊销刷新令牌 {} 条",
                actor.userId(), targetUserId, oldRole, newRole, revoked);
        return new RoleChangeResult(toAdminView(target),
                "角色已更新为 " + newRole + "；已吊销该用户的刷新令牌（" + revoked
                        + " 条），其在线会话将重新登录后生效");
    }

    private void writeAudit(LoginUser actor, UserEntity target, String oldRole, String newRole) {
        AuditLogEntity record = new AuditLogEntity();
        record.setId(idGenerator.nextId());
        record.setActorId(actor.userId());
        record.setActorPhone(actor.phone());
        record.setAction(ACTION_ROLE_CHANGE);
        record.setTargetId(target.getId());
        record.setDetail(oldRole + " -> " + newRole);
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        auditLogMapper.insert(record);
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
