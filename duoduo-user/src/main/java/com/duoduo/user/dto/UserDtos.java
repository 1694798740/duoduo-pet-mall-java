package com.duoduo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户域对外契约（请求 / 响应）。
 * 用 record 表达不可变契约；校验注解放在请求侧，保证「边界处校验一切输入」。
 */
public final class UserDtos {

    private UserDtos() {
    }

    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    // ---------------- 请求 ----------------

    public record RegisterRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "请输入合法的 11 位手机号")
            String phone,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 64, message = "密码长度需在 8-64 位之间")
            String password,

            @Size(max = 20, message = "昵称最多 20 个字符")
            String nickname
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "请输入合法的 11 位手机号")
            String phone,

            @NotBlank(message = "密码不能为空")
            String password,

            /**
             * E21 · 两步验证码（6 位数字）。仅当该账号**已启用**两步验证时才需要：
             * 未携带 → 401 且 code 为 {@code TOTP_REQUIRED}；携带但错误 → 401 且 code 为
             * {@code TOTP_INVALID}。两者分开是为了让前端能准确地区分"该输第二步了"
             * 与"码输错了"—— 都笼统报"登录失败"会让人反复重输密码。
             *
             * <p>**与密码放在同一个请求里**，而不是拆成两个端点：本项目有三端客户端
             * （Web 后台 / 小程序 / 命令行夹具），多一个端点就是三处都要改的联调面；
             * 而"密码 + 动态码一起提交"在语义上本就是一次登录的两个因子。
             */
            String totpCode,

            /**
             * E21 · 恢复码（忘记动态码时的一次性替代通道）。
             * 与 {@code totpCode} 二选一；两者都传时以 {@code totpCode} 为准（正常路径优先）。
             */
            String recoveryCode
    ) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    /**
     * A13 微信登录：小程序 wx.login() 拿到的 js_code。
     * 开发 / 测试环境（duoduo.wechat.mock-enabled=true）支持 {@code mock:<openid>} 形式，
     * 免调微信服务器即可冒烟；生产环境该开关必须关闭。
     */
    public record WechatLoginRequest(
            @NotBlank(message = "微信登录 code 不能为空")
            String code
    ) {
    }

    public record RealNameRequest(
            @NotBlank(message = "真实姓名不能为空")
            @Pattern(regexp = "^[\\u4e00-\\u9fa5·]{2,20}$", message = "请输入 2-20 位中文姓名")
            String realName,

            @NotBlank(message = "身份证号不能为空")
            @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "请输入合法的 18 位身份证号")
            String idCard
    ) {
    }

    public record UpdateProfileRequest(
            @Size(min = 1, max = 20, message = "昵称长度需在 1-20 之间")
            String nickname,
            @Size(max = 500, message = "头像地址过长")
            String avatar
    ) {
    }

    public record PetUpsertRequest(
            @NotBlank(message = "宠物名字不能为空")
            @Size(max = 20, message = "宠物名字最多 20 个字符")
            String name,

            @NotBlank(message = "请选择宠物种类")
            @Pattern(regexp = "cat|dog|other", message = "种类只能是 cat / dog / other")
            String species,

            @Size(max = 30, message = "品种最多 30 个字符")
            String breed,

            LocalDate birthday,

            @Pattern(regexp = "BABY|ADULT|SENIOR", message = "生命阶段只能是 BABY / ADULT / SENIOR")
            String lifeStage,

            BigDecimal weightKg,

            @Pattern(regexp = "MALE|FEMALE|UNKNOWN", message = "性别只能是 MALE / FEMALE / UNKNOWN")
            String gender,

            Boolean neutered,

            @Size(max = 20, message = "过敏原最多 20 项")
            List<@Size(max = 20, message = "单个过敏原最多 20 个字符") String> allergens,

            @Size(max = 500, message = "病史最多 500 个字符")
            String medicalHistory,

            Boolean isDefault
    ) {
    }

    /**
     * 信用分回写请求（服务间内部接口，A4）。
     *
     * <p>分数不是随便给的：它由履约域按权重表算出（{@code CreditModel}），
     * 用户域只负责「存下来」。因此这里**只校验范围**，不校验算法 ——
     * 算法归履约域一处维护，用户域再验一遍只会制造第二份口径。
     *
     * @param score  信用分，0~100
     * @param source 来源标记（如 SCHEDULED_REFRESH / MANUAL_REFRESH），仅用于留痕
     */
    public record CreditUpdateRequest(
            @jakarta.validation.constraints.Min(value = 0, message = "信用分不能小于 0")
            @jakarta.validation.constraints.Max(value = 100, message = "信用分不能大于 100")
            int score,
            @Size(max = 40, message = "来源标记最多 40 个字符")
            String source
    ) {
    }

    // ---------------- 收货地址（A8） ----------------

    /**
     * 新增 / 修改收货地址。
     *
     * <p>省 / 市 / 区 / 明细分开提交，由服务端拼成一段完整地址写进订单快照；
     * 客户端<b>不能</b>只提交一段拼好的文本 —— 那样后续按城市算运费就无从做起。
     *
     * <p>{@code isDefault} 语义：{@code true} = 设为默认；{@code null} / {@code false} = 不改变默认归属。
     * 用包装类型而非 {@code boolean}，是为了区分「没传」与「明确要取消默认」——
     * 「取消默认」这个动作在本模型里不存在（默认要么转移到别的地址，要么随地址一起删掉），
     * 因此 {@code false} 一律按「不改变」处理，避免出现「用户一个默认地址都没有」的中间态。
     */
    public record AddressUpsertRequest(
            @NotBlank(message = "收货人不能为空")
            @Size(max = 50, message = "收货人姓名过长")
            String receiverName,

            @NotBlank(message = "收货手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "请输入合法的 11 位手机号")
            String receiverPhone,

            @NotBlank(message = "省份不能为空")
            @Size(max = 30, message = "省份名称过长")
            String province,

            @NotBlank(message = "城市不能为空")
            @Size(max = 30, message = "城市名称过长")
            String city,

            @NotBlank(message = "区 / 县不能为空")
            @Size(max = 30, message = "区县名称过长")
            String district,

            @NotBlank(message = "详细地址不能为空")
            @Size(max = 200, message = "详细地址最多 200 个字符")
            String detail,

            @Size(max = 20, message = "标签最多 20 个字符")
            String label,

            Boolean isDefault
    ) {
    }

    /**
     * 收货地址视图。
     *
     * <p>{@code receiverPhone} 在这里是<b>完整号码</b>，不做脱敏：地址簿是用户自己的数据，
     * 编辑时把号码显示成 {@code 138****8000} 会让用户没法确认自己填错了没有。
     * 需要脱敏的是「给第三方看的场景」（如订单列表给卖家看），那个脱敏发生在交易域，
     * 不在这个视图上做 —— 一处脱敏、一处不脱敏，比两处都脱敏更好排查。
     *
     * <p>{@code fullAddress} 由服务端拼好一起下发，免得每个前端页面各拼一遍、
     * 拼出三种顺序（「省市区+明细」与「省+市+区+明细」看起来只差个空格，实际会不一致）。
     */
    public record AddressView(
            Long id,
            String receiverName,
            String receiverPhone,
            String province,
            String city,
            String district,
            String detail,
            String label,
            /** 服务端拼好的完整地址，与下单快照写入的文本严格同源 */
            String fullAddress,
            boolean isDefault,
            String createdAt
    ) {
    }

    // ---------------- 运营账号管理（A7） ----------------

    /**
     * A7 角色变更请求。角色取值白名单在服务层校验（USER / SELLER / OPERATOR / ADMIN），
     * 这里只挡空值 —— 错误角色名的具体文案（「只能是这四个之一」）由服务层给出，
     * 边界校验报「格式」，业务校验报「规则」，两者混在一起会难住排查的人。
     */
    public record RoleChangeRequest(
            @NotBlank(message = "目标角色不能为空")
            String role
    ) {
    }

    /**
     * A7 运营侧用户视图（**脱敏**）。
     *
     * <p>与 {@link UserView}（用户看自己）的差别：运营列表**不该看到**的东西一概没有 ——
     * 实名姓名、身份证掩码、完整手机号都不下发。运营管理的对象是「账号与角色」，
     * 不是「身份资料」；需要身份资料的场景将来走带审计的专项查询，而不是把列表接口撑成大杂烩。
     */
    public record AdminUserView(
            Long id,
            String phoneMasked,
            String nickname,
            String role,
            String status,
            boolean realNameVerified,
            Integer creditScore,
            String createdAt
    ) {
    }

    /**
     * A7 角色变更结果。
     *
     * <p>{@code notice} 是给运营界面的提示文案（验收②）：
     * 被改用户的访问令牌是签发时的角色快照，**重新登录后才生效** ——
     * 这句提示不带上，运营会以为提权当场生效，在对方报「没有权限」时白排查一轮。
     */
    public record RoleChangeResult(
            AdminUserView user,
            String notice
    ) {
    }

    // ---------------- 响应 ----------------
    /**
     * 用户公开资料（服务间只读视图）。
     *
     * <p>刻意只含「展示所需的三个字段」：id / 昵称 / 头像。
     * 手机号、实名信息、角色、信用分一律不返回 ——
     * 跨域接口应当按调用方真正需要什么裁剪，拿整份 {@link UserView} 图省事的代价，
     * 是把隐私字段扩散到每个调用方的日志与内存里。
     */
    public record UserBrief(
            Long id,
            String nickname,
            String avatar
    ) {
    }

    /**
     * 用户角色快照（服务间内部接口，第四十七轮）。
     *
     * <p>调用方是社交域：发私信时把发送方角色落列（sender_role），通知中心据此把
     * 私信分流到「好友消息」或「商家消息」。只回 id + role —— 角色是低敏感字段，
     * 但仍是「按调用方需要什么裁剪」的口径，不整份 {@code UserView} 下发。
     */
    public record UserRoleBrief(
            Long id,
            String role
    ) {
    }

    /**
     * 信用分回写结果（服务间内部接口，A4）。
     *
     * @param previousScore 回写前的分数（用户不存在时为 null）
     * @param changed       本次是否真的改了库 —— 分数相同时为 false，是幂等的可见证据
     */
    public record CreditUpdateResult(
            Long userId,
            Integer previousScore,
            int score,
            boolean changed
    ) {
    }

    public record UserView(
            Long id,
            String phoneMasked,
            String nickname,
            String avatar,
            String role,
            String status,
            boolean realNameVerified,
            String realName,
            String idCardMasked,
            Integer creditScore,
            String createdAt,
            /**
             * 是否已绑定微信（A14）。
             *
             * <p>只下发**布尔**、绝不下发 openid 本体：前端需要它来决定「绑定/解绑」入口的文案，
             * 而 openid 属于可用于跨应用关联用户的标识，没有下发必要（与手机号只发掩码同一纪律）。
             */
            boolean wechatBound,
            /**
             * E13 · 店铺成员身份（纯增量下发）：前端据此决定商家工作台准入与展示。
             * {@code null} = 不属于任何店（含店主本人 —— 店主走 role=SELLER 通道）。
             */
            MerchantBrief merchant
    ) {
    }

    /** 店铺成员身份简报（E13）：只下发判定与展示必需的字段 */
    public record MerchantBrief(
            String merchantId,
            String storeName,
            /** OWNER / STAFF */
            String memberRole,
            String status,
            /** F-04 · 店员生效权限键（前端据此隐藏无权限的入口；店主为全量） */
            java.util.List<String> permissions
    ) {
    }

    public record LoginResponse(
            UserView user,
            String accessToken,
            String refreshToken,
            long expiresInSeconds
    ) {
    }

    // ---------------- E21 · 平台后台两步验证（TOTP） ----------------

    /**
     * 两步验证状态。用于两端：前端据此决定是否显示"去绑定"引导；
     * 用户自己也能看到"我到底绑没绑、还剩几枚恢复码"。
     */
    public record TotpStatusView(
            /** true = 已完成绑定（此后登录必须携带动态码或恢复码） */
            boolean enabled,
            /** 绑定完成时间（ISO-8601 UTC 字符串）；未绑定为 null */
            String boundAt,
            /** 剩余可用恢复码数量。**只给数量、不给码** —— 明文只在生成那一刻出现一次 */
            int recoveryCodesRemaining
    ) {
    }

    /** 绑定第一步：返回密钥与 otpauth 链接（前端渲染二维码，或让用户手动输入密钥） */
    public record TotpSetupView(
            /** Base32 密钥（无填充），供"扫不了码"时手动录入 */
            String secret,
            /** {@code otpauth://} 链接，交给二维码组件 */
            String otpauthUri
    ) {
    }

    public record TotpEnableRequest(
            @NotBlank(message = "验证码不能为空")
            String code
    ) {
    }

    /**
     * 绑定成功：**一次性**下发恢复码明文。
     *
     * <p>这是全流程中唯一一次能拿到明文的地方 —— 服务端只存 SHA-256。
     * 响应里没有任何"以后再查"的接口，所以前端必须在这一步明确提示用户保存。
     */
    public record TotpEnableView(
            List<String> recoveryCodes
    ) {
    }

    /**
     * 解绑请求：动态码与恢复码二选一。
     *
     * <p>两个都允许，是因为真实场景里"手机还在但想关掉"和"手机丢了只能靠恢复码关掉"
     * 都会发生；只留动态码会让后者必须去找管理员。
     */
    public record TotpDisableRequest(
            /** 当前动态码；与 recoveryCode 二选一 */
            String code,
            /** 恢复码；与 code 二选一 */
            String recoveryCode
    ) {
    }

    public record PetView(
            Long id,
            String name,
            String species,
            String breed,
            LocalDate birthday,
            Integer ageMonths,
            String lifeStage,
            BigDecimal weightKg,
            String gender,
            boolean neutered,
            List<String> allergens,
            String medicalHistory,
            boolean isDefault,
            String createdAt
    ) {
    }

    /**
     * 宠物档案的**服务间裁剪视图**（第五十八轮 · AI 加强项 S1）。
     *
     * <p>刻意只留「挑商品用得上」的四个字段（名称 / 物种 / 品种 / 生命阶段 + 过敏原），
     * **不含**生日、体重、性别、绝育、病史 ——
     * 病史是健康数据，导购**完全不需要**它就能给出合适的推荐；
     * 而一旦跨服务下发，它就多了一处副本、多了一份泄露面。
     * 判断标准很简单：<b>这条字段会不会改变推荐结果？不会就别传。</b>
     */
    public record PetBrief(
            Long id,
            String name,
            String species,
            String breed,
            String lifeStage,
            List<String> allergens
    ) {
    }

    /**
     * 地址簿视图（服务间只读，A8）。
     *
     * <p>与 {@link AddressView} 的差别：<b>只有收货人三要素 + 完整地址</b>。
     * 交易域下单需要的就是这三样，用于写入订单快照；
     * 标签、省份字段、是否默认这些「地址簿管理」信息它一概不需要，
     * 按调用方真正需要什么裁剪字段（同 {@link UserBrief} 的取舍）。
     */
    public record AddressSnapshot(
            Long id,
            String receiverName,
            String receiverPhone,
            String fullAddress
    ) {
    }

    // ---------------- C7 · 账号注销 ----------------

    /**
     * 提交注销申请。
     *
     * <p>密码必填：注销是不可逆操作的前置门，必须用「你知道密码」把「会话被盗用」
     * 这种情况挡在外面 —— 只凭登录态就能注销，等于公共场所一台没锁屏的手机
     * 被别人点两下就永久失去账号。
     */
    public record DeleteAccountRequest(
            @NotBlank(message = "密码不能为空")
            String password,

            @Size(max = 200, message = "注销原因过长")
            String reason
    ) {
    }

    /**
     * 撤销注销申请（C7）。
     *
     * <p><b>刻意不带登录态要求</b>：提交注销后账号已禁止登录，用户手里没有任何
     * 有效会话，此时若要求登录态就等于「注销不可撤销」。改用「手机号 + 密码」
     * 重新验证身份，与登录同一口径的错误提示（不区分账号不存在与密码错误）。
     */
    public record CancelDeleteAccountRequest(
            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = PHONE_REGEX, message = "请输入合法的 11 位手机号")
            String phone,

            @NotBlank(message = "密码不能为空")
            String password
    ) {
    }

    /**
     * 一条阻塞项：告诉用户「先去处理什么」。
     *
     * @param type  类别：{@code ORDER} 未完成订单 / {@code AFTER_SALE} 进行中售后 /
     *              {@code MERCHANT} 店铺主体身份 / {@code LISTING} 在售闲置
     * @param ref   业务单号或店铺名（用户能认出的那一个）
     * @param detail 人类可读的状态说明，如「已付款 · ¥39.90」
     */
    public record BlockedItemView(
            String type,
            String ref,
            String detail
    ) {
    }

    /** 注销预检结果（申请前给端侧展示，也可用于提交被拒时展示） */
    public record DeleteBlockersView(
            boolean blocked,
            List<BlockedItemView> items
    ) {
    }

    /**
     * 注销状态视图。
     *
     * @param accountStatus {@code ACTIVE} / {@code CANCELLING} / {@code DELETED}
     * @param scheduledAt   冷静期截止时间（ISO-8601）；非冷静期为 null
     * @param graceDays     冷静期天数（配置项，默认 7）
     * @param blockedItems  若因阻塞而未进入冷静期，这里给出清单
     */
    public record DeleteAccountView(
            String accountStatus,
            String scheduledAt,
            int graceDays,
            List<BlockedItemView> blockedItems
    ) {
    }
}
