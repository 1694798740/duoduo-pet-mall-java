package com.duoduo.common.web.sched;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 定时任务的**选主锁**（第五十八轮 · P0-2）。
 *
 * <h2>解决什么问题</h2>
 * {@code @Scheduled} 是进程内调度：多实例部署时，同一个补偿任务会在每个实例上各跑一遍。
 * 现有 4 个任务都设计成可重入，重复跑<b>不会算错</b>，但代价真实存在 ——
 * 对数据库的压力按实例数放大、日志里出现 N 份同样的补偿报告、
 * 且「扫到就改状态」的动作会互相覆盖彼此的扫描窗口，让「这轮补偿了多少」说不清。
 *
 * <h2>为什么用数据库唯一键而不是引 ShedLock / XXL-Job</h2>
 * 那两者都要新增中间件与运维面。这里用一张只有主键的小表做选主，
 * <b>零新增组件</b>：各服务本来就都连着自己的库。生产若上了 XXL-Job，
 * 把 {@code duoduo.sched.leader-enabled} 置 {@code false} 即可整体退回外部调度。
 *
 * <h2>抢占语义（两步，顺序不能反）</h2>
 * <ol>
 *   <li><b>先 UPDATE</b>：只在「锁已过期」或「本来就是自己持有」时才改写行。
 *       这是续约路径 —— 同一个实例每轮都会走这里。</li>
 *   <li><b>再 INSERT</b>：UPDATE 影响 0 行说明还没有这行（首次运行）。
 *       撞主键即表示<b>别的实例正持有且未过期</b>，本轮放弃。</li>
 * </ol>
 * 反过来的写法（先 SELECT 再 INSERT/UPDATE）在并发下会让两个实例都看到「没有这行」
 * 而双双写入 —— 唯一键是唯一可靠的仲裁者。
 *
 * <h2>锁到期而不是显式释放</h2>
 * 任务崩溃 / 进程被杀时没人来释放锁，靠 {@code expire_at} 自动过期才能被别的实例接管。
 * 因此 TTL 必须<b>大于任务单轮执行耗时</b>，否则会出现「上一个还没跑完、下一个已接管」的并发。
 *
 * <h2>取不到锁时的默认动作是「放行」而不是「跳过」</h2>
 * 表不存在、数据库抖动这类故障下若一律跳过，补偿任务就<b>静默停摆</b> ——
 * 券被永久锁死、库存永远不释放，而日志里只有一条不起眼的 WARN。
 * 相比之下「多跑一遍」的代价小得多，且与本项目一贯的 best-effort 纪律一致。
 * 因此这里只在 WARN 日志里明确记录异常，仍然返回 {@code true}。
 */
public class SchedLeaderLock {

    private static final Logger log = LoggerFactory.getLogger(SchedLeaderLock.class);

    private final JdbcTemplate jdbc;
    private final String owner;
    private final boolean enabled;

    public SchedLeaderLock(JdbcTemplate jdbc, String owner, boolean enabled) {
        this.jdbc = jdbc;
        this.owner = owner;
        this.enabled = enabled;
    }

    /** 实例标识：容器 hostname + 进程号（同一台机器上跑多个实例也能区分） */
    public static String defaultOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown-host";
        }
        return host + "#" + ProcessHandle.current().pid();
    }

    /**
     * 尝试成为本轮的执行者。
     *
     * <p><b>为什么必须 REQUIRES_NEW（独立提交）</b>：{@code OrderService#scheduledSweep()}
     * 本身带 {@code @Transactional}。若锁的写入落在那个事务里，它要等<b>整轮扫描结束</b>
     * 才提交 —— 期间别的实例查到的仍是旧值，会同时抢到锁，选主形同虚设。
     * 锁必须先于业务动作落库，这是它能生效的前提。
     *
     * @param task  任务名（一个任务一个名字，全局唯一）
     * @param ttlMs 持锁时长（毫秒）；必须大于任务单轮执行耗时
     * @return {@code true} = 本实例可以跑这一轮
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String task, long ttlMs) {
        if (!enabled) {
            // 显式关闭 = 交给外部调度（XXL-Job）统一触发，本进程不再自行选主
            return true;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expireAt = now.plusNanos(Math.max(1L, ttlMs) * 1_000_000L);
        try {
            int renewed = jdbc.update(
                    "UPDATE t_sched_leader SET owner = ?, expire_at = ?, updated_at = ? "
                            + "WHERE name = ? AND (expire_at < ? OR owner = ?)",
                    owner, expireAt, now, task, now, owner);
            if (renewed > 0) {
                return true;
            }
            jdbc.update(
                    "INSERT INTO t_sched_leader (name, owner, expire_at, updated_at) VALUES (?, ?, ?, ?)",
                    task, owner, expireAt, now);
            return true;
        } catch (DuplicateKeyException ex) {
            // 别人正持有且在有效期内 —— 本轮放弃，这是正常的多实例协同，不是异常
            return false;
        } catch (DataAccessException ex) {
            log.warn("定时任务选主失败，本轮按「放行」处理（避免补偿任务静默停摆）task={} : {}",
                    task, ex.getMessage());
            return true;
        }
    }

    /** 当前是否启用选主（供运维核对配置是否生效） */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 优雅停机时释放自己持有的锁。
     *
     * <p>只删 {@code owner = 本实例} 的行：另一个实例可能已经接管，不能把别人的锁删掉。
     * 进程被 SIGKILL / 崩溃时走不到这里，仍由 {@code expire_at} 兜底 —— 这也是
     * 调用方的 TTL 不能设得太长的原因（A-12：增量扫描租约 15s，全量扫描才用 120s）。
     */
    public void release(String task) {
        if (!enabled) {
            return;
        }
        try {
            jdbc.update("DELETE FROM t_sched_leader WHERE name = ? AND owner = ?", task, owner);
        } catch (DataAccessException ex) {
            log.warn("定时任务选主释放失败（等 TTL 自然过期）task={} : {}", task, ex.getMessage());
        }
    }

    /** 本实例标识 */
    public String owner() {
        return owner;
    }
}
