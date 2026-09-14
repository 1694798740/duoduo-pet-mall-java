package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.RefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshTokenEntity> {

    @Update("UPDATE t_refresh_token SET revoked = 1 WHERE id = #{id} AND revoked = 0")
    int revoke(@Param("id") String id);

    @Update("UPDATE t_refresh_token SET revoked = 1 WHERE user_id = #{userId} AND revoked = 0")
    int revokeAllByUser(@Param("userId") Long userId);
}
