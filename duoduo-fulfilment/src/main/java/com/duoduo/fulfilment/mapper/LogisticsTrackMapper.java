package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.LogisticsTrackEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 物流轨迹数据访问。
 *
 * <p>没有自定义的写方法：写入一律走 {@code insert}，靠唯一索引
 * {@code uk_track_order_node_time} 把重复推送撞成 {@code DuplicateKeyException}，
 * 由服务层转成「跳过」。
 *
 * <p>为什么不用 {@code INSERT IGNORE} / {@code ON DUPLICATE KEY UPDATE} 这类 MySQL 方言：
 * 本域 schema 声明为 H2 / MySQL 双兼容，方言写法会让本地 H2 跑不起来；
 * 而且 {@code INSERT IGNORE} 会连「字段超长被截断」这类错误一起吞掉，
 * 那是最难查的一类静默数据损坏。撞键异常精确表达了我们唯一想忽略的情况。
 */
@Mapper
public interface LogisticsTrackMapper extends BaseMapper<LogisticsTrackEntity> {

    /**
     * 按订单号取轨迹，**按发生时间正序**（早的在前）。
     *
     * <p>同一时刻可能有多个节点（例如分拨中心一次录入「到达 + 发往」两条），
     * 再用 id 兜底排序：雪花 id 单调递增，恰好等于入库先后，
     * 因此同刻节点的相对顺序与推送顺序一致，不会每次查询都换一个顺序。
     */
    @Select("SELECT * FROM t_logistics_track WHERE order_no = #{orderNo} "
            + "ORDER BY occurred_at ASC, id ASC")
    List<LogisticsTrackEntity> listByOrderNo(@Param("orderNo") String orderNo);

    /** 节点条数（用于「有没有轨迹」这类判断，不必把整条轨迹读出来） */
    @Select("SELECT COUNT(*) FROM t_logistics_track WHERE order_no = #{orderNo}")
    long countByOrderNo(@Param("orderNo") String orderNo);
}
