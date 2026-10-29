package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 冻结时消耗的批次明细（R-P1）：退款退币「退回原批次」的依据。
 *
 * <p>没有这张表，「退款退回原批次、原批次已过期则补发 30 天新批次」就无从执行 ——
 * 系统不知道当初扣的是哪几批、各批扣了多少、那些批次何时到期。
 */
@TableName("t_coin_freeze_lot")
public class CoinFreezeLotEntity {

    @TableId
    private Long id;
    private Long freezeId;
    private Long lotId;
    private Long coins;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFreezeId() { return freezeId; }
    public void setFreezeId(Long freezeId) { this.freezeId = freezeId; }
    public Long getLotId() { return lotId; }
    public void setLotId(Long lotId) { this.lotId = lotId; }
    public Long getCoins() { return coins; }
    public void setCoins(Long coins) { this.coins = coins; }
}
