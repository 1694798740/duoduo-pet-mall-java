package com.duoduo.ai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具 JSON Schema（发给 LLM，也用于服务端必填校验）。
 */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final JsonNode SEARCH_PRODUCTS = parse("""
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "用户需求改写后的检索词"},
            "species": {"type": "string", "enum": ["cat","dog","small_pet","bird","fish","reptile","other"]},
            "lifeStage": {"type": "string", "enum": ["BABY","ADULT","SENIOR"]},
            "categoryId": {"type": "string"},
            "budgetMin": {"type": "integer", "description": "单位：分"},
            "budgetMax": {"type": "integer", "description": "单位：分"},
            "excludeAllergens": {"type": "array", "items": {"type": "string"}},
            "inStockOnly": {"type": "boolean", "default": true},
            "limit": {"type": "integer", "minimum": 1, "maximum": 20, "default": 8}
          },
          "required": ["query"]
        }
        """);

    public static final JsonNode GET_PRODUCT_DETAIL = parse("""
        {
          "type": "object",
          "properties": {
            "spuId": {"type": "string"},
            "skuId": {"type": "string"}
          },
          "required": ["spuId"]
        }
        """);

    public static final JsonNode CHECK_STOCK = parse("""
        {
          "type": "object",
          "properties": {
            "spuId": {"type": "string"},
            "skuId": {"type": "string"},
            "quantity": {"type": "integer", "minimum": 1, "default": 1}
          },
          "required": ["spuId","skuId"]
        }
        """);

    public static final JsonNode RECOMMEND_PRODUCTS = parse("""
        {
          "type": "object",
          "properties": {
            "spuIds": {
              "type": "array",
              "items": {"type": "string"},
              "minItems": 1,
              "maxItems": 3,
              "description": "只从工具返回的商品里选，按推荐优先级排序"
            }
          },
          "required": ["spuIds"]
        }
        """);

    public static final JsonNode ADD_TO_CART = parse("""
        {
          "type": "object",
          "properties": {
            "spuId": {"type": "string"},
            "skuId": {"type": "string"},
            "quantity": {"type": "integer", "minimum": 1, "maximum": 99},
            "confirmToken": {"type": "string", "description": "服务端二次确认令牌"}
          },
          "required": ["spuId","skuId","quantity"]
        }
        """);

    public static final JsonNode HANDOFF_TO_HUMAN = parse("""
        {
          "type": "object",
          "properties": {
            "reason": {"type": "string", "enum": ["MEDICAL_RED_FLAG","USER_REQUEST","TOOL_FAILURE","COMPLAINT"]},
            "summary": {"type": "string", "maxLength": 1000},
            "urgency": {"type": "string", "enum": ["NORMAL","HIGH"]}
          },
          "required": ["reason","summary","urgency"]
        }
        """);

    /**
     * 我的通知（阶段二 · 全能管家）。
     *
     * <p>{@code markRead} 默认 true：用户问「我有什么通知」时，AI 把**展示出来的那几条**
     * 标为已读是符合直觉的（他确实看到了）；但只作用于这 N 条具体 id，
     * 绝不做「整个列表批量已读」—— 那会把用户还没看过的通知也一起消掉红点。
     *
     * <p>{@code limit} 上限 10：通知卡片是「摘要」，不是通知中心。
     * 让模型一次拿 50 条，既超出卡片可读长度，也会把没展示的条目标成已读。
     */
    public static final JsonNode GET_USER_NOTIFICATIONS = parse("""
        {
          "type": "object",
          "properties": {
            "category": {
              "type": "string",
              "enum": ["ORDER","FRIEND","MERCHANT","SYSTEM","ANNOUNCEMENT"],
              "description": "通知分类；不传表示全部分类"
            },
            "read": {
              "type": "string",
              "enum": ["UNREAD","READ","ALL"],
              "default": "UNREAD",
              "description": "读状态筛选；默认只看未读。用户问「最近的通知」时可传 ALL"
            },
            "limit": {
              "type": "integer", "minimum": 1, "maximum": 10, "default": 5,
              "description": "返回条数；同时也是本次允许标记已读的最大条数"
            },
            "markRead": {
              "type": "boolean", "default": true,
              "description": "是否把本次展示的通知标记为已读；用户只是查询不想改变状态时传 false"
            }
          }
        }
        """);

    /** 我的券包：只返回已领取的券（含真实模板 ID 与金额），不返回领券中心的可领模板。 */
    public static final JsonNode GET_USER_COUPONS = parse("""
        {
          "type": "object",
          "properties": {
            "status": {
              "type": "string",
              "enum": ["UNUSED","LOCKED","USED","EXPIRED"],
              "description": "券状态；不传表示全部。用户问「有什么可用的券」时传 UNUSED"
            },
            "limit": {
              "type": "integer", "minimum": 1, "maximum": 20, "default": 10,
              "description": "返回条数上限"
            }
          }
        }
        """);

    /**
     * 我的订单。
     *
     * <p>{@code keyword} 走服务端包含匹配（订单号或商品标题），不要把用户整句话塞进来 ——
     * 描述里明确这一点，否则模型容易把「我上周买的猫粮到哪了」整句当关键词。
     */
    public static final JsonNode GET_USER_ORDERS = parse("""
        {
          "type": "object",
          "properties": {
            "status": {
              "type": "string",
              "enum": ["PENDING_PAYMENT","PAID","SHIPPED","COMPLETED","CLOSED","REFUNDED"],
              "description": "订单状态；不传表示全部。用户问「待付款的订单」时传 PENDING_PAYMENT"
            },
            "keyword": {
              "type": "string", "maxLength": 64,
              "description": "订单号或商品标题关键词（只要关键词本身，不要整句）"
            },
            "limit": {
              "type": "integer", "minimum": 1, "maximum": 20, "default": 5,
              "description": "返回条数上限"
            }
          }
        }
        """);

    /**
     * 页面导航。
     *
     * <p>{@code feature} 不用 enum 是刻意的：功能键的唯一权威是后端的 RouteRegistry，
     * 在 Schema 里抄一份 27 个键，改路由时必然漏改一处；这里只给约束与示例，
     * 服务端遇到未知键会返回可用键清单，模型据此重试即可。
     */
    public static final JsonNode NAVIGATE_TO_PAGE = parse("""
        {
          "type": "object",
          "properties": {
            "feature": {
              "type": "string",
              "description": "功能键，必须是服务端已注册的功能，例如 HOME、SEARCH、PRODUCT_DETAIL、CART、CHECKOUT、ORDERS、ORDER_DETAIL、AFTER_SALES、AFTER_SALE_DETAIL、LOGISTICS、NOTIFICATIONS、COUPONS、COINS、FAVORITES、HISTORY、PETS、PROFILE、SUPPORT、SECKILL、FLEA_MARKET、FLEA_PUBLISH、FLEA_MINE、MESSAGES、SHARE_CIRCLE、ADDRESSES、LOGIN、AI。不要自造键名"
            },
            "params": {
              "type": "object",
              "additionalProperties": {"type": "string"},
              "description": "该功能的路径参数，例如 ORDER_DETAIL 需要 orderNo，PRODUCT_DETAIL 需要 spuId，AFTER_SALE_DETAIL 需要 id"
            }
          },
          "required": ["feature"]
        }
        """);

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolSchemas() {
    }
}
