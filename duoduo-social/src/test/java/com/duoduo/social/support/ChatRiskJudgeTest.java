package com.duoduo.social.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-05 · 聊天风险判定与处置策略。
 *
 * <p>重点守「不误伤正常沟通」：裸词「微信」「二维码」、19 位订单号里的数字串、
 * 普通发货咨询都不应命中；手机号默认只告警；链接 / 诈骗词 / 配置敏感词才拦截。
 */
class ChatRiskJudgeTest {

    private static final List<String> WORDS = List.of("代练", "刷单", "博彩", "套现");

    @Test
    void 正常宠物咨询_不命中() {
        assertThat(ChatRiskJudge.judge("猫粮什么时候发货？订单 202610260001888888", WORDS).hit()).isFalse();
        assertThat(ChatRiskJudge.judge("用微信支付很方便，二维码在哪里？", WORDS).hit()).isFalse();
        assertThat(ChatRiskJudge.judge("我家猫咪最近不爱吃饭，有推荐吗", WORDS).hit()).isFalse();
    }

    @Test
    void 手机号_识别为联系方式并脱敏() {
        ChatRiskJudge.Verdict v = ChatRiskJudge.judge("我电话13800138000，方便联系", WORDS);
        assertThat(v.hit()).isTrue();
        assertThat(v.category()).isEqualTo(ChatRiskJudge.Category.CONTACT);
        assertThat(v.masked()).isEqualTo("138****8000");
    }

    @Test
    void 全角与空格混淆的手机号_仍能识别() {
        ChatRiskJudge.Verdict v = ChatRiskJudge.judge("１３８ ００１３ ８０００", WORDS);
        assertThat(v.hit()).isTrue();
        assertThat(v.category()).isEqualTo(ChatRiskJudge.Category.CONTACT);
    }

    @Test
    void QQ与微信号_识别为联系方式() {
        assertThat(ChatRiskJudge.judge("QQ:12345678 加我", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.CONTACT);
        assertThat(ChatRiskJudge.judge("加微信 abc_12345", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.CONTACT);
    }

    @Test
    void 站外链接_命中LINK() {
        assertThat(ChatRiskJudge.judge("看这个 https://evil.com/x", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.LINK);
        assertThat(ChatRiskJudge.judge("www.evil.com 有优惠", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.LINK);
        assertThat(ChatRiskJudge.judge("去 abc.top 看看", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.LINK);
    }

    @Test
    void 资金诈骗词_命中FRAUD() {
        assertThat(ChatRiskJudge.judge("先给我转账 500 元", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.FRAUD);
        assertThat(ChatRiskJudge.judge("把验证码发我", WORDS).category())
                .isEqualTo(ChatRiskJudge.Category.FRAUD);
    }

    @Test
    void 配置敏感词_命中SENSITIVE() {
        ChatRiskJudge.Verdict v = ChatRiskJudge.judge("接代练任务轻松赚钱", WORDS);
        assertThat(v.hit()).isTrue();
        assertThat(v.category()).isEqualTo(ChatRiskJudge.Category.SENSITIVE);
        // 不回显完整敏感词，避免日志与错误信息变成词表
        assertThat(v.masked()).isEqualTo("代***");
    }

    @Test
    void 处置策略_链接拦截_联系方式默认只告警() {
        ChatModeration strict = new ChatModeration(true, false, "刷单");
        assertThatThrownBy(() -> strict.enforce("看 https://evil.com", "test"))
                .isInstanceOf(com.duoduo.common.exception.BizException.class)
                .hasMessageContaining("站外链接");
        // 手机号默认只告警，不阻断发送
        strict.enforce("我电话 13800138000", "test");
    }

    @Test
    void 处置策略_可切换为拦截联系方式() {
        ChatModeration strict = new ChatModeration(true, true, "刷单");
        assertThatThrownBy(() -> strict.enforce("我电话 13800138000", "test"))
                .isInstanceOf(com.duoduo.common.exception.BizException.class)
                .hasMessageContaining("联系方式");
    }

    @Test
    void 总开关关闭_全部放行() {
        ChatModeration off = new ChatModeration(false, true, "刷单");
        off.enforce("看 https://evil.com 并转账", "test");
    }
}
