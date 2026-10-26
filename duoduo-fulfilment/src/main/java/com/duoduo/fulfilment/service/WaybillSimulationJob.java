package com.duoduo.fulfilment.service;

import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.fulfilment.entity.LogisticsTrackEntity;
import com.duoduo.fulfilment.entity.WaybillEntity;
import com.duoduo.fulfilment.mapper.LogisticsTrackMapper;
import com.duoduo.fulfilment.mapper.WaybillMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 模拟物流轨迹推进任务（B-05）。
 *
 * <p><b>为什么需要它</b>：电子面单生成后，如果没有真实快递推送，买家页面会一直停在
 * 「已揽收」。模拟渠道必须自己把轨迹往下走，否则这个功能只在「能生成单号」这一步成立，
 * 轨迹时间线仍然是死的。
 *
 * <p><b>推进节奏</b>：以轨迹最后一条节点的发生时间为锚，间隔
 * {@code duoduo.logistics.simulate-step-seconds}（默认 30 秒）推进一步：
 * PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → SIGNED。
 * 不用创建时间做锚，是因为卖家可能先申请面单、过很久才发货 ——
 * 用创建时间会让包裹「还没揽收就已经签收」。
 *
 * <p><b>多实例</b>：用 {@link SchedLeaderLock} 选主；节点唯一索引是第二道保险，
 * 即使两个实例同时推进，也只会有一条节点落库。
 */
@Component
public class WaybillSimulationJob {

    private static final Logger log = LoggerFactory.getLogger(WaybillSimulationJob.class);
    private static final String TASK_NAME = "waybill-simulate";
    private static final long LEADER_TTL_MS = 120_000L;
    private static final int BATCH = 50;

    private final WaybillMapper waybillMapper;
    private final LogisticsTrackMapper trackMapper;
    private final WaybillService waybillService;
    private final SchedLeaderLock leaderLock;
    private final boolean enabled;
    private final long stepSeconds;

    public WaybillSimulationJob(WaybillMapper waybillMapper,
                                LogisticsTrackMapper trackMapper,
                                WaybillService waybillService,
                                SchedLeaderLock leaderLock,
                                @Value("${duoduo.logistics.simulate-enabled:true}") boolean enabled,
                                @Value("${duoduo.logistics.simulate-step-seconds:30}") long stepSeconds) {
        this.waybillMapper = waybillMapper;
        this.trackMapper = trackMapper;
        this.waybillService = waybillService;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
        this.stepSeconds = Math.max(5L, stepSeconds);
    }

    @Scheduled(fixedDelayString = "${duoduo.logistics.simulate-interval-ms:10000}",
            initialDelayString = "${duoduo.logistics.simulate-initial-delay-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (WaybillEntity waybill : waybillMapper.listSimulatedOpen(BATCH)) {
            try {
                List<LogisticsTrackEntity> tracks = trackMapper.listByOrderNo(waybill.getOrderNo());
                if (tracks.isEmpty()) {
                    // 面单已申请但卖家还没发货：没有揽收节点，不能替它开始运输
                    continue;
                }
                LocalDateTime latest = tracks.get(tracks.size() - 1).getOccurredAt();
                if (now.isBefore(latest.plusSeconds(stepSeconds))) {
                    continue;
                }
                waybillService.advance(waybill.getOrderNo());
            } catch (Exception e) {
                // 单张面单失败不影响其它面单；下一轮会重试
                log.warn("订单 {} 模拟轨迹推进失败：{}", waybill.getOrderNo(), e.toString());
            }
        }
    }
}
