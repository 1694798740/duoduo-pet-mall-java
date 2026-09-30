package com.duoduo.ai.client.dto;

/**
 * 宠物档案（用户域 → AI 服务的取数口径，第五十八轮 · AI 加强项 S1）。
 *
 * <p>字段与用户域 {@code UserDtos.PetBrief} 一一对应，**刻意不含病史 / 生日 / 体重**：
 * 导购不需要这些数据就能给出合适的推荐，而每多带一个字段就多一处副本与泄露面
 * （裁剪在出口侧完成，理由见用户域该 DTO 的注释）。
 */
public record PetBrief(
        Long id,
        String name,
        String species,
        String breed,
        String lifeStage,
        java.util.List<String> allergens
) {
}
