package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;

import java.util.List;

/**
 * mock 形态的回复生成器（第五十八轮 · AI 加强项）。
 *
 * <h2>为什么 mock 不能复用护栏的事实版回复</h2>
 * 早先 mock 直接返回 {@link ReplyGuard#factsReply} —— 而 {@code factsReply} 同时又是
 * 护栏命中后的<b>替换话术</b>。于是形成了一个自指：
 * 「用护栏的替换结果去问护栏，这结果合规吗？」答案永远是「合规」——
 * mock 形态下护栏<b>结构上不可能命中</b>（AI 加强项清单第 21 条）。
 * 后果很具体：护栏在 mock 下的行为**无人验证**，而 mock 恰恰是本地与 CI 唯一能跑的形态，
 * 等真上 llm 形态才发现护栏有洞就晚了。
 *
 * <p>所以这里给 mock 一条**独立的话术路径**：它像模型一样自己组织语言（也自己带价格），
 * 因此有可能说出违规词或写错价格，也就**真的会被护栏拦下**——
 * 护栏的两条判定（合规词 / 价格一致性）在 mock 下从此可达、可测。
 *
 * <h2>为什么话术里刻意带上价格</h2>
 * 就是为了给「价格一致性」这条判定留出可达路径：
 * 话术里的每个价格都取自候选（{@link #yuan}），正常情况下不会被拦；
 * 一旦有人改坏了取价逻辑（比如手滑写成 maxPrice 再打个折），护栏立刻转红。
 */
public final class MockReply {

    private MockReply() {
    }

    /** 组织一句导购话术（与 {@link ReplyGuard#factsReply} 是两套措辞，便于区分哪条路径在生效） */
    public static String compose(List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "暂时没找到匹配的商品，换个描述试试，比如「幼猫粮 200 元以内」。";
        }
        StringBuilder sb = new StringBuilder("按你的条件筛了一遍，比较合适的是");
        int top = Math.min(3, candidates.size());
        for (int i = 0; i < top; i++) {
            ProductCandidate c = candidates.get(i);
            if (i > 0) {
                sb.append(i == top - 1 ? " 和 " : "、");
            }
            sb.append("「").append(c.title()).append("」").append(yuan(c.minPrice())).append(" 元起");
        }
        sb.append("。");
        ProductCandidate first = candidates.get(0);
        if (!first.inStock()) {
            sb.append("这款现在缺货，可以先看看后面的。");
        } else {
            sb.append("喜欢的话登录后在商品页加购就行。");
        }
        return sb.toString();
    }

    /**
     * 分 → 元（与 {@link ReplyGuard} 的口径一致）。
     *
     * <p>必须一致：护栏会把话术里的价格文本拿去和候选价格比对，
     * 两处格式化差一点（「199.0」 vs 「199」）就会**天天误报**，
     * 而误报一旦常态化，真报也就没人看了。
     */
    static String yuan(long cents) {
        long abs = Math.abs(cents);
        return abs % 100 == 0 ? String.valueOf(cents / 100)
                : (cents / 100) + "." + String.format("%02d", abs % 100);
    }
}
