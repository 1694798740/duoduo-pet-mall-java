package com.duoduo.gateway.support;

import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * E17 · 「某用户当前的权限版本」的读取口。
 *
 * <h2>为什么是一个接口而不是直接写 Redis 调用</h2>
 * 两个理由，都不是为了"将来可能换实现"这种空泛理由：
 * <ol>
 *   <li><b>可测</b>：网关鉴权是"判错方向就出事"的地方，必须能构造
 *       「版本一致 / 版本不一致 / 压根查不到」三种情形做断言，
 *       而不必在单测里起一个 Redis。</li>
 *   <li><b>契约要写下来</b>：返回值用 {@link Optional} 而不是可空值 / 空 Mono ——
 *       Reactor 里 {@code Mono.empty()} 是一个**会被下游 flatMap 跳过**的信号，
 *       用它表示"未知"会让"放行"这条分支根本不被执行（请求既不通过也不被拒，直接挂住）。
 *       用 Optional 把"未知"变成一个**真实的信号值**，就没有这个歧义。</li>
 * </ol>
 *
 * <h2>调用方必须遵守的语义</h2>
 * <ul>
 *   <li>{@code Optional.empty()} = <b>未知</b>（Redis 不可用、键不存在、值不是数字）
 *       → 调用方<b>放行</b>。理由见 {@code PermVersion} 的取舍段：
 *       失败方向要指向"可用"，否则一次缓存抖动等于全站已登录用户集体掉线。</li>
 *   <li>实现<b>不允许</b>返回空 Mono（{@code Mono.empty()}），也不允许抛异常上来 ——
 *       两者都会让调用方失去"放行"这一分支。异常一律在实现内部转成"未知"。</li>
 * </ul>
 */
public interface PermVersionChecker {

    /**
     * 读取该用户当前的权限版本。
     *
     * @param userId 用户 id
     * @return 版本号；查不到 / 读失败 → {@code Optional.empty()}（调用方据此放行）
     */
    Mono<Optional<Long>> currentVersion(Long userId);
}
