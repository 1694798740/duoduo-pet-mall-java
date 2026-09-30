package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.support.CategoryCatalog;
import com.duoduo.ai.support.ConstraintParser;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.ReplyGuard;
import com.duoduo.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话编排单测（第五十八轮 · AI 加强项第 21 / 22 条在链路上的落地）。
 *
 * <h2>为什么要在链路级再断言一次</h2>
 * {@code MockReplyTest} 证明「mock 话术能被护栏拦住」，但那只是零件级：
 * 真正要守住的是「mock 形态下 {@code chat()} <b>真的走了护栏</b>、且命中时<b>真的换了回复</b>」。
 * 少了这一层，将来有人在编排里加一句「mock 直接返回」的快捷分支，
 * 护栏又变回不可达，而零件级测试照样全绿。
 */
class ChatServiceMockGuardTest {

    private final ProductSearchClient searchClient = mock(ProductSearchClient.class);
    private final DashScopeClient dashScopeClient = mock(DashScopeClient.class);
    private final CategoryCatalog categoryCatalog = mock(CategoryCatalog.class);
    private final ConstraintParser constraintParser = new ConstraintParser(categoryCatalog);
    private final ReplyGuard replyGuard = mock(ReplyGuard.class);

    private ChatService service() {
        return new ChatService(searchClient, dashScopeClient, constraintParser, categoryCatalog,
                replyGuard, null, true);
    }

    private static ProductCandidate c(long id, String title, long price) {
        return new ProductCandidate(id, title, "副标题", price, price, 10, 4.5, true);
    }

    @Test
    void mock形态下护栏仍然会被调用() {
        List<ProductCandidate> candidates = List.of(c(1L, "幼猫粮 500g", 9900));
        when(searchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(candidates));
        when(replyGuard.inspect(anyString(), any()))
                .thenReturn(new ReplyGuard.Verdict(false, null, "放行"));

        var response = service().chat("幼猫粮", List.of(), false, null);

        verify(replyGuard).inspect(anyString(), eq(candidates));
        assertThat(response.reply()).isEqualTo("放行");
        assertThat(response.recommendations()).hasSize(1);
    }

    @Test
    void mock形态下护栏命中则换成事实版回复() {
        List<ProductCandidate> candidates = List.of(c(1L, "幼猫粮 500g", 9900));
        when(searchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(candidates));
        when(replyGuard.inspect(anyString(), any()))
                .thenReturn(new ReplyGuard.Verdict(true, "合规词：绝对", ReplyGuard.factsReply(candidates)));

        var response = service().chat("幼猫粮", List.of(), false, null);

        assertThat(response.reply()).isEqualTo(ReplyGuard.factsReply(candidates));
    }

    @Test
    void 召回后同款不同规格被去重() {
        when(searchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(List.of(
                        c(1L, "幼猫粮 500g", 9900),
                        c(2L, "幼猫粮 1kg", 18900),
                        c(3L, "猫玩具", 1900))));
        when(replyGuard.inspect(anyString(), any()))
                .thenAnswer(invocation -> new ReplyGuard.Verdict(false, null, invocation.getArgument(0)));

        var response = service().chat("幼猫粮", List.of(), false, null);

        // 3 条候选里两款同规格 → 去重后推荐只剩 2 条（不是 3 条）
        assertThat(response.recommendations()).hasSize(2);
    }

    @Test
    void 问候出口仍然不调模型也不走护栏() {
        var response = service().chat("你好", List.of(), false, null);

        assertThat(response.recommendations()).isEmpty();
        verify(replyGuard, org.mockito.Mockito.never()).inspect(anyString(), any());
        org.mockito.Mockito.verifyNoInteractions(dashScopeClient);
    }
}
