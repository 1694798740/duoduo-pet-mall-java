package com.duoduo.user.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.user.client.dto.AccountDeletionDtos.PersonalDataCleanupResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * C7 · 账号注销：社交域：删关注、私信、通知已读。
 *
 * <p><b>为什么必须走内部端点而不是用户域直连库</b>：这些表属于 duoduo-social 的库，
 * 跨库直连是本项目明令禁止的硬约束；且各域可以在自己的事务里顺带处理
 * 索引与缓存，不会留下「库改了、索引还是旧的」这种半截状态。
 *
 * <p><b>幂等可重入是硬要求</b>：注销执行分「五域清理 → 全部成功 → 置 DELETED」两步，
 * 任一步失败都要靠下一轮调度重试。DELETE / UPDATE 语句本身幂等，
 * 因此重复调用不会二次破坏，也不需要分布式锁。
 *
 * <p>{@code dryRun=true} 用于<b>预检</b>：不写任何数据，只回「将会删多少、脱敏多少、
 * 有多少行会挡住注销」，让申请阶段就能把「先去处理哪几件事」讲清楚。
 */
@FeignClient(name = "duoduo-social", contextId = "accountDeletionSocialClient", path = "/api/socials/internal")
public interface SocialPersonalDataClient {

    /**
     * 清理（或预检）某用户的个人数据。
     *
     * @param userId 用户 id
     * @param dryRun true = 只查不写（预检）
     */
    @PostMapping("/users/{userId}/personal-data")
    ApiResponse<PersonalDataCleanupResult> cleanupPersonalData(
            @PathVariable("userId") Long userId,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun);
}
