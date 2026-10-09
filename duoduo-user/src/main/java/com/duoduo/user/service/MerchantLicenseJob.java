package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.user.entity.AuditLogEntity;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMaterialEntity;
import com.duoduo.user.mapper.AuditLogMapper;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.support.LicenseExpiry;
import com.duoduo.user.support.MerchantMaterialType;
import com.duoduo.user.support.MerchantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 资质到期扫描任务（E15）：临期提醒 + 过期自动停业。
 *
 * <h2>为什么要有个任务，而不是「用到时才判」</h2>
 * 到期这件事的特点是<b>没有请求会来</b>：商家不会每天打开后台看证还剩几天，
 * 买家也不会因为一家店的执照过期就停止下单。如果只在「上架商品」时判一次，
 * 那么一家已经三个月没上新品的过期店会永远挂在架上卖 —— 这正是要防的。
 * 到期必须由系统自己发现、自己处置、自己通知。
 *
 * <h2>两件事，两个方向</h2>
 * <ol>
 *   <li><b>提醒</b>（状态不变）：进入 30 天档发一条、7 天档再发一条。去重靠
 *       {@code expire_notify_stage} 记档位、「只升档才发」，所以每天扫到同一家也
 *       只发两次，不会变成每日骚扰。</li>
 *   <li><b>停业</b>（状态变 SUSPENDED）：到期即停，并联动下架在售商品。
 *       每轮只查 {@code status='APPROVED'}，所以停过的不会再被处理 —— 幂等来自
 *       查询条件本身，而不是「记得加个 if」。</li>
 * </ol>
 *
 * <h2>任务本身的三条纪律</h2>
 * <ul>
 *   <li><b>选主</b>：多实例部署时只有抢到锁的实例跑（{@link SchedLeaderLock}），
 *       否则 N 个实例会各发一遍通知；</li>
 *   <li><b>单轮限量</b>：一次最多处理 {@value #BATCH_LIMIT} 家，剩下的下一轮继续。
 *       任务里没有「一次全扫完」的写法 —— 那种实现在量大时会拖垮库，而且失败就得从头再来；</li>
 *   <li><b>异常不外抛</b>：任务抛异常对调度器没有任何补救作用，只会让下一轮
 *       在同一个坑里再摔一次，所以这里逐家处理、单家失败不影响其余。</li>
 * </ul>
 */
@Service
public class MerchantLicenseJob {

    private static final Logger log = LoggerFactory.getLogger(MerchantLicenseJob.class);

    /** 选主用的任务名（跨实例必须一致） */
    private static final String TASK_NAME = "merchant-license-sweep";
    /** 持锁时长：单轮最多 200 家，留足余量 */
    private static final long LEADER_TTL_MS = 600_000L;
    /** 单轮处理上限（提醒与停业各自独立计数） */
    private static final int BATCH_LIMIT = 200;

    /** 审计动作：系统因资质过期停业 */
    private static final String ACTION_SUSPEND = "MERCHANT_LICENSE_SUSPEND";
    /** 系统动作的 actorId 占位（t_audit_log.actor_id 非空；0 = 平台系统） */
    private static final long SYSTEM_ACTOR = 0L;

    private final MerchantMapper merchantMapper;
    private final MerchantMaterialMapper materialMapper;
    private final MerchantLicenseNotifier notifier;
    private final AuditLogMapper auditLogMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final SchedLeaderLock leaderLock;
    private final boolean enabled;

    public MerchantLicenseJob(MerchantMapper merchantMapper,
                              MerchantMaterialMapper materialMapper,
                              MerchantLicenseNotifier notifier,
                              AuditLogMapper auditLogMapper,
                              SnowflakeIdGenerator idGenerator,
                              SchedLeaderLock leaderLock,
                              @Value("${duoduo.merchant.license.sweep-enabled:true}") boolean enabled) {
        this.merchantMapper = merchantMapper;
        this.materialMapper = materialMapper;
        this.notifier = notifier;
        this.auditLogMapper = auditLogMapper;
        this.idGenerator = idGenerator;
        this.leaderLock = leaderLock;
        this.enabled = enabled;
        if (enabled) {
            log.info("资质到期扫描任务已启用（提醒 {} 天 / {} 天，过期自动停业）",
                    LicenseExpiry.REMIND_DAYS_FIRST, LicenseExpiry.REMIND_DAYS_SECOND);
        }
    }

    /** 单轮扫描的结果，供日志与单测断言 */
    public record SweepReport(int suspended, int reminded, int notifyFailed, int offShelfFailed) {
    }

    /**
     * 调度入口。默认 6 小时一轮 —— 提醒精度按天算，跑得再勤也只是重复扫同一批数据；
     * 而「过期停业」多延迟几小时并不会改变什么（它本来就是个兜底动作）。
     */
    @Scheduled(fixedDelayString = "${duoduo.merchant.license.sweep-interval-ms:21600000}",
            initialDelayString = "${duoduo.merchant.license.sweep-initial-delay-ms:180000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;         // 别的实例正在跑本轮，正常的多实例协同
        }
        try {
            SweepReport report = sweepOnce(LocalDateTime.now(ZoneOffset.UTC));
            if (report.suspended() > 0 || report.reminded() > 0
                    || report.notifyFailed() > 0 || report.offShelfFailed() > 0) {
                log.info("资质扫描完成：停业 {} 家、提醒 {} 家、通知失败 {} 家、下架失败 {} 家",
                        report.suspended(), report.reminded(),
                        report.notifyFailed(), report.offShelfFailed());
            }
        } catch (Exception ex) {
            // 不外抛：抛出去对调度器毫无用处，只会让日志更难读
            log.error("资质扫描任务异常", ex);
        }
    }

    /**
     * 手动触发一轮（运营入口 {@code POST /api/admin/merchants/license-sweep}）。
     *
     * <p>与 {@link #sweep()} 有两处刻意不同：
     * <ul>
     *   <li><b>不看 {@code enabled}</b>：关掉定时开关的语义是「别自己跑」，
     *       而「关掉之后人工补扫一遍」正是使用这个入口的典型场景；</li>
     *   <li><b>不抢选主锁</b>：锁是为了让多实例的<b>定时器</b>之间互斥。
     *       运营点按钮是一次明确的人工意图，被别处的定时任务挡掉会让人以为功能坏了。
     *       重复执行也不会重复处置 —— {@code suspendOne} 与档位写入都是条件更新，
     *       且提醒的「只升档才发」保证同一档不会发第二条。</li>
     * </ul>
     *
     * <p>之所以需要这个入口，而不是"等 6 小时自会跑"：到期提醒与自动停业若只能靠
     * 定时器观察，验收与排障就都得掐着表等 —— 改完一份材料的有效期后想立刻确认
     * 后果，唯一办法是等到下一轮。有了它才谈得上"当场验证"。
     */
    public SweepReport sweepNow() {
        SweepReport report = sweepOnce(LocalDateTime.now(ZoneOffset.UTC));
        log.info("资质巡检（手动触发）：停业 {} 家、提醒 {} 家、通知失败 {} 家、下架失败 {} 家",
                report.suspended(), report.reminded(), report.notifyFailed(), report.offShelfFailed());
        return report;
    }

    /**
     * 单轮逻辑（纯函数式：传入基准时刻，便于单测构造「30 天后到期」这类场景）。
     *
     * <p>顺序刻意是<b>先停业、后提醒</b>：刚刚因为过期被停业的那批店铺，
     * 不应该在同一轮里再收到一封「您的证件即将到期」——它们是已经到期了。
     * 反过来先提醒的话，两批查询之间没有隔离，就会出现这种自相矛盾的通知。
     */
    public SweepReport sweepOnce(LocalDateTime now) {
        int suspended = 0;
        int offShelfFailed = 0;
        for (MerchantEntity merchant : expiredMerchants(now)) {
            if (suspendOne(merchant, now)) {
                suspended++;
                if (notifier.offShelfAll(merchant.getOwnerUserId()) < 0) {
                    offShelfFailed++;
                }
            }
        }

        int reminded = 0;
        int notifyFailed = 0;
        for (MerchantEntity merchant : expiringMerchants(now)) {
            Boolean sent = remindOne(merchant, now);
            if (sent == null) {
                continue;                   // 未升档，本档已提醒过
            }
            if (sent) {
                reminded++;
            } else {
                notifyFailed++;
            }
        }
        return new SweepReport(suspended, reminded, notifyFailed, offShelfFailed);
    }

    // ---------------- 停业 ----------------

    /** 已过期的在营店铺（{@code license_expire_at <= now} 含恰好相等） */
    private List<MerchantEntity> expiredMerchants(LocalDateTime now) {
        return merchantMapper.selectList(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getStatus, MerchantStatus.APPROVED.name())
                .isNotNull(MerchantEntity::getLicenseExpireAt)
                .le(MerchantEntity::getLicenseExpireAt, now)
                .orderByAsc(MerchantEntity::getLicenseExpireAt)
                .last("LIMIT " + BATCH_LIMIT));
    }

    /**
     * 停业一家店。
     *
     * <p>★ 用<b>条件更新</b>（{@code WHERE status='APPROVED'}）而不是「按 id 覆盖」：
     * 扫描与商家自己发起的续期可能撞在同一瞬间 —— 续期会把状态改成 SUBMITTED，
     * 而按 id 覆盖会把它压回 SUSPENDED，商家刚提交的续期就这么凭空消失了，
     * 且两边都没有报错。条件更新让「这一轮扫描看到的状态」与「真正被改的状态」必须一致，
     * 影响 0 行就说明状态在这期间变了，本轮跳过即可。
     *
     * @return 是否真的执行了停业
     */
    private boolean suspendOne(MerchantEntity merchant, LocalDateTime now) {
        LocalDateTime expireAt = merchant.getLicenseExpireAt();
        MerchantMaterialEntity material = expiringMaterial(merchant.getId(), expireAt);
        String label = material == null
                ? "经营资质" : MerchantMaterialType.parse(material.getMaterialType()).label();
        String reason = "经营资质已过期（" + expireAt.toLocalDate() + "），店铺已暂停营业";

        int changed = merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                .eq(MerchantEntity::getId, merchant.getId())
                .eq(MerchantEntity::getStatus, MerchantStatus.APPROVED.name())
                .set(MerchantEntity::getStatus, MerchantStatus.SUSPENDED.name())
                .set(MerchantEntity::getSuspendedReason, reason)
                // 记下「因过期被停」，排查「这家店为什么被停」时不必去翻扫描日志
                .set(MerchantEntity::getExpireNotifyStage, LicenseExpiry.Stage.EXPIRED.name())
                .set(MerchantEntity::getUpdatedAt, now));
        if (changed == 0) {
            log.info("资质停业跳过 merchantId={}：状态已不是 APPROVED（可能刚提交续期）",
                    merchant.getId());
            return false;
        }
        // 内存里的实体同步一下，供通知文案使用（通知读的是 merchant 对象）
        merchant.setStatus(MerchantStatus.SUSPENDED.name());
        merchant.setSuspendedReason(reason);

        notifier.notifySuspended(merchant, label, expireAt);
        writeAudit(merchant, label, expireAt);
        log.info("资质过期自动停业 merchantId={} owner={} 到期日={} 材料={}",
                merchant.getId(), merchant.getOwnerUserId(), expireAt.toLocalDate(), label);
        return true;
    }

    // ---------------- 提醒 ----------------

    /** 30 天内到期、且尚未过期的在营店铺 */
    private List<MerchantEntity> expiringMerchants(LocalDateTime now) {
        return merchantMapper.selectList(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getStatus, MerchantStatus.APPROVED.name())
                .isNotNull(MerchantEntity::getLicenseExpireAt)
                .gt(MerchantEntity::getLicenseExpireAt, now)
                .le(MerchantEntity::getLicenseExpireAt, now.plusDays(LicenseExpiry.REMIND_DAYS_FIRST))
                .orderByAsc(MerchantEntity::getLicenseExpireAt)
                .last("LIMIT " + BATCH_LIMIT));
    }

    /**
     * 提醒一家店。
     *
     * @return {@code null} = 未升档（本档已提醒过，跳过）；
     *         {@code true} = 已提醒并记档；{@code false} = 通知没发出去（不记档，下轮重试）
     */
    private Boolean remindOne(MerchantEntity merchant, LocalDateTime now) {
        LicenseExpiry.Stage stage = LicenseExpiry.stageOf(merchant.getLicenseExpireAt(), now);
        if (stage == LicenseExpiry.Stage.NONE
                || !stage.deeperThan(LicenseExpiry.Stage.parse(merchant.getExpireNotifyStage()))) {
            return null;
        }
        LocalDateTime expireAt = merchant.getLicenseExpireAt();
        MerchantMaterialEntity material = expiringMaterial(merchant.getId(), expireAt);
        String label = material == null
                ? "经营资质" : MerchantMaterialType.parse(material.getMaterialType()).label();
        long remaining = LicenseExpiry.remainingDays(expireAt, now);

        if (!notifier.notifyExpiring(merchant, label, expireAt, remaining)) {
            // ★ 刻意不记档位：记了就永远不会再发，而商家其实没收到 ——
            //   那种漏报没有任何人能发现（状态全对、日志只有一行 WARN）。
            //   不记则下一轮重试，最多重复一条通知。
            return false;
        }
        // 与停业同一套条件更新：并发续期时不要把档位写回一个已进入审核的申请
        merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                .eq(MerchantEntity::getId, merchant.getId())
                .eq(MerchantEntity::getStatus, MerchantStatus.APPROVED.name())
                .set(MerchantEntity::getExpireNotifyStage, stage.name())
                .set(MerchantEntity::getUpdatedAt, now));
        return true;
    }

    // ---------------- 内部 ----------------

    /** 找出「就是它到期」的那份材料 —— 提醒文案要说清是哪张证 */
    private MerchantMaterialEntity expiringMaterial(Long merchantId, LocalDateTime expireAt) {
        return materialMapper.selectOne(new LambdaQueryWrapper<MerchantMaterialEntity>()
                .eq(MerchantMaterialEntity::getMerchantId, merchantId)
                .eq(MerchantMaterialEntity::getExpireAt, expireAt)
                .last("LIMIT 1"));
    }

    /**
     * 留痕：平台单方面让一家店停业，必须有据可查。
     *
     * <p>actor 是系统（{@value #SYSTEM_ACTOR}）：这条记录回答的是「什么时候、
     * 因为哪份证到期、停了哪家店」，而不是「谁点的按钮」。
     */
    private void writeAudit(MerchantEntity merchant, String label, LocalDateTime expireAt) {
        try {
            AuditLogEntity record = new AuditLogEntity();
            record.setId(idGenerator.nextId());
            record.setActorId(SYSTEM_ACTOR);
            record.setActorPhone("");
            record.setAction(ACTION_SUSPEND);
            record.setTargetId(merchant.getId());
            record.setDetail("APPROVED -> SUSPENDED，依据：" + label + " 到期日 " + expireAt.toLocalDate());
            record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            auditLogMapper.insert(record);
        } catch (Exception ex) {
            // 留痕失败不阻断停业（停业是合规动作，必须成立），但必须吵到日志
            log.error("资质停业留痕失败 merchantId={}", merchant.getId(), ex);
        }
    }
}
