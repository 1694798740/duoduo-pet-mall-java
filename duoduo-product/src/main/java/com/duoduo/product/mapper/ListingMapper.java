package com.duoduo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.product.entity.ListingEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

    /**
     * 「想要」去重登记（第五十四轮）：主键 (listing_id, buyer_id) 唯一，重复上报被 IGNORE。
     *
     * <p><b>去重为什么必须落在数据库</b>：应用层「先查有没有、没有再加一」在并发下会双写
     * （两个请求同时查到"没有"），而数据库唯一键是把判断交给存储引擎的原子操作。
     *
     * @return 影响行数 —— <b>1 = 这位买家第一次咨询（应当计数）；0 = 已在册（不得重复计数）</b>。
     *         调用方必须依据返回值决定是否 {@link #incrementWantCount}，不能无条件自增。
     */
    @Insert("INSERT IGNORE INTO t_listing_want (listing_id, buyer_id, created_at) "
            + "VALUES (#{listingId}, #{buyerId}, #{createdAt})")
    int insertWantIgnore(@Param("listingId") Long listingId,
                         @Param("buyerId") Long buyerId,
                         @Param("createdAt") LocalDateTime createdAt);

    /** 想要人数原子 +1；仅在 {@link #insertWantIgnore} 真的插入成功（返回 1）后调用 */
    @Update("UPDATE t_listing SET want_count = want_count + 1 WHERE id = #{id}")
    int incrementWantCount(@Param("id") Long id);
}
