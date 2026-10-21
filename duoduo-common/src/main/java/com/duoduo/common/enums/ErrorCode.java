package com.duoduo.common.enums;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。
 * 每个错误码绑定一个 HTTP 状态码，保证「HTTP 语义」与「业务语义」一致，客户端无需额外映射表。
 *
 * <p>与 PRD 的对应关系：VALIDATION 对应边界校验（400）、BUSINESS_RULE 对应状态机与库存等业务约束（422）、
 * FORBIDDEN 对应权限矩阵（403）、RATE_LIMITED 对应限流（429）。
 */
public enum ErrorCode {

    SUCCESS(HttpStatus.OK, "成功"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求不合法"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "请求参数不合法"),
    /**
     * 请求体格式不支持（415）。
     *
     * <p>典型触发：客户端发 POST 却带 {@code application/x-www-form-urlencoded}
     * （curl、部分 HTTP 库在无 body 时的默认行为），而接口要的是 JSON。
     * 这<b>是客户端的问题</b>，此前落到兜底分支变成 500，
     * 既污染了 5xx 告警，又把「你发错了格式」说成了「我坏了」。
     */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的请求体格式，请使用 application/json"),
    /**
     * 请求方法不支持（405）。
     *
     * <p>典型触发：路径存在但方法用错 —— 对只读端点发 POST（或反之）。
     * 与 {@link #UNSUPPORTED_MEDIA_TYPE} 是同一类问题：<b>调用方发错了</b>。
     * 此前落到兜底分支变成 500，把「你调错了」说成「我坏了」，
     * 既污染 5xx 告警、又让接入方完全看不出问题在自己这一侧。
     */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不被支持，请检查 HTTP 方法是否正确"),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "身份未认证或登录已过期"),
    /**
     * E21 · 该账号已启用两步验证，本次登录未提供动态码 / 恢复码。
     *
     * <p>与 {@link #UNAUTHORIZED} 分开，是为了让前端能准确区分"该输第二步了"与"密码不对"。
     * 都报同一句话的后果是用户反复重输密码，而正确操作其实是去翻认证器 App。
     * HTTP 状态仍取 401 —— 它确实是"身份还没证明完整"，而前端对 401 的既有处理
     * （不跳登录页、留在当前页）也正是这里想要的。
     */
    TOTP_REQUIRED(HttpStatus.UNAUTHORIZED, "该账号已启用两步验证，请提供验证码"),
    /**
     * E21 · 两步验证码不正确（动态码错误，或恢复码无效 / 已被使用过）。
     *
     * <p>恢复码与动态码共用这一个码值：对客户端而言处置相同（重试，或换一枚恢复码），
     * 而分成两个码值会泄露"这枚恢复码存在、但已经用过"这类内部信息。
     */
    TOTP_INVALID(HttpStatus.UNAUTHORIZED, "验证码不正确或已失效"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行该操作"),

    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(HttpStatus.CONFLICT, "资源状态冲突"),

    BUSINESS_RULE(HttpStatus.UNPROCESSABLE_ENTITY, "业务规则不满足"),
    STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "库存不足"),
    ORDER_STATUS_ILLEGAL(HttpStatus.UNPROCESSABLE_ENTITY, "订单状态不允许该操作"),
    /** 支付渠道回调验签失败：对渠道而言等同于「调用方身份不可信」，故用 401 而非 422 */
    PAYMENT_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "支付回调签名校验未通过"),
    PAYMENT_CHANNEL_UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的支付渠道"),
    PAYMENT_CHANNEL_ERROR(HttpStatus.BAD_GATEWAY, "支付渠道调用失败"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "支付回调金额与支付单不一致"),

    /**
     * 微信一次性 code 无效 / 已过期 / 已被使用（422）。
     *
     * <p><b>刻意不用 401</b>：这是「请求参数里的一次性凭据不能用」，
     * 与「你没有登录 / 登录已过期」是两件事。此前一律 401，
     * 导致小程序把所有微信失败都当成「登录态失效」→ 清空本地会话，
     * 表现为「一绑定微信就被踢下线」（2026-10-20 用户报障）。
     * 用 400 + 独立 code，端侧才能只提示重试、不动登录态。
     * <p><b>不能用 422</b>：本项目的 422 已被「微信未绑定」这个业务语义占用，
     * 复用会把「code 过期」误解成「需要绑定」。
     */
    WECHAT_CODE_INVALID(HttpStatus.BAD_REQUEST, "微信凭据无效或已过期，请重试"),
    /**
     * 微信侧配置错误（503）：AppID 与 AppSecret 不匹配、AppID 无权限等。
     *
     * <p>这类问题<b>不是用户能修的</b>，也不该让端侧清会话；必须让它以「服务不可用」
     * 的面目出现，并在服务端日志里点名「appsecret 不匹配」。
     */
    WECHAT_CONFIG_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "微信登录未正确配置，请联系管理员"),

    /**
     * C7 · 账号注销被未完成的业务挡住（409）。
     *
     * <p>有未终态订单、进行中售后、店铺主体身份或在售闲置时，注销申请必须被拒绝 ——
     * 否则"删掉的只是账号"，订单履约、退款、店铺主体却悬在半空。
     * 响应体另带 {@code blockedItems} 清单（订单号 + 状态 / 店铺 / 闲置），
     * 端侧据此告诉用户"先去处理哪几件"，而不是干巴巴一句"不能注销"。
     *
     * <p>用 409 而不是 422：422 在本项目已被「微信未绑定」占用（见 WECHAT_CODE_INVALID
     * 注释里的同类理由），复用会把两种语义搅在一起。
     */
    ACCOUNT_DELETE_BLOCKED(HttpStatus.CONFLICT, "账号存在未完成的业务，暂时无法注销"),
    /**
     * C7 · 账号正在注销冷静期内（409）。
     *
     * <p>用户提交注销后进入 7 天冷静期，此期间登录被停用（防止"注销了还能继续买东西"），
     * 但可凭「手机号 + 密码」撤销。错误码独立出来，端侧才能把用户导向「撤销注销」页，
     * 而不是普通登录页 —— 后者会让用户以为密码错了。
     */
    ACCOUNT_DELETING(HttpStatus.CONFLICT, "账号正在注销冷静期内，登录已停用"),

    /**
     * A4 · 客服账号尚未就绪（503）。
     *
     * <p>`duoduo.social.official-cs-user-id` 配的那个账号在库里不存在（种子数据没跑、
     * 被清理脚本带走、或配置改错）。这时**不能**把 userId 原样下发 —— 端侧会顺利跳进
     * 一个「查无此人」的会话（昵称退化成「用户710…」），用户发出去的消息永远没人看。
     * 明确回 503，端侧退回说明弹窗并给 AI 导购出口，比假装接通诚实。
     */
    SUPPORT_AGENT_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "客服账号暂未就绪，请稍后再试或先问 AI 导购"),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    DEGRADED(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务不可用，已降级"),

    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请稍后重试");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int httpStatusValue() {
        return httpStatus.value();
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
