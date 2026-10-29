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
 * <p>2026-10-28 重构后：对话由 Agent 编排，模型必须通过工具获取真实商品/库存/价格；
 * 会话状态在 Redis，长期记忆与知识库在 {@code duoduo_ai} 库，RAG 走 Elasticsearch。
 * 不再有 mock 模式：未配置 API Key 时如实返回 DEGRADED，而不是假装 AI 可用。
 *
 * <p>外部依赖故障的降级：模型超时 / 工具失败 → 503 DEGRADED 或安全兜底话术，
 * 不做「静默换一套模板继续冒充 AI」。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.duoduo.ai.client")
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
