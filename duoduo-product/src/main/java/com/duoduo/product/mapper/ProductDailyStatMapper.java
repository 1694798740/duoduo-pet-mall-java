package com.duoduo.product.mapper;

import com.duoduo.product.dto.ProductStatSum;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品每日曝光 / 点击统计（A-17）。
 *
 * <p><b>为什么用 UPSERT 而不是「先查再写」</b>：计数是并发写，
 * 先查后写会让两个同时到达的浏览都读到旧值、各自 +1 后写回 —— 一方被覆盖。
 * 把增量交给 {@code ON DUPLICATE KEY UPDATE exposure = exposure + 1}，
 * 由数据库行锁保证不丢计数；主键 {@code (spu_id, stat_date)} 就是幂等键，
 * 同一天重复写入只会累加，不会产生第二行。
 *
 * <p><b>曝光为什么是一条多行 INSERT</b>：一次列表请求会带回几十个商品，
 * 逐条 UPSERT 就是几十次往返；拼成一条 INSERT 后只写一次。
 */
@Mapper
public interface ProductDailyStatMapper {

    /**
     * 批量记曝光：每个 spuId 当日 +1。
     *
     * <p>调用方必须先去重且控制列表非空 —— {@code foreach} 生成空 VALUES 会直接语法错误。
     */
    @Insert("<script>"
            + "INSERT INTO t_product_daily_stat (spu_id, stat_date, exposure, clicks, updated_at) VALUES "
            + "<foreach collection='spuIds' item='spuId' separator=','>"
            + "(#{spuId}, #{statDate}, 1, 0, #{updatedAt})"
            + "</foreach> "
            + "ON DUPLICATE KEY UPDATE exposure = exposure + 1, updated_at = VALUES(updated_at)"
            + "</script>")
    int addExposure(@Param("spuIds") List<Long> spuIds,
                    @Param("statDate") LocalDate statDate,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /** 单商品记一次点击；当日行不存在则创建。 */
    @Insert("INSERT INTO t_product_daily_stat (spu_id, stat_date, exposure, clicks, updated_at) "
            + "VALUES (#{spuId}, #{statDate}, 0, 1, #{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE clicks = clicks + 1, updated_at = VALUES(updated_at)")
    int addClick(@Param("spuId") Long spuId,
                 @Param("statDate") LocalDate statDate,
                 @Param("updatedAt") LocalDateTime updatedAt);

    /** 按商品汇总窗口内的曝光 / 点击（诊断页用）。 */
    @Select("<script>"
            + "SELECT spu_id AS spuId, "
            + "COALESCE(SUM(exposure), 0) AS exposure, "
            + "COALESCE(SUM(clicks), 0) AS clicks "
            + "FROM t_product_daily_stat "
            + "WHERE stat_date &gt;= #{from} AND stat_date &lt;= #{to} AND spu_id IN "
            + "<foreach collection='spuIds' item='spuId' open='(' separator=',' close=')'>#{spuId}</foreach> "
            + "GROUP BY spu_id"
            + "</script>")
    List<ProductStatSum> sumRange(@Param("spuIds") List<Long> spuIds,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);
}
