package com.duoduo.common.api;

/**
 * 状态分桶计数（跨域通用契约，2026-10-27）。
 *
 * <p>用于各业务页「二级 Tab 角标」：订单待支付、售后待处理、商品待审核……
 * 每个服务在自己的域内算好后按此形状下发，前端三端共用一套渲染。
 *
 * <h2>为什么是 record 而不是 {@code Map<String, Long>}</h2>
 * 这是一个踩过就知道的坑：本项目 {@code LongToStringJacksonModule} 把 {@code Long}
 * 默认序列化成<b>字符串</b>（雪花 ID 超过 JS 安全整数上限），只对「字段名」命中
 * {@code NUMERIC_LONG_FIELDS} 白名单的字段放行为数字。
 *
 * <p>而 {@code Map<String, Long>} 的 value <b>不经过字段名判定</b> —— 它走 MapSerializer，
 * 命中的是全局注册在 {@code Long.class} 上的 ToString 序列化器。结果就是
 * {@code {"PENDING":"2"}}：前端 {@code counts[key] > 0} 时灵时不灵、
 * {@code count + 1} 直接变字符串拼接 {@code "21"}。
 *
 * <p>用 record 后 {@code count} 正好命中白名单里的 {@code "count"}，按数字下发；
 * {@code status} 是 String，不受影响。**加字段时请保持 {@code count} 这个名字**，
 * 改名（如 {@code cnt}）会让它立刻退回字符串。
 */
public record StatusCount(String status, long count) {
}
