package com.duoduo.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具参数的宽容读取（阶段二起统一使用）。
 *
 * <p>模型给到的 JSON 经常是「形状对、类型飘」：数字写成字符串、布尔写成 "true"、
 * 该省略的字段写成 "null"。这里集中做一次归一，避免每个工具各写一套
 * {@code hasNonNull} 判断 —— 那种复制粘贴最容易在某个工具里漏掉，
 * 于是同一个模型在不同工具上表现忽好忽坏。
 *
 * <p>只做**类型归一**，不做业务校验：业务合法性（枚举、长度、权限）留在各 Service，
 * 因为那里才知道真实规则。
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    /** 取文本；缺省、空白、"null" 一律视为未提供。 */
    public static String text(JsonNode args, String name) {
        if (args == null || !args.hasNonNull(name)) {
            return null;
        }
        String value = args.path(name).asText("").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }

    /** 取文本并转大写：用于 status / category 这类枚举型参数，避免 "pending_payment" 打进 URL。 */
    public static String textUpper(JsonNode args, String name) {
        String value = text(args, name);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    public static String textOr(JsonNode args, String name, String fallback) {
        String value = text(args, name);
        return value == null ? fallback : value;
    }

    /** 取整数并夹到 [min, max]：上限由工具 schema 声明，这里再夹一次防止模型越界。 */
    public static int intOr(JsonNode args, String name, int fallback, int min, int max) {
        if (args == null || !args.hasNonNull(name)) {
            return fallback;
        }
        JsonNode node = args.path(name);
        int value;
        if (node.isNumber()) {
            value = node.asInt(fallback);
        } else {
            // 模型常把数字写成字符串 "5"；JsonNode.asInt(default) 对文本节点只会返回 default
            try {
                value = Integer.parseInt(node.asText("").trim());
            } catch (NumberFormatException e) {
                value = fallback;
            }
        }
        return Math.max(min, Math.min(max, value));
    }

    /** 取布尔：兼容 true / "true" / 1 三种模型常见写法。 */
    public static boolean boolOr(JsonNode args, String name, boolean fallback) {
        if (args == null || !args.hasNonNull(name)) {
            return fallback;
        }
        JsonNode node = args.path(name);
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String value = node.asText("").trim();
        if (value.isEmpty()) {
            return fallback;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /** 对象属性 → 字符串参数表（navigate_to_page 的 params 用）。 */
    public static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            String text = value.asText("").trim();
            if (!text.isEmpty()) {
                out.put(entry.getKey(), text);
            }
        });
        return out;
    }
}
