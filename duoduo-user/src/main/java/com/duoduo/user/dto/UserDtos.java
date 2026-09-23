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
            String password
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
            String createdAt
    ) {
    }

    public record LoginResponse(
            UserView user,
            String accessToken,
            String refreshToken,
            long expiresInSeconds
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
}
