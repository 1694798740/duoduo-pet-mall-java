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
     * 按 SPU 批量聚合真实评分（第七十三轮）：条数 + 平均分，只统计审核通过的。
     *
     * <p>评价落库后推给商品域回写 t_spu.rating / rating_count 的数据源 ——
     * 聚合口径只在这里有一份，商品域不重复实现（两边各算迟早漂移）。
     * 批量：一次批量评价可能覆盖多件商品，逐个调 {@link #aggregateBySpu} 是 N 次查询。
     */
    @Select("<script>"
            + "SELECT spu_id AS spuId, COUNT(*) AS cnt, COALESCE(AVG(rating), 0) AS avgRating "
            + "FROM t_review WHERE audit_status = 'APPROVED' AND spu_id IN "
            + "<foreach collection='spuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " GROUP BY spu_id"
            + "</script>")
    List<Map<String, Object>> ratingAggregatesBySpu(@Param("spuIds") Collection<Long> spuIds);

    /**
     * 全量评分聚合（2026-10-18）：对账用，一次取回所有「有评价的 SPU」的真实聚合。
     *
     * <p>为什么不能只查候选：商品域的候选是「它认为自己有评分」的集合，
     * 若某次回写失败，评价库里已有评价但商品域是 0 —— 只在候选里找永远补不上。
     * 两个方向都扫（候选 ∪ 有评价），才能把漂移收敛干净。
     */
    @Select("SELECT spu_id AS spuId, COUNT(*) AS cnt, COALESCE(AVG(rating), 0) AS avgRating "
            + "FROM t_review WHERE audit_status = 'APPROVED' GROUP BY spu_id")
    List<Map<String, Object>> ratingAggregatesAll();

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

    /**
     * 某卖家收到的评价聚合：条数 + 平均分（信用分的「评价质量」因子）。
     *
     * <p>走 SQL 聚合而不是把评价捞回来在内存里算 —— 与 {@link #aggregateBySpu} 同一理由：
     * 信用分要展示在商品页，是高频读路径，而评价条数会随销量单调增长。
     *
     * @return 形如 {@code {total: 12, avgRating: 4.5833}}；无数据时 total=0、avgRating=0
     */
    @Select("SELECT COUNT(*) AS total, COALESCE(AVG(rating), 0) AS avgRating "
            + "FROM t_review WHERE seller_id = #{sellerId} AND audit_status = 'APPROVED'")
    Map<String, Object> aggregateBySeller(@Param("sellerId") Long sellerId);

    /**
     * 本域已知的卖家标识（评价表里出现过的 seller_id，去重）。
     *
     * <p>信用分刷新任务用它枚举「有评价记录、值得重算」的卖家。
     * 只覆盖有评价的卖家是**刻意的**：没有交易/评价痕迹的用户分数恒为基础分，
     * 每次全量重算也只会得到同一个数，没必要也不该由本域凭空枚举全站用户。
     */
    @Select("SELECT DISTINCT seller_id FROM t_review WHERE seller_id IS NOT NULL")
    List<Long> distinctSellerIds();
}
