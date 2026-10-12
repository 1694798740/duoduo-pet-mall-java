package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos.PetBrief;
import com.duoduo.user.entity.PetProfileEntity;
import com.duoduo.user.mapper.PetProfileMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 宠物档案的服务间裁剪视图单测（第五十八轮 · AI 加强项 S1）。
 *
 * <h2>核心断言：病史不下发</h2>
 * 健康数据一旦跨服务下发，就多一处副本、多一份泄露面，而导购<b>根本用不上它</b>。
 * 这条断言的意义在于：将来有人为了「让 AI 说得更贴心」往 PetBrief 里加字段时，
 * 会被这条测试挡下来重新想一遍 —— 「这个字段会改变推荐结果吗？」
 */
class PetProfileBriefTest {

    private final PetProfileMapper petMapper = mock(PetProfileMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final PetProfileService service = new PetProfileService(petMapper, idGenerator);

    private static PetProfileEntity pet(String name, String species) {
        PetProfileEntity entity = new PetProfileEntity();
        entity.setId(1L);
        entity.setUserId(710000000000000001L);
        entity.setName(name);
        entity.setSpecies(species);
        entity.setBreed("布偶");
        entity.setBirthday(LocalDate.of(2025, 1, 1));
        entity.setLifeStage("幼年");
        entity.setWeightKg(new BigDecimal("3.20"));
        entity.setGender("F");
        entity.setNeutered(0);
        entity.setAllergens("鸡肉,海鲜");
        entity.setMedicalHistory("曾患猫癣，2025-03 治愈"); // 敏感：绝不能下发
        entity.setIsDefault(1);
        return entity;
    }

    @Test
    void 只下发挑商品用得上的字段() {
        when(petMapper.selectList(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<PetProfileEntity>>any()))
                .thenReturn(List.of(pet("咪咪", "cat")));

        List<PetBrief> briefs = service.briefsForAssistant(710000000000000001L);

        assertThat(briefs).hasSize(1);
        PetBrief brief = briefs.get(0);
        assertThat(brief.name()).isEqualTo("咪咪");
        assertThat(brief.species()).isEqualTo("cat");
        assertThat(brief.lifeStage()).isEqualTo("幼年");
        assertThat(brief.allergens()).containsExactly("鸡肉", "海鲜");
    }

    @Test
    void 用户id为空时不查库() {
        assertThat(service.briefsForAssistant(null)).isEmpty();
    }

    @Test
    void 裁剪视图本身不含病史字段() {
        // 用结构断言守住：PetBrief 的组件里不允许出现 medicalHistory
        var components = PetBrief.class.getRecordComponents();
        for (var c : components) {
            assertThat(c.getName()).isNotIn("medicalHistory", "birthday", "weightKg", "gender");
        }
    }
}
