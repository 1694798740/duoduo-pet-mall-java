package com.duoduo.ai.agent;

import com.duoduo.ai.guardrail.MedicalRedFlagGuard;
import com.duoduo.ai.memory.SlotState;
import com.duoduo.ai.memory.SessionState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图路由（2026-10-28）：规则先兜底，避免把医疗急症当商品搜索。
 *
 * <p>规则只负责「一定不能错」的分流；真正需要语义判断的，交给 SlotExtractor 的 LLM 结果与工具链路。
 */
@Component
public class IntentRouter {

    private static final List<String> ORDER_WORDS = List.of(
            "订单", "物流", "快递", "发货", "到哪", "退款", "退货", "换货", "售后", "发票", "优惠券");
    private static final List<String> COMPARE_WORDS = List.of("对比", "比较", "哪个好", "选哪个", "有什么区别");
    private static final List<String> HEALTH_WORDS = List.of(
            "拉稀", "腹泻", "软便", "呕吐", "便血", "咳嗽", "打喷嚏", "过敏", "泪痕",
            "关节", "不吃", "食欲", "精神", "皮肤", "掉毛", "驱虫", "疫苗");

    private final MedicalRedFlagGuard medical;

    public IntentRouter(MedicalRedFlagGuard medical) {
        this.medical = medical;
    }

    public Intent route(String message, SlotState slots, SessionState session) {
        String text = message == null ? "" : message;
        if (medical.isRedFlag(text)) {
            return Intent.HIGH_RISK_MEDICAL;
        }
        if (containsAny(text, ORDER_WORDS)) {
            return Intent.ORDER_SUPPORT;
        }
        if (containsAny(text, COMPARE_WORDS)) {
            return Intent.PRODUCT_COMPARE;
        }
        if (containsAny(text, HEALTH_WORDS)) {
            return Intent.PET_HEALTH;
        }
        if (text.trim().length() <= 2 && !slots.hasSpecies() && !slots.hasBudget()) {
            return Intent.CHITCHAT;
        }
        return Intent.PRODUCT_SEARCH;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
