package com.duoduo.trade.payment;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 支付渠道抽象（SPI）。
 *
 * <p><b>为什么要有这一层</b>：订单、库存冻结、资金托管、回调处理这些逻辑
 * 与「用哪家渠道收款」毫无关系。把它们绑死在某个渠道的 SDK 上，
 * 将来换渠道（沙箱 → 微信 → 再加支付宝）就得改动订单与账本代码 ——
 * 那是改动风险最高、最不该碰的地方。
 *
 * <p>所以约定：<b>渠道差异只允许出现在本接口的实现类里</b>。
 * 目前有两个实现：
 * <ul>
 *   <li>{@link MockPaymentChannel}（{@code MOCK}）：预下单即成功，用于回归测试与本地最简联调；</li>
 *   <li>{@link SandboxPaymentChannel}（{@code SANDBOX}）：完整流程 ——
 *       预下单拿二维码内容 → 用户支付 → 回调验签 → 幂等入账。
 *       形态与真实渠道完全一致，将来接入微信 / 支付宝时照抄这个骨架。</li>
 * </ul>
 *
 * <p><b>一次支付的生命周期</b>（渠道无关的部分）：
 * <pre>
 *   预下单(prepay) ──→ 支付单 PENDING ──→ 渠道回调(verifyCallback) ──→ 入账 → 订单转已付款
 *                            └─ 超时 ──→ 支付单 EXPIRED
 * </pre>
 */
public interface PaymentChannel {

    /** 渠道代码（存库用，大写），如 {@code MOCK} / {@code SANDBOX} / {@code WECHAT} / {@code ALIPAY} */
    String code();

    /**
     * 是否「预下单即成功」。
     *
     * <p>只有模拟渠道是 {@code true}。真实渠道（含沙箱）都是 {@code false}：
     * 预下单只负责生成支付凭证，钱到没到必须以**渠道回调**为准 ——
     * 把「预下单成功」当成「已付款」是支付系统最经典的严重缺陷。
     */
    boolean instantSuccess();

    /**
     * 预下单：向渠道登记一笔支付，拿到用户可扫的二维码内容。
     *
     * @param command 预下单参数（支付单号 / 订单号 / 金额 / 商品标题）
     * @return 二维码内容与支付单过期时间
     */
    PrepayResult prepay(PrepayCommand command);

    /**
     * 校验渠道回调并解析出有效载荷。
     *
     * <p>实现必须做三件事，缺一不可：
     * <ol>
     *   <li><b>验签</b>：用渠道密钥校验签名，**失败必须抛错**（不能"验不过就放过"）；</li>
     *   <li><b>防重放</b>：校验时间戳窗口，避免旧回调被反复投递；</li>
     *   <li>解析出支付单号、渠道流水号、金额、状态。</li>
     * </ol>
     *
     * <p>调用方还会再核对一遍「回调金额 == 支付单金额」——
     * 签名只证明「这条回调来自渠道」，不证明「金额没被改过」。
     *
     * @throws com.duoduo.common.exception.BizException 验签失败 / 时间戳超窗 / 报文无法解析
     */
    CallbackPayload verifyCallback(String rawBody, Map<String, String> headers);

    /** 预下单参数 */
    record PrepayCommand(String payNo, String orderNo, long amount, String subject) {
    }

    /** 预下单结果 */
    record PrepayResult(String codeUrl, LocalDateTime expireAt) {
    }

    /** 渠道回调的有效载荷（已通过验签） */
    record CallbackPayload(String payNo, String channelTradeNo, long amount, String status, LocalDateTime paidAt) {
    }
}
