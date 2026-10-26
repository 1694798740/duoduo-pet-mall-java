package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.support.entity.SupportTicketLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 客服工单处理记录（C-04）。
 *
 * <p>放在 {@code com.duoduo.social.mapper} 而不是 support 子包：
 * 本服务的 {@code @MapperScan} 只扫这一层（与 SupportSessionMapper 同包），
 * 放进子包会导致启动时「找不到 Bean」—— 而报错只提到构造器参数，很容易误判成别的问题。
 */
@Mapper
public interface SupportTicketLogMapper extends BaseMapper<SupportTicketLogEntity> {

    /** 某会话的处理记录，按时间正序（1 条 = 最早） */
    @Select("SELECT * FROM t_support_ticket_log WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<SupportTicketLogEntity> listBySession(@Param("sessionId") Long sessionId);
}
