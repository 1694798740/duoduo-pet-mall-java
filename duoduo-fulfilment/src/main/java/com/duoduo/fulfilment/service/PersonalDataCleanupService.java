package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.fulfilment.dto.PersonalDataCleanupResult;
import com.duoduo.fulfilment.entity.AfterSaleEntity;
import com.duoduo.fulfilment.entity.ShareCommentEntity;
import com.duoduo.fulfilment.entity.ShareEntity;
import com.duoduo.fulfilment.mapper.AfterSaleMapper;
import com.duoduo.fulfilment.mapper.ShareCommentMapper;
import com.duoduo.fulfilment.mapper.ShareEngagementMapper;
import com.duoduo.fulfilment.mapper.ShareMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * C7 · 账号注销：履约域的个人数据清理（服务间，由用户域注销编排调用）。
 *
 * <p>设计依据：{@code docs/account-deletion-design.md} 第 4.4 节与第五节数据清理矩阵。
 * 履约域存着两类数据，注销时待遇完全不同：
 * <ul>
 *   <li><b>分享圈内容</b>（分享 / 评论 / 点赞 / 收藏）—— 用户主动发表的 UGC，
 *       无留存义务，物理删；</li>
 *   <li><b>评价（{@code t_review}）与售后单</b>—— 与订单、商品评分强绑定的交易
 *       留档，整行不动（第五节矩阵：{@code t_review} 保留）。评价展示层对已注销
 *       作者已有「已注销用户」兜底，物理删评价的收益是零，代价是商品历史评分失真。</li>
 * </ul>
 *
 * <p><b>幂等可重入是硬要求</b>：注销执行分「五域清理 → 全部成功 → 置 DELETED」两步，
 * 任一步失败都要靠下一轮调度重试。本域的 DELETE 全部按 {@code user_id} 定位，
 * 是幂等语句 —— 重复调用时该用户的四类行已空（删 0 行），不报错、不二次破坏。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li>{@link #preview}（dryRun）：只查不写，回报「若执行将会删多少行」；</li>
 *   <li>{@link #execute}（缺省 / false）：真正执行删除，回报实际影响行数。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（共用同一组 WHERE），预估与实际必然对得上。
 */
@Service
public class PersonalDataCleanupService {

    /** 域标识（与用户域 Feign 契约 {@code AccountDeletionDtos}、注销日志口径一致） */
    private static final String DOMAIN = "fulfilment";

    /**
     * 售后终态：已退款 / 已关闭。
     *
     * <p>非终态（待卖家处理 / 卖家已拒绝 / 待寄回 / 已寄回待签收 / 仲裁中）
     * = 注销阻塞项，与设计文档第六节「注销中的退款 → 阻塞」一致：
     * 钱与货的争议还没结案，账号不能先消失。
     */
    private static final List<String> FINAL_AFTER_SALE_STATUSES =
            List.of("REFUNDED", "CLOSED");

    private final ShareMapper shareMapper;
    private final ShareCommentMapper shareCommentMapper;
    private final ShareEngagementMapper shareEngagementMapper;
    private final AfterSaleMapper afterSaleMapper;

    public PersonalDataCleanupService(ShareMapper shareMapper,
                                      ShareCommentMapper shareCommentMapper,
                                      ShareEngagementMapper shareEngagementMapper,
                                      AfterSaleMapper afterSaleMapper) {
        this.shareMapper = shareMapper;
        this.shareCommentMapper = shareCommentMapper;
        this.shareEngagementMapper = shareEngagementMapper;
        this.afterSaleMapper = afterSaleMapper;
    }

    /**
     * 预检（dryRun）：只查不写，回报「若执行将会删多少行、多少进行中售后要挡下注销」。
     *
     * <p>{@code readOnly = true} 让连接池/驱动有机会走只读优化，
     * 也防将来有人手滑在这里加写语句。
     */
    @Transactional(readOnly = true)
    public PersonalDataCleanupResult preview(Long userId) {
        long deleted = shareMapper.selectCount(sharesOf(userId))
                + shareCommentMapper.selectCount(commentsOf(userId))
                // 点赞 / 收藏两张表没有实体（复合主键、只有「在不在」两类操作），
                // 计数与删除都走 ShareEngagementMapper 的手写 SQL
                + shareEngagementMapper.countLikesByUser(userId)
                + shareEngagementMapper.countFavoritesByUser(userId);
        long blocking = afterSaleMapper.selectCount(blockingOf(userId));
        return new PersonalDataCleanupResult(userId, DOMAIN, (int) deleted, 0, (int) blocking);
    }

    /**
     * 执行：删该用户的分享 / 评论 / 点赞 / 收藏。幂等，可重复调用。
     */
    @Transactional
    public PersonalDataCleanupResult execute(Long userId) {
        int deletedShares = shareMapper.delete(sharesOf(userId));
        int deletedComments = shareCommentMapper.delete(commentsOf(userId));
        int deletedLikes = shareEngagementMapper.deleteLikesByUser(userId);
        int deletedFavorites = shareEngagementMapper.deleteFavoritesByUser(userId);
        // 售后单不在清理范围内，执行前后数量不变；照实重报一次，
        // 与 preview 的 blocking 口径一致（用户域执行模式不依赖它，仅日志用）
        long blocking = afterSaleMapper.selectCount(blockingOf(userId));
        return new PersonalDataCleanupResult(userId, DOMAIN,
                deletedShares + deletedComments + deletedLikes + deletedFavorites,
                0, (int) blocking);
    }

    /** 本人发布的分享（含转发行：转发行的 userId 就是转发人，同口径删除） */
    private static LambdaQueryWrapper<ShareEntity> sharesOf(Long userId) {
        return new LambdaQueryWrapper<ShareEntity>()
                .eq(ShareEntity::getUserId, userId);
    }

    /** 本人发表的评论 / 回复 */
    private static LambdaQueryWrapper<ShareCommentEntity> commentsOf(Long userId) {
        return new LambdaQueryWrapper<ShareCommentEntity>()
                .eq(ShareCommentEntity::getUserId, userId);
    }

    /** 未结案的售后：钱与货的争议还没了结，账号不能先消失（设计文档第六节） */
    private static LambdaQueryWrapper<AfterSaleEntity> blockingOf(Long userId) {
        return new LambdaQueryWrapper<AfterSaleEntity>()
                .eq(AfterSaleEntity::getBuyerId, userId)
                .notIn(AfterSaleEntity::getStatus, FINAL_AFTER_SALE_STATUSES);
    }
}
