package com.duoduo.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.marketing.entity.CouponTemplateEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券模板 Mapper。
 *
 * <p>唯一需要注意的是 {@link #claimOne}：<b>库存扣减必须是 CAS</b>，
 * 把「还有余量」写进 WHERE 而不是先 {@code SELECT claimed_count} 再决定要不要发。
 * 「先查再写」在并发领券下必然超发 —— 两个请求同时查到「还剩 1 张」，
 * 于是两个都发了券，库里的 claimed_count 也只自增一次，凭空多出一张券。
 */
public interface CouponTemplateMapper extends BaseMapper<CouponTemplateEntity> {

    /**
     * 领取一张：已领数 +1，且仅当「启用中 + 有余量 + 在领取窗口内」才生效。
     *
     * <p>返回受影响行数：1 = 抢到，0 = 没抢到（已发完 / 已停用 / 不在窗口内）。
     * 这三条判据全部写进 WHERE，是为了让「为什么没抢到」可以再查一次模板来解释，
     * 而正确性不依赖那次查询 —— 查询只用于生成给用户的文案。
     */
    @Update("UPDATE t_coupon_template SET claimed_count = claimed_count + 1, updated_at = #{now} "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND claimed_count < total_count "
            + "AND start_at <= #{now} AND end_at >= #{now}")
    int claimOne(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 归还一张已占的额度（仅在「CAS 成功但插券撞了唯一索引」时调用）。
     *
     * <p>为什么必须有它：领券是「先占额度（CAS）再插券」两步。
     * 第二步撞上 {@code uk_coupon_user_tpl} 说明这个用户早就领过 ——
     * 此时第一步占掉的那个额度必须还回去，否则每有一个用户重复点击，
     * 库里的 claimed_count 就凭空 +1，券会在没人领到的情况下显示「已领完」。
     */
    @Update("UPDATE t_coupon_template SET claimed_count = claimed_count - 1, updated_at = #{now} "
            + "WHERE id = #{id} AND claimed_count > 0")
    int unclaimOne(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 当前可领取的模板（启用 + 窗口未结束），按 id 升序 —— 列表顺序稳定，便于断言与截图对比 */
    @Select("SELECT * FROM t_coupon_template WHERE status = 'ACTIVE' AND end_at >= #{now} "
            + "ORDER BY id ASC")
    List<CouponTemplateEntity> selectClaimable(@Param("now") LocalDateTime now);
}
