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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 政策出口 / 对比 / 追问的链路级断言（第五十八轮 · AI 加强项 S2 / S3）。
 *
 * <p>零件级测试只能证明「这些函数算得对」，证明不了「编排真的调用了它们」。
 * 这三条断言分别守住：政策问题不进模型、对比不进模型、追问随候选给出。
 * 少了这一层，有人在编排里少调一个分支，零件测试照样全绿。
 */
class ChatServiceAssistantExtrasTest {

    private final ProductSearchClient searchClient = mock(ProductSearchClient.class);
    private final DashScopeClient dashScopeClient = mock(DashScopeClient.class);
    private final CategoryCatalog categoryCatalog = mock(CategoryCatalog.class);
    private final ConstraintParser constraintParser = new ConstraintParser(categoryCatalog);
    private final ReplyGuard replyGuard = mock(ReplyGuard.class);

    private ChatService service() {
        return new ChatService(searchClient, dashScopeClient, constraintParser, categoryCatalog,
                replyGuard, null, true);
    }

    private static ProductCandidate c(long id, String title, long price, boolean inStock) {
        return new ProductCandidate(id, title, "副标题", price, price, 10, 4.5, inStock);
    }

    private void stubCandidates(List<ProductCandidate> candidates) {
        when(searchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(candidates));
        when(replyGuard.inspect(anyString(), any()))
                .thenAnswer(inv -> new ReplyGuard.Verdict(false, null, inv.getArgument(0)));
    }

    @Test
    void 政策问题走知识库且不调模型() {
        stubCandidates(List.of(c(1L, "幼猫粮", 9900, true)));

        var response = service().chat("我想退货", List.of(), false, null);

        assertThat(response.reply()).contains("卖家签收后");
        verify(searchClient, never()).candidates(anyString(), anyInt(), any(), any(), any());
        verify(dashScopeClient, never()).chat(any(), any(), any(), anyInt());
    }

    @Test
    void 对比意图走事实版且不调模型() {
        stubCandidates(List.of(c(1L, "幼猫粮", 9900, true), c(2L, "成猫粮", 12900, true)));

        var response = service().chat("这两款哪个好", List.of(), false, null);

        // 「客观差异」是对比话术独有的措辞：只断言标题的话，
        // 走回普通推荐路径也会因为标题出现在正文里而误绿
        assertThat(response.reply()).contains("客观差异").contains("幼猫粮").contains("成猫粮");
        verify(dashScopeClient, never()).chat(any(), any(), any(), anyInt());
    }

    @Test
    void 有候选时给出追问() {
        stubCandidates(List.of(c(1L, "幼猫粮", 9900, true), c(2L, "成猫粮", 12900, false)));

        var response = service().chat("猫粮", List.of(), false, null);

        assertThat(response.followUps()).isNotEmpty();
    }

    @Test
    void 没有候选时不给追问() {
        stubCandidates(List.of());

        var response = service().chat("不存在的商品xyz", List.of(), false, null);

        assertThat(response.followUps()).isEmpty();
    }
}
