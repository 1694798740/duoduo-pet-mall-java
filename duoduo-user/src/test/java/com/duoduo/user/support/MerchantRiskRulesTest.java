package com.duoduo.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入驻自动放行风险规则单测（E28）。
 *
 * <h2>这组测试守的是什么</h2>
 * 入驻自动放行比商品自动放行更重：放行一家店，放的是「这家店的收款主体资格」。
 * 判错的后果不是一件商品挂着，而是这家店的全部商品都能卖。
 * 因此每条规则都要双向钉住 —— 该转人工的必须转，不该转的不能转。
 *
 * <p>另外两条容易写错的方向也在这里加锁：
 * {@code null} 剩余天数<b>不是</b>「今天到期」（没有带有效期的材料 = 长期有效，不该转人工）；
 * 店名命中最长匹配只需<b>报一次</b>（一家店叫「官方旗舰店」报四个词出来，审核台会被噪音淹没）。
 */
class MerchantRiskRulesTest {

    private static MerchantRiskRules.Context ctx(String storeName, Long nearestExpireDays) {
        return new MerchantRiskRules.Context(storeName, nearestExpireDays);
    }

    private static List<String> codes(List<MerchantRiskRules.Hit> hits) {
        return hits.stream().map(MerchantRiskRules.Hit::code).toList();
    }

    /** 基准店铺：名字干净、材料长期有效 */
    private static MerchantRiskRules.Context clean() {
        return ctx("豆豆宠物用品店", null);
    }

    // ---------------- 总览 ----------------

    @Test
    @DisplayName("干净店铺 → 无风险，可以直接自动放行")
    void cleanShopHasNoRisk() {
        assertThat(MerchantRiskRules.risks(clean())).isEmpty();
    }

    @Test
    @DisplayName("上下文为 null 时不抛异常，按无风险处理")
    void nullContextIsSafe() {
        assertThat(MerchantRiskRules.risks(null)).isEmpty();
    }

    @Test
    @DisplayName("★ 两类规则同时命中时全部返回（运营一次看全，不必来回翻）")
    void returnsBothHits() {
        assertThat(codes(MerchantRiskRules.risks(ctx("多多官方旗舰店", 10L))))
                .containsExactly("MATERIAL_EXPIRING_SOON", "IMPERSONATION_NAME");
    }

    // ---------------- 材料有效期 ----------------

    @Test
    @DisplayName("★ null 剩余天数 = 材料长期有效，不构成风险（不是「今天到期」）")
    void nullExpiryIsNotRisk() {
        assertThat(MerchantRiskRules.risks(ctx("豆豆宠物用品店", null))).isEmpty();
    }

    @Test
    @DisplayName("剩余 30 天 = 边界，不转人工；29 天才转")
    void expiryBoundary() {
        assertThat(MerchantRiskRules.risks(ctx("豆豆宠物用品店", 30L))).isEmpty();
        assertThat(codes(MerchantRiskRules.risks(ctx("豆豆宠物用品店", 29L))))
                .containsExactly("MATERIAL_EXPIRING_SOON");
    }

    @Test
    @DisplayName("已过期（负数剩余天数）同样转人工，且在提示里说清还剩几天")
    void expiredMaterialGoesManual() {
        List<MerchantRiskRules.Hit> hits = MerchantRiskRules.risks(ctx("豆豆宠物用品店", -5L));
        assertThat(codes(hits)).containsExactly("MATERIAL_EXPIRING_SOON");
        assertThat(hits.get(0).label()).contains("-5");
    }

    // ---------------- 店名冒用词 ----------------

    @Test
    @DisplayName("★ 冒用 / 误导词逐个命中：官方 / 旗舰 / 专卖 / 自营 / 认证 / 国家级 / 直属 / 总代")
    void impersonationWordsAllDetected() {
        for (String word : List.of("官方", "旗舰", "专卖", "自营", "认证", "国家级", "直属", "总代")) {
            assertThat(codes(MerchantRiskRules.risks(ctx("豆豆" + word + "店", null))))
                    .as("店名含「%s」应转人工", word)
                    .containsExactly("IMPERSONATION_NAME");
        }
    }

    @Test
    @DisplayName("★ 命中多个词也只报一条：报四条出来只会把审核台弄乱")
    void onlyOneHitPerName() {
        assertThat(codes(MerchantRiskRules.risks(ctx("多多官方旗舰专卖自营店", null))))
                .containsExactly("IMPERSONATION_NAME");
    }

    @Test
    @DisplayName("普通店名不命中")
    void normalNameNotFlagged() {
        assertThat(MerchantRiskRules.risks(ctx("豆豆宠物用品店", null))).isEmpty();
        assertThat(MerchantRiskRules.risks(ctx("猫猫生活馆", null))).isEmpty();
        assertThat(MerchantRiskRules.risks(ctx("小鑫的宠物店", null))).isEmpty();
    }

    @Test
    @DisplayName("店名里的空格不影响判定")
    void nameIgnoresSpaces() {
        assertThat(codes(MerchantRiskRules.risks(ctx("豆豆 官方 店", null))))
                .containsExactly("IMPERSONATION_NAME");
    }

    @Test
    @DisplayName("店名为 null 或空时不抛异常、也不命中")
    void nullNameIsSafe() {
        assertThat(MerchantRiskRules.risks(ctx(null, null))).isEmpty();
        assertThat(MerchantRiskRules.risks(ctx("", null))).isEmpty();
    }

    @Test
    @DisplayName("每个命中原因都带中文说明")
    void everyHitHasChineseLabel() {
        assertThat(MerchantRiskRules.risks(ctx("多多官方旗舰店", 3L)))
                .allSatisfy(h -> {
                    assertThat(h.code()).isNotBlank();
                    assertThat(h.label()).isNotBlank();
                });
    }

    // ---------------- 明确「判不了」的两类规则 ----------------

    @Test
    @DisplayName("★ 规则集就是这两条：证件号重复注册 / 同 IP 批量注册当前判不了（不假装实现）")
    void onlyTwoRulesExist() {
        // 这条测试是给后来人看的：附录 D 里还写着两类规则，它们需要先有结构化字段
        // （证件号输入项、来源 IP 落库）才能判。谁将来实现了，请同时更新类注释与这条断言。
        assertThat(codes(MerchantRiskRules.risks(ctx("多多官方旗舰店", 1L))))
                .containsExactlyInAnyOrder("MATERIAL_EXPIRING_SOON", "IMPERSONATION_NAME");
    }
}
