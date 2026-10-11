package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.ReconDiffEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 对账差异明细数据访问（S3）。
 *
 * <p>{@link #deleteByRunOfDay} 用子查询按「业务日 + 范围」删明细，
 * 与 {@code ReconRunMapper.deleteByDayAndScope} 配对使用 ——
 * 顺序必须是<b>先删明细、再删轮次</b>（反过来的话子查询已经查不到 run 了，
 * 明细会变成删不掉的孤儿，而且不会有任何报错）。
 */
@Mapper
public interface ReconDiffMapper extends BaseMapper<ReconDiffEntity> {

    /** 删除某业务日某范围下所有轮次的明细（必须早于轮次本身的删除） */
    @Delete("DELETE FROM t_recon_diff WHERE run_id IN "
            + "(SELECT id FROM t_recon_run WHERE biz_date = #{bizDate} AND scope = #{scope})")
    int deleteByRunOfDay(@Param("bizDate") LocalDate bizDate, @Param("scope") String scope);

    /** 某轮的差异明细：HIGH 在前（运维先看要动手的那些） */
    @Select("SELECT * FROM t_recon_diff WHERE run_id = #{runId} "
            + "ORDER BY FIELD(severity, 'HIGH', 'MEDIUM'), rule, ref_id LIMIT #{limit}")
    List<ReconDiffEntity> selectByRun(@Param("runId") Long runId, @Param("limit") int limit);

    /** 某轮的差异条数（用于落库后自证：写进去多少条要与判定出来的条数一致） */
    @Select("SELECT COUNT(*) FROM t_recon_diff WHERE run_id = #{runId}")
    int countByRun(@Param("runId") Long runId);
}
