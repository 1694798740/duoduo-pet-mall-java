package com.duoduo.social.support.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.MerchantMemberBrief;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.mapper.SupportSessionMapper;
import com.duoduo.social.mapper.SupportTicketLogMapper;
import com.duoduo.social.support.dto.SupportDtos.TicketLogView;
import com.duoduo.social.support.dto.SupportDtos.TicketMemberView;
import com.duoduo.social.support.dto.SupportDtos.TicketTransferRequest;
import com.duoduo.social.support.dto.SupportDtos.TicketView;
import com.duoduo.social.support.entity.SupportSessionEntity;
import com.duoduo.social.support.entity.SupportTicketLogEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 客服工单：认领 / 转交 / 释放 / 处理记录（C-04）。
 *
 * <h2>处理人不是访问控制</h2>
 * 店主与在职店员本来就能看到全店客服会话；{@code assignee_id} 只表达
 * 「现在是谁在接这个买家」，避免多人同时回复同一个买家，也让交接有据可查。
 * 把处理人做成权限，会让「同事请假 / 停用」时会话直接没人能看。
 *
 * <h2>并发</h2>
 * 认领是条件 UPDATE（{@code assignee_id IS NULL}），转交/释放要求动作前的处理人仍是读到的值。
 * 两个店员同时点「认领」只有一个人成功；先到的人赢，后到的人收到 409 与可读提示，
 * 而不是两个人都以为自己在接待。
 *
 * <h2>审计</h2>
 * 每次成功动作都与会话更新写在**同一个事务**里，追加一条 {@code t_support_ticket_log}。
 * 日志写失败则动作回滚 —— 「查不到谁转的」比「转交失败」更难补救。
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);
    private static final int MAX_NOTE = 200;

    private final SupportSessionMapper sessionMapper;
    private final SupportTicketLogMapper logMapper;
    private final UserClient userClient;
    private final SnowflakeIdGenerator idGenerator;

    public SupportTicketService(SupportSessionMapper sessionMapper,
                                SupportTicketLogMapper logMapper,
                                UserClient userClient,
                                SnowflakeIdGenerator idGenerator) {
        this.sessionMapper = sessionMapper;
        this.logMapper = logMapper;
        this.userClient = userClient;
        this.idGenerator = idGenerator;
    }

    /** 工单视图：当前处理人 + 本店在职成员 + 处理记录。 */
    public TicketView view(Long ownerId, Long sessionId) {
        SupportSessionEntity session = requireOwnedSession(ownerId, sessionId);
        return toView(ownerId, session, membersOf(ownerId));
    }

    /**
     * 认领：只有未认领时成功；重复认领（已经是我）幂等返回，不重复记日志。
     */
    @Transactional(rollbackFor = Exception.class)
    public TicketView claim(Long ownerId, Long sessionId) {
        LoginUser actor = UserContext.require();
        SupportSessionEntity session = requireOwnedSession(ownerId, sessionId);
        if ("CLOSED".equalsIgnoreCase(session.getStatus())) {
            throw BizException.businessRule("会话已结束，无需认领；买家再次来信后会自动重新打开");
        }
        if (Objects.equals(session.getAssigneeId(), actor.userId())) {
            return toView(ownerId, session, membersOf(ownerId));
        }
        if (session.getAssigneeId() != null) {
            throw BizException.conflict("该会话已被 " + nameOf(session.getAssigneeId()) + " 认领");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = sessionMapper.claimTicket(sessionId, ownerId, actor.userId(), now);
        if (rows == 0) {
            SupportSessionEntity latest = requireOwnedSession(ownerId, sessionId);
            if (Objects.equals(latest.getAssigneeId(), actor.userId())) {
                return toView(ownerId, latest, membersOf(ownerId));
            }
            throw BizException.conflict("该会话刚被 " + nameOf(latest.getAssigneeId()) + " 认领，请刷新");
        }
        writeLog(sessionId, "CLAIM", null, actor.userId(), actor, ownerId, null, now);
        session.setAssigneeId(actor.userId());
        session.setAssignedAt(now);
        log.info("客服会话 {} 被 {} 认领", sessionId, actor.userId());
        return toView(ownerId, session, membersOf(ownerId));
    }

    /**
     * 转交：当前处理人或店主可操作；目标必须是在职成员。
     *
     * <p>店主可以把「未认领」的会话直接指派给某个店员（from 为 null），
     * 这是排班场景的常见动作；店员不能把未认领的会话指派给别人（避免互相推）。
     */
    @Transactional(rollbackFor = Exception.class)
    public TicketView transfer(Long ownerId, Long sessionId, TicketTransferRequest request) {
        LoginUser actor = UserContext.require();
        SupportSessionEntity session = requireOwnedSession(ownerId, sessionId);
        Long from = session.getAssigneeId();
        boolean owner = actor.userId().equals(ownerId);
        if (!owner && !Objects.equals(from, actor.userId())) {
            throw BizException.forbidden("只有当前处理人或店主可以转交");
        }
        Long to = request == null ? null : request.toUserId();
        if (to == null) {
            throw BizException.validation("请选择转交对象");
        }
        if (Objects.equals(to, from)) {
            return toView(ownerId, session, membersOf(ownerId));
        }
        List<TicketMemberView> members = membersOf(ownerId);
        boolean targetActive = members.stream().anyMatch(m -> String.valueOf(to).equals(m.userId()));
        if (!targetActive) {
            throw BizException.validation("转交对象不是本店在职成员");
        }
        String note = normalizeNote(request.note());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = sessionMapper.transferTicket(sessionId, ownerId, from, to, now);
        if (rows == 0) {
            throw BizException.conflict("会话处理人已变化，请刷新后再转交");
        }
        writeLog(sessionId, "TRANSFER", from, to, actor, ownerId, note, now);
        session.setAssigneeId(to);
        session.setAssignedAt(now);
        log.info("客服会话 {} 由 {} 转交给 {}（操作者 {}）", sessionId, from, to, actor.userId());
        return toView(ownerId, session, members);
    }

    /** 释放：当前处理人或店主把会话归还为「未认领」；已经是未认领则幂等返回。 */
    @Transactional(rollbackFor = Exception.class)
    public TicketView release(Long ownerId, Long sessionId) {
        LoginUser actor = UserContext.require();
        SupportSessionEntity session = requireOwnedSession(ownerId, sessionId);
        Long from = session.getAssigneeId();
        if (from == null) {
            return toView(ownerId, session, membersOf(ownerId));
        }
        boolean owner = actor.userId().equals(ownerId);
        if (!owner && !Objects.equals(from, actor.userId())) {
            throw BizException.forbidden("只有当前处理人或店主可以释放");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = sessionMapper.releaseTicket(sessionId, ownerId, from, now);
        if (rows == 0) {
            throw BizException.conflict("会话处理人已变化，请刷新后再释放");
        }
        writeLog(sessionId, "RELEASE", from, null, actor, ownerId, null, now);
        session.setAssigneeId(null);
        session.setAssignedAt(null);
        log.info("客服会话 {} 由 {} 释放为未认领（操作者 {}）", sessionId, from, actor.userId());
        return toView(ownerId, session, membersOf(ownerId));
    }

    // ---------------- 内部 ----------------

    private SupportSessionEntity requireOwnedSession(Long ownerId, Long sessionId) {
        SupportSessionEntity session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("客服会话");
        }
        if (!Objects.equals(session.getSellerId(), ownerId)) {
            throw BizException.forbidden("该会话不属于当前店铺");
        }
        return session;
    }

    /** 本店在职成员；店铺未入驻（自营客服）时退化为「只有当前操作者」 */
    private List<TicketMemberView> membersOf(Long ownerId) {
        List<TicketMemberView> members = new ArrayList<>();
        try {
            var response = userClient.merchantMembers(ownerId);
            List<MerchantMemberBrief> rows = response == null ? null : response.data();
            if (rows != null) {
                rows.forEach(m -> members.add(new TicketMemberView(
                        m.userId(),
                        m.nickname() == null ? "成员" + m.userId() : m.nickname(),
                        m.avatar(),
                        m.role())));
            }
        } catch (Exception e) {
            // 用户域抖动不应让工单面板打不开：退化为「只有当前操作者」，转交时会因名单缺失被拒
            log.warn("取店铺成员失败 ownerId={}", ownerId, e);
        }
        if (members.isEmpty()) {
            Long actorId = UserContext.get() == null ? ownerId : UserContext.get().userId();
            members.add(new TicketMemberView(String.valueOf(actorId), nameOf(actorId), null,
                    Objects.equals(actorId, ownerId) ? "OWNER" : "STAFF"));
        }
        return members;
    }

    private TicketView toView(Long ownerId, SupportSessionEntity session, List<TicketMemberView> members) {
        List<SupportTicketLogEntity> rows = logMapper.listBySession(session.getId());
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        rows.forEach(r -> {
            if (r.getFromAssignee() != null) ids.add(r.getFromAssignee());
            if (r.getToAssignee() != null) ids.add(r.getToAssignee());
            if (r.getActorId() != null) ids.add(r.getActorId());
        });
        if (session.getAssigneeId() != null) ids.add(session.getAssigneeId());
        Map<Long, UserBrief> briefs = loadBriefs(ids);
        Map<String, String> memberNames = new HashMap<>();
        members.forEach(m -> memberNames.put(m.userId(), m.name()));

        if (!ids.isEmpty()) {
            // 用户域没返回名字时，用成员名单兜底；两边都没有就显示 userId
            ids.forEach(id -> memberNames.putIfAbsent(String.valueOf(id),
                    briefs.containsKey(id) && briefs.get(id).nickname() != null
                            ? briefs.get(id).nickname() : "成员" + id));
        }
        List<TicketLogView> logs = rows.stream().map(r -> new TicketLogView(
                r.getId(), r.getAction(), actionLabelOf(r.getAction()),
                idOf(r.getFromAssignee()), nameOf(r.getFromAssignee(), memberNames),
                idOf(r.getToAssignee()), nameOf(r.getToAssignee(), memberNames),
                idOf(r.getActorId()), nameOf(r.getActorId(), memberNames),
                r.getNote(),
                r.getCreatedAt() == null ? null : r.getCreatedAt().toInstant(ZoneOffset.UTC).toString()))
                .toList();
        Long assigneeId = session.getAssigneeId();
        return new TicketView(
                session.getId(),
                session.getStatus(),
                idOf(assigneeId),
                nameOf(assigneeId, memberNames),
                session.getAssignedAt() == null ? null : session.getAssignedAt().toInstant(ZoneOffset.UTC).toString(),
                members,
                logs);
    }

    private void writeLog(Long sessionId, String action, Long from, Long to,
                          LoginUser actor, Long ownerId, String note, LocalDateTime now) {
        SupportTicketLogEntity entity = new SupportTicketLogEntity();
        entity.setId(idGenerator.nextId());
        entity.setSessionId(sessionId);
        entity.setAction(action);
        entity.setFromAssignee(from);
        entity.setToAssignee(to);
        entity.setActorId(actor.userId());
        entity.setActorRole(actor.userId().equals(ownerId) ? "OWNER" : "STAFF");
        entity.setNote(note);
        entity.setCreatedAt(now);
        logMapper.insert(entity);
    }

    private Map<Long, UserBrief> loadBriefs(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        try {
            var response = userClient.briefs(ids.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
            List<UserBrief> briefs = response == null ? null : response.data();
            if (briefs == null) {
                return Map.of();
            }
            Map<Long, UserBrief> map = new HashMap<>();
            briefs.forEach(b -> map.putIfAbsent(b.id(), b));
            return map;
        } catch (Exception e) {
            log.warn("取工单相关用户资料失败 ids={}", ids, e);
            return Map.of();
        }
    }

    private String nameOf(Long id) {
        if (id == null) {
            return "未认领";
        }
        Map<Long, UserBrief> briefs = loadBriefs(List.of(id));
        UserBrief brief = briefs.get(id);
        return brief == null || brief.nickname() == null ? "成员" + id : brief.nickname();
    }

    private static String nameOf(Long id, Map<String, String> names) {
        if (id == null) {
            return null;
        }
        return names.getOrDefault(String.valueOf(id), "成员" + id);
    }

    private static String idOf(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private static String actionLabelOf(String action) {
        return switch (action == null ? "" : action) {
            case "CLAIM" -> "认领";
            case "TRANSFER" -> "转交";
            case "RELEASE" -> "释放";
            default -> action;
        };
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String text = note.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_NOTE) {
            throw BizException.validation("转交备注不能超过 " + MAX_NOTE + " 字");
        }
        return text;
    }
}
