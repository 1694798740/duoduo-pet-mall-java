package com.duoduo.social.service;

import com.duoduo.social.dto.SocialDtos.UnreadSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 未读角标合并口径单测（第五十八轮 · P2-10）。
 *
 * <h2>为什么单独测「加法」</h2>
 * 角标 = 通知未读 + 私信未读，两个分项来自两张表、两套已读规则。
 * 这条断言守的是「两个分项<b>不重叠</b>」这一前提：一旦
 * {@code NoticeService.unreadCount} 将来把私信通知（bizType=MESSAGE）算回去，
 * 同一条消息会被数两次（第五十五轮实测：角标 7 实际只有 5 条）。
 * 这类缺陷只有「两个域分别全绿、加起来不对」时才暴露，所以加法本身必须有断言。
 */
class UnreadServiceTest {

    private final NoticeService noticeService = mock(NoticeService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final UnreadService service = new UnreadService(noticeService, messageService);

    @Test
    void 角标等于通知未读加私信未读() {
        when(noticeService.unreadCount(710000000000000001L))
                .thenReturn(new com.duoduo.social.dto.SocialDtos.UnreadCount(3));
        when(messageService.unreadCount(710000000000000001L)).thenReturn(2L);

        UnreadSummary summary = service.summary(710000000000000001L);

        assertThat(summary.notice()).isEqualTo(3);
        assertThat(summary.message()).isEqualTo(2);
        assertThat(summary.total()).isEqualTo(5);
    }

    @Test
    void 两个分项都为0时角标为0() {
        when(noticeService.unreadCount(1L)).thenReturn(new com.duoduo.social.dto.SocialDtos.UnreadCount(0));
        when(messageService.unreadCount(1L)).thenReturn(0L);

        assertThat(service.summary(1L).total()).isZero();
    }

    @Test
    void 只有私信未读时角标就是私信数() {
        when(noticeService.unreadCount(1L)).thenReturn(new com.duoduo.social.dto.SocialDtos.UnreadCount(0));
        when(messageService.unreadCount(1L)).thenReturn(7L);

        assertThat(service.summary(1L).total()).isEqualTo(7);
    }
}
