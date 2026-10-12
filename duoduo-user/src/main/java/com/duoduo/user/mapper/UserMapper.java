package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /**
     * 取全部 ADMIN 的 id，并**对命中的行加排他锁**（`SELECT … FOR UPDATE`）。
     *
     * <h2>为什么不能用 `selectCount`</h2>
     * 「平台至少保留一名超级管理员」这条规则的正确性**完全取决于这次统计读到的值是不是最新的**。
     * 而 MyBatis-Plus 的 `selectCount` 发出的是一条**普通 SELECT** —— 在 InnoDB 的
     * REPEATABLE READ 下它是**一致性快照读**：读的是本事务**开始时刻**的版本，
     * 既不加锁、也看不见别人刚提交的改动。
     *
     * <p>于是「先数后改」在并发下就退化成了「先数后改、数的还不是最新的」：
     * 甲、乙两名管理员同时降对方，两个事务各自读到 count=2 → 都放行 →
     * **平台一个管理员都不剩**。该缺陷已用 e2e 实测复现（两条都 200、库里 `role='ADMIN'` 变 0）。
     *
     * <h2>加锁读为什么能修好它</h2>
     * 锁定读**读最新已提交版本**并阻塞后来的事务：后到者会一直等到先到者提交，
     * 再重新扫描 → 此时只剩 1 名 ADMIN → 命中规则返回 422。
     * 这也正是 {@code AdminUserService} 注释里原本就写着的设计意图
     * （"后到者会看到 count 已减一"）—— 只是之前的实现拿不到这个效果。
     *
     * <p>锁的粒度：`role` 列上没有索引，因此这是一次全表扫描 + 对扫描到的行加锁，
     * 效果等同"串行化所有改角色操作"。可以接受 —— 改角色是**极低频**的管理动作，
     * 用一点并发度换"平台永远不会没有管理员"是划算的。
     *
     * <p>只返回 id 而不是 `COUNT(*)`：`SELECT COUNT(*) … FOR UPDATE` 的加锁行为依赖执行计划
     * （走覆盖索引时可能不加行锁），只取 id 的语义没有歧义 —— **行数就是 size()**，
     * 也让"到底锁了哪些行"是可断言的。
     *
     * @return 当前 ADMIN 的 id 列表（调用方在事务内统计与判级）
     */
    @Select("SELECT id FROM t_user WHERE role = 'ADMIN' FOR UPDATE")
    List<Long> selectAdminIdsForUpdate();
}
