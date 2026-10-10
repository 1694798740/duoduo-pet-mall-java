package com.duoduo.common.tenant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 租户列的**单一权威**（E20）：哪张表有租户维度、对应哪一列。
 *
 * <h2>为什么必须集中在一处</h2>
 * 数据层拦截器按"表名 → 列名"决定往 SQL 里追加什么条件。若每个服务各写一份映射，
 * 出现的是典型的**写错不报错**：
 * <ul>
 *   <li>表名拼错（{@code t_spu} 写成 {@code spu}）→ 白名单不命中 → **静默不追加条件**，
 *       表现是"隔离上线了、但某张表其实没隔离"，接口 200、无任何日志；</li>
 *   <li>列名写错（{@code seller_id} 写成 {@code sellerId}）→ MySQL 报 Unknown column，
 *       这个反而好，会立刻炸；</li>
 *   <li>多个服务对同一张表写不同列名 → 同一份数据在不同服务里按不同维度隔离，
 *       比没隔离更难查。</li>
 * </ul>
 * 因此：**表名与列名的对应关系只在这里维护**，其余地方一律 {@link #columnOf(String)} 取。
 *
 * <h2>本清单的来源</h2>
 * 由 {@code information_schema} 实算得出（2026-10-10，全部 {@code duoduo_*} 库）：
 * <pre>
 *   duoduo_product   t_spu          seller_id
 *   duoduo_product   t_listing      seller_id
 *   duoduo_product   t_audit_sample seller_id
 *   duoduo_trade     t_order        seller_id
 *   duoduo_trade     t_seller_debt  seller_id
 *   duoduo_trade     t_settlement   seller_id
 *   duoduo_fulfilment t_after_sale  seller_id
 *   duoduo_fulfilment t_review      seller_id
 *   duoduo_user      t_audit_log    tenant_id   ← 唯一叫 tenant_id 的
 *   duoduo_user      t_audit_sample seller_id
 * </pre>
 * <b>新增租户列表必须同步改这里</b>，否则它是一张"看起来隔离了、实际没有"的表。
 *
 * <h2>★ 一个表名可能对应多张物理表（必读）</h2>
 * 上表是 <b>10 张物理表，但只有 9 个表名</b>：{@code t_audit_sample} 在 {@code duoduo_product}
 * （商品上架抽查）与 {@code duoduo_user}（商家资质抽查）<b>各有一张</b>，实测两张的租户列
 * <b>都叫 {@code seller_id}</b>，所以按"表名 → 列名"做 key 是安全的（见 {@link #SHARED_TABLE_NAMES}）。
 *
 * <p><b>这条前提一旦被破坏，本类就会静默出错</b>：若哪天出现"同名但列名不同"的租户表，
 * 后写的 {@code put} 会覆盖先写的，于是其中一张表被按错误的列名过滤 —— 表现为 SQL 报
 * Unknown column（还算好），或更糟：过滤条件加在一列语义完全不同的字段上，接口 200、数据只是不对。
 * 真到那一天，本映射必须改成按 (库, 表) 二元组定位。
 *
 * <h2>刻意不做的事</h2>
 * 不做"凡是列名叫 seller_id 就自动纳入白名单"。自动发现的失效方向是**静默放开**：
 * 加了一张新表、列名恰好不叫 seller_id，或者某张表虽然列名叫 seller_id 但语义不是租户
 * （比如"推荐人"），自动发现要么漏、要么错，而两种错都不报错。
 * 显式声明清单改起来只多打一行字，却让每张表的隔离状态**可被 grep 到**。
 */
public final class TenantTables {

    /**
     * 网关写入的租户头（E20）。与 {@code X-User-Id} 同一管理姿势：
     * 网关先剥离客户端同名字段，再按令牌里的 {@code tid} 声明写入，
     * 因此下游可以把它当事实用。**服务端不得从查询参数/请求体读租户**。
     */
    public static final String HEADER = "X-Tenant-Id";

    /** 表名 → 租户列名。表名统一按小写存（SQL 里出现大写也归一后再比）。 */
    private static final Map<String, String> TABLE_COLUMN;

    /**
     * 在多个库里各有一张的同名租户表。
     *
     * <p>存在的意义不是"分支逻辑"，而是把这个前提<b>写在代码里、并且可被断言</b>：
     * 白名单按表名做 key，等价于断言"同名 = 同列"。清单从 9 个表名变到 10 张物理表时，
     * 只有这里能看出差异；否则 {@link #allTableNames()} 的 size 少 1 会被当成"我数错了"。
     */
    private static final Set<String> SHARED_TABLE_NAMES = Set.of("t_audit_sample");

    static {
        Map<String, String> map = new LinkedHashMap<>();
        // product 域
        map.put("t_spu", "seller_id");
        map.put("t_listing", "seller_id");
        // ★ 这个表名覆盖两张物理表（product 的商品抽查 / user 的资质抽查），两张的列都是 seller_id
        map.put("t_audit_sample", "seller_id");
        // trade 域
        map.put("t_order", "seller_id");
        map.put("t_seller_debt", "seller_id");
        map.put("t_settlement", "seller_id");
        // fulfilment 域
        map.put("t_after_sale", "seller_id");
        map.put("t_review", "seller_id");
        // user 域（★ 只有这一张用 tenant_id，其余仍是 seller_id）
        map.put("t_audit_log", "tenant_id");
        // 用 unmodifiableMap 而不是 Map.copyOf：后者打乱迭代顺序，而这里的顺序是按域分组的，
        // 断言失败时打出来的清单要能一眼看出是哪个域少了表。
        TABLE_COLUMN = Collections.unmodifiableMap(map);
    }

    private TenantTables() {
    }

    /** 该表是否带租户维度。 */
    public static boolean isTenantTable(String tableName) {
        return tableName != null && TABLE_COLUMN.containsKey(normalize(tableName));
    }

    /** 该表的租户列名；不是租户表时返回空。 */
    public static Optional<String> columnOf(String tableName) {
        if (tableName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(TABLE_COLUMN.get(normalize(tableName)));
    }

    /** 全部租户表名（只读）。用于断言"清单与 information_schema 实算一致"。 */
    public static Set<String> allTableNames() {
        return TABLE_COLUMN.keySet();
    }

    /**
     * 库内实算的<b>物理</b>租户表张数 = 表名数 + 跨库重名的额外张数。
     *
     * <p>与 {@link #allTableNames()} 分开，是因为这两个数<b>本来就是不一样的</b>（10 与 9）。
     * 只断言其中一个，另一个的变化就会悄悄溜过去。
     */
    public static int physicalTableCount() {
        // 显式转 int：Stream.count() 返回 long，直接相加会被判成「int + long」编译不过
        return TABLE_COLUMN.size() + (int) SHARED_TABLE_NAMES.stream().filter(TABLE_COLUMN::containsKey).count();
    }

    /** 同名跨库的租户表名（当前仅 {@code t_audit_sample}）。 */
    public static Set<String> sharedTableNames() {
        return SHARED_TABLE_NAMES;
    }

    /**
     * 表名归一：SQL 里的表名可能带反引号、可能大写（{@code T_SPU}），
     * 也可能带库前缀（{@code duoduo_user.t_audit_log} —— 跨库 UPDATE ... JOIN 会这样写）。
     * 不归一的话这三种写法都**静默不命中白名单**。
     *
     * <p>刻意公开：数据层拦截器的白名单判定要用同一把尺子。
     * 两边各写一份"看起来一样"的归一逻辑，是本项目反复出现的那类坑 ——
     * 改了一处忘另一处，表现是"某一种写法悄悄不过滤"，不报错。
     */
    public static String normalize(String raw) {
        String name = raw.trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        if (name.length() >= 2 && name.charAt(0) == '`' && name.endsWith("`")) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }
}
