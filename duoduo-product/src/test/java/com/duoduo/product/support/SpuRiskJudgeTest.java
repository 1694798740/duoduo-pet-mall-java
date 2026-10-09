package com.duoduo.product.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品自动放行风险规则单测（E28）。
 *
 * <h2>这组测试守的是什么</h2>
 * 这些规则是「免除事前人工审核」那句话的**全部依据**：判定说「没问题」的商品，
 * 是直接带着「在售」状态出现在前台、没有任何人看过的。所以每条规则都要双向钉住：
 *
 * <ul>
 *   <li><b>该命中的必须命中</b>：尤其「把联系方式写进标题」这种绕过路径 ——
 *       它不报错、不留痕，只有测试能挡住；</li>
 *   <li><b>不该命中的不许命中</b>：误报把正常商品推进人工队列，还会让运营
 *       因为噪音而要求关掉规则。规则被关掉比规则漏报更严重；</li>
 *   <li><b>取不到数据一律按「不构成风险」</b>：类目名查不到、同类均价算不出、
 *       商家画像查不通，都不能变成「转人工」。否则一次缓存抖动或用户域重启，
 *       就会把整批商品堆进待审队列 —— 一个能自愈的小问题换来一次审核积压。</li>
 * </ul>
 */
class SpuRiskJudgeTest {

    private static SpuRiskJudge.Context ctx(String categoryName, String title, String subtitle,
                                            String detail, long minPrice, Long avg, boolean flagged) {
        return new SpuRiskJudge.Context(categoryName, title, subtitle, detail, minPrice, avg, flagged);
    }

    /** 各字段都干净、无风险的基准商品 */
    private static SpuRiskJudge.Context clean() {
        return ctx("猫粮", "皇家幼猫粮 2kg", "适合 1-12 月龄", "配方温和，易消化。", 12900L, 13000L, false);
    }

    private static List<String> codes(List<SpuRiskJudge.Hit> hits) {
        return hits.stream().map(SpuRiskJudge.Hit::code).toList();
    }

    // ---------------- 总览 ----------------

    @Test
    @DisplayName("干净商品 → 没有任何风险，可以自动放行")
    void cleanProductHasNoRisk() {
        assertThat(SpuRiskJudge.risks(clean())).isEmpty();
    }

    @Test
    @DisplayName("★ 一次返回全部命中项（而不是遇到第一个就返回）：复盘误伤率要的是完整分布")
    void returnsAllHits() {
        SpuRiskJudge.Context bad = ctx("宠物药品", "官方直营 13800138000", null,
                "加微信 duoduo888 咨询", 100L, 10000L, true);
        assertThat(codes(SpuRiskJudge.risks(bad)))
                .containsExactly("RESTRICTED_CATEGORY", "CONTACT_INFO", "PRICE_ANOMALY", "FLAGGED_SELLER");
    }

    @Test
    @DisplayName("上下文为 null 时不抛异常、按无风险处理（防御式兜底）")
    void nullContextIsSafe() {
        assertThat(SpuRiskJudge.risks(null)).isEmpty();
    }

    // ---------------- 前置许可类目 ----------------

    @Test
    @DisplayName("★ 前置许可类目关键词逐个命中：药品 / 处方 / 疫苗 / 兽药 / 活体 / 医用")
    void restrictedCategoryKeywords() {
        for (String kw : List.of("药品", "处方", "疫苗", "兽药", "活体", "医用")) {
            assertThat(SpuRiskJudge.hitRestrictedCategory("宠物" + kw + "专区"))
                    .as("类目含「%s」应视为前置许可类", kw)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("普通类目不命中（否则整站商品都要人工审）")
    void normalCategoryNotRestricted() {
        assertThat(SpuRiskJudge.hitRestrictedCategory("猫粮")).isFalse();
        assertThat(SpuRiskJudge.hitRestrictedCategory("宠物玩具")).isFalse();
        assertThat(SpuRiskJudge.hitRestrictedCategory("猫砂")).isFalse();
    }

    @Test
    @DisplayName("★ 类目名取不到（null / 空白）→ 不构成风险，而不是转人工")
    void nullCategoryIsNotRisk() {
        assertThat(SpuRiskJudge.hitRestrictedCategory(null)).isFalse();
        assertThat(SpuRiskJudge.hitRestrictedCategory("")).isFalse();
        assertThat(SpuRiskJudge.hitRestrictedCategory("   ")).isFalse();
    }

    @Test
    @DisplayName("类目名里的空格不影响判定（「宠 物 药 品」也要命中）")
    void categoryIgnoresSpaces() {
        assertThat(SpuRiskJudge.hitRestrictedCategory("宠 物 药 品")).isTrue();
    }

    // ---------------- 联系方式：该命中的 ----------------

    @Test
    @DisplayName("手机号命中（含 +86 与分隔符写法）")
    void mobilePhoneDetected() {
        assertThat(SpuRiskJudge.hitContactInfo("咨询 13800138000")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("客服手机：13800138000，随时联系")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("+8613800138000")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("+86 138 0013 8000")).isTrue();
    }

    @Test
    @DisplayName("带区号的座机命中")
    void landlineWithAreaCodeDetected() {
        assertThat(SpuRiskJudge.hitContactInfo("电话 010-12345678")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("座机 0755-88886666")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("021 66668888")).isTrue();
    }

    @Test
    @DisplayName("邮箱 / 微信 / QQ / 站外链接命中")
    void otherContactChannelsDetected() {
        assertThat(SpuRiskJudge.hitContactInfo("邮箱 pet@duoduo.com")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("微信：duoduo_pet_2024")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("加v信 abc12345 有优惠")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("QQ号 123456789")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("更多款式见 www.taobao.com")).isTrue();
        assertThat(SpuRiskJudge.hitContactInfo("详见 https://shop.example.com/a")).isTrue();
    }

    // ---------------- 联系方式：不许误报的 ----------------

    @Test
    @DisplayName("★ 规格 / 重量 / 金额里的数字不算联系方式（放宽成 7~8 位数字会天天误报）")
    void plainNumbersAreNotContacts() {
        assertThat(SpuRiskJudge.hitContactInfo("净含量 10000000000")).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("规格 5000000")).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("净重 12345678 克")).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("价格 990 元")).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("库存 1234 件")).isFalse();
    }

    @Test
    @DisplayName("★ 被数字/小数点包裹的数字串不算电话：价格 13800138000.5 不命中")
    void numbersEmbeddedInDecimalsAreNotContacts() {
        assertThat(SpuRiskJudge.hitContactInfo("13800138000.5")).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("编号 9913800138000")).isFalse();
    }

    @Test
    @DisplayName("空白与 null 不命中")
    void blankTextIsNotContact() {
        assertThat(SpuRiskJudge.hitContactInfo(null)).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("")).isFalse();
        assertThat(SpuRiskJudge.hitContactInfo("   ")).isFalse();
    }

    // ---------------- 联系方式：标题 / 副标题也必须判（绕过路径） ----------------

    @Test
    @DisplayName("★ 微信号写在「标题」里同样转人工 —— 只看详情是一条现成的绕过路径")
    void contactInTitleIsCaught() {
        SpuRiskJudge.Context c = ctx("猫粮", "加微信 duoduo888 领券", null, "配方温和。", 12900L, 13000L, false);
        assertThat(codes(SpuRiskJudge.risks(c))).containsExactly("CONTACT_INFO");
    }

    @Test
    @DisplayName("★ 手机号写在「副标题」里同样转人工")
    void contactInSubtitleIsCaught() {
        SpuRiskJudge.Context c = ctx("猫粮", "皇家幼猫粮", "电话 13800138000", "配方温和。", 12900L, 13000L, false);
        assertThat(codes(SpuRiskJudge.risks(c))).containsExactly("CONTACT_INFO");
    }

    @Test
    @DisplayName("三段都为空时不会因为「拼接后为空串」误判")
    void allBlankTextFieldsNotContact() {
        SpuRiskJudge.Context c = ctx("猫粮", null, null, null, 12900L, 13000L, false);
        assertThat(SpuRiskJudge.risks(c)).isEmpty();
    }

    // ---------------- 价格异常 ----------------

    @Test
    @DisplayName("价格低于同类均价 10% 即命中；等于均价当然不命中")
    void priceAnomalyBoundary() {
        assertThat(SpuRiskJudge.hitPriceAnomaly(10000L, 10000L)).isFalse();
        assertThat(SpuRiskJudge.hitPriceAnomaly(9000L, 10000L)).isFalse();   // 恰好 90% = 边界，不算异常
        assertThat(SpuRiskJudge.hitPriceAnomaly(8999L, 10000L)).isTrue();
        assertThat(SpuRiskJudge.hitPriceAnomaly(1L, 10000L)).isTrue();
    }

    @Test
    @DisplayName("★ 算不出均价（null / 0）不算异常：否则新类目的第一批商品永远进不了自动放行")
    void missingBenchmarkIsNotAnomaly() {
        assertThat(SpuRiskJudge.hitPriceAnomaly(100L, null)).isFalse();
        assertThat(SpuRiskJudge.hitPriceAnomaly(100L, 0L)).isFalse();
        assertThat(SpuRiskJudge.hitPriceAnomaly(100L, -1L)).isFalse();
    }

    @Test
    @DisplayName("自身价格非正（脏数据）不命中，避免除零式的无意义结论")
    void nonPositivePriceIsNotAnomaly() {
        assertThat(SpuRiskJudge.hitPriceAnomaly(0L, 10000L)).isFalse();
        assertThat(SpuRiskJudge.hitPriceAnomaly(-5L, 10000L)).isFalse();
    }

    // ---------------- 商家标记 ----------------

    @Test
    @DisplayName("有驳回 / 停业记录的商家 → 转人工（命中原因进留痕）")
    void flaggedSellerGoesManual() {
        SpuRiskJudge.Context c = ctx("猫粮", "皇家幼猫粮", null, "配方温和。", 12900L, 13000L, true);
        assertThat(codes(SpuRiskJudge.risks(c))).containsExactly("FLAGGED_SELLER");
    }

    @Test
    @DisplayName("每个命中原因都带中文说明：运营要看懂「为什么转到我这」")
    void everyHitHasChineseLabel() {
        SpuRiskJudge.Context bad = ctx("宠物药品", "官方 13800138000", null, null, 100L, 10000L, true);
        assertThat(SpuRiskJudge.risks(bad))
                .allSatisfy(h -> {
                    assertThat(h.code()).isNotBlank();
                    assertThat(h.label()).isNotBlank();
                });
    }
}
