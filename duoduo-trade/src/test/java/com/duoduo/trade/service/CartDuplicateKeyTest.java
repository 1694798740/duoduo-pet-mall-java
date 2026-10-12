package com.duoduo.trade.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLIntegrityConstraintViolationException;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * 并发加购的「唯一键冲突」识别（R60 压测暴露的真实缺陷）。
 *
 * <p>背景：加购走「先 INSERT 再兜底」—— 并发加购同一 SKU 时第二条必然撞上
 * {@code uk_cart_user_sku} 唯一索引，此时应该退化为**累加数量**，而不是报错。
 *
 * <p>缺陷原貌：原实现只 {@code catch DuplicateKeyException}。而 MyBatis-Plus 在无事务
 * 的加购方法里抛出来的往往不是 Spring 翻译过的 {@code DataAccessException}，而是包着
 * {@code SQLIntegrityConstraintViolationException} 的 {@code PersistenceException}。
 * 于是兜底分支根本进不去，并发加购直接 500 —— 压测在并发 10 下稳定复现 9/120，
 * 用户侧就是「连点两下加购，弹一个『服务内部错误』」。
 *
 * <p>修法改为「沿异常链找 Duplicate entry + 本表键名」，同时保留一条硬边界：
 * **别的唯一键 / 别的数据库错误不能被当成并发误吞**，否则最难排查的那类静默就回来了。
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CartDuplicateKeyTest {

    private static final String CONFLICT_MSG =
            "Duplicate entry '363943971128479744-2014' for key 't_cart_item.uk_cart_user_sku'";

    @Test
    void Spring翻译过的DuplicateKeyException要认出来() {
        assertTrue(CartService.isCartUniqueConflict(new DuplicateKeyException(CONFLICT_MSG)));
    }

    @Test
    void MyBatis风格的包装异常也要认出来() {
        // 这才是真实线上抛出来的形态：底层是原始 SQL 异常，外层只包一层运行时异常，
        // Spring 的 DataAccessException 翻译不介入 —— 原实现就栽在这里
        RuntimeException wrapped =
                new RuntimeException(new SQLIntegrityConstraintViolationException(CONFLICT_MSG));
        assertTrue(CartService.isCartUniqueConflict(wrapped));
    }

    @Test
    void 再深一层的异常链也能认出来() {
        RuntimeException deep = new RuntimeException(
                new IllegalStateException(new SQLIntegrityConstraintViolationException(CONFLICT_MSG)));
        assertTrue(CartService.isCartUniqueConflict(deep));
    }

    @Test
    void 别表的唯一键冲突不能当成购物车并发误吞() {
        RuntimeException other = new RuntimeException(
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry '7' for key 't_order.uk_idempotent'"));
        assertFalse(CartService.isCartUniqueConflict(other),
                "别表的冲突必须继续上抛，吞掉它会变成最难的静默失败");
    }

    @Test
    void 非唯一键的数据库错误不能被误判() {
        assertFalse(CartService.isCartUniqueConflict(
                new SQLIntegrityConstraintViolationException("Data too long for column 'title'")));
        assertFalse(CartService.isCartUniqueConflict(new IllegalStateException("boom")));
    }

    @Test
    void 没有信息的异常返回false而不是抛异常() {
        // 兜底分支里不能再抛：那会把「加购失败」变成「加购失败 + 抛出一个不相干的错」
        assertFalse(CartService.isCartUniqueConflict(new RuntimeException()));
        assertFalse(CartService.isCartUniqueConflict(null));
    }
}
