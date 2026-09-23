package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.client.dto.AddressCallDtos.ReceiverSnapshot;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用户地址访问网关（A8）：把「怎么调用户域取地址」与「取不到怎么处理」收敛到一处。
 *
 * <p><b>降级口径与商品域刻意相反，这是本节最需要注意的地方</b>：
 * <ul>
 *   <li>{@link ProductGateway} 对 4xx 一律返回空结果，因为「商品查不到」可以按业务事实处理
 *       （提示用户从购物车移除即可）；</li>
 *   <li>本网关对 4xx <b>必须拒单</b>：地址查不到 / 不属于当前用户，
 *       意味着这次请求的收货信息是无效的。绝不能「查不到就按空地址下单」——
 *       那会产出一张不知道寄给谁、寄到哪的订单，而钱已经扣了。</li>
 * </ul>
 *
 * <p>5xx 与网络故障转 503 {@code DEGRADED}：这里的语义是「系统暂时不能下单」，
 * 而不是「你的地址有问题」。两者必须分开，否则用户会去反复检查自己的地址簿，
 * 而问题其实在服务端。
 */
@Component
public class UserAddressGateway {

    private static final Logger log = LoggerFactory.getLogger(UserAddressGateway.class);

    private final UserAddressClient userAddressClient;

    public UserAddressGateway(UserAddressClient userAddressClient) {
        this.userAddressClient = userAddressClient;
    }

    /**
     * 取收货快照。返回 {@code null} 表示<b>该地址不可用</b>（不存在或不属于该用户），
     * 由调用方拒单并给出统一文案 —— 不区分「不存在」与「无权限」，
     * 与用户域的处理保持一致（避免用返回码差异枚举他人地址 ID）。
     */
    public ReceiverSnapshot load(Long userId, Long addressId) {
        try {
            ApiResponse<ReceiverSnapshot> response =
                    userAddressClient.snapshot(userId, addressId);
            // 用户域对「查不到」返回 200 + code=NOT_FOUND（见 AddressController#snapshot），
            // 因此这里必须看 code 而不是只看 data —— 只看 data 会把失败当成功调用方兜住。
            if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null) {
                log.warn("取收货地址快照失败 code={} userId={} addressId={}",
                        response == null ? "NULL_RESPONSE" : response.code(), userId, addressId);
                return null;
            }
            return response.data();
        } catch (FeignException e) {
            if (e.status() >= 400 && e.status() < 500) {
                // 4xx = 这个地址不可用，按业务校验失败处理（拒单）
                log.warn("取收货地址快照失败 {}（userId={} addressId={}）", e.status(), userId, addressId);
                return null;
            }
            throw new BizException(ErrorCode.DEGRADED, "地址服务暂时不可用，请稍后重试", e);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.DEGRADED, "地址服务暂时不可用，请稍后重试", e);
        }
    }
}
