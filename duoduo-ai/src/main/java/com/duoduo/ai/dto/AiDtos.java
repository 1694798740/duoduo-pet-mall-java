package com.duoduo.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * AI 导购对外契约（A9）。record 不可变 + 边界处校验，与全站一致。
 */
public final class AiDtos {

    private AiDtos() {
    }

    /**
     * 一轮历史对话。
     *
     * <p>role 由服务端规范化（只认 user，其余一律 assistant），不接受客户端任意字符串 ——
     * 直接把它拼进提示词等于把提示词结构的控制权交给调用方。
     */
    public record ChatTurn(
            @NotBlank(message = "历史角色不能为空")
            @Size(max = 16, message = "历史角色最长 16 个字符")
            String role,
            @NotBlank(message = "历史内容不能为空")
            @Size(max = 500, message = "单条历史最多 500 个字符")
            String content
    ) {
    }

    /**
     * 对话请求。
     *
     * <p>history 可空；带的时候必须是<b>带角色</b>的轮次 —— 早先的形态是纯字符串数组，
     * 服务端只能把每条都当 assistant 说过的话，而前端很可能连用户提问一起塞进来，
     * 于是模型会把用户说过的话当成自己说过的，多轮对话立刻失真。
     * v1 不落库，历史由前端维护并只回传最近 6 条。
     */
    public record ChatRequest(
            @NotBlank(message = "消息不能为空")
            @Size(max = 500, message = "消息最多 500 个字符")
            String message,
            @Size(max = 6, message = "历史最多保留 6 条")
            List<@Valid ChatTurn> history
    ) {
    }

    /** 对话响应：回复 + 推荐卡片（每一条都来自真实商品库）+ 服务形态标识 */
    public record ChatResponse(
            String reply,
            List<ProductRecommendation> recommendations,
            /** mock / llm —— 让前端与排查者一眼看清「这句回复是谁生成的」 */
            String mode
    ) {
    }

    /** 推荐卡片：只透出展示所需字段；价格单位为分（全站口径） */
    public record ProductRecommendation(
            Long id,
            String title,
            String subtitle,
            long minPrice,
            long maxPrice,
            boolean inStock
    ) {
    }

    /** 能力声明（前端据此决定是否展示 AI 入口与降级提示） */
    public record CapabilityView(
            boolean chatAvailable,
            String mode,
            String hint
    ) {
    }
}
