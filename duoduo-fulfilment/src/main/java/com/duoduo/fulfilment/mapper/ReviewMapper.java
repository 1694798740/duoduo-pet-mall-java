package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.ReviewEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface ReviewMapper extends BaseMapper<ReviewEntity> {

    /**
     * 某商品的评分聚合：总条数、平均分、好评数（≥4 星）、带图条数。
     *
     * <p>写成 SQL 聚合而不是「把评价查出来在内存里算」：评分要显示在商品详情页，
     * 是最高频的读路径之一，而评价条数会随销量单调增长。今天的量级跑得动，
     * 不代表一年后还跑得动 —— 这类慢查询往往等数据库 CPU 打满才被发现。
     */
    @Select("SELECT COUNT(*) AS total, "
            + "COALESCE(AVG(rating), 0) AS avgRating, "
            + "COALESCE(SUM(CASE WHEN rating >= 4 THEN 1 ELSE 0 END), 0) AS goodCount, "
            + "COALESCE(SUM(CASE WHEN images IS NOT NULL AND images <> '' THEN 1 ELSE 0 END), 0) AS imageCount, "
            + "COALESCE(SUM(CASE WHEN append_content IS NOT NULL AND append_content <> '' THEN 1 ELSE 0 END), 0) AS appendCount "
            + "FROM t_review WHERE spu_id = #{spuId} AND audit_status = 'APPROVED'")
    Map<String, Object> aggregateBySpu(@Param("spuId") Long spuId);

    /** 星级分布，返回形如 {@code [{rating:5, cnt:12}, ...]}；无数据返回空列表 */
    @Select("SELECT rating, COUNT(*) AS cnt FROM t_review "
            + "WHERE spu_id = #{spuId} AND audit_status = 'APPROVED' GROUP BY rating")
    List<Map<String, Object>> starDistribution(@Param("spuId") Long spuId);

    /**
     * 我在这些订单里已评价过的订单号（去重）。
     *
     * <p>订单列表页要给「已完成」订单显示「去评价 / 已评价」，若逐单调
     * {@code /order/{orderNo}} 就是一页 20 次请求。这里一次查回集合，
     * 前端拿集合做判断即可。
     */
    @Select("<script>"
            + "SELECT DISTINCT order_no FROM t_review WHERE user_id = #{userId} AND order_no IN "
            + "<foreach collection='orderNos' item='no' open='(' separator=',' close=')'>#{no}</foreach>"
            + "</script>")
    List<String> reviewedOrderNos(@Param("userId") Long userId, @Param("orderNos") Collection<String> orderNos);

    /** 某订单已评价的 SPU（用于「还差哪几件没评」的判断） */
    @Select("SELECT spu_id FROM t_review WHERE order_id = #{orderId} AND user_id = #{userId}")
    List<Long> reviewedSpuIds(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
