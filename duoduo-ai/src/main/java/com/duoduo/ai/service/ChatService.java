package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.dto.AiDtos.ChatTurn;
import com.duoduo.ai.dto.AiDtos.ProductRecommendation;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 导购对话编排（A9）。
 *
 * <p>流水线：**召回 → 组织**。召回（真实商品）交给商品域；组织（说人话 + 挑货）交给 LLM 或
 * mock 规则。模型拿到的提示词里只有候选列表，且系统提示明确「候选是可选集合、只推符合的」——
 * 结构上杜绝编造（验收①）：模型回的是**商品 id**，服务端再拿 id 到候选里反查，
 * 编出来的 id 一律落空。不是事后用正则去抓幻觉。
 *
 * <p>降级三形态（见 {@code AiApplication} 类注释）：mock / unconfigured 503 / 超时 503。
 * mock 的回复也是**从真实候选拼出来的**，与真实模式共享召回链路 ——
 * 冒烟在 mock 下验证的「推荐必须来自商品库」，对真实模式同样成立。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * 系统提示：把「不得编造」写成硬约束，并给出价格口径（分）与引导话术要求（验收②）。
     *
     * <p>要点：**候选列表是「可选集合」而不是「必推清单」**。最早只说「只能推荐候选里的商品」，
     * 模型会理解成「把候选都给出去」，于是用户说「预算 200 以内」而候选里混着 328 元的商品时，
     * 回复正文只挑对的、`recommendations` 却把超预算的也渲染成卡片 —— 正文与卡片互相打脸。
     * 现在明确要求「先按用户条件筛，只推真正符合的」，并由模型显式产出选择结果。
     */
    private static final String SYSTEM_PROMPT = """
            你是宠物商店「多多宠物」的导购助手「多多」。规则：
            1. 【候选商品列表】是**可选集合**，不是必推清单 —— 先按用户的条件（品类、年龄、用途、预算等）筛一遍，
               只把真正符合的挑出来推荐，并说明为什么符合；不符合的**不要出现在推荐里**；
            2. 只能推荐【候选商品列表】里出现的商品，绝不虚构不存在的商品、价格或促销；
            3. 价格单位是「分」，请换算成「元」表述（例如 19900 分 = 199 元）；
            4. 用户提到下单 / 加购 / 购买时，引导用户登录后到对应商品页操作，不要承诺代下单；
            5. 没有符合条件（或完全没有）候选时如实说明，并请用户换个描述，不要硬推；
            6. 回复简洁友好，控制在 150 字以内。
            【候选商品列表】
            """;

    private final ProductSearchClient productSearchClient;
    private final DashScopeClient dashScopeClient;
    private final boolean mockEnabled;

    public ChatService(ProductSearchClient productSearchClient,
                       DashScopeClient dashScopeClient,
                       @Value("${duoduo.ai.mock-enabled:true}") boolean mockEnabled) {
        this.productSearchClient = productSearchClient;
        this.dashScopeClient = dashScopeClient;
        this.mockEnabled = mockEnabled;
    }

    public ChatResponse chat(String message, List<ChatTurn> history) {
        // ① 召回：真实商品库（商品域内部接口）。商品域不可用 → 503，绝不用空候选硬答
        List<ProductCandidate> candidates;
        try {
            candidates = productSearchClient.candidates(recallKeyword(message, history), 8).data();
        } catch (Exception ex) {
            log.error("候选召回失败: {}", ex.getMessage());
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后再试");
        }
        if (candidates == null) {
            candidates = List.of();
        }

        // ② 组织回复
        if (mockEnabled) {
            return new ChatResponse(mockReply(message, candidates), toRecommendations(topN(candidates, 3)), "mock");
        }
        if (candidates.isEmpty()) {
            // 无候选时也如实说没有 —— 空候选喂给模型反而容易诱导它编
            return new ChatResponse("暂时没找到匹配的商品，麻烦换个描述试试，比如「幼犬狗粮」或「猫玩具」。", List.of(), "llm");
        }
        return chatWithLlm(message, history, candidates);
    }

    /**
     * 召回关键词：本轮消息 + 历史里最近若干轮**用户**提问，拼成一个查询串。
     *
     * <p>为什么必须带上下文：商品域的候选召回是**关键词词元匹配**，追问式消息（「那最便宜的是哪个？」、
     * 「有货吗」）本身不含商品词，单用它召回必然是空 —— 于是多轮对话在第一轮之后全部退化成
     * 「没找到」，历史传了也白传。
     *
     * <p>为什么取**多轮**而不是只取上一轮：追问可以是连续的（「推荐猫粮」→「最便宜的」→「有货吗」），
     * 只并最近一轮的话，第二句追问并进来的是同样无商品词的第一句追问，照样召回为空。
     * 这里把历史里所有用户消息**由近及远**追加，命中商品词的那一轮自然落进查询串 ——
     * 商品域是词元 OR 匹配，多带几个词只会提升召回，不会因为多带而变空。
     *
     * <p>只并**用户**的话、不并助手的话：助手回复往往含商品标题，把电商文案当检索词会把召回
     * 带偏到「标题里恰好有这几个字」的商品上，反而不如用户原话准。
     */
    private static String recallKeyword(String message, List<ChatTurn> history) {
        StringBuilder sb = new StringBuilder(message);
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatTurn t = history.get(i);
                if (t == null || t.content() == null || t.content().isBlank()) {
                    continue;
                }
                if (!"user".equalsIgnoreCase(t.role() == null ? "" : t.role().trim())) {
                    continue;
                }
                String text = t.content().trim();
                if (sb.indexOf(text) >= 0) {
                    continue;
                }
                if (sb.length() + text.length() + 1 > 200) {
                    break;
                }
                sb.append(' ').append(text);
            }
        }
        return sb.toString();
    }

    /**
     * LLM 形态：一次调用同时产出「给用户看的话术」与「真正被选中的商品 id」。
     *
     * <p>为什么让模型回 id 而不是靠正文文本反查：正文是自然语言，拿它去比对商品标题等于
     * 用字符串相似度猜「模型到底推荐了哪个」，是幻觉检测的反面教材。让模型直接给出结构化的
     * id 列表，再由服务端**用 id 到候选里反查**（{@link #selectByIds}）——
     * id 不在候选里的一律丢弃，模型就算编了 id 也拼不出不存在的商品。
     */
    private ChatResponse chatWithLlm(String message, List<ChatTurn> history, List<ProductCandidate> candidates) {
        String prompt = SYSTEM_PROMPT + candidatesJson(candidates) + """

                【输出格式】
                先输出给用户看的回复正文；正文结束后另起一行，输出一行严格 JSON：{"ids":[选中的商品id...]}
                ids 只能从候选里挑，最多 4 个，按推荐优先级排序；一个都不合适就给空数组 []。
                """;
        String raw = dashScopeClient.chat(prompt, toMessages(history), message, 512);
        LlmReply parsed = parseLlmReply(raw, candidates);

        // 模型明确表示没有合适的：如实转达，不回退成「硬推全部候选」
        if (parsed.ids().isEmpty()) {
            String reply = parsed.reply().isBlank()
                    ? "这几款里暂时没有特别符合你要求的，换个描述试试？比如「幼猫粮 200 元内」。"
                    : parsed.reply();
            return new ChatResponse(reply, List.of(), "llm");
        }
        return new ChatResponse(parsed.reply(), toRecommendations(parsed.selected()), "llm");
    }

    /** LLM 回复的解析结果：话术正文 + 被选中的候选（已按 id 反查回真实商品） */
    private record LlmReply(String reply, List<ProductCandidate> selected, List<Long> ids) {
    }

    /**
     * 解析「正文 + 结构化 id」的混合回复。
     *
     * <p>解析失败不抛错：模型偶尔不守格式是常态，此时**退化为只展示候选前 3 条**
     * （宁可少推，也不把候选全量倒给用户）。正文本身仍然可用，不因格式问题丢回复。
     */
    private static LlmReply parseLlmReply(String raw, List<ProductCandidate> candidates) {
        String text = raw == null ? "" : raw.trim();
        int jsonAt = text.lastIndexOf("{\"ids\"");
        if (jsonAt < 0) {
            text = extractAnyIdsObject(text);
            jsonAt = text.lastIndexOf("{\"ids\"");
        }
        if (jsonAt < 0) {
            // 没按格式回：正文照用，推荐退化为候选前 3 条
            log.warn("LLM 回复未含结构化 id，退化展示候选前 3 条");
            return new LlmReply(text, candidates.subList(0, Math.min(3, candidates.size())), List.of());
        }
        String reply = text.substring(0, jsonAt).trim();
        List<Long> ids = parseIds(text.substring(jsonAt));
        List<ProductCandidate> selected = selectByIds(candidates, ids);
        return new LlmReply(reply, selected, ids);
    }

    /** 容错：模型可能写成 ```json 包裹或带空格 `{"ids": [...]}`，统一压成无空格的规范形态再定位 */
    private static String extractAnyIdsObject(String text) {
        int at = text.indexOf("{\"ids\"");
        if (at >= 0) {
            return text;
        }
        // 容忍 `{"ids": [...]`（冒号后带空格）与 markdown 代码块
        return text.replace("{\"ids\":", "{\"ids\":").replace(" ", "");
    }

    /**
     * 从 `{"ids":[1,2,3]}` 里抽 id。
     *
     * <p>只认数字与逗号：这是**不可信输入**（模型输出，等同外部数据），
     * 不引 JSON 库也不做宽松语法，扫到非法字符就停 —— 拿不到就少推，不会错推。
     */
    private static List<Long> parseIds(String jsonPart) {
        int lb = jsonPart.indexOf('[');
        int rb = jsonPart.indexOf(']', lb + 1);
        if (lb < 0 || rb < 0) {
            return List.of();
        }
        List<Long> ids = new java.util.ArrayList<>();
        StringBuilder num = new StringBuilder();
        for (int i = lb + 1; i < rb; i++) {
            char c = jsonPart.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (c == ',' || Character.isWhitespace(c)) {
                if (!num.isEmpty()) {
                    ids.add(Long.parseLong(num.toString()));
                    num.setLength(0);
                }
            }
        }
        if (!num.isEmpty()) {
            ids.add(Long.parseLong(num.toString()));
        }
        return ids;
    }

    /**
     * 按 id 到候选里反查并保持模型给的优先级顺序。
     *
     * <p>这是「不得编造」的最后一道闸：模型给的 id 若不在候选集合里，直接丢弃 ——
     * 于是被推荐出来的每一条都必然有真实的价格与库存事实。
     */
    private static List<ProductCandidate> selectByIds(List<ProductCandidate> candidates, List<Long> ids) {
        List<ProductCandidate> selected = new java.util.ArrayList<>();
        for (Long id : ids) {
            candidates.stream()
                    .filter(c -> c.id().equals(id))
                    .findFirst()
                    .ifPresent(selected::add);
            if (selected.size() >= 4) {
                break;
            }
        }
        return selected;
    }

    /** 历史轮次 → OpenAI 兼容 messages：role 规范化（只认 user，其余一律 assistant），空内容直接丢弃 */
    private static List<Map<String, String>> toMessages(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(t -> t != null && t.content() != null && !t.content().isBlank())
                .map(t -> Map.of("role", "user".equalsIgnoreCase(t.role() == null ? "" : t.role().trim())
                        ? "user" : "assistant", "content", t.content()))
                .toList();
    }

    /**
     * mock 回复：确定性规则把召回结果拼成导购话术 —— 每个推荐都真实存在。
     * 关键词不存在时如实说没有（这也是验收①「不得编造」的一部分）。
     */
    private static String mockReply(String message, List<ProductCandidate> candidates) {
        if (candidates.isEmpty()) {
            return "暂时没找到与「" + message + "」匹配的商品。换个说法试试，比如「幼犬狗粮」「猫零食」？";
        }
        StringBuilder sb = new StringBuilder("为你找到 " + candidates.size() + " 款相关商品：");
        int top = Math.min(3, candidates.size());
        for (int i = 0; i < top; i++) {
            ProductCandidate c = candidates.get(i);
            sb.append("「").append(c.title()).append("」")
                    .append(c.minPrice() / 100).append("元起");
            if (!c.inStock()) {
                sb.append("（暂时缺货）");
            }
            sb.append("；");
        }
        sb.append("如需下单，请登录后在商品页加购。");
        return sb.toString();
    }

    private static String candidatesJson(List<ProductCandidate> candidates) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            ProductCandidate c = candidates.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":").append(c.id())
                    .append(",\"title\":\"").append(escape(c.title())).append('"')
                    .append(",\"priceCents\":").append(c.minPrice())
                    .append(",\"inStock\":").append(c.inStock()).append('}');
        }
        return sb.append(']').toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<ProductRecommendation> toRecommendations(List<ProductCandidate> candidates) {
        return candidates.stream()
                .map(c -> new ProductRecommendation(c.id(), c.title(), c.subtitle(),
                        c.minPrice(), c.maxPrice(), c.inStock()))
                .toList();
    }

    private static List<ProductCandidate> topN(List<ProductCandidate> candidates, int n) {
        return candidates.size() <= n ? candidates : candidates.subList(0, n);
    }
}
