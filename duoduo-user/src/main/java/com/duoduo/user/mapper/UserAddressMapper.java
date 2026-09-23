package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.UserAddressEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址仓储（A8）。
 *
 * <p>与 {@link PetProfileMapper} 同一取舍：只暴露 MyBatis-Plus 的 {@code BaseMapper}，
 * 不写自定义 SQL —— 地址的全部查询都是「按 user_id 或 id 等值匹配」，
 * 用 lambda 条件构造器表达即可，SQL 拼写错误这类问题因此不存在。
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddressEntity> {
}
