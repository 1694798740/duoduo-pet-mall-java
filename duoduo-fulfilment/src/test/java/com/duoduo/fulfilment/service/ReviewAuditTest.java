package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.CoinTaskClient;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.dto.ReviewDtos.AdminReviewView;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewAuditRequest;
import com.duoduo.fulfilment.entity.ReviewEntity;
import com.duoduo.fulfilment.mapper.AdminQueueReadMapper;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.ProductRatingSyncer;
import com.duoduo.fulfilment.support.ReviewRiskJudge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H-02 · 评价人工审核（下架 / 恢复）单测。
 *
 * <p>钉住的四条事实：
 * <ol>
 *   <li>下架必须写原因，且把 REJECTED / 操作人 / 时间落进实体；</li>
 *   <li>已处于目标态时幂等成功且<b>零写库</b>；</li>
 *   <li>恢复要显式清空原因（MP 默认不写 null 是项目踩过的坑）；</li>
 *   <li>可见性变化必须发布评分重算事件 —— 下架后商品评分不能停在旧值。</li>
 * </ol>
 */
class ReviewAuditTest {

    private final ReviewMapper mapper = mock(ReviewMapper.class);
    private final OrderFacts orderFacts = mock(OrderFacts.class);
    private final UserClient userClient = mock(UserClient.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ReviewService service = new ReviewService(mapper, mock(AdminQueueReadMapper.class),
            orderFacts, userClient, idGenerator,
            new ObjectMapper(), eventPublisher, new ReviewRiskJudge(""), mock(CoinTaskClient.class),
            1000, 9, 30, 500);

    @BeforeAll
    static void initMybatisMetadata() {
        // LambdaUpdateWrapper 的列名解析依赖 MyBatis-Plus 实体元数据缓存；纯单测必须手动初始化，
        // 否则抛出「can not find lambda cache for this entity」（与 OrderListFilterTest 同一处理）。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewEntity.class);
    }

    @BeforeEach
    void loginOperator() {
        UserContext.set(new LoginUser(901L, "13684295254", "OPERATOR", false));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private static ReviewEntity review(long id, String auditStatus) {
        ReviewEntity e = new ReviewEntity();
        e.setId(id);
        e.setOrderNo("H02-ORDER-1");
        e.setUserId(101L);
        e.setSellerId(202L);
        e.setSpuId(303L);
        e.setProductTitle("H02 评价审核夹具商品");
        e.setRating(5);
        e.setContent("猫粮很香");
        e.setAnonymous(0);
        e.setAuditStatus(auditStatus);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("下架：写 REJECTED + 原因 + 操作人/时间，并发布评分重算事件")
    void 下架写REJECTED与原因及操作人() {
        ReviewEntity entity = review(1L, "APPROVED");
        when(mapper.selectById(1L)).thenReturn(entity);
        when(mapper.update(any(), any())).thenReturn(1);

        AdminReviewView view = service.audit(1L, new ReviewAuditRequest("REJECT", "含微信号导流"));

        assertThat(view.auditStatus()).isEqualTo("REJECTED");
        assertThat(view.auditReason()).isEqualTo("含微信号导流");
        assertThat(view.auditedBy()).isEqualTo(901L);
        assertThat(view.auditedAt()).isNotBlank();
        assertThat(view.auditSource()).isEqualTo("MANUAL");
        assertThat(entity.getAuditStatus()).isEqualTo("REJECTED");
        assertThat(entity.getAuditReason()).isEqualTo("含微信号导流");
        assertThat(entity.getAuditSource()).isEqualTo("MANUAL");
        verify(mapper).update(any(), any());
        verify(eventPublisher).publishEvent(any(ProductRatingSyncer.ReviewVisibilityChanged.class));
    }

    @Test
    @DisplayName("下架缺原因：422，且零写库")
    void 下架缺原因被拒() {
        ReviewEntity entity = review(2L, "APPROVED");
        when(mapper.selectById(2L)).thenReturn(entity);

        assertThatThrownBy(() -> service.audit(2L, new ReviewAuditRequest("REJECT", "   ")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("下架评价必须填写原因")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);
        verify(mapper, never()).update(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("已下架再下架：幂等成功且零写库（不刷新审核时间）")
    void 重复下架幂等() {
        ReviewEntity entity = review(3L, "REJECTED");
        entity.setAuditReason("历史原因");
        entity.setAuditedAt(LocalDateTime.now().minusDays(1));
        when(mapper.selectById(3L)).thenReturn(entity);

        AdminReviewView view = service.audit(3L, new ReviewAuditRequest("REJECT", "新的原因"));

        assertThat(view.auditStatus()).isEqualTo("REJECTED");
        assertThat(view.auditReason()).isEqualTo("历史原因");
        verify(mapper, never()).update(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("恢复：写 APPROVED，原因显式清空，并发布评分重算事件")
    void 恢复清空原因() {
        ReviewEntity entity = review(4L, "REJECTED");
        entity.setAuditReason("误判");
        when(mapper.selectById(4L)).thenReturn(entity);
        when(mapper.update(any(), any())).thenReturn(1);

        AdminReviewView view = service.audit(4L, new ReviewAuditRequest("APPROVE", null));

        assertThat(view.auditStatus()).isEqualTo("APPROVED");
        assertThat(view.auditReason()).isNull();
        assertThat(view.auditedBy()).isEqualTo(901L);
        assertThat(view.auditSource()).isEqualTo("MANUAL");
        assertThat(entity.getAuditReason()).isNull();
        verify(mapper).update(any(), any());
        verify(eventPublisher).publishEvent(any(ProductRatingSyncer.ReviewVisibilityChanged.class));
    }

    @Test
    @DisplayName("非运营：403 且零写库")
    void 非运营被拒() {
        UserContext.set(new LoginUser(102L, "13600003333", "USER", false));
        when(mapper.selectById(5L)).thenReturn(review(5L, "APPROVED"));

        assertThatThrownBy(() -> service.audit(5L, new ReviewAuditRequest("REJECT", "违规")))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("未知评价：404")
    void 未知评价404() {
        when(mapper.selectById(6L)).thenReturn(null);

        assertThatThrownBy(() -> service.audit(6L, new ReviewAuditRequest("REJECT", "违规")))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("非法动作：400，不读库不写库")
    void 非法动作400() {
        assertThatThrownBy(() -> service.audit(7L, new ReviewAuditRequest("DELETE", "违规")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("APPROVE")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(mapper, never()).selectById(any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("并发状态已变：条件更新 0 行且重读仍非目标态 → 409")
    void 并发状态变化409() {
        when(mapper.selectById(8L)).thenReturn(review(8L, "APPROVED"), review(8L, "PENDING"));
        when(mapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.audit(8L, new ReviewAuditRequest("REJECT", "违规")))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }
}
