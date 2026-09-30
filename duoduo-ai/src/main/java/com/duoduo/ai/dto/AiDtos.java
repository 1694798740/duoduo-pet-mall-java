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
     *
     * <p>{@code clarified}（第五十七轮）：<b>本次之前是否已经澄清过一轮</b>。
     * 服务端没有任何会话状态，只能由前端把这个事实带上来；
     * 它的唯一用途是**保证一次对话最多只反问一次** —— 否则「AI 一直追问、用户一直答」
     * 会变成填问卷，而导购不该是问卷。
     */
    public record ChatRequest(
            @NotBlank(message = "消息不能为空")
            @Size(max = 500, message = "消息最多 500 个字符")
            String message,
            @Size(max = 6, message = "历史最多保留 6 条")
            List<@Valid ChatTurn> history,
            Boolean clarified
    ) {
        public boolean clarifiedOnce() {
            return Boolean.TRUE.equals(clarified);
        }
    }

    /**
     * 对话响应：回复 + 推荐卡片（每一条都来自真实商品库）+ 服务形态标识。
     *
     * <p>{@code clarify}（第五十七轮）：非空表示「这不是回答，而是一个反问」，
     * 前端应把 {@code options} 渲染成**可点选项**（用户点一下即发送，不必打字）。
     * 行业做法一致：京东京言在「给 6 岁宝宝买生日礼物」时先问「男孩还是女孩？」，
     * 给的就是可点选项。开放式的「请补充更多信息」用户往往不理 ——
     * 让澄清成本降到一次点击，它才会真的被使用。
     */
    public record ChatResponse(
            String reply,
            List<ProductRecommendation> recommendations,
            /** mock / llm —— 让前端与排查者一眼看清「这句回复是谁生成的」 */
            String mode,
            /** 反问题（含可点选项）；非 null 时本响应是澄清而非回答 */
            ClarifyView clarify,
            /**
             * 相关追问（第五十八轮 S3）：**由当前候选推导**出来的可点问题，
             * 不是固定文案 —— 候选为空时不给（此时任何追问点下去都是空结果）。
             */
            List<String> followUps
    ) {
    }

    /** 澄清问题：一句话 + 可点选项（选项来自商品域真实类目） */
    public record ClarifyView(
            String question,
            List<ClarifyOption> options
    ) {
    }

    /**
     * 澄清选项。
     *
     * @param label 展示文案（如「宠物主粮」）
     * @param send  点击后作为用户消息发出去的内容（如「宠物主粮」）——
     *             刻意让「展示」与「发送」分离：将来若要显示「猫粮（主粮类）」这类
     *             带解释的标签，发送的仍应是干净的查询词
     */
    public record ClarifyOption(
            String label,
            String send
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
            String hint,
            /**
             * 快捷问题（第五十八轮 S3）：取自商品域的<b>真实类目</b>，
             * 保证点下去有结果；类目取不到时为空（前端不展示这一块）。
             */
            List<String> quickQuestions
    ) {
    }
}
