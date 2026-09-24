package com.duoduo.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 多多宠物商店 · AI 导购「多多」（A9）。
 *
 * <p>边界：对话理解与回复组织在这里；「有哪些真实商品」永远由商品域回答
 * （候选经内部凭据向 duoduo-product 拉取）—— 模型只允许从候选里挑着说，
 * <b>结构性杜绝编造商品</b>（验收①）。
 *
 * <p>三个明确的降级形态（外部依赖如实降级，不用 422 错怪调用方）：
 * <ul>
 *   <li><b>mock</b>：{@code DUODUO_AI_MOCK_ENABLED=true}（本地 / CI 默认）——
 *       不调 LLM，用确定性规则把真实候选组织成回复，冒烟可全链路跑通；</li>
 *   <li><b>unconfigured → 503 DEGRADED</b>：未配 API Key 且未开 mock，
 *       如实告知「AI 服务未配置」，而不是返回空回复装作正常；</li>
 *   <li><b>超时 / 依赖故障 → 503 DEGRADED</b>：调用超时或对端故障，提示稍后再试。</li>
 * </ul>
 *
 * <p>无数据库（v1 无状态对话）；每 IP 限流在应用内实现（限流是保护 LLM 配额的第一道闸）。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.duoduo.ai.client")
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
