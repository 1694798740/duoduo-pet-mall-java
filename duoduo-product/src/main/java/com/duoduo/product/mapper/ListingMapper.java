package com.duoduo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.product.entity.ListingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ListingMapper extends BaseMapper<ListingEntity> {

    /**
     * 浏览量原子 +1（第五十三轮热修）。
     *
     * <p>用 SQL 自增而不是「读出来 +1 再写回」：闲置详情是高频并发读，
     * 读-改-写会丢更新（两人同时查看结果只 +1）。返回影响行数（按主键更新，恒为 1 或 0）。
     */
    @Update("UPDATE t_listing SET view_count = view_count + 1 WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id);
}
