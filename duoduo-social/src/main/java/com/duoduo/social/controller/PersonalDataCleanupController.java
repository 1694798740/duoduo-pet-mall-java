package com.duoduo.social.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.dto.PersonalDataCleanupResult;
import com.duoduo.social.service.PersonalDataCleanupService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C7 · 账号注销的个人数据清理（服务间调用，见 {@code docs/account-deletion-design.md} 4.4）。
 *
 * <p><b>为什么单独一个控制器</b>：本域既有的两个内部控制器各有更深的业务前缀
 * （{@code /api/socials/internal/following}、{@code /api/socials/internal/notices}），
 * 方法级映射永远挂在类级前缀之下，挂进它们任何一个都会把契约路径
 * {@code /api/socials/internal/users/{userId}/personal-data} 变成
 * {@code .../following/users/...} 这种四不像。为一条路径去改那两个类的既有映射
 * （把方法级路径改成 {@code /notices/...}）反而是对现网路径的无谓折腾，
 * 因此按前缀归属单开这一个薄壳 —— 与 {@code InternalListingController}（商品域）
 * 同一样式：类注释把「为什么在这」写清楚。
 *
 * <p><b>内部接口</b>：{@code /api/socials/internal/} 已在本域
 * {@link com.duoduo.social.support.InternalTokenFilter} 的列举清单里，
 * 本路径被既有前缀覆盖，无需改 Filter。调用方是用户域的注销编排，
 * 五域全部成功后才落 {@code t_user.account_status = DELETED}。
 */
@RestController
@RequestMapping("/api/socials/internal")
public class PersonalDataCleanupController {

    private final PersonalDataCleanupService personalDataCleanupService;

    public PersonalDataCleanupController(PersonalDataCleanupService personalDataCleanupService) {
        this.personalDataCleanupService = personalDataCleanupService;
    }

    /**
     * 清理该用户的关注（双向）/ 私信（收发）/ 公告已读记录。
     *
     * <p>两种模式（查询串 {@code dryRun}）：
     * <ul>
     *   <li>{@code dryRun=true} —— 预检，只查不写，返回「将删多少行」；</li>
     *   <li>缺省 / false —— 执行。<b>幂等可重入</b>：调度失败重试时重复调用
     *       不报错、不二次破坏。</li>
     * </ul>
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
