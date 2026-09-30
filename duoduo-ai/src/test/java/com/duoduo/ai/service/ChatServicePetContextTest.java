package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.UserProfileClient;
import com.duoduo.ai.client.dto.PetBrief;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.support.CategoryCatalog;
import com.duoduo.ai.support.ConstraintParser;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.ReplyGuard;
import com.duoduo.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 宠物档案个性化的链路级断言（第五十八轮 · AI 加强项 S1）。
 *
 * <h2>为什么必须在链路层测</h2>
 * {@code PetContextTest} 只证明「档案能变成词」，证明不了「这个词在什么时候被用」。
 * 而个性化的风险全在时机上：
 * <ul>
 *   <li>用户说了品类还硬塞宠物词 →「买狗粮」推成猫粮，比不个性化更糟；</li>
 *   <li>用户域取不到档案就整轮失败 → 用可选增强换掉整条链路的可用性；</li>
 *   <li>根本没用档案却在回复里说「按你家宠物筛的」→ 这句话本身就是编造。</li>
 * </ul>
 */
class ChatServicePetContextTest {

    private final ProductSearchClient searchClient = mock(ProductSearchClient.class);
    private final DashScopeClient dashScopeClient = mock(DashScopeClient.class);
    private final CategoryCatalog categoryCatalog = mock(CategoryCatalog.class);
    private final ConstraintParser constraintParser = new ConstraintParser(categoryCatalog);
    private final ReplyGuard replyGuard = mock(ReplyGuard.class);
    private final UserProfileClient userProfileClient = mock(UserProfileClient.class);

    private ChatService service() {
        return new ChatService(searchClient, dashScopeClient, constraintParser, categoryCatalog,
                replyGuard, userProfileClient, true);
    }

    private static ProductCandidate c(long id, String title, long price) {
        return new ProductCandidate(id, title, "副标题", price, price, 10, 4.5, true);
    }

    private static final List<PetBrief> CAT_PET =
            List.of(new PetBrief(1L, "咪咪", "cat", "布偶", "幼年", List.of()));

    private void stubCandidates() {
        when(searchClient.candidates(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ApiResponse.ok(List.of(c(1L, "幼猫粮", 9900))));
        when(replyGuard.inspect(anyString(), any()))
                .thenAnswer(inv -> new ReplyGuard.Verdict(false, null, inv.getArgument(0)));
    }

    @Test
    void 没指定品类时宠物物种进召回关键词() {
        when(userProfileClient.petProfiles(anyLong())).thenReturn(ApiResponse.ok(CAT_PET));
        stubCandidates();

        service().chat("推荐点吃的", List.of(), false, 710000000000000001L);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(searchClient).candidates(keyword.capture(), anyInt(), any(), any(), any());
        assertThat(keyword.getValue()).contains("推荐点吃的").contains("猫");
    }

    @Test
    void 用户明说品类时档案不参与召回() {
        when(userProfileClient.petProfiles(anyLong())).thenReturn(ApiResponse.ok(CAT_PET));
        stubCandidates();
        // 「狗粮」要能被识别成品类，得让类目表认得这个词（CategoryCatalog.match 是识别入口）
        when(categoryCatalog.match(anyString()))
                .thenReturn(List.of(new com.duoduo.ai.client.dto.CategoryBrief(9L, "宠物主粮", 1)));

        service().chat("推荐狗粮", List.of(), false, 710000000000000001L);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(searchClient).candidates(keyword.capture(), anyInt(), any(), any(), any());
        assertThat(keyword.getValue()).doesNotContain("猫");
    }

    @Test
    void 匿名时不取档案() {
        stubCandidates();

        service().chat("推荐点吃的", List.of(), false, null);

        verify(userProfileClient, never()).petProfiles(anyLong());
    }

    @Test
    void 档案取不到时照常回答() {
        when(userProfileClient.petProfiles(anyLong())).thenThrow(new RuntimeException("用户域不可用"));
        stubCandidates();

        var response = service().chat("推荐点吃的", List.of(), false, 710000000000000001L);

        assertThat(response.reply()).isNotBlank();
        // 没用档案就不能说用了
        assertThat(response.reply()).doesNotContain("档案");
    }

    @Test
    void 真的用了档案才说用了() {
        when(userProfileClient.petProfiles(anyLong())).thenReturn(ApiResponse.ok(CAT_PET));
        stubCandidates();

        var response = service().chat("推荐点吃的", List.of(), false, 710000000000000001L);

        assertThat(response.reply()).contains("档案");
    }

    @Test
    void 档案为空时不提档案() {
        when(userProfileClient.petProfiles(anyLong())).thenReturn(ApiResponse.ok(List.of()));
        stubCandidates();

        var response = service().chat("推荐点吃的", List.of(), false, 710000000000000001L);

        assertThat(response.reply()).doesNotContain("档案");
    }
}
