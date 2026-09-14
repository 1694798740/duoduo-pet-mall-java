package com.duoduo.common.api;

import java.util.UUID;

/**
 * 请求链路 ID 持有器。
 * 由 Web 层过滤器／网关过滤器写入，贯穿日志、响应与下游调用（Feign / MQ Header）。
 */
public final class RequestIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private RequestIdHolder() {
    }

    public static String get() {
        return HOLDER.get();
    }

    public static String getOrCreate() {
        String current = HOLDER.get();
        if (current == null) {
            current = generate();
            HOLDER.set(current);
        }
        return current;
    }

    public static void set(String requestId) {
        HOLDER.set(requestId);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
