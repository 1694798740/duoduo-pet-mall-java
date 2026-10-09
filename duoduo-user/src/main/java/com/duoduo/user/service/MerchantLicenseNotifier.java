package com.duoduo.user.service;

import com.duoduo.user.client.ProductOffShelfClient;
import com.duoduo.user.client.SocialNoticeClient;
import com.duoduo.user.client.dto.LicenseNoticeDtos.Item;
import com.duoduo.user.client.dto.LicenseNoticeDtos.PushRequest;
import com.duoduo.user.entity.MerchantEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资质相关的跨域副作用（E15）：发通知、下架商品。
 *
 * <h2>为什么单独一个类</h2>
 * 这两件事都是「用户域做了判断、别的域去执行」，而且都必须<b>best-effort</b>：
 * <ul>
 *   <li>通知发失败 → 商家少一条提醒，但店铺状态本身是对的；</li>
 *   <li>下架失败 → 商品还在卖，但「已停业」这个判定必须先成立。</li>
 * </ul>
 * 若把这两段 try/catch 直接写在任务里，任务本体（状态流转）的代码会被
 * 一堆异常处理埋掉，而最该一眼看清的恰恰是「状态怎么变」。
 *
 * <h2>best-effort 不等于静默</h2>
 * 全部失败路径都打 WARN/ERROR 日志。<b>提醒发失败尤其要能看见</b>：
 * 它的后果（商家不知道要续期 → 店被停业 → 投诉）比状态写错更难追查，
 * 因为状态一切正常。
 */
@Service
public class MerchantLicenseNotifier {

    private static final Logger log = LoggerFactory.getLogger(MerchantLicenseNotifier.class);

    /** 通知分类：商家消息（社交域白名单值，与 MessageService 的分流口径一致） */
    private static final String CATEGORY_MERCHANT = "MERCHANT";
    private static final String BIZ_EXPIRING = "MERCHANT_LICENSE_EXPIRING";
    private static final String BIZ_EXPIRED = "MERCHANT_LICENSE_EXPIRED";
    /** E28：因累计商品违规被停业 */
    private static final String BIZ_VIOLATION_SUSPEND = "MERCHANT_VIOLATION_SUSPEND";

    private final SocialNoticeClient socialNoticeClient;
    private final ProductOffShelfClient productOffShelfClient;

    public MerchantLicenseNotifier(SocialNoticeClient socialNoticeClient,
                                   ProductOffShelfClient productOffShelfClient) {
        this.socialNoticeClient = socialNoticeClient;
        this.productOffShelfClient = productOffShelfClient;
    }

    /**
     * 发一条临期提醒。
     *
     * @param materialLabel 到期材料的展示名（如「营业执照」）。刻意传<b>字符串</b>而不是
     *                      {@code MerchantMaterialType}：调用方在极少数情况下（材料行缺失）
     *                      只能用兜底文案，用一个必须存在的枚举会让那条路径被迫伪造一个类型。
     * @return <b>true 表示通知已送达</b>（服务端真的落了库）。调用方据此决定要不要记下
     *         「本档位已提醒」—— 失败时不记，下一轮扫描会重试。宁可多试几次，
     *         也不能出现「库里记着提醒过了、商家其实没收到」这种没人能发现的漏报。
     */
    public boolean notifyExpiring(MerchantEntity merchant, String materialLabel,
                                  LocalDateTime expireAt, long remainingDays) {
        String urgent = remainingDays <= 7 ? "【紧急】" : "【资质提醒】";
        String title = urgent + materialLabel + "将于 " + remainingDays + " 天后到期";
        String content = "您的「" + materialLabel + "」有效期至 " + expireAt.toLocalDate()
                + "（剩余 " + remainingDays + " 天）。请及时上传有效期内的新证件并提交资质续期，"
                + "到期后店铺将自动暂停营业。";
        return push(merchant, BIZ_EXPIRING, title, content);
    }

    /**
     * 发一条「已停业」通知。
     *
     * <p>这条通知必须<b>说清停业原因与下一步动作</b>：商家看到的是「我的店突然没了」，
     * 如果通知只说「店铺已停业」，第一反应一定是找客服而不是去续期。
     *
     * @return 是否送达（停业通知失败不重发 —— 状态已是 SUSPENDED，重扫不会再命中；
     *         商家进商家端会看到常驻横幅，所以不会真的「完全不知情」）
     */
    public boolean notifySuspended(MerchantEntity merchant, String materialLabel,
                                   LocalDateTime expireAt) {
        String title = "【已停业】" + materialLabel + "已过期，店铺已暂停营业";
        String content = "您的「" + materialLabel + "」已于 " + expireAt.toLocalDate()
                + " 到期，店铺已自动暂停营业（已售出订单不受影响，可正常发货与售后）。"
                + "请上传有效期内的新证件并提交资质续期，审核通过后即可恢复营业。";
        return push(merchant, BIZ_EXPIRED, title, content);
    }

    /**
     * 发一条「因累计违规被停业」通知（E28）。
     *
     * <p>为什么不能复用 {@link #notifySuspended}：那条的文案是围绕「证件到期」写的
     * （「您的营业执照已于 X 日到期」）。套到违规场景会变成
     * 「您的『商品违规』已于 X 日到期」—— 商家看完只会更困惑。
     * 通知文案必须说清**停业的真实原因**，否则商家第一反应是找客服而不是去核查商品。
     *
     * <p>文案里刻意带上最近一次违规原因：只说「您违规了」而不同时说清是哪一件、
     * 什么原因，商家没有任何可执行动作，只能反复找客服。
     *
     * @return 是否送达（与资质停业通知同一口径：失败不重发，商家端有常驻提示兜底）
     */
    public boolean notifyViolationSuspended(MerchantEntity merchant, int violationCount, String reason) {
        String title = "【已停业】店铺因累计 " + violationCount + " 件商品违规被暂停营业";
        String content = "您的店铺累计有 " + violationCount + " 件商品在审核中被判违规，已暂停营业"
                + "（已售出订单不受影响，可正常发货与售后）。"
                + "最近一次原因：" + (reason == null ? "商品内容违规" : reason)
                + "。请核对商品内容后联系平台处理。";
        return push(merchant, BIZ_VIOLATION_SUSPEND, title, content);
    }

    /**
     * 联动下架该店铺的全部在售商品。
     *
     * @return 实际下架的商品数；调用失败返回 {@code -1}（与「下架 0 件」区分开 ——
     *         前者要人去查，后者是正常情况）
     */
    public int offShelfAll(Long sellerId) {
        try {
            Integer count = productOffShelfClient.offShelfBySeller(sellerId).data();
            log.info("资质过期联动下架完成 sellerId={} 下架 {} 件", sellerId, count);
            return count == null ? -1 : count;
        } catch (Exception ex) {
            log.error("资质过期联动下架失败 sellerId={} —— 店铺已停业但商品可能仍在售，需人工核查",
                    sellerId, ex);
            return -1;
        }
    }

    private boolean push(MerchantEntity merchant, String bizType, String title, String content) {
        try {
            var response = socialNoticeClient.push(new PushRequest(List.of(new Item(
                    merchant.getOwnerUserId(),
                    CATEGORY_MERCHANT,
                    title,
                    content,
                    bizType,
                    String.valueOf(merchant.getId()),
                    null))));      // jumpPage 留空：续期在商家端网页办，见 LicenseNoticeDtos.Item 注释
            long accepted = response.data() == null ? 0 : response.data().accepted();
            if (accepted <= 0) {
                log.warn("资质通知未被接受 merchantId={} bizType={}", merchant.getId(), bizType);
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.warn("资质通知发送失败（不影响店铺状态）merchantId={} bizType={} : {}",
                    merchant.getId(), bizType, ex.getMessage());
            return false;
        }
    }
}
