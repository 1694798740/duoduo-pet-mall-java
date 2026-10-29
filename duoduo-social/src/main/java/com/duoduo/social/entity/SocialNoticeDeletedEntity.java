package com.duoduo.social.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通知中心 · 删除集合（2026-10-27 建表）。
 *
 * <p><b>为什么删除要记在集合表而不是物理 DELETE 通知行：</b>
 * 通知有两种来源 —— 定向通知（{@code user_id = 我}）与全站公告（{@code user_id = 0}，
 * 一行多读者）。公告是<b>共享行</b>：任何一个用户删掉它，都会让其他所有用户看不到，
 * 这是最典型的一类"用户 A 的操作改变了用户 B 的界面"事故。
 *
 * <p>所以"删除"在这里的语义是<b>对我不可见</b>，而不是"从库里消失"：
 * (notice_id, user_id) 复合主键，撞主键即幂等（重复删除不报错）。
 * 与已读集合 {@link SocialNoticeReadEntity} 完全同构 —— 同一套「共享行 + 私有状态」模型，
 * 不需要为两种来源写两套逻辑。
 *
 * <p>代价是通知行会随定向通知的累积而增长。这些行由
 * {@code PersonalDataCleanupService}（注销清理）与后续的归档任务处理，
 * 删一条消息不该顺手把业务事实抹掉。
 */
@TableName("t_social_notice_deleted")
public class SocialNoticeDeletedEntity {

    @TableId
    private Long noticeId;

    private Long userId;

    private LocalDateTime deletedAt;

    public Long getNoticeId() {
        return noticeId;
    }

    public void setNoticeId(Long noticeId) {
        this.noticeId = noticeId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
