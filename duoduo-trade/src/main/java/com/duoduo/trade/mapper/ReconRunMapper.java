package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.ReconRunEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账轮次数据访问（S3）。
 *
 * <p><b>「同一天重跑覆盖」的实现放在这里、并且是先删后插</b>：
 * 应用层"先查有没有再决定插还是改"在并发下会让两方都看到"没有"从而双插 ——
 * 唯一键是唯一可靠的仲裁者。先删后插让语义变成"这一天的结论就是这个"，
 * 而不是"这一天可能有好几轮结论，自己挑一个看"。
 *
 * <p>删除同时清掉该轮次的差异明细，否则重跑后明细会越积越多，
 * 而"差异变多了"会被读成"问题变严重了"。
 */
@Mapper
public interface ReconRunMapper extends BaseMapper<ReconRunEntity> {

    /** 删除某业务日某范围的历史轮次（连同其差异明细，靠外键顺序：先明细后轮次） */
    @Delete("DELETE FROM t_recon_run WHERE biz_date = #{bizDate} AND scope = #{scope}")
    int deleteByDayAndScope(@Param("bizDate") LocalDate bizDate, @Param("scope") String scope);

    /** 最近若干轮（后台列表用） */
    @Select("SELECT * FROM t_recon_run ORDER BY started_at DESC, id DESC LIMIT #{limit}")
    List<ReconRunEntity> selectRecent(@Param("limit") int limit);

    @Select("SELECT * FROM t_recon_run WHERE biz_date = #{bizDate} AND scope = #{scope}")
    ReconRunEntity selectByDayAndScope(@Param("bizDate") LocalDate bizDate, @Param("scope") String scope);

    @Select("SELECT * FROM t_recon_run WHERE id = #{id}")
    ReconRunEntity selectRunById(@Param("id") Long id);

    /**
     * 把轮次上的计数**从差异明细表里数出来**写回去。
     *
     * <p>为什么不直接用判据算出来的条数：明细的唯一键
     * {@code (run_id, rule, ref_type, ref_id)} 有可能吃掉一条重复项。
     * 若报表上的数字来自判据，就会出现「显示 5 条、点进去只有 4 条」——
     * 而对账报表的每一个数字都必须能被点开验证，否则它不值得信。
     */
    @Update("UPDATE t_recon_run SET "
            + "diff_count = (SELECT COUNT(*) FROM t_recon_diff WHERE run_id = #{id}), "
            + "high_count = (SELECT COUNT(*) FROM t_recon_diff WHERE run_id = #{id} AND severity = 'HIGH') "
            + "WHERE id = #{id}")
    int syncCountsFromDiffs(@Param("id") Long id);

    /**
     * 按明细实际条数定状态（DIRTY / CLEAN）。
     *
     * <p>刻意带 {@code AND status <> 'FAILED'}：{@link com.duoduo.trade.support.ReconStatus#FAILED}
     * 表示"没跑成"，它与"跑成了且干净"必须分得开 —— 把它改判成 CLEAN
     * 就等于把"未知"粉饰成"全绿"，而那张全绿报表是运维唯一会看的东西。
     */
    @Update("UPDATE t_recon_run SET status = CASE WHEN diff_count > 0 THEN 'DIRTY' ELSE 'CLEAN' END "
            + "WHERE id = #{id} AND status <> 'FAILED'")
    int syncStatusFromCounts(@Param("id") Long id);
}
