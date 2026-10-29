package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinLedgerEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多多币流水数据访问。插入即幂等占位：撞 uk_coin_ledger_biz 唯一键 = 已处理过。
 */
@Mapper
public interface CoinLedgerMapper extends BaseMapper<CoinLedgerEntity> {

    /**
     * 流水占位（幂等）：唯一键 (user_id, biz_type, biz_no, direction)。
     * 返回 1 = 本次首次记账；返回 0 = 该业务已经记过账（回调重放 / 调度重试），调用方直接按已处理返回。
     */
    @Insert("INSERT IGNORE INTO t_coin_ledger (id, user_id, direction, amount, balance_after, "
            + "biz_type, biz_no, remark, created_at) "
            + "VALUES (#{id}, #{userId}, #{direction}, #{amount}, #{balanceAfter}, "
            + "#{bizType}, #{bizNo}, #{remark}, #{createdAt})")
    int insertIgnore(CoinLedgerEntity ledger);
}
