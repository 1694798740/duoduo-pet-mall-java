package com.duoduo.fulfilment.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.dto.ReviewDtos.ItemReview;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewCreateRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewCreateResult;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewView;
import com.duoduo.fulfilment.entity.ReviewEntity;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.ProductRatingSyncer;
import com.duoduo.fulfilment.support.ReviewRiskJudge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-15 · 评价机审分流单测。
 *
 * <p>钉住四条事实：
 * <ol>
 *   <li>干净内容提交即公开，来源记 AUTO，审核时间落库；</li>
 *   <li>命中风险转 PENDING（不是 REJECTED）—— 机器不下最终裁决；</li>
 *   <li>追评命中风险会整条转 PENDING 并触发评分重算（公开口径立即移除）；</li>
 *   <li>已下架评价不能追评，避免用追评把违规内容挂回去。</li>
 * </ol>
 */
class ReviewAutoAuditTest {

    private final ReviewMapper mapper = mock(ReviewMapper.class);
    private final OrderFacts orderFacts = mock(OrderFacts.class);
    private final UserClient userClient = mock(UserClient.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ReviewService service = new ReviewService(mapper, orderFacts, userClient, idGenerator,
            new ObjectMapper(), eventPublisher, new ReviewRiskJudge(""), 1000, 9, 30, 500);

    @BeforeEach
    void setUp() {
        when(orderFacts.require("A15-ORDER-001")).thenReturn(new OrderFact(
                "A15-ORDER-001", 101L, 202L, "COMPLETED", "已完成", null,
                List.of(new OrderFact.Item(501L, 303L, "A15 夹具猫粮", "默认", null, 1990L, 1, 1990L))));
        when(mapper.reviewedSpuIds(anyLong(), eq(101L))).thenReturn(List.of());
        when(idGenerator.nextId()).thenReturn(9001L);
        when(mapper.insert(any())).thenReturn(1);
    }

    private static ReviewCreateRequest request(String content) {
        return new ReviewCreateRequest("A15-ORDER-001", false,
                List.of(new ItemReview(5, content, List.of(), 303L, 501L)));
    }

    private static ReviewEntity approvedReview(long id) {
        ReviewEntity entity = new ReviewEntity();
        entity.setId(id);
        entity.setOrderNo("A15-ORDER-001");
        entity.setUserId(101L);
        entity.setSellerId(202L);
        entity.setSpuId(303L);
        entity.setProductTitle("A15 夹具猫粮");
        entity.setRating(5);
        entity.setContent("猫粮很香");
        entity.setAnonymous(0);
        entity.setAuditStatus("APPROVED");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    @Test
    @DisplayName("干净内容：提交即 APPROVED，来源 AUTO，审核时间落库")
    void 干净内容自动放行() {
        ReviewCreateResult result = service.create(101L, request("猫粮很香，猫吃了三周毛色变亮"));

        ArgumentCaptor<ReviewEntity> captor = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(mapper).insert(captor.capture());
        ReviewEntity saved = captor.getValue();
        assertThat(saved.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(saved.getAuditSource()).isEqualTo("AUTO");
        assertThat(saved.getAuditReason()).isNull();
        assertThat(saved.getAuditedAt()).isNotNull();
        assertThat(result.reviews().get(0).auditStatus()).isEqualTo("APPROVED");
        verify(eventPublisher).publishEvent(any(ProductRatingSyncer.ReviewCreated.class));
    }

    @Test
    @DisplayName("命中手机号：转 PENDING 等人工，不自动下架")
    void 命中风险转人工() {
        ReviewCreateResult result = service.create(101L, request("想要同款加微信 13800138000"));

        ArgumentCaptor<ReviewEntity> captor = ArgumentCaptor.forClass(ReviewEntity.class);
        verify(mapper).insert(captor.capture());
        ReviewEntity saved = captor.getValue();
        assertThat(saved.getAuditStatus()).isEqualTo("PENDING");
        assertThat(saved.getAuditSource()).isEqualTo("AUTO");
        assertThat(saved.getAuditReason()).contains("机审命中").contains("联系方式");
        assertThat(saved.getAuditedBy()).isNull();
        assertThat(result.reviews().get(0).auditStatus()).isEqualTo("PENDING");
        // 评分回写事件照发：聚合 SQL 只统计 APPROVED，PENDING 天然不参与
        verify(eventPublisher).publishEvent(any(ProductRatingSyncer.ReviewCreated.class));
    }

    @Test
    @DisplayName("追评命中风险：整条评价转 PENDING 并发布评分重算事件")
    void 追评命中转人工() {
        ReviewEntity entity = approvedReview(1L);
        when(mapper.selectById(1L)).thenReturn(entity);

        ReviewView view = service.append(101L, 1L, "追评：加微信领试吃 13800138000");

        assertThat(entity.getAuditStatus()).isEqualTo("PENDING");
        assertThat(entity.getAuditSource()).isEqualTo("AUTO");
        assertThat(entity.getAuditReason()).contains("机审命中");
        assertThat(entity.getAppendContent()).contains("加微信");
        assertThat(view.auditStatus()).isEqualTo("PENDING");
        verify(mapper).update(any(), any());
        verify(eventPublisher).publishEvent(any(ProductRatingSyncer.ReviewVisibilityChanged.class));
    }

    @Test
    @DisplayName("追评干净：保持原审核态，不重写来源与人审时间")
    void 追评干净不重审() {
        ReviewEntity entity = approvedReview(2L);
        when(mapper.selectById(2L)).thenReturn(entity);

        ReviewView view = service.append(101L, 2L, "用了一个月，猫还是爱吃");

        assertThat(entity.getAuditStatus()).isEqualTo("APPROVED");
        assertThat(entity.getAuditSource()).isNull();
        assertThat(entity.getAuditedAt()).isNull();
        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("已下架评价不能追评：422 且零写库")
    void 已下架不能追评() {
        ReviewEntity entity = approvedReview(3L);
        entity.setAuditStatus("REJECTED");
        when(mapper.selectById(3L)).thenReturn(entity);

        assertThatThrownBy(() -> service.append(101L, 3L, "再补一句"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已下架");
        verify(mapper, never()).updateById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
