package com.duoduo.ai.guardrail;

import com.duoduo.ai.memory.SlotState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 医疗红线（2026-10-28）：输入前置 + 输出后置双重检查。
 *
 * <p>红线命中时不做商品推荐、不推荐药品，直接给就医建议并可转人工。
 */
@Component
public class MedicalRedFlagGuard {

    private static final List<String> RED_FLAGS = List.of(
            "便血", "黑便", "血便", "持续呕吐", "呕吐超过24", "抽搐", "呼吸困难", "中毒",
            "误食", "无法排尿", "尿不出", "幼猫不吃", "幼犬不吃", "不吃不喝", "精神沉郁",
            "昏迷", "腹胀", "腹痛", "高烧", "低血糖"
    );

    public boolean isRedFlag(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String flag : RED_FLAGS) {
            if (text.contains(flag)) {
                return true;
            }
        }
        return false;
    }

    public SafeAnswer safeAnswer(SlotState slots) {
        String pet = slots != null && slots.species() != null
                ? ("cat".equals(slots.species()) ? "猫咪" : "狗狗")
                : "宠物";
        String reply = "这个情况先不建议换粮或自己用药，尤其是" + pet
                + "出现便血、持续呕吐、精神沉郁或不吃不喝时，进展可能很快。"
                + "请先保持安静、少量多次补水，并尽快联系线下兽医；"
                + "如果需要，我可以把情况整理给人工客服，帮你对接后续咨询。"
                + "以上是安全建议，不能替代兽医诊断。";
        return new SafeAnswer(reply, "用户描述了医疗红线症状，已停止商品推荐并建议就医/转人工");
    }

    public record SafeAnswer(String reply, String summary) {
    }
}
