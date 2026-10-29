package com.duoduo.trade.client;

import com.duoduo.trade.client.dto.NoticePushDtos.Item;
import com.duoduo.trade.client.dto.NoticePushDtos.PushRequest;
import com.duoduo.trade.client.dto.NoticePushDtos.PushResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 导出任务完成通知（矩阵第 9 项 · 2026-10-28）：best-effort 推给创建人。
 *
 * <p><b>为什么失败不能影响任务</b>：文件已经生成好了，能不能收到提醒只影响「多快知道」，
 * 不影响「能不能下载」。后台工单页会列出全部任务，通知只是补充通道。
 *
 * <p><b>分类用 SYSTEM</b>：导出通知是系统对操作人的回执，不是订单事件；
 * 混进 ORDER 分类会让运营在「订单」筛选里看到一堆自己导出的提醒。
 *
 * <p><b>jumpPage 传 null</b>：运营后台是网页端，没有对应的小程序页面；
 * 社交域对非空 jumpPage 做小程序页面白名单校验，拼一个不存在的路径会被整批 400。
 */
@Component
public class ExportJobNotifier {

    private static final Logger log = LoggerFactory.getLogger(ExportJobNotifier.class);

    private static final String CATEGORY_SYSTEM = "SYSTEM";
    private static final int MAX_BRIEF = 160;

    private final SocialNoticeClient socialNoticeClient;

    public ExportJobNotifier(SocialNoticeClient socialNoticeClient) {
        this.socialNoticeClient = socialNoticeClient;
    }

    /** 生成成功：告诉操作人「可以来下载了」，并说明有效期 */
    public void notifyReady(Long userId, Long jobId, int rows, String moduleLabel, int retentionDays) {
        push(userId, "导出已完成", moduleLabel + "导出已完成，共 " + rows + " 行。请到运营后台「导出任务」页下载，"
                + "文件保留 " + retentionDays + " 天。", "EXPORT_JOB_READY", jobId);
    }

    /** 生成失败：带上可读原因，让操作人知道是改筛选还是稍后重试 */
    public void notifyFailed(Long userId, Long jobId, String moduleLabel, String reason) {
        push(userId, "导出失败", moduleLabel + "导出未完成：" + brief(reason)
                + "。请到运营后台「导出任务」页查看详情或重新导出。", "EXPORT_JOB_FAILED", jobId);
    }

    private void push(Long userId, String title, String content, String bizType, Long jobId) {
        if (userId == null) {
            return;
        }
        try {
            var response = socialNoticeClient.push(new PushRequest(List.of(
                    new Item(userId, CATEGORY_SYSTEM, title, content, bizType, String.valueOf(jobId), null))));
            PushResult result = response == null ? null : response.data();
            if (result == null || result.accepted() < 1) {
                log.warn("导出任务通知未被接受 userId={} jobId={}（不影响任务结果）", userId, jobId);
                return;
            }
            log.info("导出任务通知已送达 userId={} jobId={} bizType={}", userId, jobId, bizType);
        } catch (RuntimeException e) {
            log.error("导出任务通知推送失败 userId={} jobId={}（不影响任务结果）", userId, jobId, e);
        }
    }

    private static String brief(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return "未知错误";
        }
        return normalized.length() <= MAX_BRIEF ? normalized : normalized.substring(0, MAX_BRIEF - 1) + "…";
    }
}
