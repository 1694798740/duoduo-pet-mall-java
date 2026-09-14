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

    // ---------------- 响应 ----------------

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
}
