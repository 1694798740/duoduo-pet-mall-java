package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.ShareReportEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分享举报 Mapper（第五十六轮）。
 *
 * <p>查询全部走条件构造器（状态过滤 + 时间倒序 + 分页），没有需要手写的 SQL。
 * 「同一人同一对象只能举报一次」由唯一键 {@code uk_share_report_once} 兜住。
 *
 * <p>2026-10-27 起多一条未读计数：运营后台的角标要排除「本人已点开看过」的举报。
 */
@Mapper
public interface ShareReportMapper extends BaseMapper<ShareReportEntity> {

    /**
     * 运营后台「举报处理」的未读待处理数。
     *
     * <p>口径与 {@code listForAdmin(status=PENDING)} 同源，只额外排除本人已查看的条目；
     * 已查看集合见 {@code t_admin_queue_read}。
     */
    @Select("SELECT COUNT(*) FROM t_share_report p "
            + "WHERE p.status = 'PENDING' "
            + "AND NOT EXISTS (SELECT 1 FROM t_admin_queue_read q "
            + "                WHERE q.user_id = #{operatorId} AND q.source = 'REPORT' AND q.biz_id = p.id)")
    long countUnreadPending(@Param("operatorId") Long operatorId);

    /** 当前页举报的已读集合（source=REPORT，2026-10-27 · 逐条橙点） */
    @Select("<script>SELECT biz_id FROM t_admin_queue_read "
            + "WHERE user_id = #{operatorId} AND source = 'REPORT' AND biz_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    java.util.List<Long> listAdminReadIds(@Param("operatorId") Long operatorId,
                                          @Param("ids") java.util.Collection<Long> ids);
}
