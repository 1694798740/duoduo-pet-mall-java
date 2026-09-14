package com.duoduo.product.support;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 违禁词守卫。
 *
 * <p>为什么放在商品服务：宠物行业的高风险商品（活体犬只交易、人用处方药、未经批准的兽药、
 * 保护动物制品）必须在「搜索」与「发布」两处同时拦截 —— 只在发布处拦截，
 * 搜索仍会把违规内容聚合出来，等于没拦。
 *
 * <p>词表由配置下发（{@code duoduo.product.banned-words}），便于合规同学随时补充而无需发版。
 * 生产形态下应接入内容安全服务做语义级识别，词表只作为兜底的第一道闸。
 */
@Component
@ConfigurationProperties(prefix = "duoduo.product")
public class BannedWordGuard {

    private List<String> bannedWords = List.of();

    /** 命中即拒绝。用于搜索关键词与商品标题（发布/编辑） */
    public void rejectIfHit(String text, String scene) {
        if (text == null || text.isBlank() || bannedWords.isEmpty()) {
            return;
        }
        String normalized = text.replace(" ", "").toLowerCase();
        for (String word : bannedWords) {
            if (word == null || word.isBlank()) {
                continue;
            }
            if (normalized.contains(word.replace(" ", "").toLowerCase())) {
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        "「" + scene + "」包含平台禁售或违规内容，已拦截");
            }
        }
    }

    /** 判断是否命中，供不做拒绝只做标记的场景使用 */
    public boolean hit(String text) {
        if (text == null || text.isBlank() || bannedWords.isEmpty()) {
            return false;
        }
        String normalized = text.replace(" ", "").toLowerCase();
        return bannedWords.stream()
                .filter(w -> w != null && !w.isBlank())
                .anyMatch(w -> normalized.contains(w.replace(" ", "").toLowerCase()));
    }

    public List<String> getBannedWords() {
        return bannedWords;
    }

    public void setBannedWords(List<String> bannedWords) {
        this.bannedWords = bannedWords;
    }
}
