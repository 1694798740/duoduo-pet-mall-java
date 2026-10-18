package com.duoduo.fulfilment.client;

import com.duoduo.fulfilment.client.dto.NoticePushDtos.Item;
import com.duoduo.fulfilment.client.dto.NoticePushDtos.PushRequest;
import com.duoduo.fulfilment.client.dto.NoticePushDtos.PushResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 履约域通知（第五十五轮）：售后进度 + 物流终态 → 买家的通知，best-effort 推送。
 *
 * <p><b>为什么单独立一层</b>：这条调用的成败语义与同域的其它跨服务调用完全相反 ——
 * 退款失败必须让售后单停在原状态（{@code AfterSaleService} 靠 CAS 行数判定），
 * 而通知推送失败<b>什么也不该发生</b>。策略收在这里，只有一个地方需要理解它。
 *
 * <p><b>为什么通知只发给买家</b>：卖家侧的售后处理目前在小程序里没有入口
 * （商家端是另一套界面），推一条「点了不知道去哪」的通知比不推更糟。
 * 所以本域只覆盖买家的四个售后节点与一个物流终态。
 *
 * <p><b>物流为什么只推「已签收」</b>：包裹一生会有十来个节点（揽收 / 中转 / 派送…），
 * 逐个推会把通知中心变成快递流水账 —— 而订单详情页的时间线本来就是为看这个准备的。
 * 「已签收」是唯一需要用户**立刻行动**（去确认收货 / 检查商品）的节点，只推它。
 *
 * <p><b>绝不抛出</b>：所有方法吞异常并记日志。调用方不需要 try/catch，
 * 也不该因为失败去改业务流程。
 */
@Component
public class FulfilmentNotifier {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentNotifier.class);

    /** 通知中心分类：订单与交易（售后与物流在用户眼里都属于「我的订单」） */
    private static final String CATEGORY_ORDER = "ORDER";

    /**
     * 通知中心分类：好友 / 社交（第五十六轮，分享圈评论走这一类）。
     *
     * <p>为什么不用 ORDER：评论通知讲的是「有人在你发的内容下说话」，
     * 与订单无关。分类混错会让用户在「订单」筛选里看到一堆社交提醒 ——
     * 分类是给用户做筛选用的，语义必须站得住。
     */
    private static final String CATEGORY_FRIEND = "FRIEND";

    /**
     * 售后相关的落地页：小程序的「我的售后」列表。
     *
     * <p>小程序侧不深链到具体售后单：目前没有独立的售后详情页，拼一个不存在的路径
     * 会被社交域的白名单校验挡下（那正是白名单存在的意义）。
     *
     * <p><b>bizId 的口径（2026-10-18 起）</b>：AFTER_SALE_* 通知的 {@code bizId}
     * 存<b>售后单 id</b>（不再是订单号）—— 网页端要深链到 {@code /after-sales/{id}} 详情页，
     * 而一张订单可能有多次售后，用订单号定位不到唯一一条。网页端的映射见
     * 社交域 {@code NoticeWebPaths}；小程序仍用本页面的列表落地页，不受影响。
     */
    private static final String PAGE_AFTER_SALES = "/pages/after-sales/after-sales";
    private static final String PAGE_ORDER_DETAIL = "/pages/order-detail/order-detail";
    /** 分享详情页（第五十六轮）：评论通知的落地页 —— 点进去直接看到被评论的那条分享 */
    private static final String PAGE_SHARE_DETAIL = "/pages/share-detail/share-detail";

    /** 摘要在通知里只占几行，用户原因 / 裁定说明可能很长，超长截断（服务端另有 500 字硬限） */
    private static final int MAX_BRIEF = 200;

    private final SocialNoticeClient socialNoticeClient;

    public FulfilmentNotifier(SocialNoticeClient socialNoticeClient) {
        this.socialNoticeClient = socialNoticeClient;
    }

    /** 买家立案成功：给一条回执，让用户确认「平台收到了」 */
    public void notifyAfterSaleCreated(Long buyerId, String orderNo, Long afterSaleId) {
        push(buyerId, "售后申请已提交", "订单的售后申请已受理，等待卖家处理",
                "AFTER_SALE_CREATED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /** 卖家同意退货（第五十八轮 P1-5）：告诉买家「接下来该你寄回了」，否则他会一直等退款 */
    public void notifyReturnAgreed(Long buyerId, String orderNo, Long afterSaleId) {
        push(buyerId, "卖家已同意退货", "请尽快寄回商品并填写物流单号，卖家签收后即退款",
                "AFTER_SALE_RETURN_AGREED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /**
     * 买家已寄回：告诉卖家去收货，否则这条单子会一直停在「待收货」。
     *
     * <p>落地页仍用 {@link #PAGE_AFTER_SALES}：小程序侧只有这一个售后页路径在白名单里，
     * 拼一个「卖家售后页」的路径会被社交域的白名单校验挡下 —— 而那种错误既不报错也
     * 不打日志，只表现为「点了通知没反应」。要用新页面，得先加进 {@code MiniProgramPages}。
     */
    public void notifyReturnShipped(Long sellerId, String orderNo, Long afterSaleId, String trackingNo) {
        push(sellerId, "买家已寄回商品", "运单号 " + trackingNo + "，收到货后请确认收货并退款",
                "AFTER_SALE_RETURN_SHIPPED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /** 卖家同意退款 */
    public void notifyAfterSaleRefunded(Long buyerId, String orderNo, Long afterSaleId, long amount) {
        push(buyerId, "售后已同意退款", "卖家已同意退款 ¥" + yuan(amount) + "，款项按原路退回",
                "AFTER_SALE_REFUNDED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /** 卖家拒绝（带上原因，并提示还有仲裁这条路） */
    public void notifyAfterSaleRejected(Long buyerId, String orderNo, Long afterSaleId, String reason) {
        String brief = brief(reason);
        push(buyerId, "售后被拒绝",
                "卖家拒绝了售后申请" + (brief == null ? "" : "：" + brief) + "。如不认可，可申请平台介入",
                "AFTER_SALE_REJECTED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /** 买家申请平台仲裁：回执 */
    public void notifyAfterSaleEscalated(Long buyerId, String orderNo, Long afterSaleId) {
        push(buyerId, "已申请平台介入", "平台将介入处理该售后，请留意后续结果",
                "AFTER_SALE_ESCALATED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /** 运营裁定支持退款（终态） */
    public void notifyAfterSaleArbitratedRefund(Long buyerId, String orderNo, Long afterSaleId, long amount, String note) {
        String brief = brief(note);
        push(buyerId, "平台裁定支持退款",
                "平台已裁定支持退款 ¥" + yuan(amount) + (brief == null ? "" : "：" + brief),
                "AFTER_SALE_ARBITRATED", String.valueOf(afterSaleId), PAGE_AFTER_SALES);
    }

    /** 运营裁定驳回（终态） */
    public void notifyAfterSaleArbitratedRejected(Long buyerId, String orderNo, Long afterSaleId, String note) {
        String brief = brief(note);
        push(buyerId, "平台裁定结果",
                "售后申请经平台审核未获支持" + (brief == null ? "" : "：" + brief),
                "AFTER_SALE_ARBITRATED", orderNo, PAGE_AFTER_SALES);
    }

    /** 包裹已签收（物流侧唯一推送的节点） */
    public void notifySigned(Long buyerId, String orderNo) {
        push(buyerId, "包裹已签收", "你的包裹已签收，去订单详情查看物流或确认收货",
                "LOGISTICS_SIGNED", orderNo, PAGE_ORDER_DETAIL + "?orderNo=" + orderNo);
    }

    // ---------------- 分享圈（第五十六轮） ----------------

    /**
     * 有人评论了你的分享。
     *
     * <p>bizId 用**分享 id**（不是评论 id）：同一条分享下的多条评论会被社交域
     * 按「同来源合并成一条未读」处理 —— 用户看到的是「有人评论了你的分享」，
     * 而不是十条各说各话的提醒。用户点进去就是那条分享的评论区。
     */
    public void notifyShareCommented(Long authorId, Long shareId, String commenterNickname, String excerpt) {
        push(authorId, "有人评论了你的分享",
                who(commenterNickname) + "评论道：" + brief(excerpt),
                "SHARE_COMMENT", String.valueOf(shareId),
                PAGE_SHARE_DETAIL + "?id=" + shareId);
    }

    /** 有人回复了你的评论 */
    public void notifyShareReplied(Long commentAuthorId, Long shareId, String replyNickname, String excerpt) {
        push(commentAuthorId, "有人回复了你",
                who(replyNickname) + "回复：" + brief(excerpt),
                "SHARE_COMMENT", String.valueOf(shareId),
                PAGE_SHARE_DETAIL + "?id=" + shareId);
    }

    // ---------------- 内部 ----------------

    /** 真正的推送（唯一一处 try/catch）：失败只记日志，绝不向上抛 */
    private void push(Long userId, String title, String content, String bizType,
                      String bizId, String jumpPage) {
        push(userId, CATEGORY_ORDER, title, content, bizType, bizId, jumpPage);
    }

    /** 真正的推送（唯一一处 try/catch）：失败只记日志，绝不向上抛 */
    private void push(Long userId, String category, String title, String content, String bizType,
                      String bizId, String jumpPage) {
        if (userId == null) {
            return;
        }
        try {
            var response = socialNoticeClient.push(new PushRequest(List.of(
                    new Item(userId, category, title, content, bizType, bizId, jumpPage))));
            PushResult result = response == null ? null : response.data();
            if (result == null || result.accepted() < 1) {
                log.warn("履约通知未被接受 userId={} bizType={} bizId={}（不影响主流程）",
                        userId, bizType, bizId);
                return;
            }
            log.info("履约通知已送达 userId={} bizType={} bizId={}", userId, bizType, bizId);
        } catch (RuntimeException e) {
            log.error("履约通知推送失败 userId={} bizType={} bizId={}（不影响主流程）",
                    userId, bizType, bizId, e);
        }
    }

    /** 「某某」：昵称不可得时退化成一个中性主语，不出现「null 评论道」这种句子 */
    private static String who(String nickname) {
        String normalized = nickname == null ? "" : nickname.trim();
        return normalized.isEmpty() ? "有人" : normalized;
    }

    /** 摘要裁剪：通知里只放得下几行，超长截断加省略号 */
    private static String brief(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.length() <= MAX_BRIEF ? normalized : normalized.substring(0, MAX_BRIEF - 1) + "…";
    }

    /** 分 → 元（整数运算，避免浮点误差）：1234 → "12.34" */
    private static String yuan(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
