package com.duoduo.common.metrics;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 租户接口调用计数器的**唯一键与字段口径**（E24）。
 *
 * <h2>它存在要解决什么问题</h2>
 * E24 的租户健康度面板第四维是「接口错误率」，而全站**没有任何一处**记录过"哪个租户的调用失败了"：
 * 网关只做鉴权与转发，日志不落库、不按租户聚合；业务服务看不到「这个请求是谁发起的店铺」。
 * 唯一同时掌握 <b>①请求状态码 ②租户标识</b> 的地方就是网关（E20 之后 {@code X-Tenant-Id}
 * 由网关按令牌里的 {@code tid} 写入）。
 *
 * <h2>为什么口径必须放在 duoduo-common，而不是各写一份</h2>
 * 写侧是网关（WebFlux / {@code ReactiveStringRedisTemplate}），读侧是交易域
 * （Servlet / {@code StringRedisTemplate}）。两边各拼一次键，任何一次不一致
 * （{@code duoduo:api:stat:} vs {@code duoduo:api:stats:}）**都不会报错** ——
 * 症状只是"面板上接口错误率永远是 0"，与"这个租户确实没出错"长得一模一样。
 * 这与 {@link com.duoduo.common.security.perm.PermVersion#key(Long)}、
 * {@link com.duoduo.common.tenant.TenantTables#HEADER} 是同一条纪律：
 * **凡跨进程边界的键名/字段名，全项目只允许有一个定义。**
 *
 * <h2>键结构</h2>
 * <pre>
 *   key   duoduo:api:stat:{yyyy-MM-dd}          ← 哈希，一天一个键（UTC 自然日）
 *   field {tenantId}:total                       ← 该租户当天的全部请求数
 *         {tenantId}:server_error                ← 其中 5xx
 *         {tenantId}:client_error                ← 其中 4xx
 * </pre>
 *
 * <h2>★ 为什么是「一天一个键 + 租户做字段」，而不是「一个租户一个键」</h2>
 * 面板要读的是「某租户最近 N 天」＝要跨 N 个租户键翻 N×M 次，或者对
 * {@code duoduo:tenant:*} 做 {@code KEYS}/{@code SCAN} —— 前者是 N×M 次往返，
 * 后者在生产上是一条禁止使用的命令（阻塞事件循环）。
 * 反过来：<b>一天一个键</b>时，读一整天的全租户快照就是一次 {@code HGETALL}，
 * 读 30 天就是 30 次 —— 与租户数量无关，且天然按天分片、过期即整体回收。
 *
 * <h2>★ 与 {@link com.duoduo.common.tenant.TenantKeys} 的关系（刻意不走那个前缀）</h2>
 * {@code TenantKeys} 管的是**租户级缓存**：键里带租户、值属于那一个租户，
 * 拼错租户的后果是"A 店看到 B 店的数据"。这里是**跨租户的当日聚合快照**：
 * 它的值本来就要同时装下当天所有租户，租户是**字段**不是键的一部分，
 * 因此用 {@code duoduo:tenant:{tid}:…} 反而会拼出一个错误的形状
 * （要么一租户一键、丢掉整天的快照，要么拼出 {@code duoduo:tenant:null:…}
 * 这种所有租户共用的假键）。字段级仍带显式租户 id，{@link #field} 拒绝 null。
 *
 * <h2>刻意不做的事</h2>
 * <ul>
 *   <li><b>不记 1xx/2xx/3xx 的细分</b>：面板要回答的是"这个店铺调接口顺不顺"，
 *       细化到每一档只会让键长大、而信息量并不增加；</li>
 *   <li><b>不记路径</b>：那会退化成一份访问日志（键数量随 path 爆炸），
 *       而排障要的明细本就该去服务日志里按 requestId 找；</li>
 *   <li><b>不写时间戳/最后出现时刻</b>：键本身就带日期，再加一份时间戳等于同一事实存两处。</li>
 * </ul>
 */
public final class TenantApiMetrics {

    /** 全部当日计数键的公共前缀。 */
    public static final String PREFIX = "duoduo:api:stat:";

    /**
     * 键的存活天数。
     *
     * <h2>★ 它必须 ≥ 面板允许的最大窗口，否则会出现"静默的 0"</h2>
     * 键过期之后，读侧只会读到"这一天没有数据" —— 与"这一天该租户一次都没调用"
     * 得到**完全相同的空结果**，于是接口错误率会安静地变成 0，而面板看起来一切正常。
     * 这正是本项目反复栽的那类坑（"空结果"与"链路没接通"长得一样）。
     * 因此这里给出一个**硬边界**：面板窗口上限 30 天（见 {@code TenantHealthService.MAX_DAYS}），
     * 键存活 40 天 —— 任何合法窗口内的每一天都一定还在。服务层另有一道
     * "窗口不得超过 {@link #KEEP_DAYS}" 的校验作为第二道保险。
     *
     * <p>取 40 天而不是"永久"：这是**运维观测量**，不是业务事实 ——
     * 它只有和"最近一段时间的健康度"比较才有意义，留一年只是让 Redis 慢慢变胖。
     */
    public static final int KEEP_DAYS = 40;

    /** 字段名后缀：全部请求数 */
    public static final String FIELD_TOTAL = "total";
    /** 字段名后缀：服务端错误（5xx） */
    public static final String FIELD_SERVER_ERROR = "server_error";
    /** 字段名后缀：客户端错误（4xx） */
    public static final String FIELD_CLIENT_ERROR = "client_error";

    private static final String SEP = ":";

    private TenantApiMetrics() {
    }

    /**
     * 某一天的计数键：{@code duoduo:api:stat:{yyyy-MM-dd}}。
     *
     * @param day UTC 自然日；为 null 时返回 null（调用方通常据此跳过本次记录），
     *            而不是抛异常 —— 网关是在响应完成回调里记录，
     *            那里抛异常会把一次成功的请求变成一个 500
     */
    public static String dayKey(LocalDate day) {
        return day == null ? null : PREFIX + day;
    }

    /** 该键是否是本口径的当日计数键（供巡检 / 断言用）。 */
    public static boolean isDayKey(String key) {
        return key != null && key.startsWith(PREFIX) && parseDay(key) != null;
    }

    /**
     * 从键里取回日期；形状不对返回 null。
     *
     * <p>读侧**按窗口逐天算出键**（{@code dayKey(from.plusDays(i))}），不靠通配扫描 ——
     * 因此本方法与 {@link #isDayKey} 的用途不是"发现有哪些天"，而是
     * <b>巡检与断言</b>：脚本用 {@code KEYS duoduo:api:stat:*} 拿到实际存在的键之后，
     * 需要能判断"这个键形状合不合法、是哪一天"，以及"有没有超出 TTL 的僵尸键"。
     * 一条脏键（有人手工建过 {@code duoduo:api:stat:test}）不该让整个面板 500，
     * 所以这里显式判空而不是让 {@code LocalDate.parse} 抛出去。
     */
    public static LocalDate parseDay(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String tail = key.substring(PREFIX.length());
        try {
            return LocalDate.parse(tail);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * 哈希字段名：{@code {tenantId}:{facet}}。
     *
     * <p>{@code facet} 只接受本类声明的三个常量（大小写归一后比对），
     * 其余一律抛异常。理由与 {@code TenantKeys.key} 拒绝 null 相同：
     * 一个拼错的 facet（{@code servers_error}）在哈希里是一条**永远读不到的新字段**，
     * 写侧不报错、读侧看不到，表现是"错误率恒为 0"。宁可在这里炸。
     *
     * @param tenantId 租户标识（店铺主体 userId；{@code 0} = 平台自营，**是有效值**）
     */
    public static String field(Long tenantId, String facet) {
        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "接口指标字段必须带租户标识：拼成 " + PREFIX + "…:" + facet
                            + " 会把所有租户的调用混成一条");
        }
        if (tenantId < 0) {
            throw new IllegalArgumentException("租户标识不能为负数：" + tenantId);
        }
        String normalized = normalizeFacet(facet);
        if (normalized == null) {
            throw new IllegalArgumentException("未知的接口指标维度：" + facet
                    + "（只能是 " + FIELD_TOTAL + " / " + FIELD_SERVER_ERROR + " / " + FIELD_CLIENT_ERROR + "）");
        }
        return tenantId + SEP + normalized;
    }

    /**
     * 维度归一：大小写与前后空白都容错。
     *
     * <p>为什么容错而不再是精确比对：写入侧是配置/枚举拼出来的，读取侧要解析
     * 存量字段 —— 一份写入期就固定的大小写不该让**读**失败。未知值返回 null
     * （由 {@link #field} 决定是否抛），不在这里抛，因为 {@link #tenantOf(String)}
     * 复用同一个归一函数时需要"不是 facet"这个安静的答案。
     */
    public static String normalizeFacet(String facet) {
        if (facet == null) {
            return null;
        }
        String v = facet.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case FIELD_TOTAL, FIELD_SERVER_ERROR, FIELD_CLIENT_ERROR -> v;
            default -> null;
        };
    }

    /**
     * 从哈希字段名里解析出租户 id；形状不对返回 -1。
     *
     * <p>返回 {@code -1} 而不是 0：{@code 0} 是**平台自营这个真实租户**的标识，
     * 拿它当"解析失败"会让一次脏字段被算进自营的账上。
     */
    public static long tenantOf(String field) {
        if (field == null) {
            return -1L;
        }
        int i = field.indexOf(SEP);
        if (i <= 0) {
            return -1L;
        }
        try {
            long id = Long.parseLong(field.substring(0, i));
            return id < 0 ? -1L : id;
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /** 从哈希字段名里解析出维度名；形状不对或维度未知返回 null。 */
    public static String facetOf(String field) {
        if (field == null) {
            return null;
        }
        int i = field.indexOf(SEP);
        if (i <= 0) {
            return null;
        }
        return normalizeFacet(field.substring(i + 1));
    }
}
