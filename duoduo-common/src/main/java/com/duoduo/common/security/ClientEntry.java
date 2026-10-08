package com.duoduo.common.security;

import java.util.Set;

/**
 * 客户端入口标识（E8）：区分一枚令牌是**从哪个站点**签发的。
 *
 * <h2>要解决什么</h2>
 * 三个站点（商城 / 平台管理后台 / 第三方商家平台）各有自己的登录页，
 * 但登录接口是同一个账号中心。于是出现一个纵深防御缺口：
 * 「在商城登录拿到的令牌」在令牌层面对三个站点一视同仁 ——
 * 一旦 Cookie 的隔离失效（例如某天有人图省事把 Cookie 加上 {@code Domain=.localhost}），
 * 或令牌以 Bearer 形式被带出浏览器，它就能直接去调后台/商家的接口。
 *
 * <p>E8 把入口写进令牌的 {@code ent} 声明，网关据此**按入口裁剪可访问的路径族**
 * （见 {@code GatewaySecurityProperties#entryRules}）。
 *
 * <h2>★ 定位：这是纵深防御，不是角色校验的替代品</h2>
 * 必须讲清楚它的**边界**，否则容易被误当成强隔离：
 * <ul>
 *   <li>入口标识由**站点 nginx 注入**（{@code X-Duoduo-Entry}），网关会先剥离客户端自带的同名字段
 *       再写入归一化值。但网关**无法分辨**「这个头是本站 nginx 注入的」还是
 *       「调用方直连网关自己塞的」—— 端口暴露在宿主上，两者在 TCP 层面没有区别。</li>
 *   <li>因此：**能自己登录的人可以声明自己是任意入口**。它挡不住恶意方，
 *       挡的是「令牌被跨入口误用」与「泄露后的可用范围」。</li>
 *   <li>真正决定「能不能做这个操作」的仍然是**角色**（服务端 OperatorGuard / 归属校验）。
 *       {@code ent} 只是在它前面多收窄一层。</li>
 * </ul>
 * 要把入口变成**不可伪造**的强边界，需要 nginx 侧对入口头做 HMAC 签名（本仓已有
 * {@code InternalCallAuth} 那套机制），或依赖网络拓扑只信任内网来源 —— 两者成本都明显更高，
 * 收益（把"误用"变成"防伪造"）与本项目当前的威胁模型不匹配。此处**刻意不做**，
 * 并把理由留在这里，避免后人误以为已经做到了。
 *
 * <h2>向后兼容</h2>
 * 老令牌没有 {@code ent} 声明 → 解析结果为 {@code null} → 网关**不施加任何入口限制**。
 * 这是刻意的：加这个字段时线上正有活跃会话，若把"无声明"一律当作"非法"，
 * 等于把所有在线用户踢下线。安全收益（收窄新令牌）不需要以打断在线会话为代价。
 * 同理，小程序直连网关（不带任何入口头）签发的令牌也是 {@code null} —— 不受限。
 */
public final class ClientEntry {

    /** 商城站点 */
    public static final String MALL = "mall";
    /** 平台管理后台站点 */
    public static final String ADMIN = "admin";
    /** 第三方商家平台站点 */
    public static final String MERCHANT = "merchant";

    /** 承载入口标识的请求头。三个站点的 nginx 各自注入，网关负责归一化 */
    public static final String HEADER = "X-Duoduo-Entry";

    /** 全部合法取值。除此之外一律视为"未声明"（null），不做兜底猜测 */
    public static final Set<String> ALL = Set.of(MALL, ADMIN, MERCHANT);

    private ClientEntry() {
    }

    /**
     * 归一化入口标识：**只认白名单里的三个字面量（大小写敏感）**，
     * 其余（大小写不符、任意字符串、空串）一律返回 {@code null}。
     *
     * <p>为什么不做"宽容解析"（如 lower + 前缀匹配）：入口标识会进令牌、
     * 会影响网关的放行判断，它必须是一个**封闭集合**。任何"尽量理解调用方意图"的解析
     * 都会让这个集合慢慢变成开放集合，而那正是这类字段最后失效的方式。
     * 合法值只会来自三份 nginx 配置（写死的小写字面量），不存在需要宽容的真实来源。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return ALL.contains(trimmed) ? trimmed : null;
    }
}
