package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.ExportJobEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 异步导出任务数据访问。
 *
 * <p>所有状态迁移都是 <b>CAS 条件更新</b>：PENDING → RUNNING 只认 PENDING，
 * SUCCESS / FAILED 只认 RUNNING。多实例、重复提交、调度与手工触发撞车时，
 * 只有一个能推进状态 —— 不需要分布式锁。
 */
@Mapper
public interface ExportJobMapper extends BaseMapper<ExportJobEntity> {

    /** 抢占任务：PENDING → RUNNING。返回 0 表示已被别的线程 / 实例抢走 */
    @Update("UPDATE t_export_job SET status = 'RUNNING', started_at = #{at} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markRunning(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 进度：只对 RUNNING 行写（任务已被标记失败后，迟到的进度不覆盖结果） */
    @Update("UPDATE t_export_job SET row_count = #{rows} WHERE id = #{id} AND status = 'RUNNING'")
    int updateProgress(@Param("id") Long id, @Param("rows") int rows);

    /** 成功：RUNNING → SUCCESS，同时落文件名 / 路径 / 大小 / 过期时间 */
    @Update("UPDATE t_export_job SET status = 'SUCCESS', row_count = #{rows}, file_name = #{fileName}, "
            + "file_path = #{filePath}, file_size = #{fileSize}, finished_at = #{finishedAt}, "
            + "expires_at = #{expiresAt} WHERE id = #{id} AND status = 'RUNNING'")
    int markSuccess(@Param("id") Long id,
                    @Param("rows") int rows,
                    @Param("fileName") String fileName,
                    @Param("filePath") String filePath,
                    @Param("fileSize") long fileSize,
                    @Param("finishedAt") LocalDateTime finishedAt,
                    @Param("expiresAt") LocalDateTime expiresAt);

    /** 失败：PENDING / RUNNING → FAILED（SUCCESS 是终态，不会被迟到的失败改写） */
    @Update("UPDATE t_export_job SET status = 'FAILED', error_message = #{message}, "
            + "finished_at = #{at} WHERE id = #{id} AND status IN ('PENDING', 'RUNNING')")
    int markFailed(@Param("id") Long id, @Param("message") String message, @Param("at") LocalDateTime at);

    /** 过期：SUCCESS → EXPIRED（文件已被调度删掉） */
    @Update("UPDATE t_export_job SET status = 'EXPIRED' WHERE id = #{id} AND status = 'SUCCESS'")
    int markExpired(@Param("id") Long id);

    /** 下载计数（不参与状态机） */
    @Update("UPDATE t_export_job SET download_count = download_count + 1, last_downloaded_at = #{at} "
            + "WHERE id = #{id}")
    int markDownloaded(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 把长时间停在 PENDING / RUNNING 的任务判定为中断（服务重启后没人再推进它） */
    @Update("UPDATE t_export_job SET status = 'FAILED', "
            + "error_message = '任务中断（服务重启或执行超时），请重新导出', finished_at = #{at} "
            + "WHERE status IN ('PENDING', 'RUNNING') AND created_at < #{before}")
    int failStale(@Param("before") LocalDateTime before, @Param("at") LocalDateTime at);
}
