package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.ShareReportEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分享举报 Mapper（第五十六轮）。
 *
 * <p>查询全部走条件构造器（状态过滤 + 时间倒序 + 分页），没有需要手写的 SQL。
 * 「同一人同一对象只能举报一次」由唯一键 {@code uk_share_report_once} 兜住。
 */
@Mapper
public interface ShareReportMapper extends BaseMapper<ShareReportEntity> {
}
