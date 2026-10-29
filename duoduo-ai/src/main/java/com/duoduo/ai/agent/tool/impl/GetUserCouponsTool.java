package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolArgs;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.butler.dto.ButlerViews.CouponListData;
import com.duoduo.ai.butler.service.CouponQueryService;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.dto.ai.AiAction;
import com.duoduo.ai.dto.ai.ToolResponse;
import com.duoduo.ai.route.ResolvedRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 「我的优惠券」工具（阶段二）。
 *
 * <p><b>只读</b>：领券、锁券、核销一概由营销域在自己的页面里完成。
 * AI 若能代领券，一次提示词注入就能把用户券包领满、甚至触发库存与并发问题 ——
 * 按 Q7，交易类动作只给导航入口。
 */
@Component
public class GetUserCouponsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GetUserCouponsTool.class);

    private final CouponQueryService couponQueryService;
    private final NavigationService navigation;
    private final ObjectMapper mapper;

    public GetUserCouponsTool(CouponQueryService couponQueryService,
                              NavigationService navigation, ObjectMapper mapper) {
        this.couponQueryService = couponQueryService;
        this.navigation = navigation;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_user_coupons";
    }

    @Override
    public String description() {
        return "查询我的券包（已领取的券），返回真实的模板 ID、门槛与抵扣金额、折率、有效期与状态。"
                + "需要登录；不返回可领取的活动券。";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.GET_USER_COUPONS;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        if (!context.loggedIn() || context.userId() == null) {
            return ToolResult.of(ToolResponse.needLogin(navigation.loginUrl("/ai")), mapper);
        }

        String status = ToolArgs.textUpper(args, "status");
        int limit = ToolArgs.intOr(args, "limit", 10, 1, 20);

        try {
            CouponListData data = couponQueryService.load(context.userId(), status, limit);
            ResolvedRoute route = navigation.route("COUPONS", java.util.Map.of());
            List<AiAction> actions = List.of(new AiAction(
                    AiAction.TYPE_NAVIGATE, "打开券包", route.webPath(), route.miniPath()));

            // 券没有红点概念：这里没有任何角标变化，badgePatch 必须为 null
            return ToolResult.of(ToolResponse.ok(data, route.webPath(), actions, null), mapper);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_ARGS", e.getMessage());
        } catch (Exception e) {
            log.warn("券包查询失败 users={}", context.userId(), e);
            return ToolResult.fail("COUPON_QUERY_FAILED", "券包暂时取不到，请稍后再试");
        }
    }
}
