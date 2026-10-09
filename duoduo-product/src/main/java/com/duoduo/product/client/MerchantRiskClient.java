package com.duoduo.product.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.client.dto.MerchantRiskInfo;
import com.duoduo.product.client.dto.MerchantViolationResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户域商家风险画像客户端（E28）：商品提交时判「自动放行还是转人工」。
 *
 * <p><b>contextId 必须显式声明</b>：本域已有两个指向 {@code duoduo-user} 的接口
 * （{@link MembershipClient}、{@link MerchantLicenseClient}），Feign 以 {@code name}
 * 为注册键，不区分 {@code contextId} 会互相覆盖、启动即炸（{@code BeanDefinitionOverrideException}）。
 *
 * <p><b>调用失败一律按「无风险标记」处理</b>（调用方负责兜底，见
 * {@code SellerProductService#resolveAuditDecision}）：
 * 这条规则只是「多看一眼」的加分项，不是安全边界。若在 Feign 抖动时改成
 * 「查不到就转人工」，用户域一次重启就会让全平台的商品提交全部堆进待审队列 ——
 * 用一个可自愈的小问题换一次审核积压，代价完全不成比例。
 */
@FeignClient(name = "duoduo-user", contextId = "productMerchantRiskClient", path = "/api/users")
public interface MerchantRiskClient {

    /**
     * 按店主 userId 查风险画像。
     *
     * <p>返回体 {@code data} 为 {@code null} 表示该用户没有入驻申请
     * （平台自营的商品归属、或从未申请过）—— 调用方按「无标记」处理。
     */
    @GetMapping("/internal/merchant-risk")
    ApiResponse<MerchantRiskInfo> of(@RequestParam("ownerUserId") Long ownerUserId);

    /**
     * 上报一次商家违规（E28）：用户域累加违规计数，达阈值则自动升级为商家级停业。
     *
     * <p>为什么由商品域发起而不是用户域自己扫：违规判定发生在**商品侧**
     * （人工驳回、抽查判违规），用户域无从得知。做成「商品域判完主动上报」，
     * 比「用户域定时扫商品库」少一次全量扫描，也不会因为扫描周期让停业迟到。
     *
     * <p><b>上报失败不回滚已完成的商品动作</b>：商品已经被驳回并且下架了，
     * 那是已经发生的事实；违规计数少加一次只会让停业晚一步，
     * 而回滚商品动作会让「违规商品仍在售」—— 两者代价完全不对称。
     * 调用方按 best-effort 处理并把失败写进日志。
     */
    @PostMapping("/internal/merchant-violation")
    ApiResponse<MerchantViolationResult> markViolation(
            @RequestParam("ownerUserId") Long ownerUserId,
            @RequestParam("reason") String reason);
}
