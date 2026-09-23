package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.JwtTokenService;
import com.duoduo.user.dto.UserDtos;
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
import com.duoduo.user.support.WechatGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
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
    private final WechatGateway wechatGateway;
    /**
     * 新用户初始信用分（= 信用分模型的基础分，默认 60）。
     *
     * <p>必须与履约域 {@code CreditModel} 的 base 权重一致，否则会出现
     * 「资料页显示 100、信用分页显示 60」这种谁也说不清哪个对的分裂。
     * 两个服务各自持有这个常量（没有共享模块），一致性由冒烟测试守住：
     * 断言「新注册用户的库内分数 == 信用分接口给出的基础分」。
     */
    private final int creditDefault;

    public AuthService(UserMapper userMapper,
                       RefreshTokenMapper refreshTokenMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       SnowflakeIdGenerator idGenerator,
                       WechatGateway wechatGateway,
                       @Value("${duoduo.user.credit-default:60}") int creditDefault) {
        this.userMapper = userMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.idGenerator = idGenerator;
        this.wechatGateway = wechatGateway;
        this.creditDefault = creditDefault;
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
        // 初始信用分 = 信用分模型的基础分（默认 60），不是「满分 100」：
        // 信用分体现的是「已积累的履约表现」，刚注册的人还没有可评价的行为，
        // 给满分等于让空账号显示得比老卖家还可信。详见履约域 CreditModel 的权重表。
        entity.setCreditScore(creditDefault);
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

    /**
     * A13 · 微信小程序登录（code2Session）。
     *
     * <p>openid 与账号是「一对一绑定」关系（{@code t_user.wx_openid} 唯一索引）：
     * 已绑定且账号 ACTIVE → 直接发令牌；未绑定 → 422 引导用户先手机号登录再绑定。
     * **不做「首次微信登录自动注册」** —— 微信授权拿不到手机号，自动注册会造出
     * 一批没有手机号、无法接收通知、连密码都没有的孤儿账号。
     *
     * <p>封禁账号即使已绑定也拒绝登录（与手机号登录同一口径，ACC-05）。
     */
    @Transactional
    public LoginResponse wechatLogin(String code, String userAgent) {
        String openid = wechatGateway.codeToOpenid(code);
        UserEntity entity = findByOpenid(openid);
        if (entity == null) {
            throw BizException.businessRule("该微信未绑定账号，请先用手机号登录，再在「账号与安全」中绑定");
        }
        if ("BANNED".equals(entity.getStatus())) {
            throw BizException.forbidden("账号已被封禁，仅可浏览与申诉");
        }

        entity.setLastLoginAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(entity);

        log.info("微信登录成功 userId={}", entity.getId());
        return issueTokens(entity, userAgent);
    }

    /**
     * A13 · 登录态下把当前账号与微信 openid 绑定。
     *
     * <p>绑定语义与并发安全：
     * <ul>
     *   <li><b>幂等</b>：当前账号已绑同一 openid → 直接成功返回，不报错
     *       （重复点「绑定微信」不该把人吓一跳，与 applyMerchant 同口径）。</li>
     *   <li><b>冲突</b>：openid 已绑在**其他**账号上 → 409。正常路径由
     *       {@code uk_user_wxopenid} 唯一索引兜底（先查后插只是给出友好提示，
     *       并发抢绑的最终防线是数据库约束 —— 与手机号唯一性同一套姿势）。</li>
     *   <li><b>换绑</b>：本方案暂不支持一个微信号在两个账号间迁移，A13 不做解绑。</li>
     * </ul>
     */
    @Transactional
    public UserView wechatBind(Long userId, String code) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }

        String openid = wechatGateway.codeToOpenid(code);
        if (openid.equals(entity.getWxOpenid())) {
            return toView(entity); // 幂等：重复绑定同一个微信
        }

        UserEntity holder = findByOpenid(openid);
        if (holder != null) {
            throw BizException.conflict("该微信已绑定其他账号");
        }

        entity.setWxOpenid(openid);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            userMapper.updateById(entity);
        } catch (DuplicateKeyException ex) {
            // 并发窗口：先查时还没有人绑，update 时被另一个请求抢先 —— 唯一索引兜底
            throw BizException.conflict("该微信已绑定其他账号");
        }

        log.info("微信绑定成功 userId={} openidPrefix={} len={}",
                userId, openid.substring(0, Math.min(4, openid.length())), openid.length());
        return toView(entity);
    }

    public UserView profile(Long userId) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }
        return toView(entity);
    }

    /**
     * 服务间只读：批量取用户公开资料（昵称 / 头像）。
     *
     * <p>供履约域在评价列表里补全展示信息。**一次批量**而不是逐条调用：
     * 评价列表一页 20 条，逐条调用户服务就是 20 次跨服务往返（N+1 的跨进程版本），
     * 页面一慢就会被怀疑是「评价服务不行」，而根因在调用姿势。
     *
     * <p>查不到的 id 直接**跳过**而不是抛异常：用户被注销 / 数据不一致时，
     * 评价列表应当降级显示（前端用「已注销用户」兜底），而不是整页 500。
     */
    public List<UserDtos.UserBrief> userBriefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .map(u -> new UserDtos.UserBrief(u.getId(), u.getNickname(), u.getAvatar()))
                .toList();
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

    /**
     * 申请成为卖家（商家入驻）。
     *
     * <p>前置条件：**必须已实名**。这不是形式主义 —— 资金托管最终会放款给卖家，
     * 匿名卖家意味着「钱可以打给一个无法追责的主体」，担保交易的信任基础就没了（对应 PRD ACC-04）。
     *
     * <p>本期不做资质审核（没有人工审核台），实名即开通；真实业务里这里应生成待审核的入驻单。
     * 幂等：已是卖家时直接返回当前资料，不报错 —— 重复点击「成为卖家」不该把人吓一跳。
     */
    @Transactional
    public UserView applyMerchant(Long userId) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }
        if (!entity.isRealNameVerified()) {
            throw BizException.businessRule("请先完成实名认证再申请成为卖家");
        }
        if (!"SELLER".equalsIgnoreCase(entity.getRole())) {
            entity.setRole("SELLER");
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            userMapper.updateById(entity);
            log.info("用户升级为卖家 userId={}", userId);
        }
        return toView(entity);
    }

    /**
     * 服务间写：回写信用分（A4）。
     *
     * <p><b>幂等</b>：分数与库里一致时**不写库**（不推 {@code updated_at}、不产生无谓写放大），
     * 直接返回 {@code changed=false}。刷新任务因此可以放心重放 ——
     * 「重放不改变结果」是这类回写任务唯一可靠的安全保证。
     *
     * <p>分数上限 100 由请求侧 {@code @Min/@Max} 挡住；服务层不再复验，
     * 避免第二份口径（算法归履约域一处维护）。
     */
    @Transactional
    public UserDtos.CreditUpdateResult updateCreditScore(Long userId, int score, String source) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.notFound("用户");
        }
        Integer previous = entity.getCreditScore();
        if (previous != null && previous == score) {
            return new UserDtos.CreditUpdateResult(userId, previous, score, false);
        }
        entity.setCreditScore(score);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(entity);
        log.info("信用分回写 userId={} {} -> {} source={}", userId, previous, score, source);
        return new UserDtos.CreditUpdateResult(userId, previous, score, true);
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

    private UserEntity findByOpenid(String openid) {
        return userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getWxOpenid, openid).last("LIMIT 1"));
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
