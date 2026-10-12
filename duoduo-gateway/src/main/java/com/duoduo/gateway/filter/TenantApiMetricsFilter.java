package com.duoduo.gateway.filter;

import com.duoduo.common.metrics.TenantApiMetrics;
import com.duoduo.common.tenant.TenantTables;
import com.duoduo.gateway.config.GatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * E24 · 租户接口调用计数（租户健康度面板「接口错误率」的**唯一数据来源**）。
 *
 * <h2>为什么必须是网关</h2>
 * 「某个店铺调平台接口的失败率」需要同时知道两件事：<b>这次调用属于哪个租户</b> 与
 * <b>这次调用的结果</b>。全站只有网关同时持有：
 * <ul>
 *   <li>租户标识 —— E20 之后 {@code X-Tenant-Id} 由网关按令牌里的 {@code tid} 写入
 *       （客户端伪造成的那份已经被剥离），下游拿到的就是这个头；</li>
 *   <li>响应状态 —— 网关是响应的最后一道，也是**唯一**能看到"服务端 500 还是业务 404"的层。</li>
 * </ul>
 * 业务服务侧做不到：交易域看不到"这个请求是谁发起的店铺"（它只知道被操作的数据归属），
 * 而且大量 4xx/5xx 在下游根本走不到业务代码（鉴权、路由、超时）。
 *
 * <h2>口径（写进 {@link TenantApiMetrics} 的键里）</h2>
 * 键＝{@code duoduo:api:stat:{UTC 日}}，字段＝{@code {tenantId}:{total|server_error|client_error}}。
 * 一天一个键，租户做字段 —— 读侧读一整天只要一次 {@code HGETALL}。
 *
 * <h2>★ 只统计「带租户身份」的请求 —— 这是刻意的，也是必须写清楚的口径</h2>
 * 买家 / 运营 / 匿名流量<b>一次都不计</b>。原因有三条，缺一条这个指标就不可解释：
 * <ol>
 *   <li><b>面板的形状要求</b>：健康度面板是「一行一个租户」，没有租户的请求无处可放；</li>
 *   <li><b>它会被误读成"平台接口错误率"</b>：那一维的正确名字是
 *       「<b>该店铺账号调用平台接口</b>的错误率」——反映的是商家端集成是否顺畅，
 *       而不是平台整体可用性（后者属于网关自身的 metrics）；</li>
 *   <li><b>成本</b>：给全站每一次请求（含匿名商品浏览）都写一次 Redis，
 *       换来的只是把分母做大、把比例冲淡。</li>
 * </ol>
 *
 * <h2>★ 为什么写在 {@code doFinally} 里、并且是"旁路"</h2>
 * <ul>
 *   <li><b>必须等响应落地</b>：状态码在链完成时才确定。放在链之前只能记"发了多少请求"，
 *       记不了"错了几次"，而那正是本指标的全部价值。</li>
 *   <li><b>必须旁路</b>：计数是观测，不是业务。计数失败绝不能影响响应 ——
 *       用 {@code subscribe} 让它自己跑，失败只记 {@code warn}。
 *       这里与 {@code RedisPermVersionChecker} 的降级方向**刻意相反**：
 *       那一个是鉴权路径（失败放行），这一个不参与判定（失败无所谓）。</li>
 *   <li><b>不猜状态码</b>：{@code doFinally} 在客户端取消 / 连接断开时也会触发，
 *       此时 {@code getStatusCode()} 为空。宁可少记一条，也不要把"没有响应"
 *       记成"成功"（那会稀释错误率）或"失败"（那会凭空造出错误）。</li>
 *   <li><b>不吞异常</b>：{@code doFinally} 里的代码抛异常会把一次成功的请求变成失败。
 *       因此整个记录过程包在 try/catch 里，最坏情况是少记一条。</li>
 * </ul>
 *
 * <h2>顺序</h2>
 * {@code -90}：必须晚于 {@link JwtAuthGlobalFilter}（{@code -100}）——
 * 那个过滤器负责剥离客户端伪造的 {@code X-Tenant-Id} 再写入可信值；
 * 早于它执行会读到**调用方自己编的租户 id**，等于把统计口径交给外部（这是一个越权面，
 * 不只是数据不准：伪造 tid 能让别人的错误率飙高）。同一条纪律见 E20 的
 * 「网关先剥离再写入」。
 */
@Component
public class TenantApiMetricsFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantApiMetricsFilter.class);

    /** 与 {@code JwtAuthGlobalFilter} 引用同一个常量：跨进程边界的键名只允许一个定义 */
    private static final String HEADER_TENANT_ID = TenantTables.HEADER;

    /**
     * 写入的硬上限。
     *
     * <p>计数是旁路，绝不能因为 Redis 假死而堆积：超时之后直接放弃这次记录
     * （{@code subscribe} 的 onError 只记 warn）。取 200ms ——
     * 与 {@code RedisPermVersionChecker.TIMEOUT}（150ms）同量级，
     * 区别是本处**不在请求链上等待**，所以可以稍宽一点。
     */
    private static final Duration TIMEOUT = Duration.ofMillis(200);

    private final ReactiveStringRedisTemplate redis;
    private final GatewaySecurityProperties properties;

    public TenantApiMetricsFilter(ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
                                  GatewaySecurityProperties properties) {
        // 没有配置 Redis 时 bean 为 null（本地零依赖开发）→ 本过滤器整体不生效，
        // 行为与"开关关闭"一致。这里不抛异常：网关的首要职责是转发，不是统计。
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Long tenantId = resolveTenantId(exchange);
        if (tenantId == null) {
            // 无租户身份的请求不进统计（口径见类注释）。**不是**提前 return 一个 Mono——
            // 仍然要把 chain 走下去，这里只是不加旁路。
            return chain.filter(exchange);
        }
        return chain.filter(exchange)
                .doFinally(signal -> {
                    try {
                        Integer status = statusOf(exchange);
                        if (status == null) {
                            // 没有响应状态：客户端取消 / 连接断开。不猜（见类注释）
                            return;
                        }
                        record(tenantId, status);
                    } catch (RuntimeException ex) {
                        // doFinally 里的异常会污染整条响应链，必须自己兜住
                        log.warn("租户接口计数失败（不影响响应）tenantId={}: {}", tenantId, ex.toString());
                    }
                });
    }

    /**
     * 取本次请求的可信租户标识。
     *
     * <p>读的是**请求头**而不是令牌：走到本过滤器时，{@link JwtAuthGlobalFilter}
     * 已经把客户端伪造的同名字段剥掉、写入了由令牌 {@code tid} 转写的可信值。
     * 因此这里的头可以当事实用 —— 前提是 order 必须晚于它（见类注释）。
     *
     * @return 租户 id；无声明（买家 / 运营 / 匿名 / 老令牌）或格式非法时返回 null
     */
    private Long resolveTenantId(ServerWebExchange exchange) {
        if (redis == null || !properties.isApiMetricsEnabled()) {
            return null;
        }
        String raw = exchange.getRequest().getHeaders().getFirst(HEADER_TENANT_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(raw.trim());
            // 负数不可能是合法租户（0 = 自营，是有效值）
            return id < 0 ? null : id;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 响应状态码；尚未确定（取消 / 断开）时返回 null */
    private static Integer statusOf(ServerWebExchange exchange) {
        HttpStatusCode code = exchange.getResponse().getStatusCode();
        return code == null ? null : code.value();
    }

    /**
     * 记一次调用。**只做两件事**：总数 +1；若是 4xx/5xx 再给对应档 +1。
     *
     * <p>TTL 只在当天第一次写入时设置（{@code total} 自增后返回 1），
     * 而不是每次请求都 {@code EXPIRE} 一遍 —— 后者是"每个请求 2 次往返"，
     * 而且每天重置一次与每天刷新一次对 TTL 语义没有区别。
     */
    private void record(long tenantId, int status) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String key = TenantApiMetrics.dayKey(today);
        if (key == null) {
            return;
        }
        String facet = status >= 500 ? TenantApiMetrics.FIELD_SERVER_ERROR
                : status >= 400 ? TenantApiMetrics.FIELD_CLIENT_ERROR
                : null;

        Mono<Long> op = redis.opsForHash().increment(key, TenantApiMetrics.field(tenantId, TenantApiMetrics.FIELD_TOTAL), 1);
        if (facet != null) {
            final String facetField = TenantApiMetrics.field(tenantId, facet);
            op = op.flatMap(total -> redis.opsForHash().increment(key, facetField, 1).thenReturn(total));
        }
        op.flatMap(total -> {
                    // 当天首次写入（total 恰好从 0 变 1）→ 给整个哈希挂一个 TTL
                    if (total != null && total == 1L) {
                        return redis.expire(key, Duration.ofDays(TenantApiMetrics.KEEP_DAYS));
                    }
                    return Mono.just(Boolean.TRUE);
                })
                .timeout(TIMEOUT)
                .subscribe(ok -> {
                }, ex -> log.warn("租户接口计数写入失败（不影响响应）tenantId={} status={}: {}",
                        tenantId, status, ex.toString()));
    }

    @Override
    public int getOrder() {
        // -90：晚于 JwtAuthGlobalFilter(-100)，早于路由转发（正向 order）
        return -90;
    }
}
