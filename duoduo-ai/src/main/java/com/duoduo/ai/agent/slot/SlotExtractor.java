package com.duoduo.ai.agent.slot;

import com.duoduo.ai.memory.SlotState;
import com.duoduo.ai.support.ConstraintParser;
import com.duoduo.ai.support.DashScopeClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位抽取（2026-10-28）：LLM 做语义抽取，规则做确定性兜底。
 *
 * <p>预算/品类继续用 {@link ConstraintParser}（数字错一眼能看出来，不能交给模型猜）；
 * 物种/生命阶段/过敏原/症状先由 LLM 结构化输出，失败时用关键词兜底，保证链路不断。
 */
@Service
public class SlotExtractor {

    private static final Logger log = LoggerFactory.getLogger(SlotExtractor.class);

    private static final String PROMPT = """
        你是宠物电商导购的槽位抽取器。把用户这句话抽成严格 JSON：
        {"species":"cat|dog|small_pet|bird|fish|reptile|other|null",
         "lifeStage":"BABY|ADULT|SENIOR|null",
         "breed":"品种或null",
         "allergens":["鸡肉","牛肉","谷物"...],
         "symptoms":["腹泻","呕吐"...],
         "urgency":"NORMAL|WATCH|RED_FLAG"}
        规则：
        1. 只抽用户明确说出的信息，不推测；没说就填 null 或空数组。
        2. 出现便血、持续呕吐、抽搐、呼吸困难、误食毒物、无法排尿、幼宠不吃不喝 → RED_FLAG。
        3. 只输出 JSON，不要解释。
        """;

    private static final Pattern ALLERGEN = Pattern.compile(
            "(?:对|不能吃|忌口|过敏(?:于)?)\\s*(鸡肉|牛肉|谷物|乳制品|鸡蛋|鱼肉|羊肉|大豆)");
    private static final List<String> SPECIES_WORDS = List.of("猫", "狗", "犬", "仓鼠", "兔", "龙猫", "鸟", "鹦鹉", "鱼", "爬", "龟");
    private static final List<String> SYMPTOMS = List.of(
            "拉稀", "腹泻", "软便", "呕吐", "便血", "黑便", "咳嗽", "打喷嚏", "过敏",
            "泪痕", "关节", "跛行", "不吃", "食欲差", "掉毛", "皮肤", "驱虫", "疫苗");

    private final DashScopeClient llm;
    private final ObjectMapper mapper;
    private final ConstraintParser constraintParser;

    public SlotExtractor(DashScopeClient llm, ObjectMapper mapper, ConstraintParser constraintParser) {
        this.llm = llm;
        this.mapper = mapper;
        this.constraintParser = constraintParser;
    }

    public SlotState extract(String message, SlotState current) {
        SlotState rule = ruleExtract(message);
        SlotState llmSlots = llmExtract(message);
        return combine(llmSlots, rule);
    }

    private SlotState combine(SlotState llm, SlotState rule) {
        if (llm == null) {
            return rule;
        }
        return new SlotState(
                first(llm.species(), rule.species()),
                first(llm.lifeStage(), rule.lifeStage()),
                first(llm.breed(), rule.breed()),
                rule.budgetMinCents() != null ? rule.budgetMinCents() : llm.budgetMinCents(),
                rule.budgetMaxCents() != null ? rule.budgetMaxCents() : llm.budgetMaxCents(),
                rule.categoryId() != null ? rule.categoryId() : llm.categoryId(),
                merge(llm.allergens(), rule.allergens()),
                merge(llm.symptoms(), rule.symptoms()),
                first(rule.urgency(), llm.urgency(), "NORMAL"),
                // 健康备注与称呼来自长期记忆，不由本轮消息抽取
                first(llm.healthNotes(), rule.healthNotes()),
                first(llm.nickname(), rule.nickname()));
    }

    private SlotState llmExtract(String message) {
        if (!llm.isConfigured()) {
            return null;
        }
        try {
            String raw = llm.chat(PROMPT, List.of(), message, 300);
            String json = extractJson(raw);
            if (json == null) {
                return null;
            }
            JsonNode n = mapper.readTree(json);
            return new SlotState(
                    text(n, "species"),
                    text(n, "lifeStage"),
                    text(n, "breed"),
                    null,
                    null,
                    null,
                    list(n, "allergens"),
                    list(n, "symptoms"),
                    first(text(n, "urgency"), "NORMAL"),
                    null,
                    null);
        } catch (Exception e) {
            log.warn("LLM 槽位抽取失败，使用规则兜底: {}", e.getMessage());
            return null;
        }
    }

    private SlotState ruleExtract(String message) {
        String text = message == null ? "" : message;
        ConstraintParser.Constraints c = constraintParser.parse(text);

        String species = null;
        if (text.contains("猫")) {
            species = "cat";
        } else if (text.contains("狗") || text.contains("犬")) {
            species = "dog";
        } else if (text.contains("仓鼠") || text.contains("兔") || text.contains("龙猫")) {
            species = "small_pet";
        } else if (text.contains("鸟") || text.contains("鹦鹉")) {
            species = "bird";
        } else if (text.contains("鱼")) {
            species = "fish";
        } else if (text.contains("爬") || text.contains("龟") || text.contains("蜥蜴")) {
            species = "reptile";
        }

        String lifeStage = null;
        if (text.contains("幼") || text.contains("奶糕") || text.contains("离乳")) {
            lifeStage = "BABY";
        } else if (text.contains("老年") || text.contains("高龄")) {
            lifeStage = "SENIOR";
        } else if (text.contains("成猫") || text.contains("成犬")) {
            lifeStage = "ADULT";
        }

        List<String> allergens = new ArrayList<>();
        Matcher m = ALLERGEN.matcher(text);
        while (m.find()) {
            allergens.add(m.group(1));
        }

        List<String> symptoms = new ArrayList<>();
        for (String s : SYMPTOMS) {
            if (text.contains(s)) {
                symptoms.add(s);
            }
        }
        String urgency = symptoms.stream().anyMatch(s -> s.equals("便血") || s.equals("黑便"))
                ? "RED_FLAG" : (symptoms.isEmpty() ? "NORMAL" : "WATCH");

        return new SlotState(
                species, lifeStage, null,
                c.budgetMin(), c.budgetMax(),
                c.categoryIds().isEmpty() ? null : c.categoryIds().get(0),
                allergens, symptoms, urgency,
                null, null);
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText(null);
    }

    private static List<String> list(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        v.forEach(x -> {
            if (x != null && !x.asText("").isBlank()) {
                out.add(x.asText());
            }
        });
        return out;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static List<String> merge(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (a != null) {
            set.addAll(a);
        }
        if (b != null) {
            set.addAll(b);
        }
        return List.copyOf(set);
    }
}
