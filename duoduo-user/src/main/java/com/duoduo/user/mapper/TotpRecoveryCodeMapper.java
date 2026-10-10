package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.TotpRecoveryCodeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * E21 · TOTP 恢复码。
 *
 * <p>刻意只保留 MyBatis-Plus 的通用方法，不自定义 SQL：本表只有三种操作 ——
 * 批量插入（生成的若干枚）、按 user_id 查未用列表（校验）、按主键标记已用。
 * 后两者都能用 {@code LambdaQueryWrapper} / {@code updateById} 表达，
 * 少写一句手写 SQL 就少一处"列名改了但 SQL 没改"的静默失效面。
 */
@Mapper
public interface TotpRecoveryCodeMapper extends BaseMapper<TotpRecoveryCodeEntity> {
}
