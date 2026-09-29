package com.duoduo.social.service;

import com.duoduo.social.dto.SocialDtos.UnreadSummary;
import org.springframework.stereotype.Service;

/**
 * 未读合并口径（第五十五轮）：tabBar 角标的**唯一**计算口。
 *
 * <p>为什么要有这个类，而不是让通知控制器直接算：
 * <ul>
 *   <li><b>口径只能有一处</b>：角标数字 = 通知未读 + 私信未读。两个来源分属两张表、
 *       两套已读规则（通知走集合表、私信走在行上的 read_at），任何一处口径变动
 *       （比如以后公告不再计入未读）都必须同时生效，否则同一次刷新里「总数」与
 *       「明细」会自相矛盾；</li>
 *   <li><b>它不拥有数据</b>：本类不碰 Mapper，只把两个域已经算好的计数相加 ——
 *       这样「通知怎么算未读」仍然只由 {@link NoticeService} 回答，
 *       不会出现第二份实现。</li>
 * </ul>
 *
 * <p>依赖方向：{@code MessageService → NoticeService}（发消息要写通知），
 * 本类依赖两者 —— 有向无环，不会出现构造器注入的循环依赖。
 */
@Service
public class UnreadService {

    private final NoticeService noticeService;
    private final MessageService messageService;

    public UnreadService(NoticeService noticeService, MessageService messageService) {
        this.noticeService = noticeService;
        this.messageService = messageService;
    }

    /** 合并未读：通知 + 私信 + 总数（三个值同刻同源） */
    public UnreadSummary summary(Long userId) {
        // 两个分项都不重叠：NoticeService.unreadCount 已排除私信通知（bizType=MESSAGE），
        // 否则「一条消息」会既进通知未读、又进私信未读，角标凭空翻倍（第五十五轮实测）。
        long notice = noticeService.unreadCount(userId).count();
        long message = messageService.unreadCount(userId);
        return new UnreadSummary(notice, message, notice + message);
    }
}
