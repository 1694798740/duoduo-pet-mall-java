package com.duoduo.fulfilment.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-15 · 评价机审规则单测。
 *
 * <p>钉住三类事实：
 * <ol>
 *   <li>五类风险各自能命中，且原因可读、联系方式脱敏；</li>
 *   <li>归一化能挡住「加空格 / 全角」这种最省事的绕过；</li>
 *   <li><b>正常评价不误伤</b>：提到微信支付、二维码包装不算导流。</li>
 * </ol>
 */
class ReviewRiskJudgeTest {

    private final ReviewRiskJudge judge = new ReviewRiskJudge("刷好评, 黑产");

    @Test
    @DisplayName("正常评价：放行")
    void 正常评价放行() {
        ReviewRiskJudge.Verdict verdict = judge.judge("猫粮很香，包装扎实，猫吃了三周毛色变亮。");
        assertThat(verdict.risky()).isFalse();
        assertThat(verdict.reason()).isNull();
    }

    @Test
    @DisplayName("空 / null：放行（只有星级没有文字的评价是合法形态）")
    void 空内容放行() {
        assertThat(judge.judge((String) null).risky()).isFalse();
        assertThat(judge.judge("   ").risky()).isFalse();
        assertThat(judge.judge().risky()).isFalse();
    }

    @Test
    @DisplayName("手机号：转人工，原因里的号码脱敏")
    void 手机号命中且脱敏() {
        ReviewRiskJudge.Verdict verdict = judge.judge("想要同款加我 13800138000");
        assertThat(verdict.risky()).isTrue();
        assertThat(verdict.reason()).contains("联系方式").contains("138****8000");
        assertThat(verdict.reason()).doesNotContain("13800138000");
    }

    @Test
    @DisplayName("手机号中间夹空格：归一化后仍命中")
    void 空格绕过被挡住() {
        assertThat(judge.judge("联系 138 0013 8000").risky()).isTrue();
    }

    @Test
    @DisplayName("全角数字：NFKC 归一化后仍命中")
    void 全角数字被挡住() {
        assertThat(judge.judge("电话１３８００１３８０００").risky()).isTrue();
    }

    @Test
    @DisplayName("站外链接 / 域名：命中")
    void 链接命中() {
        assertThat(judge.judge("同款在这里 https://example.com/x").risky()).isTrue();
        assertThat(judge.judge("看我主页 www.shop-example.cn").risky()).isTrue();
    }

    @Test
    @DisplayName("导流话术：加微信 / 私聊 / QQ 群号")
    void 导流话术命中() {
        assertThat(judge.judge("加微信聊").risky()).isTrue();
        assertThat(judge.judge("私聊我").risky()).isTrue();
        assertThat(judge.judge("QQ群 12345678").risky()).isTrue();
    }

    @Test
    @DisplayName("广告话术：刷单 / 返现")
    void 广告话术命中() {
        assertThat(judge.judge("刷单返现，日结").risky()).isTrue();
        assertThat(judge.judge("好评返 5 元").risky()).isTrue();
    }

    @Test
    @DisplayName("违禁品：代开发票 / 枪支")
    void 违禁品命中() {
        assertThat(judge.judge("能代开发票吗").risky()).isTrue();
        assertThat(judge.judge("卖枪支模型").risky()).isTrue();
    }

    @Test
    @DisplayName("运营配置的敏感词：命中（中英文逗号与空格都能切分）")
    void 配置敏感词命中() {
        assertThat(judge.judge("这家是刷好评的").risky()).isTrue();
        assertThat(judge.judge("黑产团队").risky()).isTrue();
        assertThat(judge.judge("猫粮很香").risky()).isFalse();
    }

    @Test
    @DisplayName("不误伤：微信支付 / 包装二维码 / 二维码在说明书里")
    void 正常词不误伤() {
        assertThat(judge.judge("用微信支付很方便").risky()).isFalse();
        assertThat(judge.judge("包装上有二维码，扫码能看说明书").risky()).isFalse();
    }

    @Test
    @DisplayName("多段文本：正文干净、追评命中也要转人工")
    void 追评命中() {
        ReviewRiskJudge.Verdict verdict = judge.judge("猫粮很香", "追评：加微信领试吃");
        assertThat(verdict.risky()).isTrue();
        assertThat(verdict.reason()).contains("导流");
    }
}
