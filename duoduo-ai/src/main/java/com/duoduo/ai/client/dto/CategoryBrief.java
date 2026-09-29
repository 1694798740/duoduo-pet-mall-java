package com.duoduo.ai.client.dto;

/**
 * 类目简表（商品域 → AI 服务的取数口径，第五十七轮）。
 *
 * <p>字段与商品域 {@code ProductDtos.CategoryBrief} 一一对应。
 * 跨域 DTO 刻意各写一份、不共用实体：共用一个类会把两个服务的编译期耦在一起，
 * 商品域改字段会直接编译不过 AI 域，而它们本该独立演进。
 */
public record CategoryBrief(
        Long id,
        String name,
        int depth
) {
}
