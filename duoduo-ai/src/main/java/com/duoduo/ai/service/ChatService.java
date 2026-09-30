package com.duoduo.ai.service;

import com.duoduo.ai.client.ProductSearchClient;
import com.duoduo.ai.client.UserProfileClient;
import com.duoduo.ai.client.dto.PetBrief;
import com.duoduo.ai.client.dto.ProductCandidate;
import com.duoduo.ai.dto.AiDtos.ChatResponse;
import com.duoduo.ai.dto.AiDtos.ChatTurn;
import com.duoduo.ai.dto.AiDtos.ClarifyOption;
import com.duoduo.ai.dto.AiDtos.ClarifyView;
import com.duoduo.ai.dto.AiDtos.ProductRecommendation;
import com.duoduo.ai.support.CategoryCatalog;
import com.duoduo.ai.support.ConstraintParser;
import com.duoduo.ai.support.MockReply;
import com.duoduo.ai.support.PetContext;
import com.duoduo.ai.support.PolicyKnowledge;
import com.duoduo.ai.support.Suggestions;
import com.duoduo.ai.support.RecallDiversifier;
import com.duoduo.ai.support.DashScopeClient;
import com.duoduo.ai.support.ReplyGuard;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 导购对话编排（A9；第五十七轮补「约束 → 澄清 → 召回 → 组织 → 护栏 → 出口」）。
 *
 * <h2>流水线（每一步都可单独测试，这是能长期维护的关键）
 * <pre>
 *   ① 出口预判：是不是「我答不了」的问题（物流/退换/优惠…）→ 如实说 + 给入口，不调模型
 *   ② 约束解析：预算上下界、品类（规则解析，不押在模型身上）
 *   ③ 召回：带约束去商品域取候选（**约束在 SQL 里生效**，超预算的商品根本进不来）
 *   ④ 澄清判断：关键信息全缺且没澄清过 → 反问 1 次，给可点选项
 *   ⑤ 组织：mock 规则拼装 / LLM 生成话术 + 结构化 id
 *   ⑥ 护栏：合规词与价格一致性过一遍出口（命中即换成事实版回复）
 * </pre>
 *
 * <h2>「不得编造」这条底线没有变
 * 模型回的仍然是**商品 id**，服务端拿 id 到候选里**反查**，不在候选里的一律丢弃 ——
 * 编出来的 id 天然落空。这不是事后用正则抓幻觉，而是让幻觉无处落脚。
 * 第五十七轮新增的约束过滤是从**另一端**加固同一件事：连「符合条件但超预算」的商品
 * 都进不了候选池，模型也就没机会推错。
 *
 * <h2>降级三形态（见 {@code AiApplication} 类注释）
 * mock / unconfigured 503 / 超时 503。mock 的回复是从**真实候选**拼出来的，
 * 与真实模式共享召回链路 —— 因此冒烟在 mock 下验证的「推荐不得超预算」，
 * 对真实模式同样成立（这正是第五十七轮把那条断言改成无条件必查的依据）。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * 召回条数：向商品域<b>多要一些</b>再在本地去重（第五十八轮）。
     *
     * <p>为什么要多要：同款不同规格会各占一行，去重之后剩下的才是真正不同的商品。
     * 按「去重后仍够用」反推，原始取 12、去重后留 8 —— 与去重前的候选位一致，
     * 用户看到的推荐数量不变，但**款数变多了**。
     */
    private static final int RECALL_RAW_LIMIT = 12;
    private static final int RECALL_KEEP_LIMIT = 8;

    /**
     * 系统提示：把「不得编造」写成硬约束，并给出价格口径（分）与引导话术要求（验收②）。
     *
     * <p>要点：**候选列表是「可选集合」而不是「必推清单」**。最早只说「只能推荐候选里的商品」，
     * 模型会理解成「把候选都给出去」，于是用户说「预算 200 以内」而候选里混着 328 元的商品时，
     * 回复正文只挑对的、`recommendations` 却把超预算的也渲染成卡片 —— 正文与卡片互相打脸。
     * 现在明确要求「先按用户条件筛，只推真正符合的」，并由模型显式产出选择结果。
     *
     * <p><b>第五十七轮新增第 7、8 条</b>：① 禁止绝对化用语（广告法口径）；
     * ② **明确给出「答不了」的出口** —— 行业共识里「给模型一个说不会的出口」是
     * 最有效的抑幻手段，比任何「不要编造」的叮嘱都管用。
     */
    private static final String SYSTEM_PROMPT = """
            你是宠物商店「多多宠物」的导购助手「多多」。规则：
            1. 【候选商品列表】是**可选集合**，不是必推清单 —— 先按用户的条件（品类、年龄、用途、预算等）筛一遍，
               只把真正符合的挑出来推荐，并说明为什么符合；不符合的**不要出现在推荐里**；
            2. 只能推荐【候选商品列表】里出现的商品，绝不虚构不存在的商品、价格或促销；
            3. 价格单位是「分」，请换算成「元」表述（例如 19900 分 = 199 元）；
               提到的任何价格都必须是候选里真实存在的数字，不要自己算总价、不要编折扣价；
            4. 用户提到下单 / 加购 / 购买时，引导用户登录后到对应商品页操作，不要承诺代下单；
            5. 没有符合条件（或完全没有）候选时如实说明，并请用户换个描述，不要硬推；
            6. 回复简洁友好，控制在 150 字以内；
            7. 禁止使用「绝对」「保证」「100%」「最低价」这类绝对化或承诺性说法 ——
               说事实、给理由，不要打包票；
            8. 用户问的是物流、退换货、发票、优惠券这类你**没有依据**回答的问题时，
               直接说明你查不到并请用户去对应页面查看，**不要凭常识猜**。
            【候选商品列表】
            """;

    /**
     * 「我答不了」的问题识别（第五十七轮 · M5 失败出口分类）。
     *
     * <p>为什么要在调模型之前拦：这类问题**没有可依据的数据源**（本轮不含政策知识库），
     * 交给模型只会得到一段听起来很像真的、但可能是编的答案 —— 售后政策说错是要担责任的。
     * 所以这里直接给出「我查不到 + 你去哪儿看」，既诚实又省配额。
     *
     * <p>顺带的好处：把「没货」与「我答不了」分开。此前两者都落到
     * 「暂时没找到匹配的商品，换个描述试试」—— 用户会一遍遍换说法，
     * 而他问的其实是一件永远不会有结果的事。
     */
    private static final List<OffTopicRule> OFF_TOPIC_RULES = List.of(
            new OffTopicRule(
                    List.of("物流", "快递", "发货了", "到哪了", "什么时候到", "签收", "运单"),
                    "包裹进度我这边查不到，去「我的 → 我的订单 → 订单详情」就能看到完整物流轨迹哦。"),
            new OffTopicRule(
                    List.of("退换", "退货", "退款", "换货", "售后", "保修"),
                    "售后规则我给不了准数，怕说错。你可以在「我的 → 我的订单」里对具体订单发起售后，平台会按规则处理。"),
            new OffTopicRule(
                    List.of("运费", "包邮", "邮费", "发票", "开票"),
                    "运费和发票的规则请以结算页／订单页显示为准，我这边看不到你的订单，不方便替你判断。"),
            new OffTopicRule(
                    List.of("优惠券", "有券吗", "折扣", "满减", "领券", "优惠"),
                    "券我查不到你账户里的具体情况，去首页的「领券中心」能领到当前可用的券。"),
            new OffTopicRule(
                    List.of("怎么支付", "支付失败", "付款失败", "付不了"),
                    "支付相关的问题请到结算页重试；如果一直失败，把订单号发给我，我帮你转人工客服。")
    );

    /**
     * 问候 / 身份识别（第五十八轮 · 用户实测报障）。
     *
     * <p><b>报障现场</b>：发「你好，你是谁」，得到的是「暂时没找到匹配的商品」。
     * 这句话在技术上「没说错」（商品库里确实没有叫「你好你是谁」的东西），
     * 但它答非所问 —— 用户问的是「你是谁」，系统按「你想买什么」处理了。
     * 链路是：没有问候/身份分支 → 约束解析为空 → 用整句话当关键词召回 → 空候选 →
     * mock 分支把空候选拼成「没找到」。
     * 这类缺陷的可恨之处在于它<b>没有任何报错</b>，只有真人翻开来看才知道不对。
     *
     * <p><b>为什么做成规则而不是交给模型</b>：这两类问题有<b>唯一正确答案且不需要任何外部数据</b>
     * （我是谁、我能做什么），交给模型一是花配额，二是模型可能顺手编一段功能介绍
     * （比如声称能查物流）—— 那正好踩在「不得编造」的底线反面。
     *
     * <p><b>为什么先判「去掉问候词之后还剩什么」</b>：直接拿「你好」做关键词会把
     * 「你好，我想买猫粮」也拦下来 —— 那就是用一条问候规则掐掉了真实购物意图。
     * 剥掉问候词与语气词后：剩下为空 = 纯问候；剩下命中身份问法 = 身份问题；
     * 剩下还有别的内容（「我想买猫粮」）则<b>一律放行</b>给正常导购链路。
     */
    private static final List<String> GREETING_TOKENS = List.of(
            "你好", "您好", "你们好", "hello", "hi", "嗨", "哈喽", "哈啰",
            "在吗", "在么", "在不在", "早上好", "中午好", "晚上好", "早");

    /** 语气词：剥完问候词后只剩这些，仍然算纯问候（「你好呀」） */
    private static final List<String> FILLER_TOKENS = List.of("啊", "呀", "哦", "噢", "呢", "吧", "哈");

    /**
     * 身份 / 能力问法。
     *
     * <p>刻意<b>不含</b>「怎么用」「帮助」「有什么功能」这类词：「优惠券怎么用」里的「怎么用」
     * 属于优惠券出口而不是自我介绍；「这个猫爬架有什么功能」问的是商品不是助手 ——
     * 带上「你」字（你能做什么 / 你是谁）才是问助手，这条区分靠的就是那个「你」。
     * 顺序上也让「答不了」出口（物流/退款/优惠/支付）先跑，避免抢答 ——
     * 那几条才是用户真正要的答案。漏判一句奇怪的问候只让用户多问一次，
     * 误判一句真实购物意图则会让用户觉得这助手听不懂人话，两者代价不对等。
     */
    private static final List<String> IDENTITY_PATTERNS = List.of(
            "你是谁", "你叫啥", "你叫什么", "你的名字", "你是什么", "你是做什么的",
            "介绍一下你", "自我介绍", "你能做什么", "你能干什么", "你会什么",
            "你是机器人", "你是人工", "你是ai");

    private static final String GREETING_REPLY =
            "你好，我是多多宠物商店的 AI 导购「多多」。想买什么直接说就行，"
                    + "比如「幼猫粮 200 元以内」「适合柯基的耐咬玩具」。";

    private static final String IDENTITY_REPLY =
            "我是多多宠物商店的 AI 导购「多多」，负责帮你挑商品：按预算、品类、宠物年龄去库里找"
                    + "符合条件的，再说明为什么合适。物流进度、退款售后、优惠券这些我查不到，"
                    + "要去「我的订单」或首页「领券中心」看。想买什么直接告诉我就好。";

    private final ProductSearchClient productSearchClient;
    private final DashScopeClient dashScopeClient;
    private final ConstraintParser constraintParser;
    private final CategoryCatalog categoryCatalog;
    private final ReplyGuard replyGuard;
    private final UserProfileClient userProfileClient;
    private final boolean mockEnabled;

    public ChatService(ProductSearchClient productSearchClient,
                       DashScopeClient dashScopeClient,
                       ConstraintParser constraintParser,
                       CategoryCatalog categoryCatalog,
                       ReplyGuard replyGuard,
                       UserProfileClient userProfileClient,
                       @Value("${duoduo.ai.mock-enabled:true}") boolean mockEnabled) {
        this.productSearchClient = productSearchClient;
        this.dashScopeClient = dashScopeClient;
        this.constraintParser = constraintParser;
        this.categoryCatalog = categoryCatalog;
        this.replyGuard = replyGuard;
        this.userProfileClient = userProfileClient;
        this.mockEnabled = mockEnabled;
    }

    /**
     * 一轮对话。
     *
     * @param clarifiedOnce 前端告知「之前已经澄清过一轮」→ 本轮不再反问（最多澄清 1 次）
     * @param userId        当前登录用户（**可为 null**：AI 允许匿名问答）；
     *                      有值时按宠物档案做个性化（第五十八轮 S1），详见 {@link PetContext}
     */
    public ChatResponse chat(String message, List<ChatTurn> history, boolean clarifiedOnce, Long userId) {
        // ⓪ 宠物档案（可选增强）：取不到就当没有，绝不影响能不能答
        List<PetBrief> pets = loadPets(userId);
        // ①a 政策知识库（第五十八轮 S2）：退货 / 物流 / 仲裁这类问题**有确定答案**，
        //     答案就在系统流程里 —— 先于「答不了」出口命中，别把知道的说成不知道
        String policy = PolicyKnowledge.answerFor(message);
        if (policy != null) {
            log.info("命中政策知识库，未调用模型");
            return new ChatResponse(policy, List.of(), mockEnabled ? "mock" : "llm", null, List.of());
        }

        // ①b 出口预判：真的答不了的问题不调模型（诚实 + 省配额，且避免编造售后政策）
        String offTopic = offTopicReply(message);
        if (offTopic != null) {
            log.info("命中「答不了」出口，未调用模型");
            return new ChatResponse(offTopic, List.of(), mockEnabled ? "mock" : "llm", null, List.of());
        }

        // ①c 问候 / 身份：有唯一正确答案、不需要外部数据，规则直答
        //    （放在「答不了」之后：优惠券怎么用归优惠券出口，不归自我介绍）
        String social = greetingOrIdentityReply(message);
        if (social != null) {
            log.info("命中「问候/身份」出口，未调用模型");
            return new ChatResponse(social, List.of(), mockEnabled ? "mock" : "llm", null, List.of());
        }

        // ② 约束解析：预算 + 品类（规则解析，确定性优先）
        ConstraintParser.Constraints constraints = constraintParser.parse(message);

        // ③ 召回（带约束；约束在商品域 SQL 里生效）
        //    宠物档案只在「用户没指定品类」时补进关键词 —— 见 PetContext 类注释第 1 条
        List<ProductCandidate> candidates = recall(message, history, constraints,
                constraints.categoryIds().isEmpty() ? PetContext.recallBoost(pets) : "");
        boolean personalized = !pets.isEmpty() && constraints.categoryIds().isEmpty()
                && !PetContext.recallBoost(pets).isEmpty();

        // ④ 澄清判断：关键信息全缺 + 没澄清过 + 有可选选项 → 反问 1 次
        if (needsClarify(message, constraints, candidates, clarifiedOnce)) {
            List<ClarifyOption> options = clarifyOptions();
            if (!options.isEmpty()) {
                log.info("信息不足，发起澄清（选项 {} 个）", options.size());
                return new ChatResponse(
                        "我先确认一下再帮你挑：你想买哪一类？",
                        List.of(),
                        mockEnabled ? "mock" : "llm",
                        new ClarifyView("你想买哪一类？", options),
                        List.of());
            }
            // 类目取不到 → 不问一个没有选项的问题（那等于把难题丢回给用户）
        }

        // ⑤ 组织回复（mock / LLM）
        //    对比意图**不进模型**：对比结论最容易编出「这款更适合你家猫」这类无据判断，
        //    而它直接影响购买决定 —— 一律用候选里的客观字段并列（见 Suggestions.compareReply）
        ChatResponse draft;
        String compare = Suggestions.isCompare(message)
                ? Suggestions.compareReply(candidates, 3) : null;
        if (compare != null) {
            draft = new ChatResponse(compare, toRecommendations(topN(candidates, 3)),
                    mockEnabled ? "mock" : "llm", null, List.of());
        } else {
            draft = mockEnabled
                    ? mockReply(candidates, personalized)
                    : llmReply(message, history, candidates, constraints, pets);
        }

        // ⑥ 护栏：合规词与价格一致性过一遍出口（命中即换成事实版回复）
        //
        // 一律以护栏返回的 reply 为准：未拦截时它就是原文，拦截时是事实版。
        // 写成「blocked ? verdict.reply() : draft.reply()」也能跑，但那是把同一句话的
        // 出处分成两处 —— 将来有人给护栏加第三种处理（比如脱敏）时，
        // 只要漏改这一行，护栏的新行为就只在「拦截」时生效，属于静默失效。
        ReplyGuard.Verdict verdict = replyGuard.inspect(draft.reply(), candidates);
        String reply = verdict.reply();

        // ⑦ 预算没听懂 → 必须如实告知（绝不能当成「用户没提预算」静默放过）
        if (constraints.budgetUnparsed()) {
            reply = "（我没听清你的预算数字，这次没按预算筛）" + reply;
        }
        // ⑧ 相关追问：由当前候选推导（候选为空时一条都不给 —— 见 Suggestions 类注释）
        List<String> followUps = Suggestions.followUps(candidates, constraints.hasBudget(), 3);
        return new ChatResponse(reply, draft.recommendations(), draft.mode(), null, followUps);
    }

    // ---------------- ① 出口预判 ----------------

    /** 命中「答不了」规则则返回出口话术，否则 null */
    private static String offTopicReply(String message) {
        String text = message == null ? "" : message;
        for (OffTopicRule rule : OFF_TOPIC_RULES) {
            for (String kw : rule.keywords()) {
                if (text.contains(kw)) {
                    return rule.reply();
                }
            }
        }
        return null;
    }

    /**
     * 问候 / 身份识别：命中则返回直答话术，否则 {@code null}（放行给正常导购链路）。
     *
     * <p>「剥掉问候词看还剩什么」是关键一步 —— 见 {@link #GREETING_TOKENS} 的注释。
     * 宁可漏判（一句奇怪的问候被当成购物意图，用户再问一次）也不要误判
     * （一句真实购物意图被当成问候，用户会觉得这助手听不懂人话）。
     */
    private static String greetingOrIdentityReply(String message) {
        String rest = stripTokens(normalize(message), GREETING_TOKENS);
        if (rest.isEmpty()) {
            return GREETING_REPLY;
        }
        rest = stripTokens(rest, FILLER_TOKENS);
        if (rest.isEmpty()) {
            return GREETING_REPLY;
        }
        for (String pattern : IDENTITY_PATTERNS) {
            if (rest.contains(pattern)) {
                return IDENTITY_REPLY;
            }
        }
        return null;
    }

    /** 归一化：小写 + 去空白 + 去标点（标点不该影响「这是一句问候」的判断） */
    private static String normalize(String message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (Character.isWhitespace(c) || PUNCTUATION.indexOf(c) >= 0) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static final String PUNCTUATION = ",.!?;:，。！？、；：~～\"'「」『』…—-";

    /**
     * 反复剥离词表中的词，直到没有变化。
     *
     * <p>为什么要循环：用户会叠加（「你好你好」「嗨呀」），一次替换剥不干净，
     * 剩下的「你好」会让纯问候判不出来。
     */
    private static String stripTokens(String text, List<String> tokens) {
        String cur = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String t : tokens) {
                int at = cur.indexOf(t);
                if (at >= 0) {
                    cur = cur.substring(0, at) + cur.substring(at + t.length());
                    changed = true;
                }
            }
        }
        return cur;
    }

    /**
     * 取宠物档案（best-effort）。
     *
     * <p>失败只记 WARN 并当「没有档案」继续：个性化是可选增强，
     * 不能因为用户域抖动就让「推荐个猫粮」都答不出来。
     */
    private List<PetBrief> loadPets(Long userId) {
        if (userId == null || userProfileClient == null) {
            return List.of();
        }
        try {
            var resp = userProfileClient.petProfiles(userId);
            List<PetBrief> list = resp == null ? null : resp.data();
            return list == null ? List.of() : list;
        } catch (Exception ex) {
            log.warn("宠物档案取不到，按无档案继续（个性化为可选增强）: {}", ex.getMessage());
            return List.of();
        }
    }

    // ---------------- ④ 澄清判断 ----------------

    /**
     * 是否需要澄清。
     *
     * <p>三个条件同时满足才问，缺一不问（问多了就是问卷）：
     * <ol>
     *   <li><b>没澄清过</b>：一次对话最多反问一次；</li>
     *   <li><b>没有任何硬约束</b>：既没说品类也没说预算 —— 有任一条都能直接去查；</li>
     *   <li><b>确实问不出结果</b>：召回为空，**或者**用户这句极短（≤6 字，如「买点吃的」「推荐一下」）。
     *       用「召回为空」做主要判据是有意的 —— 它来自真实商品库的信号，
     *       比「这句话看起来含糊吗」这种主观判断可靠得多。</li>
     * </ol>
     */
    private static boolean needsClarify(String message, ConstraintParser.Constraints constraints,
                                        List<ProductCandidate> candidates, boolean clarifiedOnce) {
        if (clarifiedOnce) {
            return false;
        }
        if (constraints.hasBudget() || !constraints.categoryIds().isEmpty()) {
            return false;
        }
        String trimmed = message == null ? "" : message.trim();
        return candidates.isEmpty() || trimmed.length() <= 6;
    }

    private List<ClarifyOption> clarifyOptions() {
        return categoryCatalog.clarifyOptions(4).stream()
                .map(c -> new ClarifyOption(c.name(), c.name()))
                .toList();
    }

    // ---------------- ③ 召回 ----------------

    private List<ProductCandidate> recall(String message, List<ChatTurn> history,
                                          ConstraintParser.Constraints constraints, String petBoost) {
        String categoryIds = constraints.categoryIds().isEmpty() ? null
                : constraints.categoryIds().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(","));
        try {
            // 多取一些再去重：同款不同规格会各占一行，召回位被同款吃掉一半
            // （第五十八轮 AI 加强项：去重与多样性，见 RecallDiversifier 类注释）
            var resp = productSearchClient.candidates(
                    recallKeyword(message, history, petBoost), RECALL_RAW_LIMIT,
                    constraints.budgetMin(), constraints.budgetMax(), categoryIds);
            List<ProductCandidate> list = resp == null ? null : resp.data();
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            return RecallDiversifier.diversify(list, RECALL_KEEP_LIMIT);
        } catch (Exception ex) {
            // 商品域取不到 → 503，绝不用空候选硬答（空候选喂给模型更容易诱导它编）
            log.error("候选召回失败: {}", ex.getMessage());
            throw new BizException(ErrorCode.DEGRADED, "商品服务暂时不可用，请稍后再试");
        }
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
        return recallKeyword(message, history, "");
    }

    /** {@code petBoost}：宠物档案补充的检索词（没有时传空串），只在未指定品类时由调用方传入 */
    private static String recallKeyword(String message, List<ChatTurn> history, String petBoost) {
        StringBuilder sb = new StringBuilder(message);
        if (petBoost != null && !petBoost.isBlank() && sb.indexOf(petBoost) < 0) {
            sb.append(' ').append(petBoost);
        }
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

    // ---------------- ⑤ 组织 ----------------

    /**
     * mock 回复（第五十八轮：改走 {@link MockReply} 自己的话术）。
     *
     * <p><b>刻意不再复用 {@link ReplyGuard#factsReply}</b>：那是护栏命中后的替换话术，
     * 拿它当 mock 的回复等于让护栏检查自己的输出 —— 结构上永远合规，
     * 于是「mock 形态下护栏到底拦不拦得住」这件事<b>从来没被验证过</b>。
     * 现在 mock 有自己的措辞（也自己带价格），护栏的两条判定在 mock 下真正可达，
     * 命中时同样回退到事实版（见 {@link #chat} 第 ⑥ 步）。
     */
    private static ChatResponse mockReply(List<ProductCandidate> candidates, boolean personalized) {
        String reply = MockReply.compose(candidates);
        // 只有真的用了档案才说用了 —— 夸口「按你家宠物挑的」而实际没用，是另一种编造
        if (personalized && !candidates.isEmpty()) {
            reply = "（已结合你档案里的宠物一起筛）" + reply;
        }
        return new ChatResponse(reply, toRecommendations(topN(candidates, 3)), "mock", null, List.of());
    }

    /**
     * LLM 形态：一次调用同时产出「给用户看的话术」与「真正被选中的商品 id」。
     *
     * <p>为什么让模型回 id 而不是靠正文文本反查：正文是自然语言，拿它去比对商品标题等于
     * 用字符串相似度猜「模型到底推荐了哪个」，是幻觉检测的反面教材。让模型直接给出结构化的
     * id 列表，再由服务端**用 id 到候选里反查**（{@link #selectByIds}）——
     * id 不在候选里的一律丢弃，模型就算编了 id 也拼不出不存在的商品。
     */
    private ChatResponse llmReply(String message, List<ChatTurn> history,
                                  List<ProductCandidate> candidates,
                                  ConstraintParser.Constraints constraints,
                                  List<PetBrief> pets) {
        if (candidates.isEmpty()) {
            // 无候选时也如实说没有 —— 空候选喂给模型反而容易诱导它编
            return new ChatResponse(
                    "暂时没找到匹配的商品，麻烦换个描述试试，比如「幼犬狗粮」或「猫玩具」。",
                    List.of(), "llm", null, List.of());
        }
        String prompt = SYSTEM_PROMPT + candidatesJson(candidates) + constraintBlock(constraints)
                + PetContext.promptBlock(pets) + """

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
            return new ChatResponse(reply, List.of(), "llm", null, List.of());
        }
        return new ChatResponse(parsed.reply(), toRecommendations(parsed.selected()), "llm", null, List.of());
    }

    /**
     * 把「服务端已经应用的约束」告诉模型。
     *
     * <p>不写这一段会怎样：候选已经按 200 元筛过了，但模型不知道，
     * 于是它可能在回复里说「这些都在 200 元以内哦，不过我没有你的预算信息」——
     * 一句话暴露两处不一致。约束既然是服务端落实的，就要作为**事实**同步给模型。
     */
    private static String constraintBlock(ConstraintParser.Constraints constraints) {
        if (!constraints.hasBudget() && constraints.categoryNames().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n【已生效的筛选条件（服务端已执行，候选里只剩符合的）】\n");
        if (constraints.budgetMax() != null) {
            sb.append("- 预算上限：").append(yuan(constraints.budgetMax())).append(" 元\n");
        }
        if (constraints.budgetMin() != null) {
            sb.append("- 预算下限：").append(yuan(constraints.budgetMin())).append(" 元\n");
        }
        if (!constraints.categoryNames().isEmpty()) {
            sb.append("- 品类：").append(String.join("、", constraints.categoryNames())).append("\n");
        }
        return sb.toString();
    }

    private static String yuan(Long cents) {
        if (cents == null) {
            return "";
        }
        long abs = Math.abs(cents);
        return abs % 100 == 0 ? String.valueOf(cents / 100)
                : (cents / 100) + "." + String.format("%02d", abs % 100);
    }

    /** LLM 回复的解析结果：话术正文 + 被选中的候选（已按 id 反查回真实商品） */
    private record LlmReply(String reply, List<ProductCandidate> selected, List<Long> ids) {
    }

    /** 出口规则：关键词组 → 出口话术 */
    private record OffTopicRule(List<String> keywords, String reply) {
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
