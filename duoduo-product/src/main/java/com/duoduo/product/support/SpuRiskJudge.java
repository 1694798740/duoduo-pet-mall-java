package com.duoduo.product.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 商品侧「必须转人工」的风险规则（E28）。
 *
 * <h2>它判的是什么</h2>
 * 不是「这件商品违不违规」（那要人看），而是「这件商品**敢不敢**让它自动通过」。
 * 换句话说：命中任意一条不表示商品有问题，只表示「这件值得人看一眼」。
 * 判定结果不拦截发布，只把审核态从自动通过改成待审。
 *
 * <h2>为什么违禁词不在这里</h2>
 * 违禁词（{@link BannedWordGuard}）是**硬拒绝** —— 命中的商品连库都进不去（400）。
 * 硬拒绝比「转人工」更强，能覆盖它的场景；在这里再判一次，只会得到一条
 * 永远走不到的风险原因，让人误以为「违禁词商品会进待审队列」。
 *
 * <h2>默认清单是拍板的，不是查来的</h2>
 * 「哪些类目属于前置许可」在真实业务里应该来自类目资质表（每个类目挂它需要的证）。
 * 当前系统没有这张表，所以用**类目名关键词**兜底，并在此写明假设：
 * <ul>
 *   <li>命中「药品 / 处方 / 疫苗 / 兽药 / 活体 / 医用」任一关键词的类目，视为前置许可类；</li>
 *   <li>它是**宁可多转人工**的保守口径 —— 宠物药品、处方粮、活体交易确实是需要
 *       前置许可的品类，把它们放进自动放行才是真的在赌。</li>
 * </ul>
 * 将来接上类目资质表时，把 {@link #RESTRICTED_CATEGORY_KEYWORDS} 的判定换成查表即可，
 * 调用方一行都不用改。
 */
public final class SpuRiskJudge {

    /** 前置许可类目关键词（默认清单，见类注释的假设说明） */
    private static final List<String> RESTRICTED_CATEGORY_KEYWORDS = List.of(
            "药品", "处方", "疫苗", "兽药", "活体", "医用");

    /**
     * 联系方式与站外链接。
     *
     * <p>为什么这条要转人工而不是直接拒：商品详情里出现一个电话号，可能是违规导流，
     * 也可能是「客服电话」这种正常写法（尤其是宠物药品、活体这类需要售后咨询的品类）。
     * 直接拒会误伤正常商家，放过去又确实有风险 —— 转人工正好。
     *
     * <h2>为什么手机号一段要 11 位、座机要带区号</h2>
     * 放宽成「任意 7~8 位数字」会立刻把「规格 5000000」「净重 12345678 克」全打成联系方式。
     * 误报的代价不只是运营多看几眼：一条天天误报的规则最终一定被人要求关掉，
     * 那才是真正的损失。所以这里宁可窄一点 —— 漏掉「只写 8 位本地座机」的极少数情形，
     * 也还有事后抽查兜底；而手机号（导流的主流方式，含 +86 写法）必须抓住。
     */
    private static final Pattern CONTACT_PATTERN = Pattern.compile(
            "(?i)(?<![\\d.])"
                    + "(?:(?:\\+?86[- ]?)?(?:1[3-9]\\d[- ]?\\d{4}[- ]?\\d{4}|0\\d{2,3}[- ]?\\d{7,8}))"
                    + "(?![\\d.])"
                    + "|[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"
                    + "|(?:微信|weixin|wechat|vx|v信|威信|扣扣|QQ)\\s*[:：号]?\\s*[\\w-]{4,}"
                    + "|(?:https?://|www\\.)[\\w.-]+\\.[a-z]{2,}");

    /** 价格异常：低于同类在售价的此比例即视为显著偏离（0.9 = 低于均价 10%） */
    private static final double PRICE_LOW_RATIO = 0.9;

    private SpuRiskJudge() {
    }

    /** 风险命中原因（{@code code} 给程序看，{@code label} 给运营看） */
    public record Hit(String code, String label) {
    }

    /**
     * 判定上下文。字段刻意做成「已归一化后的值」而不是商品实体：
     * 规则要与「怎么从库里取数据」解耦，否则单测得先造一个完整的 SpuEntity + SKU 列表。
     *
     * @param categoryName     类目名（取不到就传 null —— 取不到不等于没问题，也不等于有问题）
     * @param categoryAvgPrice 同类在售商品的均价（分）；null / 0 表示样本不足，不参与判定
     * @param sellerFlagged    该商家是否有驳回或停业记录
     */
    public record Context(String categoryName, String title, String subtitle, String detail,
                          long minPrice, Long categoryAvgPrice, boolean sellerFlagged) {
    }

    /**
     * 返回全部命中的风险；空列表 = 可以直接自动放行。
     *
     * <p>刻意返回**全部**命中项而不是遇第一个就返回：运营在审核台看到「为什么转到我这」时，
     * 一次看全比一次看一条更省事；而且这些原因会被写进审核留痕，
     * 事后复盘「这条规则是不是误伤太多」需要的是完整命中分布。
     */
    public static List<Hit> risks(Context ctx) {
        List<Hit> hits = new ArrayList<>();
        if (ctx == null) {
            return hits;
        }
        if (hitRestrictedCategory(ctx.categoryName())) {
            hits.add(new Hit("RESTRICTED_CATEGORY",
                    "类目「" + ctx.categoryName() + "」属前置许可类，需人工核验资质"));
        }
        if (hitContactInfo(joinText(ctx.title(), ctx.subtitle(), ctx.detail()))) {
            hits.add(new Hit("CONTACT_INFO", "标题 / 副标题 / 详情含联系方式或站外链接，需人工判断是否违规导流"));
        }
        if (hitPriceAnomaly(ctx.minPrice(), ctx.categoryAvgPrice())) {
            hits.add(new Hit("PRICE_ANOMALY", "价格显著低于同类在售价，需人工核验（可能是错价或引流）"));
        }
        if (ctx.sellerFlagged()) {
            hits.add(new Hit("FLAGGED_SELLER", "该商家此前有审核驳回或停业记录，需人工复核"));
        }
        return hits;
    }

    /** 类目名是否命中前置许可关键词 */
    public static boolean hitRestrictedCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return false;
        }
        String name = categoryName.replace(" ", "");
        return RESTRICTED_CATEGORY_KEYWORDS.stream().anyMatch(name::contains);
    }

    /**
     * 单段文本是否含联系方式或站外链接。
     *
     * <p>注意调用方要把标题 / 副标题 / 详情**拼起来**再判（见 {@link #joinText}）：
     * 只判详情会让「把微信号写在标题里」成为一条绕过自动放行判定的捷径 ——
     * 而标题正是卖家最爱放联系方式的地方（买家一眼能看到）。
     */
    public static boolean hitContactInfo(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return CONTACT_PATTERN.matcher(text).find();
    }

    /** 把多段文本拼成一段再判：任一段含联系方式都算命中（null / 空白段跳过） */
    static String joinText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * 价格是否显著低于同类。
     *
     * <p>{@code avg} 为 null 或 0 时返回 false（样本不足不构成「异常」）——
     * 这条很重要：如果「算不出均价」也算异常，那么**新类目下的第一批商品永远进不了自动放行**，
     * 而它们恰恰是最需要自动化的（新类目上架最密集）。
     */
    public static boolean hitPriceAnomaly(long minPrice, Long avg) {
        if (avg == null || avg <= 0 || minPrice <= 0) {
            return false;
        }
        return minPrice < (long) Math.floor(avg * PRICE_LOW_RATIO);
    }
}
