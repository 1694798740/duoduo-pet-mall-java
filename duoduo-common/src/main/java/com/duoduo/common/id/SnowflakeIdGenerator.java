package com.duoduo.common.id;

/**
 * 雪花算法 ID 生成器（64 bit：1 符号位 + 41 时间戳 + 5 数据中心 + 5 机器 + 12 序列）。
 *
 * <p>相比 UUID 的优势：全局唯一、趋势递增（对 MySQL 聚簇索引友好）、可反解时间与节点。
 * 电商场景中订单号若直接用雪花 ID 会暴露业务量，因此本项目：
 * <ul>
 *   <li>数据库主键 / 分片键 → 雪花 ID（内部使用）</li>
 *   <li>对外展示的业务单号 orderNo → 另行生成（日期 + 随机 + 校验位）</li>
 * </ul>
 *
 * <p>集群部署时 datacenterId / workerId 由 Nacos 配置或环境变量注入，务必保证组合唯一，
 * 否则同一毫秒内可能产生重复 ID。
 */
public class SnowflakeIdGenerator {

    /** 起始纪元：2024-01-01 00:00:00 UTC，决定 ID 可用的时间跨度（约 69 年） */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long datacenterId;
    private final long workerId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId 必须在 [0, " + MAX_DATACENTER_ID + "] 之间");
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 [0, " + MAX_WORKER_ID + "] 之间");
        }
        this.datacenterId = datacenterId;
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = currentTime();

        if (timestamp < lastTimestamp) {
            // 时钟回拨：小幅度等待，大幅度直接拒绝，避免生成重复 ID
            long offset = lastTimestamp - timestamp;
            if (offset > 5L) {
                throw new IllegalStateException("检测到时钟回拨 " + offset + "ms，拒绝生成 ID");
            }
            timestamp = waitUntil(lastTimestamp);
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 反解出 ID 的生成时间，用于排查与对账 */
    public static long extractTimestamp(long id) {
        return (id >> TIMESTAMP_SHIFT) + EPOCH;
    }

    private long waitUntil(long targetTimestamp) {
        long timestamp = currentTime();
        while (timestamp < targetTimestamp) {
            timestamp = currentTime();
        }
        return timestamp;
    }

    private long currentTime() {
        return System.currentTimeMillis();
    }
}
