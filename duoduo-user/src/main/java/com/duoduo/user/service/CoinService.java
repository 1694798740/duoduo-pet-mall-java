package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.CoinDtos.CoinAdminSummary;
import com.duoduo.user.dto.CoinDtos.CoinBalanceView;
import com.duoduo.user.dto.CoinDtos.CoinLedgerView;
import com.duoduo.user.dto.CoinDtos.CoinMutationResult;
import com.duoduo.user.dto.CoinDtos.CoinReconResult;
import com.duoduo.user.dto.CoinDtos.CoinSigninMonthView;
import com.duoduo.user.dto.CoinDtos.CoinSigninResult;
import com.duoduo.user.dto.CoinDtos.ExpiringLotView;
import com.duoduo.user.entity.CoinAccountEntity;
import com.duoduo.user.entity.CoinFreezeEntity;
import com.duoduo.user.entity.CoinFreezeLotEntity;
import com.duoduo.user.entity.CoinLedgerEntity;
import com.duoduo.user.entity.CoinLotEntity;
import com.duoduo.user.entity.CoinSigninEntity;
import com.duoduo.user.entity.CoinTaskDefEntity;
import com.duoduo.user.mapper.CoinAccountMapper;
import com.duoduo.user.mapper.CoinFreezeLotMapper;
import com.duoduo.user.mapper.CoinFreezeMapper;
import com.duoduo.user.mapper.CoinLedgerMapper;
import com.duoduo.user.mapper.CoinLotMapper;
import com.duoduo.user.mapper.CoinSigninMapper;
import com.duoduo.user.mapper.CoinTaskDefMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 多多币核心服务（R-P1）。
 *
 * <p><b>定位</b>：币是钱。本类是全站唯一的多多币权威 —— 账户、批次、流水、冻结四张表
 * 只在这里写；交易域通过内部接口驱动状态机（冻结 → 扣减确认 / 释放，退款退币，完成返币），
 * 从不直接碰这些表。
 *
 * <p><b>幂等总纲</b>：每个对外动作用「业务单号 + 方向」在流水表占位
 * （{@code uk_coin_ledger_biz} 唯一索引，INSERT IGNORE）——
 * 支付回调、退款回调、定时任务重试，哪怕并发打进来，也只可能有一次真正记账。
 *
 * <p><b>资金顺序</b>：商品总额 → 优惠券 → 多多币 → 运费 → 现金实付。
 * 币只抵商品金额（运费不参与抵扣），单笔抵扣上限为券后商品金额的 50% ——
 * 上限计算在交易域预览与下单时完成（需要订单金额上下文），本服务只负责账本本身。
 *
 * <p><b>批次与有效期</b>：每笔获得的币一批（12 个月，自然月对日 Asia/Shanghai 23:59:59）；
 * 消耗按「先到期先扣」；退款退回原批次，原批次已过期则补发等额、30 天有效的新批次。
 * 为什么这么较真：有效期是平台的真实负债（未过期余额 = 平台欠用户的钱），
 * 「用一个总数」的模型回答不了「哪笔钱什么时候到期、退款退回到哪批」。
 */
@Service
public class CoinService {

    private static final Logger log = LoggerFactory.getLogger(CoinService.class);

    /** 汇率：1 币 = 1 分（唯一换算点，改口径只改这里与预览计算） */
    public static final long COIN_TO_CENT = 1L;
    /** 单笔抵扣上限：券后商品金额的 50% */
    public static final int MAX_DEDUCT_PERCENT = 50;
    /** 有效期：12 个自然月 */
    public static final int VALID_MONTHS = 12;
    /** 退款补发（原批次已过期）的临时有效期：30 天 */
    public static final int REFUND_LOT_DAYS = 30;
    /** 「即将过期」提示窗口：30 天 */
    public static final int EXPIRE_HINT_DAYS = 30;

    /** 到期时刻统一按 Asia/Shanghai 自然月对日 23:59:59（用户看得懂的本地时间），存储转 UTC */
    private static final ZoneId EXPIRE_ZONE = ZoneId.of("Asia/Shanghai");
    /** 同步导出的行数上限（矩阵统一口径：超出应转异步任务） */
    private static final int MAX_EXPORT_ROWS = 50_000;

    private final CoinAccountMapper accountMapper;
    private final CoinLotMapper lotMapper;
    private final CoinLedgerMapper ledgerMapper;
    private final CoinFreezeMapper freezeMapper;
    private final CoinFreezeLotMapper freezeLotMapper;
    private final CoinSigninMapper signinMapper;
    private final CoinTaskDefMapper taskDefMapper;
    private final CoinCampaignService campaignService;
    private final SnowflakeIdGenerator idGenerator;

    public CoinService(CoinAccountMapper accountMapper,
                       CoinLotMapper lotMapper,
                       CoinLedgerMapper ledgerMapper,
                       CoinFreezeMapper freezeMapper,
                       CoinFreezeLotMapper freezeLotMapper,
                       CoinSigninMapper signinMapper,
                       CoinTaskDefMapper taskDefMapper,
                       CoinCampaignService campaignService,
                       SnowflakeIdGenerator idGenerator) {
        this.accountMapper = accountMapper;
        this.lotMapper = lotMapper;
        this.ledgerMapper = ledgerMapper;
        this.freezeMapper = freezeMapper;
        this.freezeLotMapper = freezeLotMapper;
        this.signinMapper = signinMapper;
        this.taskDefMapper = taskDefMapper;
        this.campaignService = campaignService;
        this.idGenerator = idGenerator;
    }

    // ==================== 查询 ====================

    /** 账户概览（买家「我的多多币」）：余额 + 累计 + 即将过期提示 */
    public CoinBalanceView balanceOf(Long userId) {
        CoinAccountEntity account = accountOf(userId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<CoinLotEntity> expiring = lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                .eq(CoinLotEntity::getUserId, userId)
                .gt(CoinLotEntity::getRemaining, 0)
                .gt(CoinLotEntity::getExpireAt, now)
                .le(CoinLotEntity::getExpireAt, now.plusDays(EXPIRE_HINT_DAYS))
                .orderByAsc(CoinLotEntity::getExpireAt));
        long expiringCoins = expiring.stream().mapToLong(CoinLotEntity::getRemaining).sum();
        List<ExpiringLotView> lots = expiring.stream()
                .map(l -> new ExpiringLotView(String.valueOf(l.getId()), l.getRemaining(), iso(l.getExpireAt())))
                .toList();
        return new CoinBalanceView(
                account.getBalance(), account.getTotalEarned(), account.getTotalSpent(),
                expiringCoins, lots);
    }

    /** 可用余额（交易域预览 / 下单冻结前校验用） */
    public long availableCoins(Long userId) {
        CoinAccountEntity account = accountMapper.selectOne(
                Wrappers.<CoinAccountEntity>lambdaQuery().eq(CoinAccountEntity::getUserId, userId));
        return account == null ? 0L : Math.max(0L, account.getBalance());
    }

    /**
     * 我的流水（买家侧）。
     *
     * <p>返回 {@link PageResult} 而不是裸 List：前端要渲染「第 x / y 页」与空态，
     * 需要 total / pageCount 这类元数据。裸数组会让前端读 {@code items} 时拿到 undefined
     * （R-P1 UI e2e 首跑就白屏在这个契约错位上）。
     */
    public PageResult<CoinLedgerView> ledgerOf(Long userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 50);
        Long total = ledgerMapper.selectCount(Wrappers.<CoinLedgerEntity>lambdaQuery()
                .eq(CoinLedgerEntity::getUserId, userId));
        List<CoinLedgerView> items = ledgerMapper.selectList(Wrappers.<CoinLedgerEntity>lambdaQuery()
                        .eq(CoinLedgerEntity::getUserId, userId)
                        .orderByDesc(CoinLedgerEntity::getCreatedAt)
                        .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize))
                .stream().map(this::toView).toList();
        return PageResult.of(items, total == null ? 0L : total, safePage, safeSize);
    }

    /** 后台全量流水（按用户 / 方向筛选），同样返回分页元数据 */
    public PageResult<CoinLedgerView> ledgerForAdmin(Long userId, String direction, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Long total = ledgerMapper.selectCount(adminLedgerFilter(userId, direction));
        List<CoinLedgerView> items = ledgerMapper.selectList(adminLedgerFilter(userId, direction)
                        .orderByDesc(CoinLedgerEntity::getCreatedAt)
                        .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize))
                .stream().map(this::toView).toList();
        return PageResult.of(items, total == null ? 0L : total, safePage, safeSize);
    }

    /** 后台流水导出（矩阵第二批）：与列表同一筛选，单次上限 5 万行 */
    public List<CoinLedgerView> exportLedgerForAdmin(Long userId, String direction) {
        return ledgerMapper.selectList(adminLedgerFilter(userId, direction)
                        .orderByDesc(CoinLedgerEntity::getCreatedAt)
                        .last("LIMIT " + MAX_EXPORT_ROWS))
                .stream().map(this::toView).toList();
    }

    /**
     * 我的流水导出（矩阵 · 买家侧）：条件里<b>只有当前登录人</b>，不存在传 userId 越权的入口。
     *
     * <p>排序补了 id 作为次级键：同一秒内可能有多条流水（下单扣币 + 奖励），
     * 只按 created_at 排会让两次导出得到不同的顺序，用户会以为是数据变了。
     */
    public List<CoinLedgerView> exportLedgerOf(Long userId) {
        return ledgerMapper.selectList(Wrappers.<CoinLedgerEntity>lambdaQuery()
                        .eq(CoinLedgerEntity::getUserId, userId)
                        .orderByDesc(CoinLedgerEntity::getCreatedAt)
                        .orderByDesc(CoinLedgerEntity::getId)
                        .last("LIMIT " + MAX_EXPORT_ROWS))
                .stream().map(this::toView).toList();
    }

    /** 我的流水条数（导出前判定上限，避免静默截断） */
    public long countLedgerOf(Long userId) {
        Long total = ledgerMapper.selectCount(Wrappers.<CoinLedgerEntity>lambdaQuery()
                .eq(CoinLedgerEntity::getUserId, userId));
        return total == null ? 0L : total;
    }

    /**
     * 后台流水的筛选条件（不含排序/分页）。
     * 每次新建一个 wrapper：MyBatis-Plus 的 lambda wrapper 被 {@code last()} 追加后不宜复用，
     * 计数与取数各建一份最稳。
     */
    private LambdaQueryWrapper<CoinLedgerEntity> adminLedgerFilter(Long userId, String direction) {
        LambdaQueryWrapper<CoinLedgerEntity> query = Wrappers.<CoinLedgerEntity>lambdaQuery();
        if (userId != null) {
            query.eq(CoinLedgerEntity::getUserId, userId);
        }
        if (direction != null && !direction.isBlank()) {
            query.eq(CoinLedgerEntity::getDirection, direction.trim().toUpperCase());
        }
        return query;
    }

    /** 运营汇总：负债口径 = 全站账户余额合计（含负余额账户的抵减） */
    public CoinAdminSummary adminSummary() {
        List<CoinAccountEntity> accounts = accountMapper.selectList(Wrappers.emptyWrapper());
        long balance = 0;
        long earned = 0;
        long spent = 0;
        for (CoinAccountEntity a : accounts) {
            balance += nz(a.getBalance());
            earned += nz(a.getTotalEarned());
            spent += nz(a.getTotalSpent());
        }
        long expired = expiredTotal();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<CoinLotEntity> expiring = lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                .gt(CoinLotEntity::getRemaining, 0)
                .gt(CoinLotEntity::getExpireAt, now)
                .le(CoinLotEntity::getExpireAt, now.plusDays(EXPIRE_HINT_DAYS)));
        long expiringCoins = expiring.stream().mapToLong(CoinLotEntity::getRemaining).sum();
        return new CoinAdminSummary(balance, earned, spent, expired, accounts.size(), expiringCoins);
    }

    private long expiredTotal() {
        List<CoinLedgerEntity> rows = ledgerMapper.selectList(Wrappers.<CoinLedgerEntity>lambdaQuery()
                .eq(CoinLedgerEntity::getDirection, CoinLedgerEntity.D_EXPIRE));
        return rows.stream().mapToLong(CoinLedgerEntity::getAmount).sum();
    }

    // ==================== 冻结 / 扣减确认 / 释放（交易域下单 → 支付 / 取消） ====================

    /**
     * 下单冻结：真实扣减余额并按「先到期先扣」消耗批次。
     *
     * <p>幂等：按 orderNo 占位（{@code uk_coin_freeze_order}）—— 已存在同单号冻结且数量一致
     * 则直接返回已处理；数量不一致说明出现了同单号但不同金额的异常调用，409 拒绝。
     *
     * <p>整段在一个事务里：冻结单 + 余额扣减 + 批次扣减 + 流水要么全部成立、要么全不成立。
     * 不允许「冻结单建了、钱没扣」的中间态。
     */
    @Transactional
    public CoinMutationResult freeze(Long userId, String orderNo, long coins) {
        if (coins <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "冻结币数必须为正");
        }
        CoinFreezeEntity existing = freezeMapper.selectOne(Wrappers.<CoinFreezeEntity>lambdaQuery()
                .eq(CoinFreezeEntity::getOrderNo, orderNo));
        if (existing != null) {
            if (existing.getCoins() != null && existing.getCoins() == coins) {
                return new CoinMutationResult(false, accountOf(userId).getBalance());
            }
            throw new BizException(ErrorCode.BUSINESS_RULE, "同一订单已存在币冻结且数量不一致：" + orderNo);
        }
        CoinFreezeEntity freeze = new CoinFreezeEntity();
        freeze.setId(idGenerator.nextId());
        freeze.setUserId(userId);
        freeze.setOrderNo(orderNo);
        freeze.setCoins(coins);
        freeze.setStatus(CoinFreezeEntity.STATUS_FROZEN);
        freeze.setCreatedAt(nowUtc());
        freeze.setUpdatedAt(nowUtc());
        // 先建冻结单占位：并发重入撞唯一索引直接返回 0，钱不会被扣两次
        int created = freezeMapper.insertIgnore(freeze);
        if (created == 0) {
            // 并发下另一事务已建：回读校验数量
            CoinFreezeEntity again = freezeMapper.selectOne(Wrappers.<CoinFreezeEntity>lambdaQuery()
                    .eq(CoinFreezeEntity::getOrderNo, orderNo));
            if (again != null && again.getCoins() != null && again.getCoins() == coins) {
                return new CoinMutationResult(false, accountOf(userId).getBalance());
            }
            throw new BizException(ErrorCode.BUSINESS_RULE, "同一订单已存在币冻结且数量不一致：" + orderNo);
        }
        // 原子扣余额：balance >= coins 才成功
        int deducted = accountMapper.deductBalance(userId, coins, nowUtc());
        if (deducted == 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "多多币余额不足或账户不存在");
        }
        spendLots(userId, freeze.getId(), coins);
        // 流水占位：正常必然首次插入（冻结单唯一索引已挡住重入）；撞键说明账本已乱，宁可失败
        recordLedger(userId, CoinLedgerEntity.D_SPEND, coins,
                accountOf(userId).getBalance(), "ORDER_FREEZE", orderNo, "下单冻结");
        log.info("多多币冻结：userId={} orderNo={} coins={}", userId, orderNo, coins);
        return new CoinMutationResult(true, accountOf(userId).getBalance());
    }

    /**
     * 支付成功确认扣减：FROZEN → DEDUCTED（余额在下单时已扣过，这里只推进状态）。
     */
    @Transactional
    public CoinMutationResult deductConfirm(String orderNo) {
        CoinFreezeEntity freeze = requireFreeze(orderNo);
        int marked = freezeMapper.markDeducted(orderNo, nowUtc());
        if (marked == 0) {
            if (CoinFreezeEntity.STATUS_DEDUCTED.equals(freeze.getStatus())) {
                return new CoinMutationResult(false, accountOf(freeze.getUserId()).getBalance());
            }
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "冻结单状态不允许确认扣减（" + freeze.getStatus() + "）：" + orderNo);
        }
        log.info("多多币扣减确认：orderNo={} coins={}", orderNo, freeze.getCoins());
        return new CoinMutationResult(true, accountOf(freeze.getUserId()).getBalance());
    }

    /**
     * 取消 / 超时关单释放：FROZEN → RELEASED，余额与批次一并退回，流水记 REFUND。
     */
    @Transactional
    public CoinMutationResult release(String orderNo) {
        CoinFreezeEntity freeze = requireFreeze(orderNo);
        int marked = freezeMapper.markReleased(orderNo, nowUtc());
        if (marked == 0) {
            if (CoinFreezeEntity.STATUS_RELEASED.equals(freeze.getStatus())) {
                return new CoinMutationResult(false, accountOf(freeze.getUserId()).getBalance());
            }
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "冻结单状态不允许释放（" + freeze.getStatus() + "，订单可能已支付）：" + orderNo);
        }
        // 余额与批次必须成对退回：冻结时 spendLots 扣了批次，这里只还余额的话
        // 「余额 = Σ未过期批次」的对账恒等式当场作废（第二批币会被误判成不存在）。
        restoreLots(freeze.getUserId(), freeze.getId(), orderNo, freeze.getCoins());
        accountMapper.addBalanceAndRefundSpent(freeze.getUserId(), freeze.getCoins(), nowUtc());
        recordLedger(freeze.getUserId(), CoinLedgerEntity.D_REFUND, freeze.getCoins(),
                accountOf(freeze.getUserId()).getBalance(), "ORDER_RELEASE", orderNo, "订单取消释放");
        log.info("多多币释放：orderNo={} coins={}", orderNo, freeze.getCoins());
        return new CoinMutationResult(true, accountOf(freeze.getUserId()).getBalance());
    }

    // ==================== 退款退币 / 完成返币 ====================

    /**
     * 退款退币：把订单抵扣的币退回买家。
     *
     * <p>按扣币时的批次明细逐批退回 —— 原批次未过期 → 直接加回该批剩余；
     * 原批次已过期 → 补发等额、30 天有效的新批次（不把过期币复活）。
     * 幂等：流水 (REFUND, ORDER_REFUND, orderNo) 占位，退一次就不再退。
     */
    @Transactional
    public CoinMutationResult refundBack(Long userId, String orderNo, long coins) {
        if (coins <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "退回币数必须为正");
        }
        ensureAccount(userId);
        if (!reserveLedger(userId, CoinLedgerEntity.D_REFUND, coins, "ORDER_REFUND", orderNo, "退款退回")) {
            return new CoinMutationResult(false, accountOf(userId).getBalance());
        }
        restoreLots(userId, requireFreeze(orderNo).getId(), orderNo, coins);
        accountMapper.addBalanceAndEarned(userId, coins, nowUtc());
        rewriteBalanceAfter(userId, CoinLedgerEntity.D_REFUND, "ORDER_REFUND", orderNo);
        log.info("多多币退款退回：userId={} orderNo={} coins={}", userId, orderNo, coins);
        return new CoinMutationResult(true, accountOf(userId).getBalance());
    }

    /**
     * 按冻结批次明细把币退回批次：原批次未过期 → 加回该批剩余；
     * 已过期 → 补发等额、30 天有效的新批次（绝不把过期币复活）。
     * 明细缺失（历史数据 / 异常）时差额按新批次补齐并告警 —— 不允许少退。
     *
     * <p>release（取消释放）与 refundBack（退款退币）共用：两者都是
     * 「冻结时 spendLots 扣走的批次，现在原路还回去」，只是触发场景不同。
     */
    private void restoreLots(Long userId, Long freezeId, String orderNo, long coins) {
        List<CoinFreezeLotEntity> consumed = freezeLotMapper.selectList(
                Wrappers.<CoinFreezeLotEntity>lambdaQuery()
                        .eq(CoinFreezeLotEntity::getFreezeId, freezeId));
        long returned = 0;
        LocalDateTime now = nowUtc();
        for (CoinFreezeLotEntity item : consumed) {
            long take = Math.min(nz(item.getCoins()), coins - returned);
            if (take <= 0) {
                break;
            }
            int back = lotMapper.addRemainingIfValid(item.getLotId(), take, now);
            if (back > 0) {
                returned += take;
            } else {
                createLot(userId, "EXPIRE_COMPENSATE", orderNo, take, now.plusDays(REFUND_LOT_DAYS));
                returned += take;
            }
        }
        if (returned < coins) {
            long gap = coins - returned;
            log.error("多多币批次明细不足，差额补发新批次：orderNo={} 缺口={} 币", orderNo, gap);
            createLot(userId, "EXPIRE_COMPENSATE", orderNo, gap, now.plusDays(REFUND_LOT_DAYS));
        }
    }

    /**
     * 完成返币（交易域 T+7 调度调用）：按「现金实付商品金额」1 元 : 1 币 发放，12 个月有效。
     *
     * <p>活动期间按 {@code earn_multiplier} 加成（向下取整），加成部分同样受活动总预算与
     * 每人上限约束，预算不足时退回基础值不报错。流水先按基础值占位（幂等闸门），
     * 加成确定后在同一事务里改写金额 —— 这样重放不会白白吃掉活动预算。
     */
    @Transactional
    public CoinMutationResult earn(Long userId, String orderNo, long coins) {
        if (coins <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "返币数必须为正");
        }
        ensureAccount(userId);
        if (!reserveLedger(userId, CoinLedgerEntity.D_EARN, coins, "ORDER_EARN", orderNo, "完成订单返币")) {
            return new CoinMutationResult(false, accountOf(userId).getBalance());
        }
        long granted = campaignService.earnBonus(userId, coins);
        if (granted != coins) {
            rewriteLedgerAmount(userId, CoinLedgerEntity.D_EARN, "ORDER_EARN", orderNo, granted);
        }
        createLot(userId, "ORDER_EARN", orderNo, granted, VALID_MONTHS);
        accountMapper.addBalanceAndEarned(userId, granted, nowUtc());
        rewriteBalanceAfter(userId, CoinLedgerEntity.D_EARN, "ORDER_EARN", orderNo);
        log.info("多多币返币：userId={} orderNo={} coins={}（基础 {}）", userId, orderNo, granted, coins);
        return new CoinMutationResult(true, accountOf(userId).getBalance());
    }

    /**
     * 任务奖励发放（R-P1 第三轮）：与 {@link #earn} 同一套记账口径 ——
     * 流水占位（biz_type=COIN_TASK，biz_no=taskCode:bizDate）、12 个月批次、余额与累计同步。
     *
     * <p>幂等完全由流水唯一键承担：同一用户同一任务同一业务日只可能发一次，
     * 即使「已领」标记与发币之间的重试打进来，也只会返回 applied=false。
     */
    @Transactional
    public CoinMutationResult awardTaskCoins(Long userId, String taskCode, LocalDate bizDate, int coins) {
        if (coins <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "任务奖励币数必须为正");
        }
        ensureAccount(userId);
        String bizNo = taskCode + ":" + bizDate;
        String remark = taskNameOf(taskCode);
        if (!reserveLedger(userId, CoinLedgerEntity.D_EARN, coins, "COIN_TASK", bizNo, remark)) {
            return new CoinMutationResult(false, accountOf(userId).getBalance());
        }
        createLot(userId, "COIN_TASK", bizNo, coins, VALID_MONTHS);
        // mapper 入参是 Long：int 自动加宽只到 long，装箱不会跟着变，必须显式转
        accountMapper.addBalanceAndEarned(userId, (long) coins, nowUtc());
        rewriteBalanceAfter(userId, CoinLedgerEntity.D_EARN, "COIN_TASK", bizNo);
        log.info("多多币任务奖励：userId={} taskCode={} bizDate={} coins={}", userId, taskCode, bizDate, coins);
        return new CoinMutationResult(true, accountOf(userId).getBalance());
    }

    /** 流水备注用任务名（任务定义缺失时退回任务编码，绝不空着） */
    private String taskNameOf(String taskCode) {
        CoinTaskDefEntity def = taskDefMapper.selectOne(Wrappers.<CoinTaskDefEntity>lambdaQuery()
                .eq(CoinTaskDefEntity::getCode, taskCode)
                .last("LIMIT 1"));
        return def == null || def.getName() == null || def.getName().isBlank() ? taskCode : def.getName();
    }

    // ==================== 签到（R-P2） ====================

    /** 签到奖励口径（PLAN §7 默认值）：每日 5 币；连签达 3 天那笔 +10、达 7 天那笔 +30 */
    public static final int SIGNIN_DAILY_COINS = 5;
    public static final int SIGNIN_BONUS3_AT = 3;
    public static final int SIGNIN_BONUS3_COINS = 10;
    public static final int SIGNIN_BONUS7_AT = 7;
    public static final int SIGNIN_BONUS7_COINS = 30;

    /**
     * 每日签到：发 5 币（12 个月批次），连签达 3/7 天的**当天那笔**额外 +10/+30，断签重置连签。
     *
     * <p>防刷三道闸：① 服务层先查今日是否已签（友好提示）；
     * ② {@code uk_coin_signin (user_id, sign_date)} 唯一索引 + INSERT IGNORE（并发双击兜底，
     * 一天一行，奖励绝不可能发两次）；③ 发币走流水幂等占位（EARN / SIGNIN_EARN / 日期），
     * 与签到行同一事务 —— 哪怕行建成功、发币失败，整体回滚，不存在「签了到没拿到币」。
     *
     * <p>连签判定只读**昨天一行**的 continuous_days 快照（有 → +1，无 → 重置 1），
     * 不回扫全月历史：一行快照换 O(1) 判定，且快照本身就在发放事务里写定，天然可信。
     *
     * <p>活动期间按 {@code signin_multiplier} 加成（向下取整、最低 1），受活动总预算与每人上限约束；
     * 预算不足时退回基础值。加成在签到行插入成功之后才计算 —— 重复签到先被唯一键挡掉，
     * 不会白白吃掉活动预算。
     */
    @Transactional
    public CoinSigninResult signin(Long userId) {
        LocalDate today = LocalDate.now(EXPIRE_ZONE);
        if (signinMapper.selectCount(Wrappers.<CoinSigninEntity>lambdaQuery()
                .eq(CoinSigninEntity::getUserId, userId)
                .eq(CoinSigninEntity::getSignDate, today)) > 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "今天已经签到过了，明天再来");
        }
        CoinSigninEntity yesterday = signinMapper.selectOne(Wrappers.<CoinSigninEntity>lambdaQuery()
                .eq(CoinSigninEntity::getUserId, userId)
                .eq(CoinSigninEntity::getSignDate, today.minusDays(1))
                .last("LIMIT 1"));
        int continuous = yesterday == null || yesterday.getContinuousDays() == null
                ? 1
                : yesterday.getContinuousDays() + 1;
        int baseCoins = SIGNIN_DAILY_COINS
                + (continuous == SIGNIN_BONUS3_AT ? SIGNIN_BONUS3_COINS : 0)
                + (continuous == SIGNIN_BONUS7_AT ? SIGNIN_BONUS7_COINS : 0);

        ensureAccount(userId);
        CoinSigninEntity row = new CoinSigninEntity();
        row.setId(idGenerator.nextId());
        row.setUserId(userId);
        row.setSignDate(today);
        row.setCoins(baseCoins);
        row.setContinuousDays(continuous);
        row.setCreatedAt(nowUtc());
        if (signinMapper.insertIgnore(row) == 0) {
            // 并发重入撞唯一索引：与前置查重同义，但这里语义更强（另一事务已经发完币）
            throw new BizException(ErrorCode.BUSINESS_RULE, "今天已经签到过了，明天再来");
        }
        int coins = campaignService.signinBonus(userId, baseCoins);
        if (coins != baseCoins) {
            // 签到行上的 coins 是「当日实际发放数」，加成后要落回该行（月历与对账都读它）
            row.setCoins(coins);
            signinMapper.updateById(row);
        }
        String bizNo = "SIGNIN-" + today;
        if (!reserveLedger(userId, CoinLedgerEntity.D_EARN, coins, "SIGNIN_EARN", bizNo, "每日签到")) {
            throw new BizException(ErrorCode.INTERNAL, "签到流水已存在但签到行缺失（数据异常）：" + bizNo);
        }
        createLot(userId, "SIGNIN_EARN", bizNo, coins, VALID_MONTHS);
        // mapper 入参是 Long：int 自动加宽只到 long，装箱不会跟着变，必须显式转
        accountMapper.addBalanceAndEarned(userId, (long) coins, nowUtc());
        rewriteBalanceAfter(userId, CoinLedgerEntity.D_EARN, "SIGNIN_EARN", bizNo);
        log.info("多多币签到：userId={} date={} coins={}（基础 {}） continuous={}",
                userId, today, coins, baseCoins, continuous);
        return new CoinSigninResult(true, coins, continuous, accountOf(userId).getBalance());
    }

    /** 当月签到月历：已签日期（ISO）、当前连签天数、今日是否已签 */
    public CoinSigninMonthView signinMonth(Long userId, String ym) {
        LocalDate today = LocalDate.now(EXPIRE_ZONE);
        YearMonth month;
        try {
            month = (ym == null || ym.isBlank()) ? YearMonth.from(today) : YearMonth.parse(ym.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "月份格式应为 yyyy-MM：" + ym);
        }
        List<CoinSigninEntity> rows = signinMapper.selectList(Wrappers.<CoinSigninEntity>lambdaQuery()
                .eq(CoinSigninEntity::getUserId, userId)
                .ge(CoinSigninEntity::getSignDate, month.atDay(1))
                .le(CoinSigninEntity::getSignDate, month.atEndOfMonth())
                .orderByAsc(CoinSigninEntity::getSignDate));
        List<String> dates = rows.stream().map(r -> r.getSignDate().toString()).toList();
        // 连签天数以「今天」的快照为准（今天没签则回读昨天，断签自然归零/重置）；
        // 这里直接取最近一行：若它是今天或昨天，其快照就是当前连签；否则已断签，显示 0
        int continuous = 0;
        if (!rows.isEmpty()) {
            CoinSigninEntity last = rows.get(rows.size() - 1);
            if (!last.getSignDate().isBefore(today.minusDays(1))) {
                continuous = last.getContinuousDays() == null ? 0 : last.getContinuousDays();
            }
        }
        boolean signedToday = rows.stream().anyMatch(r -> today.equals(r.getSignDate()));
        return new CoinSigninMonthView(month.toString(), dates, continuous, signedToday);
    }

    // ==================== 过期 / 调整 / 对账 ====================

    /**
     * 过期批次清零：到期批次的剩余从余额扣掉并记 EXPIRE 流水。
     * 由定时任务周期调用；balance_after 重算与清理同事务。
     */
    @Transactional
    public int expireLots() {
        LocalDateTime now = nowUtc();
        List<CoinLotEntity> due = lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                .gt(CoinLotEntity::getRemaining, 0)
                .le(CoinLotEntity::getExpireAt, now)
                .orderByAsc(CoinLotEntity::getExpireAt)
                .last("LIMIT 500"));
        int count = 0;
        for (CoinLotEntity lot : due) {
            long remaining = lot.getRemaining();
            int cleared = lotMapper.clearRemainingIfAny(lot.getId(), now);
            if (cleared == 0) {
                continue;
            }
            // 余额同步扣减；条件保护：正常数据必然成立，不成立说明账已乱（对账会抓）
            int subtracted = accountMapper.subtractBalance(lot.getUserId(), remaining, now);
            if (subtracted == 0) {
                log.error("过期批次扣余额失败（余额不足以覆盖过期数，账目已异常）：lotId={} userId={} 币={}",
                        lot.getId(), lot.getUserId(), remaining);
            }
            reserveLedger(lot.getUserId(), CoinLedgerEntity.D_EXPIRE, remaining,
                    "LOT_EXPIRE", String.valueOf(lot.getId()), "批次到期清零");
            count++;
        }
        if (count > 0) {
            log.info("多多币过期清理：{} 批", count);
        }
        return count;
    }

    /** 人工调整（运营后台，带审计；amount 允许负数表示调减，回收上限 = 当前批次可扣量） */
    /**
     * 人工调整：加发建新批次（12 个月），回收（负数）按「先到期先扣」从批次扣减。
     *
     * <p>回收**必须**走批次：余额是「Σ未过期批次剩余」的物化视图，
     * 绕开批次直接减余额会把账本拆成两套数字，对账恒等式当场作废。
     * 因此可回收上限就是当前批次可扣量 —— 超出直接拒绝（409），
     * 不允许「余额扣成负数、批次却还在」的暗亏形态。
     */
    @Transactional
    public CoinMutationResult adjust(Long userId, long amount, String reason) {
        if (amount == 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "调整数量不能为 0");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "调整必须填写原因");
        }
        ensureAccount(userId);
        String adjustNo = "ADJ" + idGenerator.nextId();
        if (amount > 0) {
            // 加发计入「累计获得」：运营发币对用户而言与购物返币同为一次获得
            accountMapper.addBalanceAndEarned(userId, amount, nowUtc());
            createLot(userId, "ADMIN_ADJUST", adjustNo, amount, VALID_MONTHS);
        } else {
            long recoverable = lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                            .eq(CoinLotEntity::getUserId, userId)
                            .gt(CoinLotEntity::getRemaining, 0)
                            .gt(CoinLotEntity::getExpireAt, nowUtc())
                            .orderByAsc(CoinLotEntity::getExpireAt))
                    .stream().mapToLong(CoinLotEntity::getRemaining).sum();
            if (recoverable < -amount) {
                throw new BizException(ErrorCode.CONFLICT,
                        "回收数量超过该用户当前批次可扣量（" + recoverable + " 币）");
            }
            accountMapper.adjustBalance(userId, amount, nowUtc());
            long left = -amount;
            for (CoinLotEntity lot : lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                    .eq(CoinLotEntity::getUserId, userId)
                    .gt(CoinLotEntity::getRemaining, 0)
                    .gt(CoinLotEntity::getExpireAt, nowUtc())
                    .orderByAsc(CoinLotEntity::getExpireAt))) {
                if (left <= 0) {
                    break;
                }
                long take = Math.min(nz(lot.getRemaining()), left);
                if (lotMapper.deductRemaining(lot.getId(), take, nowUtc()) > 0) {
                    left -= take;
                }
            }
        }
        recordLedger(userId, CoinLedgerEntity.D_ADJUST, amount,
                accountOf(userId).getBalance(), "ADMIN_ADJUST", adjustNo, reason);
        log.warn("多多币人工调整：userId={} amount={} reason={} adjustNo={}", userId, amount, reason, adjustNo);
        return new CoinMutationResult(true, accountOf(userId).getBalance());
    }

    /**
     * 对账：余额 = Σ 未过期批次剩余；且流水最新 balance_after == 当前余额。
     * 返回问题清单（空 = 账实一致）；定时任务调用并打日志，冒烟断言零问题。
     */
    public CoinReconResult reconcile() {
        List<CoinAccountEntity> accounts = accountMapper.selectList(Wrappers.emptyWrapper());
        List<String> issues = new ArrayList<>();
        LocalDateTime now = nowUtc();
        for (CoinAccountEntity account : accounts) {
            List<CoinLotEntity> lots = lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                    .eq(CoinLotEntity::getUserId, account.getUserId())
                    .gt(CoinLotEntity::getRemaining, 0)
                    .gt(CoinLotEntity::getExpireAt, now));
            long lotsSum = lots.stream().mapToLong(CoinLotEntity::getRemaining).sum();
            if (lotsSum != nz(account.getBalance())) {
                issues.add("账户 " + account.getUserId() + " 余额 " + account.getBalance()
                        + " ≠ 未过期批次合计 " + lotsSum);
            }
            CoinLedgerEntity latest = ledgerMapper.selectOne(Wrappers.<CoinLedgerEntity>lambdaQuery()
                    .eq(CoinLedgerEntity::getUserId, account.getUserId())
                    .orderByDesc(CoinLedgerEntity::getCreatedAt)
                    .orderByDesc(CoinLedgerEntity::getId)
                    .last("LIMIT 1"));
            if (latest != null && latest.getBalanceAfter() != null
                    && latest.getBalanceAfter().longValue() != nz(account.getBalance())) {
                issues.add("账户 " + account.getUserId() + " 最新流水 balance_after " + latest.getBalanceAfter()
                        + " ≠ 当前余额 " + account.getBalance());
            }
        }
        return new CoinReconResult(accounts.size(), issues);
    }

    // ==================== 内部实现 ====================

    /** 按「先到期先扣」消耗批次并记录冻结批次明细（调用方已扣账户余额） */
    private void spendLots(Long userId, Long freezeId, long coins) {
        long left = coins;
        List<CoinLotEntity> lots = lotMapper.selectList(Wrappers.<CoinLotEntity>lambdaQuery()
                .eq(CoinLotEntity::getUserId, userId)
                .gt(CoinLotEntity::getRemaining, 0)
                .gt(CoinLotEntity::getExpireAt, nowUtc())
                .orderByAsc(CoinLotEntity::getExpireAt)
                .orderByAsc(CoinLotEntity::getCreatedAt));
        for (CoinLotEntity lot : lots) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(nz(lot.getRemaining()), left);
            int updated = lotMapper.deductRemaining(lot.getId(), take, nowUtc());
            if (updated == 0) {
                continue; // 并发被别的请求扣了，重读后续批次
            }
            CoinFreezeLotEntity item = new CoinFreezeLotEntity();
            item.setId(idGenerator.nextId());
            item.setFreezeId(freezeId);
            item.setLotId(lot.getId());
            item.setCoins(take);
            freezeLotMapper.insert(item);
            left -= take;
        }
        if (left > 0) {
            // 余额条件已挡住负数，批次合计却不够 = 账本不一致，绝不能带着暗亏继续
            throw new BizException(ErrorCode.INTERNAL, "批次合计与余额不一致，冻结中止（对账必须处理）");
        }
    }

    /** 流水占位（幂等闸门）：返回 false = 已记过账，调用方按已处理返回 */
    private boolean reserveLedger(Long userId, String direction, long amount,
                                  String bizType, String bizNo, String remark) {
        CoinLedgerEntity row = new CoinLedgerEntity();
        row.setId(idGenerator.nextId());
        row.setUserId(userId);
        row.setDirection(direction);
        row.setAmount(amount);
        row.setBalanceAfter(0L); // 先占位，动作完成后 rewriteBalanceAfter 补真实快照
        row.setBizType(bizType);
        row.setBizNo(bizNo);
        row.setRemark(remark);
        row.setCreatedAt(nowUtc());
        int inserted = ledgerMapper.insertIgnore(row);
        return inserted > 0;
    }

    /** 动作完成后回写流水上的余额快照（同事务，保证对账可校验） */
    private void rewriteBalanceAfter(Long userId, String direction, String bizType, String bizNo) {
        CoinLedgerEntity row = ledgerMapper.selectOne(Wrappers.<CoinLedgerEntity>lambdaQuery()
                .eq(CoinLedgerEntity::getUserId, userId)
                .eq(CoinLedgerEntity::getDirection, direction)
                .eq(CoinLedgerEntity::getBizType, bizType)
                .eq(CoinLedgerEntity::getBizNo, bizNo));
        if (row != null) {
            row.setBalanceAfter(accountOf(userId).getBalance());
            ledgerMapper.updateById(row);
        }
    }

    /**
     * 调整已占位流水的金额（活动加成用）。
     *
     * <p>为什么不在占位时就写最终值：占位是幂等闸门，必须先于活动预算占用发生 ——
     * 否则重复回调会先吃掉一份预算再发现「这单已经发过币」。加成确定后在同一事务内改金额，
     * 对账看到的仍是最终发放数。
     */
    private void rewriteLedgerAmount(Long userId, String direction, String bizType, String bizNo, long amount) {
        CoinLedgerEntity row = ledgerMapper.selectOne(Wrappers.<CoinLedgerEntity>lambdaQuery()
                .eq(CoinLedgerEntity::getUserId, userId)
                .eq(CoinLedgerEntity::getDirection, direction)
                .eq(CoinLedgerEntity::getBizType, bizType)
                .eq(CoinLedgerEntity::getBizNo, bizNo));
        if (row != null) {
            row.setAmount(amount);
            ledgerMapper.updateById(row);
        }
    }

    /** 直接记账（无占位语义的动作：冻结 / 人工调整） */
    private void recordLedger(Long userId, String direction, long amount,
                              long balanceAfter, String bizType, String bizNo, String remark) {
        CoinLedgerEntity row = new CoinLedgerEntity();
        row.setId(idGenerator.nextId());
        row.setUserId(userId);
        row.setDirection(direction);
        row.setAmount(amount);
        row.setBalanceAfter(balanceAfter);
        row.setBizType(bizType);
        row.setBizNo(bizNo);
        row.setRemark(remark);
        row.setCreatedAt(nowUtc());
        int inserted = ledgerMapper.insertIgnore(row);
        if (inserted == 0 && !CoinLedgerEntity.D_SPEND.equals(direction)) {
            throw new BizException(ErrorCode.INTERNAL, "流水唯一键冲突：" + bizType + "/" + bizNo);
        }
    }

    /** 建新批次：按月数算到期（正整数月）；见重载 {@link #createLot(Long, String, String, long, LocalDateTime)} */
    private void createLot(Long userId, String bizType, String bizNo, long coins, int validMonths) {
        createLot(userId, bizType, bizNo, coins, expireAfterMonths(validMonths));
    }

    /** 建新批次（到期时刻直给）：退款补发的 30 天批次走这条，避免「天数被当月数」的口径事故 */
    private void createLot(Long userId, String bizType, String bizNo, long coins, LocalDateTime expireAt) {
        CoinLotEntity lot = new CoinLotEntity();
        lot.setId(idGenerator.nextId());
        lot.setUserId(userId);
        lot.setBizType(bizType);
        lot.setBizNo(bizNo);
        lot.setAmount(coins);
        lot.setRemaining(coins);
        lot.setExpireAt(expireAt);
        lot.setCreatedAt(nowUtc());
        lot.setUpdatedAt(nowUtc());
        lotMapper.insert(lot);
    }

    /** 到期时刻：Asia/Shanghai 自然月对日 23:59:59（本地时间直觉），存储转 UTC */
    static LocalDateTime expireAfterMonths(int months) {
        ZonedDateTime expire = ZonedDateTime.now(EXPIRE_ZONE)
                .plusMonths(months)
                .toLocalDate()
                .atTime(23, 59, 59)
                .atZone(EXPIRE_ZONE);
        return LocalDateTime.ofInstant(expire.toInstant(), ZoneOffset.UTC);
    }

    private CoinAccountEntity accountOf(Long userId) {
        CoinAccountEntity account = accountMapper.selectOne(
                Wrappers.<CoinAccountEntity>lambdaQuery().eq(CoinAccountEntity::getUserId, userId));
        if (account == null) {
            // 只读路径：没开户视为零余额（开户动作在首次写路径 ensureAccount）
            CoinAccountEntity empty = new CoinAccountEntity();
            empty.setUserId(userId);
            empty.setBalance(0L);
            empty.setTotalEarned(0L);
            empty.setTotalSpent(0L);
            return empty;
        }
        return account;
    }

    private void ensureAccount(Long userId) {
        CoinAccountEntity account = new CoinAccountEntity();
        account.setId(idGenerator.nextId());
        account.setUserId(userId);
        account.setCreatedAt(nowUtc());
        account.setUpdatedAt(nowUtc());
        accountMapper.insertIgnore(account);
    }

    private CoinFreezeEntity requireFreeze(String orderNo) {
        CoinFreezeEntity freeze = freezeMapper.selectOne(Wrappers.<CoinFreezeEntity>lambdaQuery()
                .eq(CoinFreezeEntity::getOrderNo, orderNo));
        if (freeze == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "币冻结单不存在：" + orderNo);
        }
        return freeze;
    }

    private CoinLedgerView toView(CoinLedgerEntity row) {
        return new CoinLedgerView(String.valueOf(row.getId()), row.getUserId(), row.getDirection(),
                nz(row.getAmount()), nz(row.getBalanceAfter()), row.getBizType(), row.getBizNo(),
                row.getRemark(), iso(row.getCreatedAt()));
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
