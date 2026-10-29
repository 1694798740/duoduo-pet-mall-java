package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinAccountEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 多多币账户数据访问。
 *
 * <p>余额变动全部走**条件 UPDATE**（原子 CAS），绝不「先查再算再写」：
 * <ul>
 *   <li>扣减带 {@code balance >= coins} 条件 —— 并发下单不可能把余额扣成负数；</li>
 *   <li>加回（释放 / 返币 / 退款）无条件条件 —— 加钱不需要防超加，
 *       幂等由流水唯一键保证：能走到加钱这一步，说明流水插入已成功占位。</li>
 * </ul>
 */
@Mapper
public interface CoinAccountMapper extends BaseMapper<CoinAccountEntity> {

    /** 开户（幂等，撞 uk_coin_account_user 静默跳过）。单实体入参，占位符直接绑实体属性 */
    @Insert("INSERT IGNORE INTO t_coin_account (id, user_id, balance, total_earned, total_spent, "
            + "version, created_at, updated_at) VALUES (#{id}, #{userId}, 0, 0, 0, 0, #{createdAt}, #{updatedAt})")
    int insertIgnore(CoinAccountEntity account);

    /** 扣减余额（冻结用币）：balance >= coins 才成功，返回 0 = 余额不足 */
    @Update("UPDATE t_coin_account SET balance = balance - #{coins}, "
            + "total_spent = total_spent + #{coins}, version = version + 1, updated_at = #{at} "
            + "WHERE user_id = #{userId} AND balance >= #{coins}")
    int deductBalance(@Param("userId") Long userId, @Param("coins") Long coins, @Param("at") LocalDateTime at);

    /** 加回余额（释放 / 退款退币） */
    @Update("UPDATE t_coin_account SET balance = balance + #{coins}, "
            + "version = version + 1, updated_at = #{at} WHERE user_id = #{userId}")
    int addBalance(@Param("userId") Long userId, @Param("coins") Long coins, @Param("at") LocalDateTime at);

    /** 加余额并累计获得（返币 / 退款退币 / 补发），与 addBalance 区分在 total_earned */
    @Update("UPDATE t_coin_account SET balance = balance + #{coins}, "
            + "total_earned = total_earned + #{coins}, version = version + 1, updated_at = #{at} "
            + "WHERE user_id = #{userId}")
    int addBalanceAndEarned(@Param("userId") Long userId, @Param("coins") Long coins, @Param("at") LocalDateTime at);

    /** 过期清零：把过期批次剩余从余额扣掉（可扣成负数吗？不会 —— 过期的批次早已不在余额里）
     *  注意：过期只发生在批次剩余上，余额同步减，条件 balance >= coins 在正常数据下必然成立；不成立说明账已经乱了，由对账兜底 */
    @Update("UPDATE t_coin_account SET balance = balance - #{coins}, "
            + "version = version + 1, updated_at = #{at} WHERE user_id = #{userId} AND balance >= #{coins}")
    int subtractBalance(@Param("userId") Long userId, @Param("coins") Long coins, @Param("at") LocalDateTime at);

    /** 累计消耗回退（冻结释放时 total_spent 减回） */
    @Update("UPDATE t_coin_account SET balance = balance + #{coins}, "
            + "total_spent = total_spent - #{coins}, version = version + 1, updated_at = #{at} "
            + "WHERE user_id = #{userId}")
    int addBalanceAndRefundSpent(@Param("userId") Long userId, @Param("coins") Long coins, @Param("at") LocalDateTime at);

    /** 人工调整（允许调减到负数）：amount 可正可负 */
    @Update("UPDATE t_coin_account SET balance = balance + #{amount}, version = version + 1, updated_at = #{at} "
            + "WHERE user_id = #{userId}")
    int adjustBalance(@Param("userId") Long userId, @Param("amount") Long amount, @Param("at") LocalDateTime at);
}
