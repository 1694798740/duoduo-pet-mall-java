package com.duoduo.marketing.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.marketing.dto.CouponDtos.ConsumeRequest;
import com.duoduo.marketing.dto.CouponDtos.ConsumeResult;
import com.duoduo.marketing.dto.CouponDtos.LockRequest;
import com.duoduo.marketing.dto.CouponDtos.LockResult;
import com.duoduo.marketing.dto.CouponDtos.ReleaseRequest;
import com.duoduo.marketing.dto.CouponDtos.ReleaseResult;
import com.duoduo.marketing.dto.CouponDtos.SweepResult;
import com.duoduo.marketing.dto.PersonalDataCleanupResult;
import com.duoduo.marketing.service.CouponReleaseJob;
import com.duoduo.marketing.service.CouponService;
import com.duoduo.marketing.service.PersonalDataCleanupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 券的内部接口（A5）：锁券 / 解锁 / 核销，供交易域调用。
 *
 * <p>路径以 {@code /api/coupons/internal/} 开头，由本域 {@code InternalTokenFilter}
 * 校验 {@code X-Internal-Token}；<b>不经网关</b>（网关白名单只放行
 * {@code /api/coupons/templates}，其余一律要求登录态，走了也只会被网关先按「未登录」拦掉，
 * 见 08 篇 T-25）。
 *
 * <p>三个动作刻意都<b>按订单号</b>定位而不是按券码：交易域在关单 / 支付时只知道订单号，
 * 「这单用了哪张券」是营销域自己的数据（{@code t_coupon.locked_order_no}）。
 * 让交易域为了调用而先记住券码，等于在两个服务之间复制同一份事实。
 */
@RestController
@RequestMapping("/api/coupons/internal")
public class CouponInternalController {

    private final CouponService couponService;
    private final CouponReleaseJob releaseJob;
    private final PersonalDataCleanupService personalDataCleanupService;

    public CouponInternalController(CouponService couponService,
                                    CouponReleaseJob releaseJob,
                                    PersonalDataCleanupService personalDataCleanupService) {
        this.couponService = couponService;
        this.releaseJob = releaseJob;
        this.personalDataCleanupService = personalDataCleanupService;
    }

    /**
     * 锁券（下单时）。
     *
     * <p>返回 {@code success=false} 而不是抛异常：调用方要把 {@code reason}
     * 原样转给下单用户（「订单金额未达门槛，该券满 200 元可用」）。
     * 抛异常的话，这层 HTTP 语义会把人话变成状态码。
     */
    @PostMapping("/lock")
    public ApiResponse<LockResult> lock(@Valid @RequestBody LockRequest request) {
        return ApiResponse.ok(couponService.lock(request));
    }

    /**
     * 券试算（<b>只读</b>，结算预览用）。
     *
     * <p>交易域在结算页要显示「用这张券能省多少」，但 <b>绝不能为此去调 {@code /lock}</b> ——
     * 锁券是写操作，会把用户的券置为 LOCKED。用户每改一次数量就看到券被占一次，
     * 最后没提交订单，券还被锁着等 TTL 解锁，这是纯粹的资损与体验事故。
     * 因此单开一个只读试算入口，与 {@code lock} 共用同一份校验规则（见
     * {@code CouponService#precheck}），保证「预览能用」与「下单能用」结论一致。
     *
     * <p>走 {@code /api/coupons/internal/**}，由服务间凭据保护，不经网关、不对外暴露。
     */
    @PostMapping("/trial")
    public ApiResponse<LockResult> trial(@Valid @RequestBody LockRequest request) {
        return ApiResponse.ok(couponService.trial(request.code(), request.userId(), request.orderAmount()));
    }

    /** 解锁（关单 / 取消 / 超时）。{@code released=0} 是常态（该单没用券），不是错误 */
    @PostMapping("/release")
    public ApiResponse<ReleaseResult> release(@Valid @RequestBody ReleaseRequest request) {
        return ApiResponse.ok(couponService.release(request.orderNo()));
    }

    /** 核销（支付成功）。USED 是终态，重复核销影响 0 行 */
    @PostMapping("/consume")
    public ApiResponse<ConsumeResult> consume(@Valid @RequestBody ConsumeRequest request) {
        return ApiResponse.ok(couponService.consume(request.orderNo()));
    }

    /**
     * 立即执行一轮补偿调度（运维排障与自动化测试用；正常由定时任务触发）。
     *
     * <p>暴露成接口是为了让「券被永久锁死」这类问题能在不重启、不等一分钟的前提下被验证与修复，
     * 而不是给运营一个随手可点的按钮 —— 它同样受内部凭据保护。
     */
    @PostMapping("/sweep")
    public ApiResponse<SweepResult> sweep() {
        return ApiResponse.ok(releaseJob.sweepNow());
    }

    /**
     * C7 · 账号注销的个人数据清理（服务间调用，见 {@code docs/account-deletion-design.md} 4.4）。
     *
     * <p><b>内部接口</b>：路径以 {@code /api/coupons/internal/} 开头，由本域
     * {@code InternalTokenFilter} 校验 {@code X-Internal-Token}（既有前缀已覆盖本路径，
     * 无需改 Filter）。调用方是用户域的注销编排，五域全部成功后才落
     * {@code t_user.account_status = DELETED}。
     *
     * <p>两种模式（查询串 {@code dryRun}）：
     * <ul>
     *   <li>{@code dryRun=true} —— 预检，只查不写，返回「将删多少行券」；</li>
     *   <li>缺省 / false —— 执行，删该用户未使用 / 已锁定 / 已过期的券。
     *       <b>幂等可重入</b>：调度失败重试时重复调用不报错、不二次破坏。</li>
     * </ul>
     *
     * <p>已核销（{@code USED}）的券刻意不删：它对应一笔真实订单，
     * 订单留档要回答「这单抵了多少」（理由见 {@code PersonalDataCleanupService} 类注释）。
     */
    @PostMapping("/users/{userId}/personal-data")
    public ApiResponse<PersonalDataCleanupResult> cleanupPersonalData(
            @PathVariable Long userId,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        PersonalDataCleanupResult result = dryRun
                ? personalDataCleanupService.preview(userId)
                : personalDataCleanupService.execute(userId);
        return ApiResponse.ok(result);
    }
}
