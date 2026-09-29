package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.dto.ShareDtos;
import com.duoduo.fulfilment.dto.ShareDtos.ReportHandleRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ReportView;
import com.duoduo.fulfilment.entity.ShareCommentEntity;
import com.duoduo.fulfilment.entity.ShareEntity;
import com.duoduo.fulfilment.entity.ShareReportEntity;
import com.duoduo.fulfilment.mapper.ShareCommentMapper;
import com.duoduo.fulfilment.mapper.ShareEngagementMapper;
import com.duoduo.fulfilment.mapper.ShareMapper;
import com.duoduo.fulfilment.mapper.ShareReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 分享 / 评论举报服务（第五十六轮）。
 *
 * <p>用户侧只有「举报」一个动作；运营侧有「看列表」与「判定」两个动作。
 * 两者共用一张表 {@code t_share_report}（SHARE 与 COMMENT 的处理流程完全一致）。
 *
 * <p>四条规则：
 * <ol>
 *   <li><b>理由必填</b>：无理由举报让运营无从判断，等于把成本转嫁给运营；</li>
 *   <li><b>不能举报自己的内容</b>：那是「删除」，不该占运营队列；</li>
 *   <li><b>同一人对同一对象只能举报一次</b>：重复提交返回 409 而不是静默成功 ——
 *       用户需要知道「平台已经收到了」，而不是「点了几次都没反应」；
 *       唯一键 {@code uk_share_report_once} 兜住并发；</li>
 *   <li><b>判定通过才下架</b>，且下架是<b>软删</b>：留痕，也保住转发单的引用块
 *       （显示「原内容已被作者删除」而不是崩掉）。</li>
 * </ol>
 *
 * <p><b>不做什么</b>：不做自动判定（关键词或模型）、不做举报计数触发的自动下架 ——
 * 那需要误伤评估与申诉链路，是另一个量级的产品决策，本轮明确不做。
 */
@Service
public class ShareReportService {

    private static final Logger log = LoggerFactory.getLogger(ShareReportService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** 列表里放的内容摘要长度（运营看个大概，点进详情看全文） */
    private static final int EXCERPT_LENGTH = 60;

    private final ShareReportMapper reportMapper;
    private final ShareMapper shareMapper;
    private final ShareCommentMapper commentMapper;
    private final ShareEngagementMapper engagementMapper;
    private final ShareTopicService topicService;
    private final SnowflakeIdGenerator idGenerator;

    public ShareReportService(ShareReportMapper reportMapper,
                              ShareMapper shareMapper,
                              ShareCommentMapper commentMapper,
                              ShareEngagementMapper engagementMapper,
                              ShareTopicService topicService,
                              SnowflakeIdGenerator idGenerator) {
        this.reportMapper = reportMapper;
        this.shareMapper = shareMapper;
        this.commentMapper = commentMapper;
        this.engagementMapper = engagementMapper;
        this.topicService = topicService;
        this.idGenerator = idGenerator;
    }

    // ---------------- 用户侧 ----------------

    /** 举报分享 */
    public ReportView reportShare(Long userId, Long shareId, String reason) {
        ShareEntity share = shareMapper.selectById(shareId);
        if (share == null || share.getDeletedAt() != null) {
            throw BizException.notFound("分享");
        }
        if (Objects.equals(share.getUserId(), userId)) {
            throw BizException.businessRule("这是你自己的分享，如需下架请直接删除");
        }
        return create(userId, ShareDtos.REPORT_TARGET_SHARE, shareId, reason);
    }

    /** 举报评论 */
    public ReportView reportComment(Long userId, Long commentId, String reason) {
        ShareCommentEntity comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null) {
            throw BizException.notFound("评论");
        }
        if (Objects.equals(comment.getUserId(), userId)) {
            throw BizException.businessRule("这是你自己的评论");
        }
        return create(userId, ShareDtos.REPORT_TARGET_COMMENT, commentId, reason);
    }

    private ReportView create(Long userId, String targetType, Long targetId, String reason) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再举报");
        }
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw BizException.validation("请填写举报理由");
        }
        if (normalized.length() > 200) {
            throw BizException.validation("举报理由不能超过 200 字");
        }
        // 先查一次只为给出「你已举报过」的人话提示；真正的并发防线是下面的唯一键
        Long existed = reportMapper.selectCount(new LambdaQueryWrapper<ShareReportEntity>()
                .eq(ShareReportEntity::getTargetType, targetType)
                .eq(ShareReportEntity::getTargetId, targetId)
                .eq(ShareReportEntity::getReporterId, userId));
        if (existed != null && existed > 0) {
            throw BizException.conflict("你已经举报过了，平台正在处理");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ShareReportEntity entity = new ShareReportEntity();
        entity.setId(idGenerator.nextId());
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setReporterId(userId);
        entity.setReason(normalized);
        entity.setStatus(ShareDtos.REPORT_PENDING);
        entity.setCreatedAt(now);
        try {
            reportMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发窗口：先查时都没有，插入时撞唯一键。按「已举报」处理，不报 500
            throw BizException.conflict("你已经举报过了，平台正在处理");
        }
        log.info("用户 {} 举报了 {} {}（理由：{}）", userId, targetType, targetId, normalized);
        return toView(entity, Map.of(), Map.of());
    }

    // ---------------- 运营侧 ----------------

    /** 举报列表（可按状态过滤；默认全部，按提交时间倒序） */
    public PageResult<ReportView> listForAdmin(String status, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<ShareReportEntity> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(ShareReportEntity::getStatus, status.trim().toUpperCase());
        }
        wrapper.orderByDesc(ShareReportEntity::getCreatedAt).orderByDesc(ShareReportEntity::getId);
        Page<ShareReportEntity> result = reportMapper.selectPage(new Page<>(pageNo, size), wrapper);

        Map<Long, ShareEntity> shares = loadShares(result.getRecords());
        Map<Long, ShareCommentEntity> comments = loadComments(result.getRecords());
        List<ReportView> views = result.getRecords().stream()
                .map(r -> toView(r, shares, comments))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /**
     * 处理举报（运营）。
     *
     * <p>ACCEPT：下架被举报的内容（软删）+ 回退相关计数；REJECT：只记结论。
     * 两种动作都必须写处理人与处理说明 —— 举报处理是「对用户决定的解释」，
     * 没有说明就没法回应申诉。
     *
     * <p>已处理过的一律 409：重复处理会让「下架」被执行两次
     * （第二次虽然幂等，但处理人/时间会被覆盖，审计链就断了）。
     */
    public ReportView handle(Long operatorId, Long reportId, ReportHandleRequest request) {
        ShareReportEntity report = reportMapper.selectById(reportId);
        if (report == null) {
            throw BizException.notFound("举报记录");
        }
        if (!ShareDtos.REPORT_PENDING.equals(report.getStatus())) {
            throw BizException.conflict("该举报已处理过（当前状态：" + report.getStatus() + "）");
        }
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        if (!"ACCEPT".equals(action) && !"REJECT".equals(action)) {
            throw BizException.validation("处理结论只能是 ACCEPT 或 REJECT");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if (note.isEmpty()) {
            throw BizException.validation("请填写处理说明（用户看到的就是它）");
        }
        if (note.length() > 200) {
            throw BizException.validation("处理说明不能超过 200 字");
        }

        if ("ACCEPT".equals(action)) {
            takedown(report);
        }
        report.setStatus("ACCEPT".equals(action) ? ShareDtos.REPORT_ACCEPTED : ShareDtos.REPORT_REJECTED);
        report.setHandledBy(operatorId);
        report.setHandledAt(LocalDateTime.now(ZoneOffset.UTC));
        report.setHandleNote(note);
        reportMapper.updateById(report);

        log.info("运营 {} 处理举报 {}：{}（{}）", operatorId, reportId, action, note);
        return toView(report, loadShares(List.of(report)), loadComments(List.of(report)));
    }

    /** 下架被举报的内容（软删 + 计数回退） */
    private void takedown(ShareReportEntity report) {
        if (ShareDtos.REPORT_TARGET_SHARE.equals(report.getTargetType())) {
            ShareEntity share = shareMapper.selectById(report.getTargetId());
            if (share == null || share.getDeletedAt() != null) {
                return;
            }
            share.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
            share.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            shareMapper.updateById(share);
            // 话题计数同步回退：话题榜是按「被使用次数」排的，下架内容不回落会让榜单虚高
            topicService.detachAll(share.getId());
            return;
        }
        ShareCommentEntity comment = commentMapper.selectById(report.getTargetId());
        if (comment == null || comment.getDeletedAt() != null) {
            return;
        }
        // 回复随父评论一起不可达（打不开已删除的评论，也就看不到它的回复），故一并回退
        long aliveReplies = commentMapper.countAliveReplies(comment.getId());
        commentMapper.softDelete(comment.getId());
        engagementMapper.bumpCommentCount(comment.getShareId(), (int) -(1 + aliveReplies));
    }

    // ---------------- 装配 ----------------

    private Map<Long, ShareEntity> loadShares(List<ShareReportEntity> reports) {
        List<Long> ids = reports.stream()
                .filter(r -> ShareDtos.REPORT_TARGET_SHARE.equals(r.getTargetType()))
                .map(ShareReportEntity::getTargetId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return shareMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ShareEntity::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, ShareCommentEntity> loadComments(List<ShareReportEntity> reports) {
        List<Long> ids = reports.stream()
                .filter(r -> ShareDtos.REPORT_TARGET_COMMENT.equals(r.getTargetType()))
                .map(ShareReportEntity::getTargetId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return commentMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ShareCommentEntity::getId, c -> c, (a, b) -> a));
    }

    private ReportView toView(ShareReportEntity report,
                              Map<Long, ShareEntity> shares,
                              Map<Long, ShareCommentEntity> comments) {
        String excerpt;
        Long authorId;
        Long shareId;
        if (ShareDtos.REPORT_TARGET_SHARE.equals(report.getTargetType())) {
            ShareEntity share = shares.get(report.getTargetId());
            excerpt = share == null || share.getDeletedAt() != null
                    ? "（内容已被删除）" : excerpt(share.getContent());
            authorId = share == null ? null : share.getUserId();
            shareId = report.getTargetId();
        } else {
            ShareCommentEntity comment = comments.get(report.getTargetId());
            excerpt = comment == null || comment.getDeletedAt() != null
                    ? "（评论已被删除）" : excerpt(comment.getContent());
            authorId = comment == null ? null : comment.getUserId();
            shareId = comment == null ? null : comment.getShareId();
        }
        return new ReportView(
                report.getId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReporterId(),
                report.getReason(),
                report.getStatus(),
                report.getCreatedAt() == null ? null : report.getCreatedAt().toInstant(ZoneOffset.UTC).toString(),
                report.getHandledBy(),
                report.getHandledAt() == null ? null : report.getHandledAt().toInstant(ZoneOffset.UTC).toString(),
                report.getHandleNote(),
                excerpt,
                authorId,
                shareId);
    }

    /** 摘要裁剪：列表里放不下全文，超长截断加省略号（空内容也给一个中性占位，不留空串） */
    private String excerpt(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return "（无文字内容）";
        }
        return normalized.length() <= EXCERPT_LENGTH
                ? normalized : normalized.substring(0, EXCERPT_LENGTH - 1) + "…";
    }
}
