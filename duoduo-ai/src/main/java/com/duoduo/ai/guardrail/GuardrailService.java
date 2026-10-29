package com.duoduo.ai.guardrail;

import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.dto.AiDtos.ProductRecommendation;
import com.duoduo.ai.memory.SessionState;
import com.duoduo.ai.memory.SlotState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 输出护栏总入口（2026-10-28；2026-10-29 增加链接护栏）。
 *
 * <p>顺序：商品事实 → 引用标记 → 链接清洗。
 * 链接必须放在**最后**：前面几步都可能重写正文（价格不一致时 FactGuard 会整段替换），
 * 只有对最终展示文本做一次链接清洗，才能保证「用户看到的这段话」里没有模型自造的地址。
 */
@Service
public class GuardrailService {

    private final MedicalRedFlagGuard medical;
    private final FactGuard factGuard;
    private final CitationGuard citationGuard;
    private final LinkGuard linkGuard;

    public GuardrailService(MedicalRedFlagGuard medical, FactGuard factGuard,
                            CitationGuard citationGuard, LinkGuard linkGuard) {
        this.medical = medical;
        this.factGuard = factGuard;
        this.citationGuard = citationGuard;
        this.linkGuard = linkGuard;
    }

    public MedicalRedFlagGuard.SafeAnswer medicalFallback(SlotState slots) {
        return medical.safeAnswer(slots);
    }

    /**
     * 输入侧医疗红线判定：只查用户原话。
     *
     * <p>暴露给调度层是为了让「急症优先于游客配额」能在最便宜的位置判断 ——
     * 等做完槽位抽取（一次 LLM 调用）再判定，急症用户已经为配额付出了成本。
     */
    public boolean isMedicalRedFlag(String text) {
        return medical.isRedFlag(text);
    }

    public GuardedAnswer check(String reply, SessionState session,
                               List<ToolResult> toolResults) {
        // 医疗红线在 Agent 入口按**用户原话**判定；不能拿模型自己的“就医提醒”再判一次，
        // 否则正常的商品推荐里只要出现“呕吐/不吃”就会被误伤成医疗兜底。
        FactGuard.Result facts = factGuard.verify(reply, toolResults);
        String cleaned = citationGuard.clean(facts.reply());
        // 最后一道：屏蔽外链 / 伪协议 / 未登记的站内路径
        cleaned = linkGuard.sanitize(cleaned);
        return new GuardedAnswer(cleaned, facts.recommendations(), facts.recommendedIds());
    }

    public record GuardedAnswer(String reply, List<ProductRecommendation> recommendations,
                                List<Long> recommendedIds) {
    }
}
