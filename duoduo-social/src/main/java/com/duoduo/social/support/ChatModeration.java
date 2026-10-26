package com.duoduo.social.support;

import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 聊天内容质检与拦截（C-05）。
 *
 * <h2>处置口径</h2>
 * <ul>
 *   <li><b>站外链接 / 诈骗词 / 配置敏感词</b>：直接 422 拦截，消息不落库、不推送 ——
 *       这些内容一旦发出就无法撤回（对方已看到），先拦截是唯一有效时点；</li>
 *   <li><b>手机号 / QQ / 微信号</b>：默认<b>只告警不拦截</b>
 *       （售后沟通中交换电话是正常需求），日志里记录脱敏片段；
 *       {@code duoduo.chat.block-contact=true} 可切换为拦截。</li>
 * </ul>
 *
 * <p>不修改用户原文、不静默替换字符 —— 命中就是「发不出去 + 说明原因」，
 * 让用户自己改；悄悄改字会让双方看到的内容不一致，比拦截更难解释。
 */
@Component
public class ChatModeration {

    private static final Logger log = LoggerFactory.getLogger(ChatModeration.class);

    private final boolean enabled;
    private final boolean blockContact;
    private final Set<String> sensitiveWords;

    public ChatModeration(@Value("${duoduo.chat.moderation-enabled:true}") boolean enabled,
                          @Value("${duoduo.chat.block-contact:false}") boolean blockContact,
                          @Value("${duoduo.chat.sensitive-words:}") String sensitiveWords) {
        this.enabled = enabled;
        this.blockContact = blockContact;
        this.sensitiveWords = parseWords(sensitiveWords);
    }

    /**
     * 检查一条即将发送的聊天内容；命中且策略为拦截时抛 422，调用方不会继续落库。
     *
     * @param content 已归一化后的正文（媒体消息可能是占位符，不影响判定）
     * @param scene   场景（support / social），只用于日志定位
     */
    public void enforce(String content, String scene) {
        if (!enabled || content == null || content.isBlank()) {
            return;
        }
        ChatRiskJudge.Verdict verdict = ChatRiskJudge.judge(content, sensitiveWords);
        if (!verdict.hit()) {
            return;
        }
        boolean block = verdict.category() != ChatRiskJudge.Category.CONTACT || blockContact;
        log.warn("聊天内容命中{}（场景 {}，{}）：{}", verdict.reason(), scene,
                block ? "拦截" : "仅告警", verdict.masked());
        if (block) {
            throw BizException.businessRule("消息未发送：检测到" + verdict.reason()
                    + "，请修改后再发送（站外联系与资金交易请走平台内流程）");
        }
    }

    private static Set<String> parseWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> words = new LinkedHashSet<>();
        Arrays.stream(raw.split("[,，、;；]"))
                .map(String::trim)
                .filter(w -> !w.isEmpty())
                .forEach(words::add);
        return Set.copyOf(words);
    }
}
