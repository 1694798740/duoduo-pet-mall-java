package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.trade.dto.PersonalDataCleanupResult;
import com.duoduo.trade.entity.CartItemEntity;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.mapper.CartItemMapper;
import com.duoduo.trade.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * C7 · 账号注销：交易域的个人数据清理（服务间，由用户域注销编排调用）。
 *
 * <p>设计依据：{@code docs/account-deletion-design.md} 第 4.4 节与第五节数据清理矩阵。
 * 交易域是五域里唯一「<b>留行脱敏</b>」的域 —— 订单本体承载资金与审计事实，
 * 《电子商务法》要求交易信息自交易完成之日起保存不少于三年，物理删行会连带毁掉
 * 买卖双方的事后追溯；但收货人姓名 / 手机号是 PII，必须改写
 * （设计文档第五节：交易关联数据脱敏、个人数据删）。
 *
 * <p><b>为什么 {@code t_seckill_order} 不单独脱敏</b>：那张表只记
 * (order_no, activity_id) 关联，没有任何收货人列（见 {@code SeckillOrderEntity}
 * 与 {@code docs/sql/03-duoduo-trade.sql}）—— 秒杀订单同样落一条 {@code t_order} 行，
 * 收货人事实只在 {@code t_order} 存一份，脱敏 {@code t_order} 即完整覆盖秒杀订单。
 *
 * <p><b>幂等可重入是硬要求</b>：注销执行分「五域清理 → 全部成功 → 置 DELETED」两步，
 * 任一步失败都要靠下一轮调度重试。DELETE（按 user_id 清购物车）与
 * UPDATE（按 user_id 脱敏收货人）都是幂等语句 —— 重复调用时购物车已空（删 0 行）、
 * 收货人已是脱敏值（MySQL 按「变更行数」计影响行数，把同样的值再写一遍计 0 行），
 * 不报错、不二次破坏，也不需要分布式锁。
 *
 * <p><b>两种模式</b>（由 {@code dryRun} 参数区分）：
 * <ul>
 *   <li>{@code true}：只做 SELECT COUNT，不产生任何写。用于「申请注销」时的阻塞预检；</li>
 *   <li>{@code false}：真正执行删除 / 脱敏，返回实际影响行数。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（同一个 WHERE 条件，COUNT 与 DELETE / UPDATE
 * 对同一批行生效），因此「预检说会删 N 行」与「执行后回报删了 N 行」必然对得上。
 */
@Service
public class PersonalDataCleanupService {

    /** 域标识（与用户域 Feign 契约 {@code AccountDeletionDtos}、注销日志口径一致） */
    private static final String DOMAIN = "trade";

    /** 脱敏后的收货人姓名 / 手机号（设计文档 4.4：姓名置「已注销用户」，手机号置空串） */
    private static final String MASKED_NAME = "已注销用户";
    private static final String MASKED_PHONE = "";

    /**
     * 订单终态：已完成 / 已退款 / 已关闭。
     *
     * <p>非终态（待付款 / 已付款 / 已发货）= 注销阻塞项，与设计文档第六节
     * 「注销中的订单 → 阻塞，用户需先完成或取消」一致。
     */
    private static final List<String> FINAL_ORDER_STATUSES =
            List.of("COMPLETED", "REFUNDED", "CLOSED");

    private final CartItemMapper cartItemMapper;
    private final OrderMapper orderMapper;

    public PersonalDataCleanupService(CartItemMapper cartItemMapper, OrderMapper orderMapper) {
        this.cartItemMapper = cartItemMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 预检（dryRun）：只查不写，回报「若执行将会删多少行、脱敏多少行」，
     * 以及**有多少行会挡住注销**（未终态订单）。
     *
     * <p>{@code readOnly = true}：既是给驱动/连接池的只读提示，
     * 也是给将来改这个方法的自己的约束 —— 这里多一条写语句就是真事故。
     */
    @Transactional(readOnly = true)
    public PersonalDataCleanupResult preview(Long userId) {
        long cartItems = cartItemMapper.selectCount(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId));
        long maskableOrders = orderMapper.selectCount(maskableOrdersOf(userId));
        long blockingOrders = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .notIn(OrderEntity::getStatus, FINAL_ORDER_STATUSES));
        return new PersonalDataCleanupResult(userId, DOMAIN,
                (int) cartItems, (int) maskableOrders, (int) blockingOrders);
    }

    /**
     * 执行：删购物车、脱敏订单收货人。幂等，可重复调用。
     */
    @Transactional
    public PersonalDataCleanupResult execute(Long userId) {
        long blockingOrders = orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .notIn(OrderEntity::getStatus, FINAL_ORDER_STATUSES));

        // 购物车：纯个人主动数据，无留存义务 → 物理删（重复调用删 0 行，幂等）
        int deleted = cartItemMapper.delete(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId));

        /*
         * 订单：行保留（三年留档 + 审计关联），只脱敏收货人 PII。
         *
         * WHERE 带「尚未脱敏」守卫，两个作用：
         *   1) 幂等 —— 重复执行影响 0 行，响应数字随之归零；
         *   2) 口径一致 —— 与 dryRun 的 COUNT 用同一个条件，预估与实际必然对得上。
         * updated_at 显式赋值：本项目无 MetaObjectHandler，与各 Mapper 手写 SQL 里
         * 显式写 updated_at 的做法保持一致（脱敏也是一次真实变更，时间戳该动）。
         */
        OrderEntity patch = new OrderEntity();
        patch.setReceiverName(MASKED_NAME);
        patch.setReceiverPhone(MASKED_PHONE);
        patch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        int masked = orderMapper.update(patch, maskableOrdersOf(userId));

        return new PersonalDataCleanupResult(userId, DOMAIN, deleted, masked, (int) blockingOrders);
    }

    /** 「收货人尚未脱敏」的订单条件：姓名不是脱敏值、或手机号不是空串（两者 OR） */
    private static LambdaQueryWrapper<OrderEntity> maskableOrdersOf(Long userId) {
        return new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .and(w -> w.ne(OrderEntity::getReceiverName, MASKED_NAME)
                        .or().ne(OrderEntity::getReceiverPhone, MASKED_PHONE));
    }
}
