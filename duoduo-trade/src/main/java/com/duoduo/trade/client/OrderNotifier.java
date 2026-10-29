package com.duoduo.trade.client;

import com.duoduo.trade.client.dto.NoticePushDtos.Item;
import com.duoduo.trade.client.dto.NoticePushDtos.PushRequest;
import com.duoduo.trade.client.dto.NoticePushDtos.PushResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单通知（第五十五轮）：把「订单发生了什么」翻译成给买家的通知，best-effort 推送。
 *
 * <p><b>为什么单独一层</b>：这条调用的成败语义与其它跨服务调用完全相反 ——
 * 冻结库存失败必须补偿、核销券失败必须让支付回滚，而通知推送失败<b>什么也不该发生</b>。
 * 把「失败了怎么办」写在调用点旁边，下一个人很可能顺手改成抛异常，
 * 于是「社交域抖一下，买家就付不了款」。策略收在这里，只有一个地方需要理解它。
 *
 * <p><b>为什么可以不管它失败</b>：
 * <ul>
 *   <li>订单的正确性由订单状态机、库存台账、资金托管决定，与通知里有没有这条记录无关；</li>
 *   <li>通知是**可重建**的：订单详情页永远能看到最新状态，
 *       丢了提醒最多是「用户晚一点知道」，不会造成任何资金或库存后果；</li>
 *   <li>反过来若让交易依赖它，就等于把通知服务的可用性抬到支付主链路上。</li>
 * </ul>
 *
 * <p><b>绝不抛出</b>：所有方法吞掉异常并记日志，调用方不需要 try/catch，
 * 也不该因为失败去改业务流程。由 {@code OrderService} 的
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 监听器调用 ——
 * 事务没提交完就推通知，用户会点进一个还查不到的订单。
 */
@Component
public class OrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(OrderNotifier.class);

    /** 通知中心分类：订单与交易（与社交域 t_social_notice.category 约定一致） */
    private static final String CATEGORY_ORDER = "ORDER";

    /** 落地页常量：必须与小程序 app.json 的真实页面完全一致（社交域按白名单校验，拼错会 400） */
    private static final String PAGE_ORDER_LIST = "/pages/orders/orders";
    private static final String PAGE_ORDER_DETAIL = "/pages/order-detail/order-detail";

    private final SocialNoticeClient socialNoticeClient;

    public OrderNotifier(SocialNoticeClient socialNoticeClient) {
        this.socialNoticeClient = socialNoticeClient;
    }

    /**
     * 下单成功。
     *
     * <p>一次结算可能因卖家不同拆成多单 —— 这种时候通知只发**一条**（一次下单在用户心里
     * 就是一个事件），落点改为订单列表，让他自己挑要看哪笔；单笔下单则直接落到该订单详情。
     */
    public void notifyPlaced(Long buyerId, List<String> orderNos, long totalPay) {
        if (buyerId == null || orderNos == null || orderNos.isEmpty()) {
            return;
        }
        boolean single = orderNos.size() == 1;
        String title = "订单已提交";
        String content = single
                ? "应付 ¥" + yuan(totalPay) + "，请尽快完成支付"
                : "本次结算生成 " + orderNos.size() + " 笔订单，合计 ¥" + yuan(totalPay) + "，请尽快完成支付";
        push(buyerId, title, content, "ORDER_PLACED",
                single ? orderNos.get(0) : null,
                single ? detailPage(orderNos.get(0)) : PAGE_ORDER_LIST);
    }

    /** 支付成功：钱已进平台托管，等卖家发货 */
    public void notifyPaid(Long buyerId, String orderNo, long amount) {
        push(buyerId, "支付成功", "已支付 ¥" + yuan(amount) + "，商家将尽快发货",
                "ORDER_PAID", orderNo, detailPage(orderNo));
    }

    /** 卖家发货：带上物流信息（可能为空 —— 卖家没填单号也允许发货） */
    public void notifyShipped(Long buyerId, String orderNo, String logisticsCompany, String logisticsNo) {
        String logistics = describeLogistics(logisticsCompany, logisticsNo);
        String content = "你的订单已发货"
                + (logistics == null ? "" : "（" + logistics + "）")
                + "，点击可查看物流";
        push(buyerId, "卖家已发货", content, "ORDER_SHIPPED", orderNo, detailPage(orderNo));
    }

    /** 退款成功 */
    public void notifyRefunded(Long buyerId, String orderNo, long amount) {
        push(buyerId, "退款成功", "已退款 ¥" + yuan(amount) + "，款项按原路退回",
                "ORDER_REFUNDED", orderNo, detailPage(orderNo));
    }

    /**
     * 重复支付自动退款（B-04）：同一订单被多收的那笔钱已按原路退回。
     *
     * <p>与 {@link #notifyRefunded} 分开：订单本身还在正常履约（钱进托管、卖家照常发货），
     * 退的只是**多收的那一笔**。文案必须把这点说清，否则买家会以为整单被退了、
     * 等着收货却看不到订单变动。
     */
    public void notifyAutoRefund(Long buyerId, String orderNo, long amount) {
        push(buyerId, "重复支付已退款",
                "检测到订单重复支付，多收的 ¥" + yuan(amount) + " 已按原路退回；订单本身不受影响",
                "PAYMENT_AUTO_REFUND", orderNo, detailPage(orderNo));
    }

    // ---------------- 内部 ----------------

    /** 真正的推送（唯一一处 try/catch）：失败只记日志，绝不向上抛 */
    private void push(Long userId, String title, String content, String bizType, String bizId, String jumpPage) {
        try {
            var response = socialNoticeClient.push(new PushRequest(List.of(
                    new Item(userId, CATEGORY_ORDER, title, content, bizType, bizId, jumpPage))));
            PushResult result = response == null ? null : response.data();
            if (result == null || result.accepted() < 1) {
                log.warn("订单通知未被接受 userId={} bizType={} bizId={}（不影响交易）", userId, bizType, bizId);
                return;
            }
            log.info("订单通知已送达 userId={} bizType={} bizId={}", userId, bizType, bizId);
        } catch (RuntimeException e) {
            log.error("订单通知推送失败 userId={} bizType={} bizId={}（不影响交易）", userId, bizType, bizId, e);
        }
    }

    /** 订单详情落地页（orderNo 由订单号生成器产出，字符集安全，无需额外转义） */
    private static String detailPage(String orderNo) {
        return PAGE_ORDER_DETAIL + "?orderNo=" + orderNo;
    }

    /** 物流描述：「顺丰速运 SF1234567890」；两者都没有时返回 null（不发括号里空内容） */
    private static String describeLogistics(String company, String logisticsNo) {
        String c = trimToNull(company);
        String n = trimToNull(logisticsNo);
        if (c == null && n == null) {
            return null;
        }
        return c == null ? n : (n == null ? c : c + " " + n);
    }

    /** 分 → 元（整数运算，不用浮点）：1234 → "12.34"；100 → "1.00" */
    private static String yuan(long cents) {
        long abs = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
