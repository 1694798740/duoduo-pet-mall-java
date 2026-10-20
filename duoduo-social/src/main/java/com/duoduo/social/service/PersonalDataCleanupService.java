package com.duoduo.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.social.dto.PersonalDataCleanupResult;
import com.duoduo.social.entity.SocialFollowEntity;
import com.duoduo.social.entity.SocialMessageEntity;
import com.duoduo.social.entity.SocialNoticeReadEntity;
import com.duoduo.social.mapper.SocialFollowMapper;
import com.duoduo.social.mapper.SocialMessageMapper;
import com.duoduo.social.mapper.SocialNoticeReadMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C7 · 账号注销时社交域的个人数据清理（服务间调用，见 {@code docs/account-deletion-design.md} 4.4）。
 *
 * <h2>为什么删、为什么留</h2>
 * <ul>
 *   <li><b>删</b>：关注关系（{@code t_social_follow}，关注与被关注<b>双向</b>）、
 *       私信（{@code t_social_message}，发送与接收<b>双向</b>）、公告已读标记
 *       （{@code t_social_notice_read}）。三者都是纯社交行为数据，无任何留存义务 ——
 *       账号注销后，「谁关注过谁、和谁聊过什么」不该继续留在库里供人翻查；</li>
 *   <li><b>留</b>：公告本体（{@code t_social_notice}）一行不动。公告是公共行
 *       （{@code userId = 0}，一行多读者），不属于任何个人，删它会连累其他读者；
 *       已读标记是「这个人读过哪些公告」的个人数据，随注销删除。</li>
 * </ul>
 *
 * <h2>为什么私信按「收发」都删</h2>
 * 私信内容同时是收发双方的个人数据。账号注销删掉自己发过的消息，对方会话里
 * 不再出现已注销用户的发言 —— 与交易域「订单收货人脱敏」是同一个取舍：
 * 个人数据原则上删，不为留档把 PII 继续摆在别人的页面上。
 *
 * <h2>幂等可重入</h2>
 * DELETE 都是幂等语句：重复调用不报错、不二次破坏，第二次命中 0 行、数字归零。
 * 注销调度「失败重试、断点恢复」依赖这一点（设计文档第六节）。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li>{@link #preview}（dryRun）：只查不写，回报「若执行将会删多少行」；</li>
 *   <li>{@link #execute}（缺省 / false）：真正删除，回报实际影响行数。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（共用同一组 WHERE），预估与实际必然对得上。
 * 本域不阻塞注销：{@code blocking} 恒 {@code 0} —— 进行中的会话不构成
 * 「未完成的业务」，没有必须先处理完才能注销的事项。
 */
@Service
public class PersonalDataCleanupService {

    /** 域标识（与用户域 Feign 契约 {@code AccountDeletionDtos}、注销日志口径一致） */
    private static final String DOMAIN = "social";

    private final SocialFollowMapper followMapper;
    private final SocialMessageMapper messageMapper;
    private final SocialNoticeReadMapper noticeReadMapper;

    public PersonalDataCleanupService(SocialFollowMapper followMapper,
                                      SocialMessageMapper messageMapper,
                                      SocialNoticeReadMapper noticeReadMapper) {
        this.followMapper = followMapper;
        this.messageMapper = messageMapper;
        this.noticeReadMapper = noticeReadMapper;
    }

    /**
     * 预检（dryRun）：只查不写，回报「若执行将会删多少行」。
     *
     * <p>{@code readOnly = true} 让连接池/驱动有机会走只读优化，
     * 也防将来有人手滑在这里加写语句。
     */
    @Transactional(readOnly = true)
    public PersonalDataCleanupResult preview(Long userId) {
        long follows = followMapper.selectCount(followsOf(userId));
        long messages = messageMapper.selectCount(messagesOf(userId));
        long reads = noticeReadMapper.selectCount(readMarksOf(userId));
        return new PersonalDataCleanupResult(userId, DOMAIN,
                (int) (follows + messages + reads), 0, 0);
    }

    /**
     * 执行：删该用户的关注（双向）、私信（收发双向）、公告已读。幂等，可重复调用。
     */
    @Transactional
    public PersonalDataCleanupResult execute(Long userId) {
        int deletedFollows = followMapper.delete(followsOf(userId));
        int deletedMessages = messageMapper.delete(messagesOf(userId));
        int deletedReads = noticeReadMapper.delete(readMarksOf(userId));
        // social 不阻塞注销：blocking 恒 0（见类注释）
        return new PersonalDataCleanupResult(userId, DOMAIN,
                deletedFollows + deletedMessages + deletedReads, 0, 0);
    }

    /** 「与该用户有关」的关注行：我关注的人，或关注我的人（两个方向各一行） */
    private static LambdaQueryWrapper<SocialFollowEntity> followsOf(Long userId) {
        return new LambdaQueryWrapper<SocialFollowEntity>()
                .eq(SocialFollowEntity::getFollowerId, userId)
                .or().eq(SocialFollowEntity::getFolloweeId, userId);
    }

    /** 「与该用户有关」的私信：我发的，或我收的（会话对端视角的行一并删，见类注释） */
    private static LambdaQueryWrapper<SocialMessageEntity> messagesOf(Long userId) {
        return new LambdaQueryWrapper<SocialMessageEntity>()
                .eq(SocialMessageEntity::getSenderId, userId)
                .or().eq(SocialMessageEntity::getReceiverId, userId);
    }

    /** 「这个人读过哪些公告」的已读标记（公告本体是公共行，不删） */
    private static LambdaQueryWrapper<SocialNoticeReadEntity> readMarksOf(Long userId) {
        return new LambdaQueryWrapper<SocialNoticeReadEntity>()
                .eq(SocialNoticeReadEntity::getUserId, userId);
    }
}
