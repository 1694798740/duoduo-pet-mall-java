package com.duoduo.social.service;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.dto.SocialDtos.NoticePushItem;
import com.duoduo.social.entity.SocialNoticeEntity;
import com.duoduo.social.mapper.SocialNoticeDeletedMapper;
import com.duoduo.social.mapper.SocialNoticeMapper;
import com.duoduo.social.mapper.SocialNoticeReadMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知生产者单测（第五十八轮 · P2-10，社交域第一批单测）。
 *
 * <h2>为什么钉住「两阶段落库」</h2>
 * 内部推送是<b>批量写入</b>入口：如果边校验边插，第 3 条非法时前 2 条已经落库，
 * 调用方拿到 400 会以为「整批没成」—— 要么漏掉那 2 条，要么重推造成重复。
 * 「失败 = 什么都没发生」这个语义只能靠断言守住：整批有一条非法时，
 * {@code insert} 必须<b>一次都没被调用</b>。
 *
 * <h2>为什么钉住落地页白名单</h2>
 * {@code jump_page} 拼错时编译期与运行日志都不报错，只表现为「用户点了通知没反应」。
 * 这是典型的「功能已上线但链路不通」，必须在写入前拦住。
 */
class NoticeServicePushTest {

    private final SocialNoticeMapper noticeMapper = mock(SocialNoticeMapper.class);
    private final SocialNoticeReadMapper readMapper = mock(SocialNoticeReadMapper.class);
    /** 删除集合（2026-10-27）：本单测只覆盖推送路径，不触发删除逻辑，mock 即可 */
    private final SocialNoticeDeletedMapper deletedMapper = mock(SocialNoticeDeletedMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final NoticeService service = new NoticeService(noticeMapper, readMapper, deletedMapper, idGenerator);

    private static NoticePushItem item(long userId, String category, String jumpPage) {
        return new NoticePushItem(userId, category, "标题", "正文", "ORDER_PLACED", "9001", jumpPage);
    }

    @Test
    void 全部合法时逐条落库() {
        when(idGenerator.nextId()).thenReturn(1L, 2L);

        long count = service.pushBatch(List.of(
                item(710000000000000001L, "ORDER", "/pages/orders/orders"),
                item(710000000000000002L, "SYSTEM", "/pages/index/index")));

        assertThat(count).isEqualTo(2);
        verify(noticeMapper, times(2)).insert(any(SocialNoticeEntity.class));
    }

    @Test
    void 批内有一条非法则整批不写() {
        when(idGenerator.nextId()).thenReturn(1L);

        List<NoticePushItem> items = List.of(
                item(710000000000000001L, "ORDER", "/pages/orders/orders"),
                item(710000000000000002L, "NOT_A_CATEGORY", "/pages/orders/orders"));

        assertThatThrownBy(() -> service.pushBatch(items))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        // 关键断言：第一条虽然合法，也不能留下半批数据
        verify(noticeMapper, never()).insert(any());
    }

    @Test
    void 落地页不是小程序真实页面时拒绝() {
        List<NoticePushItem> items = List.of(
                item(710000000000000001L, "ORDER", "/pages/order-detail/detail-typo"));

        assertThatThrownBy(() -> service.pushBatch(items))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("通知落地页");

        verify(noticeMapper, never()).insert(any());
    }

    @Test
    void 落地页带查询参数时只校验路径段() {
        long count = service.pushBatch(List.of(
                item(710000000000000001L, "ORDER",
                        "/pages/order-detail/order-detail?id=710000000000000001")));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void 空批次与超上限都拒绝() {
        assertThatThrownBy(() -> service.pushBatch(List.of()))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.pushBatch(null))
                .isInstanceOf(BizException.class);

        List<NoticePushItem> tooMany = java.util.stream.Stream.generate(
                        () -> item(710000000000000001L, "ORDER", "/pages/orders/orders"))
                .limit(101).toList();
        assertThatThrownBy(() -> service.pushBatch(tooMany))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("单次最多推送");
    }

    @Test
    void 全站公告的收件人固定为0() {
        service.announce(new com.duoduo.social.dto.SocialDtos.AnnounceRequest(
                "系统维护", "今晚 02:00 维护", "ANNOUNCEMENT", null, "/pages/index/index"));

        ArgumentCaptor<SocialNoticeEntity> captor = ArgumentCaptor.forClass(SocialNoticeEntity.class);
        verify(noticeMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isZero();
        assertThat(captor.getValue().getCategory()).isEqualTo("ANNOUNCEMENT");
    }

    @Test
    void 收件人缺失或为负值时拒绝() {
        assertThatThrownBy(() -> service.pushBatch(List.of(
                item(-1L, "ORDER", "/pages/orders/orders"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("收件人");

        // null 不能「默认成公告」—— 公告收件人固定为 0 是 announce() 的职责，
        // 通用批次里 null 就是调用方漏传，静默补 0 会把定向通知发成全站可见
        assertThatThrownBy(() -> service.pushBatch(List.of(
                new NoticePushItem(null, "ORDER", "标题", "正文", null, null, "/pages/orders/orders"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("收件人");
    }
}
