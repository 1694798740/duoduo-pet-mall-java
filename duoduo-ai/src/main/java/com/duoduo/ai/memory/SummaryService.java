package com.duoduo.ai.memory;

import com.duoduo.ai.support.DashScopeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话摘要（2026-10-28）：超过 8 轮压缩，保留最近 6 条 + 摘要。
 *
 * <p>摘要只保留事实（宠物、预算、症状、已排除项），不保留寒暄与营销话术。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private static final int COMPRESS_AFTER_TURNS = 8;
    private static final int KEEP_MESSAGES = 6;

    private static final String PROMPT = """
        把下面的宠物导购对话压缩成 200 字以内的「会话纪要」，只保留事实：
        宠物物种/品种/年龄、预算、症状与持续时间、过敏/忌口、已推荐商品、用户明确拒绝的选项。
        不要保留寒暄、不要编造未出现的信息。只输出纪要正文。
        """;

    private final DashScopeClient llm;

    public SummaryService(DashScopeClient llm) {
        this.llm = llm;
    }

    public SessionState compressIfNeeded(SessionState state) {
        if (state.turnCount() < COMPRESS_AFTER_TURNS || state.messages().size() <= KEEP_MESSAGES) {
            return state;
        }
        List<ChatMessage> messages = state.messages();
        List<ChatMessage> dropped = messages.subList(0, messages.size() - KEEP_MESSAGES);
        List<ChatMessage> keep = messages.subList(messages.size() - KEEP_MESSAGES, messages.size());
        String summary = summaryOf(state.summary(), dropped);
        return state.withSummary(summary, List.copyOf(keep));
    }

    private String summaryOf(String oldSummary, List<ChatMessage> dropped) {
        StringBuilder text = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            text.append("已有纪要：").append(oldSummary).append('\n');
        }
        for (ChatMessage m : dropped) {
            text.append(m.role()).append("：").append(m.content()).append('\n');
        }
        try {
            String summary = llm.chat(PROMPT, List.of(), text.toString(), 256);
            return summary == null || summary.isBlank() ? fallback(text) : summary.trim();
        } catch (Exception e) {
            log.warn("会话摘要失败，使用确定性截断: {}", e.getMessage());
            return fallback(text);
        }
    }

    private static String fallback(StringBuilder text) {
        String s = text.toString().replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
