package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.product.dto.PersonalDataCleanupResult;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.mapper.ListingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C7 · 账号注销时商品域的个人数据清理（服务间调用，见 {@code docs/account-deletion-design.md} 4.4）。
 *
 * <h2>为什么删、为什么留（挡）</h2>
 * <ul>
 *   <li><b>删</b>：已下架的闲置（{@code t_listing.status = OFF_SHELF}，含历史上
 *       已被软删的行）。闲置是发布者个人主动数据，下架后无任何展示与交易价值，
 *       注销即物理删 —— 与 {@code ListingService.remove} 的软删是两个场合：
 *       那个要保住「这条内容曾经存在」给订单与会话引用，这个连账号都不要了；</li>
 *   <li><b>挡（blocking）</b>：在售的闲置（{@code status = ON_SALE}）一行不动，
 *       只计数。它随时可能被买家拍下，执行阶段一刀删掉会造成
 *       「买家已付款、商品却不存在」。因此设计文档第六节把它定为<b>阻塞项</b>：
 *       用户申请注销时预检挡下、引导先自行下架，下架后才落入删除口径。</li>
 * </ul>
 *
 * <h2>两个 WHERE 口径的刻意差异</h2>
 * <ul>
 *   <li>删除 / 预估删除用 {@code seller_id + OFF_SHELF}，<b>不带</b> {@code deleted_at}
 *       过滤 —— 注销要清掉这个人留下的一切行，含此前软删的；</li>
 *   <li>在售计数用 {@code seller_id + ON_SALE + deleted_at IS NULL} ——
 *       与 {@code ListingService} 的公开可见性规则一致：软删的行对外本就不可见，
 *       不该拿一条看不见的闲置挡住用户的注销申请。</li>
 * </ul>
 *
 * <h2>幂等可重入</h2>
 * DELETE 是幂等语句：重复调用不报错、不二次破坏，第二次命中 0 行、数字归零。
 * 注销调度「失败重试、断点恢复」依赖这一点（设计文档第六节）。
 *
 * <h2>两种模式</h2>
 * <ul>
 *   <li>{@link #preview}（dryRun）：只查不写，回报「将删多少行、有多少在售要挡下」；</li>
 *   <li>{@link #execute}（缺省 / false）：真正删除已下架闲置，回报实际影响行数。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（共用同一组 WHERE），预估与实际必然对得上。
 */
@Service
public class PersonalDataCleanupService {

    /** 域标识（与用户域 Feign 契约 {@code AccountDeletionDtos}、注销日志口径一致） */
    private static final String DOMAIN = "product";

    /** 与 {@code ListingService} 的同名常量保持同一组字面量（状态机单一事实来源在该服务） */
    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";

    private final ListingMapper listingMapper;

    public PersonalDataCleanupService(ListingMapper listingMapper) {
        this.listingMapper = listingMapper;
    }

    /**
     * 预检（dryRun）：只查不写，回报「将删多少行已下架闲置、多少行在售要挡下注销」。
     *
     * <p>{@code readOnly = true} 让连接池/驱动有机会走只读优化，
     * 也防将来有人手滑在这里加写语句。
     */
    @Transactional(readOnly = true)
    public PersonalDataCleanupResult preview(Long userId) {
        long offShelf = listingMapper.selectCount(offShelfOf(userId));
        long onSale = listingMapper.selectCount(onSaleOf(userId));
        return new PersonalDataCleanupResult(userId, DOMAIN, (int) offShelf, 0, (int) onSale);
    }

    /**
     * 执行：物理删该用户已下架的闲置（在售的预检阶段已挡下，不会走到这里）。
     * 幂等，可重复调用。
     */
    @Transactional
    public PersonalDataCleanupResult execute(Long userId) {
        int deleted = listingMapper.delete(offShelfOf(userId));
        // 在售行不在删除范围内，执行前后数量不变；照实重报一次，
        // 与 preview 的 blocking 口径一致（用户域执行模式不依赖它，仅日志用）
        long onSale = listingMapper.selectCount(onSaleOf(userId));
        return new PersonalDataCleanupResult(userId, DOMAIN, deleted, 0, (int) onSale);
    }

    /** 可随注销物理删的行：本人 + 已下架（含已软删的，见类注释） */
    private static LambdaQueryWrapper<ListingEntity> offShelfOf(Long userId) {
        return new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getSellerId, userId)
                .eq(ListingEntity::getStatus, STATUS_OFF_SHELF);
    }

    /** 阻塞项：本人 + 在售 + 未被软删（与 ListingService 的公开可见性同口径） */
    private static LambdaQueryWrapper<ListingEntity> onSaleOf(Long userId) {
        return new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getSellerId, userId)
                .eq(ListingEntity::getStatus, STATUS_ON_SALE)
                .isNull(ListingEntity::getDeletedAt);
    }
}
