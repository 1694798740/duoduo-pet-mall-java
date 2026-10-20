package com.duoduo.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.marketing.dto.PersonalDataCleanupResult;
import com.duoduo.marketing.entity.CouponEntity;
import com.duoduo.marketing.mapper.CouponMapper;
import com.duoduo.marketing.support.CouponStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * C7 · 账号注销时营销域的个人数据清理（服务间调用，见 {@code docs/account-deletion-design.md} 4.4）。
 *
 * <h2>为什么删、为什么留</h2>
 * <ul>
 *   <li><b>删</b>：{@code UNUSED}（未使用）/ {@code LOCKED}（已锁定，挂在未付订单上）/
 *       {@code EXPIRED}（已过期）三种状态的券。它们从未进入资金链路 ——
 *       删掉不影响任何账目，账号注销后也没有「留着一张用不了的券」的意义；</li>
 *   <li><b>留</b>：{@code USED}（已核销）的券一行不动。它对应着一笔真实成交的订单，
 *       订单详情要给买卖双方展示「本单用了哪张券、抵了多少」，订单侧的留档
 *       （设计文档第五节）需要它来回答「这 30 块是哪张券抵的」。
 *       锁在未付订单上的 {@code LOCKED} 随关单 / 注销一并消失，
 *       交易域那边订单行本身仍按三年口径留存。</li>
 * </ul>
 *
 * <h2>幂等可重入</h2>
 * DELETE 是幂等语句：重复调用不报错、不二次破坏，第二次命中 0 行、数字归零。
 * 注销调度「失败重试、断点恢复」依赖这一点（设计文档第六节）。
 */
@Service
public class PersonalDataCleanupService {

    /** 域标识（回给用户域编排做日志与对账） */
    private static final String DOMAIN = "marketing";

    private final CouponMapper couponMapper;

    public PersonalDataCleanupService(CouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    /**
     * 预检（dryRun）：只查不写，回报「若执行将会删多少行券」。
     *
     * <p>{@code readOnly = true} 让连接池/驱动有机会走只读优化，
     * 也防将来有人手滑在这里加写语句。
     */
    @Transactional(readOnly = true)
    public PersonalDataCleanupResult preview(Long userId) {
        long deleted = couponMapper.selectCount(deletableByUser(userId));
        return new PersonalDataCleanupResult(userId, DOMAIN, (int) deleted, 0, 0);
    }

    /**
     * 执行：删该用户未核销的券。幂等，可重复调用。
     */
    @Transactional
    public PersonalDataCleanupResult execute(Long userId) {
        int deleted = couponMapper.delete(deletableByUser(userId));
        // marketing 不阻塞注销：券的任何状态都不拦（blocking 恒 0，见类注释）
        return new PersonalDataCleanupResult(userId, DOMAIN, deleted, 0, 0);
    }

    /**
     * 「可随注销删除」的条件：归属该用户，且状态为未使用 / 已锁定 / 已过期。
     *
     * <p>状态名取自 {@link CouponStatus} 枚举而非字面量，与状态机单一事实来源对齐；
     * 预检的 COUNT 与执行的 DELETE 共用这一个方法，保证两边口径逐字一致。
     */
    private LambdaQueryWrapper<CouponEntity> deletableByUser(Long userId) {
        List<String> statuses = List.of(
                CouponStatus.UNUSED.name(),
                CouponStatus.LOCKED.name(),
                CouponStatus.EXPIRED.name());
        return new LambdaQueryWrapper<CouponEntity>()
                .eq(CouponEntity::getUserId, userId)
                .in(CouponEntity::getStatus, statuses);
    }
}
