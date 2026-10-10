package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.MerchantDtos.MerchantViolationInfo;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditOutcome;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.support.MerchantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 商家级违规计数与升级停业（E28）。
 *
 * <h2>为什么违规计数落在店铺行，而不是实时聚合抽查表</h2>
 * 升级判定发生在「每一次判违规」的那一刻。实时聚合要扫全表，而且抽查表在**商品库**
 * （本服务连不到），得为此引入一次跨服务查询。而它真正需要的只是一个能原子自增的计数器 ——
 * 一个 int 列就够。
 *
 * <h2>三条口径</h2>
 * <ol>
 *   <li><b>只增不减</b>：它是「这家店历史上被判过几次违规」的事实。复核通过（PASSED）
 *       不能减 —— 那是「这次抽查没问题」，不是「以前的违规撤销了」。唯一的清零点是
 *       复业（人工重新审核通过）。</li>
 *   <li><b>自增必须是数据库层的原子操作</b>：读出来 +1 再写回，并发下会丢计数
 *       （两次违规各自读到 1，都写回 2）。用 {@code setSql("violation_count = violation_count + 1")}
 *       让数据库自己算。</li>
 *   <li><b>停业用条件更新</b>：{@code WHERE status='APPROVED'}，并发下只有一个能改成功 ——
 *       与 E15 的资质停业同一写法。抢不到的那个直接返回「没停业」，
 *       不会把一条本不该发生的状态转换写下去。</li>
 * </ol>
 */
@Service
public class MerchantViolationService {

    private static final Logger log = LoggerFactory.getLogger(MerchantViolationService.class);

    /** 累计到此次数即自动升级为商家级停业 */
    public static final int SUSPEND_THRESHOLD = 3;

    private static final AuditAction ACTION_SUSPEND = AuditAction.MERCHANT_VIOLATION_SUSPEND;
    private static final long SYSTEM_ACTOR = 0L;

    private final MerchantMapper merchantMapper;
    private final MerchantLicenseNotifier notifier;
    private final AuditLogService auditLogService;

    public MerchantViolationService(MerchantMapper merchantMapper,
                                    MerchantLicenseNotifier notifier,
                                    AuditLogService auditLogService) {
        this.merchantMapper = merchantMapper;
        this.notifier = notifier;
        this.auditLogService = auditLogService;
    }

    /**
     * 记一次违规。累计到 {@link #SUSPEND_THRESHOLD} 次且店铺当前在营时，自动停业 + 联动下架。
     *
     * @param ownerUserId 店铺主体 user id
     * @param reason      本次违规原因（会写进停业通知与审计留痕）
     * @return 累计次数与是否触发停业；找不到店铺返回 {@code null}
     */
    public MerchantViolationInfo markViolation(Long ownerUserId, String reason) {
        if (ownerUserId == null) {
            return null;
        }
        MerchantEntity merchant = merchantMapper.selectOne(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getOwnerUserId, ownerUserId)
                .last("LIMIT 1"));
        if (merchant == null) {
            // 商品归属的用户没有店铺（历史数据 / 自营），无从记违规 —— 记 WARN 而不是抛异常：
            // 商品那边已经驳回下架了，这里再抛只会把一次已完成的操作变成一次报错。
            log.warn("违规上报：找不到店铺 ownerUserId={}", ownerUserId);
            return null;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                .eq(MerchantEntity::getId, merchant.getId())
                .setSql("violation_count = violation_count + 1")
                .set(MerchantEntity::getUpdatedAt, now));
        MerchantEntity fresh = merchantMapper.selectById(merchant.getId());
        int count = fresh == null || fresh.getViolationCount() == null ? 0 : fresh.getViolationCount();
        boolean suspended = false;
        if (count >= SUSPEND_THRESHOLD && MerchantStatus.APPROVED.name().equals(fresh.getStatus())) {
            suspended = suspendForViolation(fresh, count, reason, now);
        }
        log.info("商家 {} 违规 +1 → 累计 {}（阈值 {}，本次停业={}）",
                fresh.getId(), count, SUSPEND_THRESHOLD, suspended);
        return new MerchantViolationInfo(String.valueOf(fresh.getId()), count, suspended);
    }

    /**
     * 因**入驻资质抽查**判违规而直接停业（E28）。
     *
     * <p>与 {@link #markViolation} 的区别是「要不要攒够次数」：
     * 商品违规可以攒（一件商品内容出格，未必说明这家店整体有问题），
     * 而入驻资质造假是**主体资格**问题 —— 执照是假的，它家所有商品的可信度都归零。
     * 所以这里不累计、直接停业，同时仍然 +1 计数（留痕，也让后续风险画像能看见）。
     */
    public MerchantViolationInfo suspendByAudit(Long ownerUserId, String reason) {
        MerchantViolationInfo info = markViolation(ownerUserId, reason);
        if (info == null) {
            return null;
        }
        MerchantEntity merchant = merchantMapper.selectById(Long.valueOf(info.merchantId()));
        if (merchant == null || !MerchantStatus.APPROVED.name().equals(merchant.getStatus())) {
            // markViolation 里可能已经因为达到阈值停过业了 —— 那就没有再停一次的道理
            return info;
        }
        boolean suspended = suspendForViolation(merchant,
                merchant.getViolationCount() == null ? 1 : merchant.getViolationCount(),
                reason, LocalDateTime.now(ZoneOffset.UTC));
        return new MerchantViolationInfo(info.merchantId(), info.violationCount(), suspended);
    }

    /**
     * 复业时清零违规计数。
     *
     * <p>为什么唯一的重置点放在这里：违规计数的作用是「累计到一定次数就把店关掉」。
     * 一旦已经停业，这个计数就完成了它的使命 —— 平台重新放行说明已经人工核查过，
     * 此时还留着 3 次的历史计数，会让「下一次违规再停业」变成必然（一犯即停）。
     * 那不是我们的规则，所以必须在复业那一刻显式清零，而不是靠「反正也不会再看」。
     */
    public void resetViolations(Long merchantId) {
        if (merchantId == null) {
            return;
        }
        merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                .eq(MerchantEntity::getId, merchantId)
                .gt(MerchantEntity::getViolationCount, 0)
                .set(MerchantEntity::getViolationCount, 0)
                .set(MerchantEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** 停业 + 通知 + 联动下架 + 留痕。顺序：状态先立住，其余 best-effort */
    private boolean suspendForViolation(MerchantEntity merchant, int count, String reason,
                                        LocalDateTime now) {
        String text = "因累计 " + count + " 件商品违规，店铺已暂停营业（最近一次："
                + (reason == null ? "商品内容违规" : reason) + "）";
        int changed = merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                .eq(MerchantEntity::getId, merchant.getId())
                .eq(MerchantEntity::getStatus, MerchantStatus.APPROVED.name())
                .set(MerchantEntity::getStatus, MerchantStatus.SUSPENDED.name())
                .set(MerchantEntity::getSuspendedReason, text)
                .set(MerchantEntity::getUpdatedAt, now));
        if (changed == 0) {
            log.info("违规停业跳过 merchantId={}：状态已不是 APPROVED", merchant.getId());
            return false;
        }
        merchant.setStatus(MerchantStatus.SUSPENDED.name());
        merchant.setSuspendedReason(text);
        notifier.notifyViolationSuspended(merchant, count, reason);
        int offShelf = notifier.offShelfAll(merchant.getOwnerUserId());
        if (offShelf < 0) {
            log.error("违规停业联动下架失败 merchantId={} —— 店铺已停业但商品可能仍在售，需人工核查",
                    merchant.getId());
        }
        writeAudit(merchant, count, reason, now);
        return true;
    }

    /** 留痕：actor=系统(0)；留痕失败不阻断停业 */
    private void writeAudit(MerchantEntity merchant, int count, String reason, LocalDateTime now) {
        // E18 · 统一走 AuditLogService（本方法由内部端点触发，没有 HTTP 请求，
        // 因此不能靠 @Audited 注解，必须显式写）。
        boolean written = auditLogService.record(new AuditLogRecord(
                SYSTEM_ACTOR, "", ACTION_SUSPEND.code(),
                ACTION_SUSPEND.category().name(), AuditOutcome.SUCCESS.name(),
                merchant.getId(), ACTION_SUSPEND.targetType(),
                merchant.getOwnerUserId() == null ? 0L : merchant.getOwnerUserId(),
                "APPROVED", "SUSPENDED",
                null, com.duoduo.common.api.RequestIdHolder.get(),
                "依据：累计 " + count + " 次商品违规（阈值 " + SUSPEND_THRESHOLD
                        + "），最近一次原因：" + reason,
                now.toString()));
        if (!written) {
            log.error("违规停业留痕失败 merchantId={}", merchant.getId());
        }
    }
}
