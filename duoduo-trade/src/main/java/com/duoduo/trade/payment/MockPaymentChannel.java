package com.duoduo.trade.payment;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 模拟渠道：<b>预下单即成功</b>。
 *
 * <p>它的存在意义不是"造假"，而是给自动化测试与最简本地联调一个
 * **不依赖任何外部条件**的收款路径：用例只要断言"支付成功后订单与资金的状态"，
 * 就不必去构造签名、等回调。
 *
 * <p>它与 {@link SandboxPaymentChannel} 的差别只有一点：
 * 后者会把"用户真的去渠道侧付了一次"这件事变成一次**带签名的回调**，
 * 因而能覆盖验签、幂等、金额核对这些真实渠道才有的路径。
 * 两条路径共用同一套订单与账本代码，这正是渠道抽象要证明的事。
 *
 * <p><b>它不接受回调</b>：既然预下单即成功，就不存在"稍后回调"这件事。
 * 收到它的回调只有两种可能 —— 配错了渠道，或者有人在伪造。两种都该拒绝。
 */
@Component
public class MockPaymentChannel implements PaymentChannel {

    public static final String CODE = "MOCK";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean instantSuccess() {
        return true;
    }

    @Override
    public PrepayResult prepay(PrepayCommand command) {
        // 即时成功渠道没有真实二维码；给一个可辨识的伪协议串，避免前端误以为是可扫的地址
        return new PrepayResult("mock://instant-pay/" + command.payNo(),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30));
    }

    @Override
    public CallbackPayload verifyCallback(String rawBody, Map<String, String> headers) {
        throw new BizException(ErrorCode.PAYMENT_SIGNATURE_INVALID,
                "模拟渠道（MOCK）不支持支付回调：它在预下单时即完成收款");
    }

    /**
     * 退款（B-04）：模拟渠道没有真实资金流，「受理即到终态」。
     *
     * <p>实现它而不是沿用接口默认的「不支持」：重复收款的退款台账若遇到
     * 永远不支持退款的渠道，会一路 INIT 重试并刷错误日志；模拟渠道既然
     * 扮演的是「一个能正常收款的渠道」，退款能力也应当一并扮演。
     */
    @Override
    public RefundResult refund(RefundCommand command) {
        return new RefundResult(command.refundNo(), "MOCK-REFUND-" + command.refundNo(), "SUCCESS", true);
    }
}
