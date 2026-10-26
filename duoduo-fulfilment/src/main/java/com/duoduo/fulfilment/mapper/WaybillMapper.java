package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.WaybillEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 电子面单数据访问（B-05）。
 *
 * <p>写入走 {@code insert}，由 {@code uk_waybill_order} 把并发重复申请撞成
 * {@code DuplicateKeyException}，服务层再回读同一张面单 ——
 * 这样「两个请求同时申请」不会产生两个运单号，也不会把第二个请求判成失败。
 */
@Mapper
public interface WaybillMapper extends BaseMapper<WaybillEntity> {

    @Select("SELECT * FROM t_waybill WHERE order_no = #{orderNo}")
    WaybillEntity selectByOrderNo(@Param("orderNo") String orderNo);

    @Update("UPDATE t_waybill SET status = #{status}, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND status <> 'SIGNED'")
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("updatedAt") LocalDateTime updatedAt);

    /** 模拟任务扫描：未签收的模拟面单，按创建时间正序（先发的先推进） */
    @Select("SELECT * FROM t_waybill WHERE mode = 'SIMULATED' AND status <> 'SIGNED' "
            + "ORDER BY created_at ASC LIMIT #{limit}")
    List<WaybillEntity> listSimulatedOpen(@Param("limit") int limit);
}
