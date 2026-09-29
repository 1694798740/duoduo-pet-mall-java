package com.duoduo.ai.client;

import feign.Request;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * AI 域 Feign 客户端的超时配置（第五十七轮 · M2）。
 *
 * <p><b>为什么不能沿用全局默认</b>：导购对话的体感目标是 1~3 秒；而 Feign 默认读超时
 * 是 10 秒级。商品域一旦变慢（慢查询、连接池打满），AI 的请求线程会被长时间占住，
 * 线程池很快耗尽 —— 用户看到的现象是「AI 全挂了」，日志里的根因却在商品域。
 * 把召回超时压到 1.5s 之后：商品域慢 → AI 快速降级并给出「商品信息暂时取不到」，
 * 故障点一眼可见，也不会拖垮整个 AI 服务。
 *
 * <p><b>为什么时间比 LLM 的超时短得多</b>：LLM 调用本身就要几秒（无法压缩），
 * 而召回是**库内查询**，正常情况在百毫秒级。给库内查询留 1.5s 已经很宽松。
 *
 * <p><b>这个类刻意不加 {@code @Configuration}</b>：加了它就会被组件扫描当成全局配置，
 * 于是所有 Feign 客户端（含将来新增的）都被这个超时罩住。作为 {@code @FeignClient}
 * 的 {@code configuration} 传入时，Feign 只把它应用到该客户端。
 */
public class AiFeignConfig {

    @Bean
    public Request.Options productSearchOptions(
            @Value("${duoduo.ai.recall.connect-timeout-ms:300}") int connectTimeoutMs,
            @Value("${duoduo.ai.recall.read-timeout-ms:1500}") int readTimeoutMs) {
        return new Request.Options(connectTimeoutMs, TimeUnit.MILLISECONDS,
                readTimeoutMs, TimeUnit.MILLISECONDS, true);
    }
}
