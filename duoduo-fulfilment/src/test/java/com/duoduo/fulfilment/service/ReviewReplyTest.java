package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.dto.ReviewDtos.SellerReviewView;
import com.duoduo.fulfilment.entity.ReviewEntity;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.ReviewRiskJudge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-16 · 商家回复评价单测。
 *
 * <p>钉住六条事实：
 * <ol>
 *   <li>本店卖家回复 APPROVED 评价成功：内容 / 时间 / 操作人落库；</li>
 *   <li>一条评价只能回复一次，重复回复 409 且零写库；</li>
 *   <li>待审 / 已下架的评价不能回复（422）—— 回复是公开内容，不能挂在不公开的评价上；</li>
 *   <li>非本店卖家 403；运营只能代回复平台自营（seller_id = 0）；</li>
 *   <li>回复同样过机审，命中风险直接退回（422），不静默发布；</li>
 *   <li>商家端列表对匿名评价脱敏，且 DTO 本身不含 userId / avatar。</li>
 * </ol>
 */
class ReviewReplyTest {

    private static final long SELLER_ID = 202L;

    private final ReviewMapper mapper = mock(ReviewMapper.class);
    private final OrderFacts orderFacts = mock(OrderFacts.class);
    private final UserClient userClient = mock(UserClient.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ReviewService service = new ReviewService(mapper, orderFacts, userClient, idGenerator,
            new ObjectMapper(), eventPublisher, new ReviewRiskJudge(""), 1000, 9, 30, 500);

    @BeforeAll
    static void initMybatisMetadata() {
        // LambdaUpdateWrapper / LambdaQueryWrapper 的列名解析依赖实体元数据缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewEntity.class);
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private void loginSeller() {
        UserContext.set(new LoginUser(SELLER_ID, "13600003333", "SELLER", true));
    }

    private static ReviewEntity review(long id, String auditStatus) {
        ReviewEntity e = new ReviewEntity();
        e.setId(id);
        e.setOrderNo("A16-ORDER-1");
        e.setUserId(101L);
        e.setSellerId(SELLER_ID);
        e.setSpuId(303L);
        e.setProductTitle("A16 回复夹具商品");
        e.setRating(5);
        e.setContent("猫粮很香");
        e.setAnonymous(0);
        e.setAuditStatus(auditStatus);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    @Test
    @DisplayName("本店卖家回复成功：内容/时间/操作人落库，视图 replyable 变 false")
    void 回复成功() {
        loginSeller();
        ReviewEntity entity = review(1L, "APPROVED");
        when(mapper.selectById(1L)).thenReturn(entity);
        when(mapper.update(any(), any())).thenReturn(1);

        SellerReviewView view = service.reply(SELLER_ID, 1L, "  感谢反馈，已为您补发一袋  ");

        assertThat(view.replyContent()).isEqualTo("感谢反馈，已为您补发一袋");
        assertThat(view.replyAt()).isNotBlank();
        assertThat(view.replyable()).isFalse();
        assertThat(entity.getReplyContent()).isEqualTo("感谢反馈，已为您补发一袋");
        assertThat(entity.getRepliedBy()).isEqualTo(SELLER_ID);
        assertThat(entity.getReplyAt()).isNotNull();
        verify(mapper).update(any(), any());
        // 回复不改变评分与可见性，不应发布评分重算事件
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("已回复再回复：409 且零写库")
    void 重复回复409() {
        loginSeller();
        ReviewEntity entity = review(2L, "APPROVED");
        entity.setReplyContent("首次回复");
        when(mapper.selectById(2L)).thenReturn(entity);

        assertThatThrownBy(() -> service.reply(SELLER_ID, 2L, "再回一次"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该评价已回复")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("待审核评价：422 且零写库")
    void 待审核不能回复() {
        loginSeller();
        when(mapper.selectById(3L)).thenReturn(review(3L, "PENDING"));

        assertThatThrownBy(() -> service.reply(SELLER_ID, 3L, "提前回复"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待审核")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("已下架评价：422 且零写库")
    void 已下架不能回复() {
        loginSeller();
        when(mapper.selectById(4L)).thenReturn(review(4L, "REJECTED"));

        assertThatThrownBy(() -> service.reply(SELLER_ID, 4L, "回复已下架内容"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已下架")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("非本店卖家：403 且零写库")
    void 非本店403() {
        UserContext.set(new LoginUser(203L, "13600004444", "SELLER", true));
        when(mapper.selectById(5L)).thenReturn(review(5L, "APPROVED"));

        assertThatThrownBy(() -> service.reply(203L, 5L, "别家店的评价"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能回复自己店铺")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("普通买家角色：403（连评价都读不到）")
    void 非卖家403() {
        UserContext.set(new LoginUser(101L, "19900001111", "USER", false));
        when(mapper.selectById(6L)).thenReturn(review(6L, "APPROVED"));

        assertThatThrownBy(() -> service.reply(101L, 6L, "我是买家"))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("运营可代回复平台自营（seller_id = 0）")
    void 运营代回复自营() {
        UserContext.set(new LoginUser(901L, "13684295254", "OPERATOR", true));
        ReviewEntity entity = review(7L, "APPROVED");
        entity.setSellerId(0L);
        when(mapper.selectById(7L)).thenReturn(entity);
        when(mapper.update(any(), any())).thenReturn(1);

        SellerReviewView view = service.reply(901L, 7L, "自营客服回复");

        assertThat(view.replyContent()).isEqualTo("自营客服回复");
        assertThat(entity.getRepliedBy()).isEqualTo(901L);
    }

    @Test
    @DisplayName("运营不能代回复第三方店铺：403")
    void 运营不能动第三方店铺() {
        UserContext.set(new LoginUser(901L, "13684295254", "OPERATOR", true));
        when(mapper.selectById(8L)).thenReturn(review(8L, "APPROVED"));

        assertThatThrownBy(() -> service.reply(901L, 8L, "代回复第三方"))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("回复命中机审：422，且原因带脱敏，零写库")
    void 回复命中机审() {
        loginSeller();
        when(mapper.selectById(9L)).thenReturn(review(9L, "APPROVED"));

        assertThatThrownBy(() -> service.reply(SELLER_ID, 9L, "加微信 13800138000 给您补偿"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("回复未通过内容校验")
                .hasMessageContaining("138****8000")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("空白与超长回复：400，且零写库")
    void 空白与超长400() {
        loginSeller();
        when(mapper.selectById(10L)).thenReturn(review(10L, "APPROVED"));
        when(mapper.selectById(11L)).thenReturn(review(11L, "APPROVED"));

        assertThatThrownBy(() -> service.reply(SELLER_ID, 10L, "   "))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThatThrownBy(() -> service.reply(SELLER_ID, 11L, "a".repeat(501)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("500")
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("并发下条件更新 0 行且重读仍无回复：409（不覆盖）")
    void 并发回复409() {
        loginSeller();
        ReviewEntity entity = review(12L, "APPROVED");
        when(mapper.selectById(12L)).thenReturn(entity, review(12L, "APPROVED"));
        when(mapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reply(SELLER_ID, 12L, "并发回复"))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("商家端列表：匿名评价昵称脱敏为「匿名用户」且不带身份字段")
    void 匿名评价脱敏() {
        loginSeller();
        ReviewEntity anonymous = review(13L, "APPROVED");
        anonymous.setAnonymous(1);
        Page<ReviewEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(anonymous));
        page.setTotal(1);
        doReturn(page).when(mapper).selectPage(any(), any());

        var result = service.listForSeller(SELLER_ID, false, null, null, 1, 10);

        assertThat(result.items()).hasSize(1);
        SellerReviewView view = result.items().get(0);
        assertThat(view.nickname()).isEqualTo("匿名用户");
        assertThat(view.anonymous()).isTrue();
        assertThat(view.replyable()).isTrue();
        // record 只有这些组件：没有 userId / avatar 可供前端反查身份
        assertThat(SellerReviewView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("userId", "avatar", "sellerId");
    }

    @Test
    @DisplayName("未知评价：404")
    void 未知评价404() {
        loginSeller();
        when(mapper.selectById(14L)).thenReturn(null);

        assertThatThrownBy(() -> service.reply(SELLER_ID, 14L, "回复"))
                .isInstanceOf(BizException.class)
                .extracting((e) -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
