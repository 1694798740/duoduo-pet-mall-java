package com.duoduo.ai.agent;

import com.duoduo.ai.agent.card.CardAssembler;
import com.duoduo.ai.agent.slot.SlotExtractor;
import com.duoduo.ai.agent.tool.ToolCall;
import com.duoduo.ai.agent.tool.ToolConstraintContext;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolRegistry;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.audit.AiAuditService;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.dto.ai.BadgePatch;
import com.duoduo.ai.guardrail.DataFence;
import com.duoduo.ai.guardrail.GuardrailService;
import com.duoduo.ai.knowledge.KnowledgeRagRetriever;
import com.duoduo.ai.llm.LlmToolClient;
import com.duoduo.ai.llm.LlmTurn;
import com.duoduo.ai.memory.LongTermMemoryService;
import com.duoduo.ai.memory.RedisSessionStore;
import com.duoduo.ai.memory.SessionState;
import com.duoduo.ai.memory.SlotMerger;
import com.duoduo.ai.memory.SlotState;
import com.duoduo.ai.memory.SummaryService;
import com.duoduo.ai.support.AiMetrics;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AI 全能管家调度引擎（2026-10-29 · 阶段三；由 AgentOrchestrator 演进而来）。
 *
 * <h2>一轮请求的固定顺序（对应落地文档第 5 节伪代码）</h2>
 * <ol>
 *   <li><b>医疗红线（输入侧）</b>：按用户原话判定，命中直接走就医兜底。
 *       放在最前面且**优先于游客配额** —— 急症不能因为「游客 3 轮用尽」被挡住；
 *       同时这一步只做关键词匹配，不调模型。刻意不做输出侧复检，
 *       否则模型自己的「请就医」提醒会被误伤成医疗兜底。</li>
 *   <li><b>匿名 Cookie 鉴权 + Redis 轮次限制</b>：游客 3 轮 / 24 小时 / 设备。
 *       配额在**调用模型之前**判断 —— 让一次拒绝也消耗完 LLM 配额是最贵的浪费。</li>
 *   <li><b>会话与记忆</b>：Redis 会话（2h）+ 长期档案；槽位合并后才允许路由。</li>
 *   <li><b>Function Calling 循环（最多 4 步）</b>：每步执行模型申请的工具；
 *       工具结果同时喂回模型、留存给护栏与卡片装配。</li>
 *   <li><b>写操作二次确认拦截</b>：任一工具返回 NEED_CONFIRM 时，
 *       本轮立即停下并下发确认事件，不再让模型继续规划 ——
 *       模型只能「申请」，执行必须等用户点击。</li>
 *   <li><b>输出护栏</b>：商品事实 → 引用清理 → 链接清洗（外链/伪协议/未登记路径）。</li>
 *   <li><b>卡片装配</b>：按模型声明的 card_refs（或退回自动装配）从工具结果取数，
 *       模型不参与卡片内容。</li>
 *   <li><b>记忆与配额落账</b>：只有成功产出回复才消费游客轮次。</li>
 * </ol>
 *
 * <h2>为什么最终回复要求 JSON 信封</h2>
 * {@code {"reply": "...", "card_refs": [...]}} 让「正文」与「卡片引用」两个关注点分开：
 * 正文由护栏逐字清洗，卡片引用只允许指向本轮真实调用成功的工具。
 * 模型如果无视格式，服务端退回「纯文本 + 按工具自动装配卡片」，
 * 用户不会因为一次格式漂移就看不到数据。
 */
@Service
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /** Function Calling 循环上限（文档第 5 节：最多 4 步）。 */
    private static final int MAX_TOOL_STEPS = 4;

    private static final String DEFAULT_REPLY =
            "我先帮你整理到这里。你可以补充宠物年龄、预算或想要的品类，我继续帮你筛。";

    /** 写操作被拦截时的收口话术：不含任何业务承诺，只把「等你确认」说清楚。 */
    private static final String CONFIRM_REPLY = "这一步需要你确认后我才会执行，点一下确认就行。";

    /** 会产出卡片的工具：用于区分「没数据所以没卡」与「有数据但装配失败」。 */
    private static final Set<String> CARD_TOOLS = Set.of(
            "get_user_notifications", "get_user_coupons", "get_user_orders", "navigate_to_page");

    /** 游客轮次用尽：给出「为什么停」和「怎么继续」，并附带登录地址。 */
    private static final String GUEST_LIMIT_REPLY =
            "游客体验最多 3 轮。登录后可以继续聊，还能查订单、通知、优惠券，并记住你家宠物的档案。";

    /** 整轮预算耗尽时的收口话术：明确告诉用户「整理到这里」，不假装查到了更多。 */
    private static final String BUDGET_REPLY =
            "我先帮你整理到这里。这个问题需要更多时间，你可以补充更具体的要求，或稍后再试。";

    private final RedisSessionStore sessionStore;
    private final LongTermMemoryService memoryService;
    private final SlotExtractor slotExtractor;
    private final SlotMerger slotMerger;
    private final IntentRouter intentRouter;
    private final ToolRegistry toolRegistry;
    private final LlmToolClient llm;
    private final PromptBuilder promptBuilder;
    private final GuardrailService guardrail;
    private final SummaryService summaryService;
    private final KnowledgeRagRetriever knowledge;
    private final CardAssembler cardAssembler;
    private final GuestIdentityService guestIdentity;
    private final NavigationService navigation;
    private final AiAuditService audit;
    private final AiMetrics metrics;
    private final ObjectMapper mapper;
    /** 一整轮 Agent 的总预算：所有模型步与最终收口轮共享，防止 4×55s 拖死请求。 */
    private final Duration turnBudget;

    public AgentChatService(RedisSessionStore sessionStore,
                            LongTermMemoryService memoryService,
                            SlotExtractor slotExtractor,
                            SlotMerger slotMerger,
                            IntentRouter intentRouter,
                            ToolRegistry toolRegistry,
                            LlmToolClient llm,
                            PromptBuilder promptBuilder,
                            GuardrailService guardrail,
                            SummaryService summaryService,
                            KnowledgeRagRetriever knowledge,
                            CardAssembler cardAssembler,
                            GuestIdentityService guestIdentity,
                            NavigationService navigation,
                            AiAuditService audit,
                            AiMetrics metrics,
                            ObjectMapper mapper,
                            @Value("${duoduo.ai.turn-budget-seconds:55}") int turnBudgetSeconds) {
        this.sessionStore = sessionStore;
        this.memoryService = memoryService;
        this.slotExtractor = slotExtractor;
        this.slotMerger = slotMerger;
        this.intentRouter = intentRouter;
        this.toolRegistry = toolRegistry;
        this.llm = llm;
        this.promptBuilder = promptBuilder;
        this.guardrail = guardrail;
        this.summaryService = summaryService;
        this.knowledge = knowledge;
        this.cardAssembler = cardAssembler;
        this.guestIdentity = guestIdentity;
        this.navigation = navigation;
        this.audit = audit;
        this.metrics = metrics;
        this.mapper = mapper;
        this.turnBudget = Duration.ofSeconds(Math.max(5, Math.min(turnBudgetSeconds, 120)));
    }

    /**
     * 执行一轮对话。事件按 meta → (tool|confirm)* → recommendations → token* → cards → done 顺序下发。
     */
    public AgentResult chat(AgentCommand command, Consumer<AgentEvent> events) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String sessionId = command.sessionId() == null || command.sessionId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : command.sessionId();

        if (!llm.isConfigured()) {
            events.accept(AgentEvent.error("AI 服务未配置（DASHSCOPE_API_KEY 为空）"));
            return AgentResult.fail("AI_NOT_CONFIGURED", "AI 服务未配置，请联系管理员");
        }
        if (!sessionStore.tryLock(sessionId)) {
            return AgentResult.fail("SESSION_BUSY", "当前会话正在处理中，请稍后再问");
        }

        boolean guest = command.userId() == null;
        try {
            // 整轮预算：从进入调度开始计时，后续所有模型步与收口轮共享。
            long deadlineNanos = System.nanoTime() + turnBudget.toNanos();
            events.accept(AgentEvent.meta(sessionId, requestId));

            // 页面上下文先解析：医疗 / 配额拒绝也要能审计「用户从哪来」
            String pageUrl = navigation.safeInternalPath(command.pageUrl());

            // ① 医疗红线优先于一切配额：急症不能因为「游客 3 轮用尽」被挡在门外。
            //    这里只做关键词判定 —— 不调模型、不读会话，必须是最便宜的一步。
            if (guardrail.isMedicalRedFlag(command.message())) {
                var safe = guardrail.medicalFallback(SlotState.empty());
                events.accept(AgentEvent.handoff(safe.reply(), safe.summary()));
                events.accept(AgentEvent.done(safe.reply(), sessionId));
                audit.recordTurn(new AiAuditService.TurnRecord(
                        sessionId, command.userId(), pageUrl, command.message(),
                        safe.reply(), List.of(), Intent.HIGH_RISK_MEDICAL.name(), 0, null));
                return AgentResult.ok(sessionId, safe.reply(),
                        List.of(), List.of(), List.of(), null, null);
            }

            // ② 游客轮次：先判配额，再花模型的钱
            if (guest && guestIdentity.remaining(command.deviceId()) <= 0) {
                metrics.guestQuotaExceeded();
                String loginUrl = navigation.loginUrl("/ai");
                events.accept(AgentEvent.loginRequired(GUEST_LIMIT_REPLY, loginUrl));
                events.accept(AgentEvent.done(GUEST_LIMIT_REPLY, sessionId));
                audit.recordTurn(new AiAuditService.TurnRecord(
                        sessionId, command.userId(), pageUrl, command.message(),
                        GUEST_LIMIT_REPLY, List.of(), "GUEST_QUOTA", 0, null));
                return AgentResult.ok(sessionId, GUEST_LIMIT_REPLY,
                        List.of(), List.of(), List.of(), null, loginUrl);
            }

            // ③ 会话 + 记忆 + 槽位
            SessionState session = sessionStore.loadOrCreate(sessionId, command.userId());
            SlotState memorySlots = memoryService.loadSlots(command.userId());
            SlotState turnSlots = slotExtractor.extract(command.message(), session.slots());
            SlotState merged = slotMerger.merge(turnSlots, session.slots(), memorySlots);
            // 工具层以服务端槽位为预算最终口径（DEF-013）；必须在 finally 清理。
            ToolConstraintContext.set(merged);
            session = session.withUserMessage(command.message()).withSlots(merged);

            Intent intent = intentRouter.route(command.message(), merged, session);
            session = session.withIntent(intent.name());

            ToolContext toolContext = new ToolContext(
                    command.userId(), command.role(), command.phone(),
                    sessionId, requestId, command.userId() != null);

            var snippets = knowledge.retrieve(command.message(), merged.species(),
                    merged.lifeStage(), 3);
            List<Map<String, Object>> messages = promptBuilder.build(
                    session, merged, memorySlots, snippets, guest, pageUrl);

            List<ToolResult> toolResults = new ArrayList<>();
            List<CardAssembler.ToolInvocation> invocations = new ArrayList<>();
            String finalRaw = null;
            boolean confirmPending = false;

            // ④ Function Calling 循环：最多 4 步，且所有步共享整轮预算
            for (int step = 1; step <= MAX_TOOL_STEPS; step++) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    log.warn("AI 整轮预算用尽 session={} step={}，停止继续规划", sessionId, step);
                    break;
                }
                long llmStart = System.nanoTime();
                LlmTurn turn;
                try {
                    turn = llm.chatWithTools(messages, toolRegistry.specs(),
                            Duration.ofMillis(remainingMs));
                } catch (RuntimeException e) {
                    // 异常也要计量：超时与业务失败分开记，否则看板上的「AI 慢」没有方向
                    metrics.llmCall(System.nanoTime() - llmStart, AiMetrics.looksLikeTimeout(e));
                    // 整轮预算已经用尽时，不再把一次模型超时升级成 503：
                    // 用户已经等了一段时间，给一句如实的收口话术比直接错误更有用。
                    if (e instanceof BizException && System.nanoTime() >= deadlineNanos) {
                        log.warn("AI 整轮预算用尽且模型调用失败 session={}，返回收口话术", sessionId);
                        finalRaw = BUDGET_REPLY;
                        break;
                    }
                    throw e;
                }
                metrics.llmCall(System.nanoTime() - llmStart, false);

                if (!turn.hasToolCalls()) {
                    finalRaw = turn.content();
                    break;
                }
                messages.add(assistantToolCallMessage(turn));

                boolean needConfirm = false;
                for (ToolCall call : turn.toolCalls()) {
                    long toolStart = System.nanoTime();
                    ToolResult result = toolRegistry.execute(call, toolContext);
                    int latencyMs = (int) Math.min(Integer.MAX_VALUE,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - toolStart));
                    String resultJson = safeJson(result);

                    toolResults.add(result);
                    invocations.add(new CardAssembler.ToolInvocation(call.name(), result));
                    messages.add(toolMessage(call, resultJson));
                    // B2：每次工具调用都留痕（入参、结果、状态、耗时、页面、红点补丁）
                    audit.recordTool(new AiAuditService.ToolRecord(
                            requestId, sessionId, command.userId(), pageUrl,
                            call.name(), call.arguments(), resultJson,
                            result.success() ? "OK" : result.code(), latencyMs,
                            badgePatchJson(result.badgePatch())));
                    events.accept(AgentEvent.tool(call.name(), result.success()));

                    // ⑤ 写操作拦截：模型只能申请，执行必须等用户确认
                    if (result.needConfirm()) {
                        events.accept(AgentEvent.confirm(result.confirmToken(), result.data()));
                        needConfirm = true;
                    }
                }
                if (needConfirm) {
                    confirmPending = true;
                    break;
                }
            }

            if (finalRaw == null) {
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (confirmPending) {
                    finalRaw = CONFIRM_REPLY;
                } else if (remainingMs <= 0) {
                    log.warn("AI 整轮预算用尽 session={}，使用预算收口话术", sessionId);
                    finalRaw = BUDGET_REPLY;
                } else {
                    finalRaw = finalTurn(messages, Duration.ofMillis(remainingMs));
                }
            }

            ReplyEnvelope envelope = parseReply(finalRaw);
            String replyText = envelope.reply().isBlank() ? DEFAULT_REPLY : envelope.reply();

            // ⑥ 输出护栏：事实 → 引用 → 链接
            GuardrailService.GuardedAnswer guarded = guardrail.check(replyText, session, toolResults);

            // ⑦ 卡片装配：模型只给引用，内容全部来自工具结果
            CardAssembler.CardBundle bundle = cardAssembler.assemble(
                    invocations, envelope.cardRefs(), envelope.structured());

            // B5：工具成功、却没有产出任何卡片 —— 要么卡片类型未支持，要么装配解析失败。
            // 这条计数是「用户说卡片空」时第一个要看的信号（区分「没数据」与「装配坏了」）
            if (bundle.cards().isEmpty() && hasCardToolSuccess(invocations)) {
                metrics.emptyCard();
            }

            // 工具层返回 NEED_LOGIN（游客问「我的订单」等）时，登录按钮需要 loginUrl 才能出现。
            // 模型只会说「登录后可以查」，地址必须由服务端给 —— 它拼不出来，也不该拼。
            String loginUrl = null;
            for (ToolResult result : toolResults) {
                if (result.needLogin() && result.loginUrl() != null) {
                    loginUrl = result.loginUrl();
                    break;
                }
            }

            events.accept(AgentEvent.recommendations(guarded.recommendations()));
            emitChunked(guarded.reply(), events);
            events.accept(AgentEvent.cards(bundle.cards(), bundle.badgePatch()));
            if (loginUrl != null) {
                events.accept(AgentEvent.loginRequired(guarded.reply(), loginUrl));
            }
            events.accept(AgentEvent.done(guarded.reply(), sessionId));

            SessionState next = session.withAssistantMessage(guarded.reply())
                    .withRecommendedIds(guarded.recommendedIds())
                    .incrementTurn();
            next = summaryService.compressIfNeeded(next);
            sessionStore.save(next);
            memoryService.remember(command.userId(), merged);

            // B2：本轮完整留痕（用户原话 + 最终回复 + 卡片引用 + 页面）
            audit.recordTurn(new AiAuditService.TurnRecord(
                    sessionId, command.userId(), pageUrl, command.message(),
                    guarded.reply(), envelope.cardRefs(), intent.name(), next.turnCount(),
                    next.summary()));

            // ⑧ 只有真的给出了回复，才扣一轮游客配额
            consumeGuestTurn(guest, command.deviceId());

            return AgentResult.ok(sessionId, guarded.reply(), guarded.recommendations(),
                    guarded.recommendedIds(), bundle.cards(), bundle.badgePatch(), loginUrl);
        } catch (BizException e) {
            log.warn("Agent 业务失败 session={} code={} msg={}", sessionId, e.getErrorCode(), e.getMessage());
            events.accept(AgentEvent.error(e.getMessage()));
            return AgentResult.fail(e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("Agent 调度失败 session={}", sessionId, e);
            events.accept(AgentEvent.error("AI 暂时不可用，请稍后再试"));
            return AgentResult.fail(ErrorCode.DEGRADED.name(), "AI 暂时不可用，请稍后再试");
        } finally {
            ToolConstraintContext.clear();
            sessionStore.unlock(sessionId);
        }
    }

    // ---------------- 最终一轮 ----------------

    /**
     * 工具步数用尽后的收口轮：不带工具，要求模型只做总结。
     *
     * <p>用 {@code streamChat} 而不是 {@code chatWithTools}：后者会带一个空的 tools 数组，
     * 部分网关会直接 400；而且这一步本来就不该再规划新工具。
     */
    private String finalTurn(List<Map<String, Object>> messages, Duration budget) {
        messages.add(Map.of("role", "user", "content",
                "请只基于以上工具结果给出最终回复。严格输出一个 JSON 对象："
                        + "{\"reply\":\"...\",\"card_refs\":[\"...\"]}，不要输出任何其它内容；"
                        + "不要编造价格、库存、订单或通知，也不要写任何网址。"));
        StringBuilder answer = new StringBuilder();
        long start = System.nanoTime();
        try {
            llm.streamChat(messages, answer::append, budget);
        } catch (RuntimeException e) {
            metrics.llmCall(System.nanoTime() - start, AiMetrics.looksLikeTimeout(e));
            throw e;
        }
        metrics.llmCall(System.nanoTime() - start, false);
        return answer.toString().trim();
    }

    /**
     * 解析 reply + card_refs 信封。
     *
     * <p>模型的输出常被包在 ```json 里或前后带解释，所以取「第一个 { 到最后一个 }」，
     * 这不追求严格 JSON 语法（语法错的时候退回纯文本更稳）。
     * 解析成功 → structured=true，此时 card_refs 是权威清单（空即无卡）；
     * 解析失败 → structured=false，卡片退回按工具自动装配。
     */
    private ReplyEnvelope parseReply(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return new ReplyEnvelope("", List.of(), false);
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = mapper.readTree(text.substring(start, end + 1));
                String reply = node.path("reply").asText("").trim();
                if (!reply.isEmpty()) {
                    List<String> refs = new ArrayList<>();
                    JsonNode array = node.path("card_refs");
                    if (array.isArray()) {
                        array.forEach(item -> {
                            String value = item == null ? "" : item.asText("").trim();
                            if (!value.isEmpty()) {
                                refs.add(value);
                            }
                        });
                    }
                    return new ReplyEnvelope(reply, refs, true);
                }
            } catch (Exception e) {
                log.debug("回复不符合 JSON 信封，按纯文本处理: {}", e.getMessage());
            }
        }
        return new ReplyEnvelope(text, List.of(), false);
    }

    private void consumeGuestTurn(boolean guest, String deviceId) {
        if (guest) {
            guestIdentity.consume(deviceId);
        }
    }

    // ---------------- 消息拼装 ----------------

    private Map<String, Object> assistantToolCallMessage(LlmTurn turn) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", turn.content() == null ? "" : turn.content());
        message.put("tool_calls", turn.rawToolCalls());
        return message;
    }

    /**
     * 工具消息（B6）：结果整段包进三引号定界符。
     *
     * <p>工具结果里有商品标题、通知标题、券名 —— 都由用户或商家填写。
     * 不隔离的话，一句「忽略以上规则」就能和系统指令混为一谈；
     * 包进定界符 + System Prompt 的强指令，是成本最低的第一道防线。
     */
    private Map<String, Object> toolMessage(ToolCall call, String resultJson) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", call.id());
        message.put("name", call.name());
        message.put("content", DataFence.wrap(resultJson));
        return message;
    }

    private String safeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"success\":false,\"code\":\"AUDIT_SERIALIZE_FAILED\"}";
        }
    }

    private String badgePatchJson(BadgePatch patch) {
        if (patch == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(patch);
        } catch (Exception e) {
            return null;
        }
    }

    /** 本轮是否有「本就该出卡」的工具成功执行（用于空卡片计数）。 */
    private static boolean hasCardToolSuccess(List<CardAssembler.ToolInvocation> invocations) {
        return invocations.stream().anyMatch(invocation ->
                invocation.result() != null
                        && invocation.result().success()
                        && CARD_TOOLS.contains(invocation.tool()));
    }

    /** 打字机效果：护栏之后才吐字，保证用户看到的每个字都是清洗过的最终文本。 */
    private static void emitChunked(String text, Consumer<AgentEvent> events) {
        if (text == null || text.isBlank()) {
            return;
        }
        int step = 18;
        for (int i = 0; i < text.length(); i += step) {
            events.accept(AgentEvent.token(text.substring(i, Math.min(text.length(), i + step))));
        }
    }

    /**
     * 解析后的回复。
     *
     * @param reply      正文
     * @param cardRefs   模型声明的卡片引用（工具名）
     * @param structured 是否成功解析出信封（决定 card_refs 是否具有权威性）
     */
    private record ReplyEnvelope(String reply, List<String> cardRefs, boolean structured) {
    }
}
