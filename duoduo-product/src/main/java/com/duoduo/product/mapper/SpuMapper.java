package com.duoduo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.product.entity.SpuEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface SpuMapper extends BaseMapper<SpuEntity> {

    /**
     * 商家商品按审核态分桶（商家端「商品管理」审核页签的角标，2026-10-27）。
     *
     * <p>为什么必须服务端聚合：商品列表是分页的，前端按当前页 {@code auditStatus}
     * 去数「待审核有几件」只会得到这一页里的那几件 —— 第 2 页的待审商品在角标上
     * 整个消失，而点进「待审核」页签又能看到，用户只会认为角标坏了。
     *
     * <p>{@code deleted_at IS NULL} 与 {@code SellerProductService#list} 的口径一致：
     * 回收站里的商品不参与日常管理角标（否则卖家永远清不掉一个「待审核」）。
     *
     * <p><b>阅后即消（2026-10-27）</b>：{@code APPROVED} 只是「平台审过了」的通知，
     * 卖家看过就不该再占角标；{@code PENDING}（等审核）与 {@code REJECTED}（要改）
     * 都是还没落定的事，读过也必须留着。
     *
     * @param sellerId 传 null 表示全平台（运营视角）；否则只统计该卖家的商品
     * @param viewerId 当前读者的主体 id（商家 = 店铺主体；运营 = 本人），已读按它过滤
     * @return 形如 {@code [{status: "PENDING", cnt: 2}, ...]}；无商品时返回空列表
     */
    @Select("<script>"
            + "SELECT s.audit_status AS status, COUNT(*) AS cnt FROM t_spu s "
            + "WHERE s.deleted_at IS NULL "
            + "<if test='sellerId != null'>AND s.seller_id = #{sellerId} </if>"
            + "AND NOT (s.audit_status = 'APPROVED' "
            + "         AND EXISTS (SELECT 1 FROM t_spu_read r "
            + "                     WHERE r.user_id = #{viewerId} AND r.spu_id = s.id)) "
            + "GROUP BY s.audit_status"
            + "</script>")
    List<Map<String, Object>> countByAuditStatus(@Param("sellerId") Long sellerId,
                                                 @Param("viewerId") Long viewerId);

    /**
     * 记录「某人看过某商品」：复合主键 + {@code INSERT IGNORE}，重复上报幂等。
     *
     * <p>只影响「已通过」的角标，不改变商品任何业务状态。
     */
    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO t_spu_read (user_id, spu_id, read_at) VALUES (#{userId}, #{spuId}, #{at})")
    int markSpuRead(@Param("userId") Long userId,
                    @Param("spuId") Long spuId,
                    @Param("at") java.time.LocalDateTime at);

    /**
     * 当前页哪些商品已被本人看过（2026-10-27 · 列表「橙色未读印记」的数据源）。
     *
     * <p>与 {@link #markSpuRead} 成对：写已读集合、读这一页的已读 id。
     * 只查当前页 id，避免把整个历史已读集合捞回内存。
     */
    @Select("<script>"
            + "SELECT spu_id FROM t_spu_read WHERE user_id = #{userId} AND spu_id IN "
            + "<foreach collection='spuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> listReadSpuIds(@Param("userId") Long userId,
                              @Param("spuIds") Collection<Long> spuIds);
}
