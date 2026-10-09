package com.duoduo.user.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 入驻侧「必须转人工」的风险规则（E28）。
 *
 * <h2>与商品侧的分工</h2>
 * 商品侧判「这件商品敢不敢自动放行」，这里判「这家店敢不敢直接开张」。
 * 与 {@code SpuRiskJudge} 同一形态：命中不代表有问题，只代表「值得人看一眼」；
 * 判定结果不拦截提交，只把状态从「自动通过」改成「待审」。
 *
 * <h2>★ 附录 D 里有两类规则，当前**判不了**，这里如实写明</h2>
 * 方案里写的是「同一法人 / 身份证 / 营业执照号重复注册」与「同 IP / 设备短时间批量注册」。
 * 这两条现在都无法判定，原因具体是：
 * <ul>
 *   <li><b>证件号类</b>：营业执照号、法人身份证号在系统里是**上传的图片**，
 *       没有结构化字段。要判重必须先给入驻表单加证件号输入项并做校验，
 *       那是独立的一件事（还牵涉证件号这种敏感信息的存储与脱敏），
 *       不能靠在图片上「看一眼」实现；</li>
 *   <li><b>来源类</b>：请求里没有可靠记录 IP / 设备的字段。网关侧加来源记录
 *       并落到入驻申请上，同样是一件独立的事。</li>
 * </ul>
 * 所以本轮**不假装实现**它们：与其写两条永远返回 false 的规则（让后来人以为已经防住了），
 * 不如把它们明确列为「待接入」。真正可判的两条写在下面。
 */
public final class MerchantRiskRules {

    /**
     * 材料剩余有效期低于此天数 → 转人工。
     *
     * <p>为什么这条要转人工而不是直接拒绝：差 20 天到期不等于假证，
     * 可能是商家确实刚换了新证还没拿到；但它确实是一个**强信号** ——
     * 拿一张快过期的证来开店，最常见的动机是「先开起来再说」。
     * 让运营看一眼，比直接拒（误伤真实商家）或直接放（放进来 30 天后又要停业）都好。
     */
    public static final int MIN_REMAINING_DAYS = 30;

    /**
     * 店名里的冒用 / 误导词。
     *
     * <p>与商品违禁词同一取向：这是「宁可多转人工」的保守清单。
     * 「官方」「旗舰」「自营」这类词在电商场景里有明确的误导性 ——
     * 一家第三方店叫「多多官方旗舰店」，买家不会去分辨它是不是平台自营。
     */
    private static final List<String> IMPERSONATION_WORDS = List.of(
            "官方", "旗舰", "专卖", "自营", "认证", "国家级", "直属", "总代");

    private MerchantRiskRules() {
    }

    public record Hit(String code, String label) {
    }

    /**
     * @param storeName         店铺名（为空表示还没填 —— 提交时已强制校验，正常不会为空）
     * @param nearestExpireDays 所有带有效期材料里「最紧迫」的那个剩余天数；
     *                          null = 没有任何材料带有效期（长期有效），不构成风险
     */
    public record Context(String storeName, Long nearestExpireDays) {
    }

    /** 返回全部命中的风险；空列表 = 可以直接自动放行 */
    public static List<Hit> risks(Context ctx) {
        List<Hit> hits = new ArrayList<>();
        if (ctx == null) {
            return hits;
        }
        if (ctx.nearestExpireDays() != null && ctx.nearestExpireDays() < MIN_REMAINING_DAYS) {
            hits.add(new Hit("MATERIAL_EXPIRING_SOON",
                    "资质材料剩余有效期不足 " + MIN_REMAINING_DAYS + " 天（还剩 "
                            + ctx.nearestExpireDays() + " 天），需人工核验证件真实性"));
        }
        String name = ctx.storeName() == null ? "" : ctx.storeName().replace(" ", "");
        for (String word : IMPERSONATION_WORDS) {
            if (name.contains(word)) {
                hits.add(new Hit("IMPERSONATION_NAME",
                        "店铺名含「" + word + "」，可能误导买家以为是平台官方 / 授权店，需人工核验"));
                break;      // 同一条规则只报一次：报四个词出来只会让审核台更乱
            }
        }
        return hits;
    }
}
