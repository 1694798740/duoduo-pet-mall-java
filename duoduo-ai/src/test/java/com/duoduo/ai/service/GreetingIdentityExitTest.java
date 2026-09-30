package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.dto.CategoryBrief;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.support.CategoryCatalog;
import com.duoduo.ai.support.ConstraintParser;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.ReplyGuard;
import com.duoduo.common.api.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问候 / 身份出口单测（第五十八轮）。
 *
 * <h2>报障背景</h2>
 * 用户发「你好，你是谁」，得到「暂时没找到匹配的商品」。没有任何报错，
 * 只有真人翻开看才知道答非所问 —— 这类缺陷必须靠单测钉住，不能靠冒烟碰运气。
 *
 * <h2>真正的风险在反向用例</h2>
 * 「问候出口」最容易写坏的方向不是漏判，而是<b>误判</b>：
 * 只要用「包含『你好』」当判据，「你好，我想买猫粮」就会被掐掉，
 * 用户会觉得这助手听不懂人话。所以这里一半的用例是<b>反向</b>的
 * （带问候词的真实购物意图必须放行到导购链路）。
 *
 * <h2>「出口在召回之前」要显式断言</h2>
 * 只断言回复文案是不够的：哪天有人把出口挪到召回之后，文案照样对，
 * 但每次问候都会白白打一次商品域。所以这里断言
 * {@code productSearchClient.candidates} 一次都没被调用。
 */
class GreetingIdentityExitTest {

    private ProductSearchClient productSearchClient;
    private DashScopeClient dashScopeClient;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        productSearchClient = mock(ProductSearchClient.class);
        dashScopeClient = mock(DashScopeClient.class);

        CategoryCatalog catalog = mock(CategoryCatalog.class);
        when(catalog.match(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0, String.class);
            if (text != null && text.contains("猫粮")) {
                return List.of(new CategoryBrief(1L, "宠物主粮", 1));
            }
            return List.of();
        });
        when(catalog.clarifyOptions(anyInt())).thenReturn(List.of(new CategoryBrief(1L, "宠物主粮", 1)));

        // 候选恒非空：于是「出口没命中」会以「回复里出现商品推荐」的形式暴露出来
        when(productSearchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(List.of(
                        new ProductCandidate(101L, "幼猫粮 2kg", "全阶段", 9900L, 9900L, 10, 4.8, true))));

        // ReplyGuard 的构造器容忍 provider 为 null（护栏行为不依赖监控），单测直接传 null
        chatService = new ChatService(productSearchClient, dashScopeClient,
                new ConstraintParser(catalog), catalog, new ReplyGuard(null), true);
    }

    private void assertNoRecallAndNoLlm() {
        verify(productSearchClient, never()).candidates(anyString(), anyInt(), any(), any(), any());
        verify(dashScopeClient, never()).chat(any(), any(), any(), anyInt());
    }

    // ---------------- 正向：命中出口 ----------------

    @Test
    void 纯问候返回自我介绍且不走召回() {
        ChatResponse r = chatService.chat("你好", List.of(), false);
        assertThat(r.reply()).contains("AI 导购");
        assertThat(r.recommendations()).isEmpty();
        assertThat(r.clarify()).isNull();
        assertNoRecallAndNoLlm();
    }

    @Test
    void 报障原句_你好你是谁_回答身份而不是没找到商品() {
        ChatResponse r = chatService.chat("你好，你是谁", List.of(), false);
        assertThat(r.reply()).doesNotContain("暂时没找到匹配的商品");
        assertThat(r.reply()).contains("我是多多宠物商店的 AI 导购");
        // 身份说明必须同时说清「能做什么」与「做不到什么」，否则用户会拿它当万能入口
        assertThat(r.reply()).contains("物流").contains("优惠券");
        assertNoRecallAndNoLlm();
    }

    @Test
    void 问能力_你能做什么_命中身份出口() {
        ChatResponse r = chatService.chat("你能做什么？", List.of(), false);
        assertThat(r.reply()).contains("AI 导购");
        assertNoRecallAndNoLlm();
    }

    @Test
    void 英文问候与标点变体都能命中() {
        assertThat(chatService.chat("hi", List.of(), false).reply()).contains("AI 导购");
        assertThat(chatService.chat("Hello！", List.of(), false).reply()).contains("AI 导购");
        assertThat(chatService.chat("你好呀", List.of(), false).reply()).contains("AI 导购");
        assertThat(chatService.chat("在吗？", List.of(), false).reply()).contains("AI 导购");
    }

    @Test
    void 自我介绍类问法命中() {
        assertThat(chatService.chat("介绍一下你自己", List.of(), false).reply()).contains("AI 导购");
        assertThat(chatService.chat("你叫什么名字", List.of(), false).reply()).contains("AI 导购");
    }

    @Test
    void 出口不消耗澄清次数_不返回澄清卡片() {
        assertThat(chatService.chat("你好", List.of(), false).clarify()).isNull();
    }

    // ---------------- 反向：真实购物意图绝不能被问候规则掐掉 ----------------

    @Test
    void 带问候词的购物意图必须走导购链路() {
        ChatResponse r = chatService.chat("你好，我想买猫粮", List.of(), false);
        assertThat(r.reply()).doesNotContain("我是多多宠物商店的 AI 导购");
        assertThat(r.recommendations()).isNotEmpty();
    }

    @Test
    void 带问候词的预算问句必须走导购链路() {
        ChatResponse r = chatService.chat("你好，200 以内的猫粮", List.of(), false);
        assertThat(r.recommendations()).isNotEmpty();
    }

    @Test
    void 问商品功能不命中身份出口() {
        // 「这个猫爬架有什么功能」问的是商品：身份出口若误判，用户会收到一段自我介绍
        ChatResponse r = chatService.chat("你好，这个猫爬架有什么功能", List.of(), false);
        assertThat(r.reply()).doesNotContain("我是多多宠物商店的 AI 导购");
    }

    @Test
    void 优惠券怎么用归优惠券出口而不是身份出口() {
        ChatResponse r = chatService.chat("优惠券怎么用", List.of(), false);
        assertThat(r.reply()).contains("领券中心");
        assertThat(r.reply()).doesNotContain("我是多多宠物商店的 AI 导购");
    }

    @Test
    void 空消息不抛异常也不走召回() {
        ChatResponse r = chatService.chat("", List.of(), false);
        assertThat(r.reply()).isNotNull();
        assertNoRecallAndNoLlm();
    }
}
