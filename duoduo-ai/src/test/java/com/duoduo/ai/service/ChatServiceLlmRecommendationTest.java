package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.support.CategoryCatalog;
import com.duoduo.ai.support.ConstraintParser;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.ReplyGuard;
import com.duoduo.common.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 形态下「推荐区」与「正文」的一致性（2026-10-11 实测缺陷的单测化）。
 *
 * <h2>这条断言是怎么来的</h2>
 * ai-smoke 里有一条「推荐列表非空（幼犬/狗粮种子可召回）」。它在真实模型
 * （qwen3.8-flash）下**偶发红**：同一个提示词连发 6 次出现 1 次推荐列表为空，
 * 而那次的正文明明写着「为您找到一款符合的：XXX」—— 用户看到的是
 * 「说有货、下面却什么都没有」这种自相矛盾的回复。
 *
 * <p>根因不在召回（召回是确定性 SQL），而在**模型的两条输出通道互相打架**：
 * 正文在推荐，结构化 {@code {"ids":[]}} 却是空数组（或给的 id 抄错了、一条都对不上）。
 *
 * <h2>口径（两条，别混淆）</h2>
 * <ul>
 *   <li>模型**明确**给空数组 → 如实转达「这几款都不太合适」，**不**硬推候选。
 *       这是刻意保留的产品口径：候选已经按预算与品类在 SQL 层筛过，
 *       但"筛出来的候选里没有真正合适的"仍然是一个合法结论。</li>
 *   <li>模型给了 id 但**一条都解析不出可展示商品** → 这不是「说没有」，而是**输出与召回脱节**。
 *       按本类既定的「输出不可用就退化展示候选前 3 条」处理 —— 丢完编造的 id 之后
 *       总得有东西可展示，否则正文与推荐区自相矛盾。</li>
 * </ul>
 *
 * <p><b>为什么必须落在「解析后的结果」上</b>：这两个分支长得几乎一样，
 * 用 {@code parsed.ids().isEmpty()} 当守卫只能覆盖前者，正好漏掉后者
 * （它非空，但可展示的商品是空的）。写成「ids 为空 → 空列表 / 否则用 selected」时，
 * selected 为空就会静默下发一个空推荐区 —— 而它不会让任何接口报错。
 */
class ChatServiceLlmRecommendationTest {

    private final ProductSearchClient searchClient = mock(ProductSearchClient.class);
    private final DashScopeClient dashScopeClient = mock(DashScopeClient.class);
    private final CategoryCatalog categoryCatalog = mock(CategoryCatalog.class);
    private final ConstraintParser constraintParser = new ConstraintParser(categoryCatalog);
    private final ReplyGuard replyGuard = mock(ReplyGuard.class);

    /** mockEnabled=false → 真正走 llm 分支（mock 形态由 ChatServiceMockGuardTest 覆盖） */
    private ChatService service() {
        return new ChatService(searchClient, dashScopeClient, constraintParser, categoryCatalog,
                replyGuard, null, false);
    }

    private static ProductCandidate c(long id, String title, long price) {
        return new ProductCandidate(id, title, "副标题", price, price, 10, 4.5, true);
    }

    private void stubRecall(List<ProductCandidate> candidates) {
        when(searchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(candidates));
    }

    private void stubModel(String raw) {
        when(dashScopeClient.chat(anyString(), any(), anyString(), anyInt())).thenReturn(raw);
        when(replyGuard.inspect(anyString(), any()))
                .thenAnswer(inv -> new ReplyGuard.Verdict(false, null, inv.getArgument(0)));
    }

    /**
     * 四条**互相不同款**的候选。
     *
     * <p>标题刻意不带规格数字：{@code RecallDiversifier} 会把「同款不同规格」去重
     * （「幼猫粮 500g」与「幼猫粮 1kg」只留便宜的那件），夹具不去重就没法断言"取前 3 条"
     * 到底取了哪三条 —— 那样的断言红起来，第一眼会以为是兜底逻辑错了。
     */
    private static List<ProductCandidate> four() {
        return List.of(c(1001L, "幼猫粮", 9900), c(1002L, "成猫主食罐", 6900),
                c(1003L, "猫用化毛膏", 4500), c(1004L, "混合猫砂", 5900));
    }

    @Test
    @DisplayName("模型给的 id 全部不在候选里（抄错/自编号）→ 退化展示候选前 3 条，不得下发空推荐区")
    void 编造的id全部丢弃后退化展示候选() {
        stubRecall(four());
        stubModel("为您找到一款符合的：幼猫粮。\n{\"ids\":[88888888]}");

        var r = service().chat("推荐一款幼猫粮", List.of(), false, null);

        assertThat(r.mode()).isEqualTo("llm");
        assertThat(r.recommendations()).extracting(x -> x.id())
                .containsExactly(1001L, 1002L, 1003L);
    }

    @Test
    @DisplayName("模型给了 id 但候选只有 2 条时也只能展示现有的，不越界")
    void 候选不足三条时只展示现有的() {
        stubRecall(List.of(c(1001L, "幼猫粮", 9900), c(1002L, "成猫主食罐", 6900)));
        stubModel("给您看看这两款。\n{\"ids\":[77777777]}");

        var r = service().chat("推荐一款幼猫粮", List.of(), false, null);

        assertThat(r.recommendations()).extracting(x -> x.id()).containsExactly(1001L, 1002L);
    }

    @Test
    @DisplayName("模型明确给空数组 → 如实转达，不硬推候选（既定口径，显式钉住）")
    void 模型明确说没有合适的时不硬推候选() {
        stubRecall(four());
        stubModel("这几款里暂时没有特别符合幼犬需求的，换个描述试试？\n{\"ids\":[]}");

        var r = service().chat("推荐一款幼猫粮", List.of(), false, null);

        assertThat(r.recommendations()).isEmpty();
        assertThat(r.reply()).contains("没有特别符合");
    }

    @Test
    @DisplayName("模型只挑中部分候选 → 只展示挑中的，不倒全量也不越界")
    void 只展示模型挑中的候选() {
        stubRecall(four());
        stubModel("首推这款。\n{\"ids\":[1003]}");

        var r = service().chat("推荐一款幼猫粮", List.of(), false, null);

        assertThat(r.recommendations()).extracting(x -> x.id()).containsExactly(1003L);
    }

    @Test
    @DisplayName("模型没给结构化 id → 退化展示候选前 3 条（这一段以前是「说了但没做」的死代码）")
    void 未给结构化id时退化展示候选前三条() {
        stubRecall(four());
        stubModel("这几款都不错，看您想选哪个规格。");

        var r = service().chat("推荐一款幼猫粮", List.of(), false, null);

        assertThat(r.recommendations()).extracting(x -> x.id())
                .containsExactly(1001L, 1002L, 1003L);
    }

    @Test
    @DisplayName("召回为空时不调模型、也不给推荐（空候选喂给模型只会诱导它编）")
    void 召回为空时不调模型() {
        stubRecall(List.of());
        when(replyGuard.inspect(anyString(), any()))
                .thenAnswer(inv -> new ReplyGuard.Verdict(false, null, inv.getArgument(0)));

        var r = service().chat("推荐一款幼猫粮", List.of(), false, null);

        assertThat(r.recommendations()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(dashScopeClient);
    }
}
