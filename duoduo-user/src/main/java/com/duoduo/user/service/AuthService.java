package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.JwtTokenService;
import com.duoduo.user.dto.UserDtos.LoginRequest;
import com.duoduo.user.dto.UserDtos.LoginResponse;
import com.duoduo.user.dto.UserDtos.RealNameRequest;
import com.duoduo.user.dto.UserDtos.RegisterRequest;
import com.duoduo.user.dto.UserDtos.UpdateProfileRequest;
import com.duoduo.user.dto.UserDtos.UserView;
import com.duoduo.user.entity.RefreshTokenEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 账号与认证服务。
 *
 * <p>关键约束（对应 PRD M1）：
 * <ul>
 *   <li>手机号唯一性由数据库唯一索引 + 冲突转业务异常双重保证（ACC-01）</li>
 *   <li>封禁账号禁止登录，已登录的令牌由 refresh 阶段拦截（ACC-05）</li>
 *   <li>刷新令牌只存哈希并轮转，旧令牌立即失效</li>
 *   <li>实名信息仅保留掩码，完整身份证号不落库（个人信息最小必要）</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final SnowflakeIdGenerator idGenerator;

    public AuthService(UserMapper userMapper,
                       RefreshTokenMapper refreshTokenMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       SnowflakeIdGenerator idGenerator) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserEntity entity = new UserEntity();
        entity.setId(idGenerator.nextId());
        entity.setPhone(request.phone());
        entity.setPasswordHash(passwordEncoder.encode(request.password()));
        entity.setNickname(request.nickname() == null || request.nickname().isBlank()
                ? "宠友" + request.phone().substring(7)
                : request.nickname().trim());
        entity.setStatus(STATUS_ACTIVE);
        entity.setRole("USER");
        entity.setCreditScore(100);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            userMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // 并发注册同一手机号时由唯一索引兜底，不依赖先查后插
            throw BizException.conflict("该手机号已注册，请直接登录");
        }

        log.info("用户注册成功 userId={} phone={}", entity.getId(), maskPhone(entity.getPhone()));
        return issueTokens(entity, null);
    }

    @Transactional
    public LoginResponse login(LoginRequest request, String userAgent) {
        UserEntity entity = findByPhone(request.phone());
        if (entity == null || !passwordEncoder.matches(request.password(), entity.getPasswordHash())) {
            // 账号不存在与密码错误返回同一提示，避免手机号枚举
            throw BizException.unauthorized("手机号或密码不正确");
        }
        if ("BANNED".equals(entity.getStatus())) {
            throw BizException.forbidden("账号已被封禁，仅可浏览与申诉");
        }

        entity.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(entity);

        log.info("用户登录成功 userId={}", entity.getId());
        return issueTokens(entity, userAgent);
    }

    @Transactional
    public LoginResponse refresh(String refreshToken, String userAgent) {
        JwtTokenService.TokenPayload payload = jwtTokenService.parse(refreshToken, JwtTokenService.TYPE_REFRESH);
        if (!payload.valid() || payload.jti() == null) {
            throw BizException.unauthorized("刷新凭证无效或已过期，请重新登录");
        }

        RefreshTokenEntity record = refreshTokenMapper.selectById(payload.jti());
        if (record == null || record.getRevoked() != null && record.getRevoked() == 1) {
            throw BizException.unauthorized("刷新凭证已失效，请重新登录");
        }
        if (!sha256(refreshToken).equals(record.getTokenHash())) {
            throw BizException.unauthorized("刷新凭证已失效，请重新登录");
        }
        if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            throw BizException.unauthorized("刷新凭证已过期，请重新登录");
        }

        UserEntity entity = userMapper.selectById(payload.userId());
        if (entity == null) {
            throw BizException.unauthorized("账号不存在");
        }
        if ("BANNED".equals(entity.getStatus())) {
            throw BizException.forbidden("账号已被封禁");
        }

        // 轮转：旧令牌立即吊销，签发全新令牌对
        refreshTokenMapper.revoke(payload.jti());
        return issueTokens(entity, userAgent);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        JwtTokenService.TokenPayload payload = jwtTokenService.parse(refreshToken, JwtTokenService.TYPE_REFRESH);
        if (payload.valid() && payload.jti() != null) {
            refreshTokenMapper.revoke(payload.jti());
        }
    }

    public UserView profile(Long userId) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }
        return toView(entity);
    }

    @Transactional
    public UserView updateProfile(Long userId, UpdateProfileRequest request) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }
        if (request.nickname() != null && !request.nickname().isBlank()) {
            entity.setNickname(request.nickname().trim());
        }
        if (request.avatar() != null) {
            entity.setAvatar(request.avatar());
        }
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(entity);
        return toView(entity);
    }

    /** ACC-04：实名通过后立即解锁发布闲置与提现能力 */
    @Transactional
    public UserView submitRealName(Long userId, RealNameRequest request) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }
        if (entity.isRealNameVerified()) {
            throw BizException.conflict("该账号已完成实名认证");
        }
        entity.setRealName(request.realName().trim());
        entity.setIdCardMasked(maskIdCard(request.idCard()));
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(entity);

        log.info("实名认证通过 userId={}", userId);
        return toView(entity);
    }

    // ---------------- 内部方法 ----------------

    private LoginResponse issueTokens(UserEntity entity, String userAgent) {
        LoginUser loginUser = new LoginUser(entity.getId(), entity.getPhone(), entity.getRole(),
                entity.isRealNameVerified());
        String accessToken = jwtTokenService.createAccessToken(loginUser);

        String jti = UUID.randomUUID().toString().replace("-", "");
        String refreshToken = jwtTokenService.createRefreshToken(entity.getId(), jti);

        RefreshTokenEntity tokenRecord = new RefreshTokenEntity();
        tokenRecord.setId(jti);
        tokenRecord.setUserId(entity.getId());
        tokenRecord.setTokenHash(sha256(refreshToken));
        tokenRecord.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(jwtTokenService.refreshTtl()));
        tokenRecord.setRevoked(0);
        tokenRecord.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())));
        tokenRecord.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        refreshTokenMapper.insert(tokenRecord);

        return new LoginResponse(toView(entity), accessToken, refreshToken, jwtTokenService.accessTtl().toSeconds());
    }

    private UserEntity findByPhone(String phone) {
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPhone, phone).last("LIMIT 1"));
    }

    private UserView toView(UserEntity entity) {
        return new UserView(
                entity.getId(),
                maskPhone(entity.getPhone()),
                entity.getNickname(),
                entity.getAvatar(),
                entity.getRole(),
                entity.getStatus(),
                entity.isRealNameVerified(),
                entity.getRealName(),
                entity.getIdCardMasked(),
                entity.getCreditScore(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString()
        );
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return "****";
        }
        return idCard.substring(0, 4) + "**********" + idCard.substring(idCard.length() - 4);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.INTERNAL, "哈希算法不可用", ex);
        }
    }
}
