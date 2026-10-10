package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.totp.Base32;
import com.duoduo.common.security.totp.Totp;
import com.duoduo.user.dto.UserDtos.TotpEnableView;
import com.duoduo.user.dto.UserDtos.TotpSetupView;
import com.duoduo.user.dto.UserDtos.TotpStatusView;
import com.duoduo.user.entity.RefreshTokenEntity;
import com.duoduo.user.entity.TotpRecoveryCodeEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.TotpRecoveryCodeMapper;
import com.duoduo.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * E21 · 平台后台两步验证（TOTP）。
 *
 * <p><b>它解决的是什么</b>：平台运营 / 管理员一个账号能审商品、关订单、改权限、看全站资金。
 * 只靠密码的话，一次撞库或一次钓鱼就足以拿到整个后台 —— 两步验证让"拿到密码"不再等于"拿到后台"。
 *
 * <p><b>强制的落点</b>（这是本功能唯一的判定点，别处不要再判断一遍）：
 * 账号**一旦启用** 2FA，登录就必须提供动态码或恢复码，否则拿不到任何令牌；
 * 并且**刷新令牌也带"两步验证已满足"的声明**，否则一枚在启用之前签发的刷新令牌
 * 就能在 7 天内不断换新令牌，把这道门绕开（见 {@code JwtTokenService.CLAIM_TWO_FACTOR_SATISFIED}）。
 * 网关那边不需要任何改动 —— 没有令牌，网关本来就什么都放行不了。
 *
 * <p><b>未启用 2FA 的账号仍然可以登录</b>。这是刻意的：存量运营账号如果因为"没绑定"
 * 被一律挡在门外，等于把所有人锁死（而绑定入口本身也需要登录态）。
 * 因此本项落地的是"**已绑定即强制 + 显著引导未绑定者去绑定**"；
 * 若要收紧成"未绑定即拒绝"，需要先提供一条**绑定专用令牌**通道，属于后续边界（记在 PLAN §9）。
 *
 * <p><b>三条安全细节，改动时不要"简化"</b>：
 * <ol>
 *   <li><b>防重放</b>：动态码在 ±30 秒窗口内可被重复提交，因此记录每次成功校验的"时间步"，
 *       并用一次**条件更新**（{@code totp_last_step IS NULL OR < 本次步号}）原子地推进它 ——
 *       先读后写会在并发下把同一枚码放行两次；</li>
 *   <li><b>恢复码只存哈希、用一次作废一枚</b>，且**失败尝试不消耗**（否则攻击者可以用错误输入
 *       把用户的恢复码一枚枚耗光）；</li>
 *   <li><b>已启用时拒绝重新 setup</b>：允许覆盖密钥等于给出一个降级入口 ——
 *       攻击者登录后重新生成密钥，用户原来的认证器就失效了。想换设备必须先解绑（需验证码）。</li>
 * </ol>
 */
@Service
public class TwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);

    /** 绑定成功时下发的恢复码数量。10 枚在"够用"与"抄写负担"之间取的平衡 */
    private static final int RECOVERY_CODE_COUNT = 10;
    /** 每枚恢复码的随机字节数：5 字节 = 40 bit 熵 = 8 个 Base32 字符（展示时按 4-4 分组） */
    private static final int RECOVERY_CODE_BYTES = 5;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final TotpRecoveryCodeMapper recoveryCodeMapper;
    private final SnowflakeIdGenerator idGenerator;
    /**
     * 刷新令牌表：**启用 2FA 时用它把该账号的存量刷新令牌一次性吊销**。
     *
     * <p>为什么必须做这一步：刷新令牌有效期 7 天，而"它是在什么状态下签发的"只能靠令牌里的
     * {@code tfv} 声明判断。启用 2FA **之前**签发的令牌，其 {@code tfv} 为 true
     * （当时确实没有这项要求）→ 它依然合法、依然能刷新。
     * 不吊销就等于：用户以为"我开了两步验证"，而手上那枚令牌还能免验证地续命一周。
     */
    private final RefreshTokenMapper refreshTokenMapper;
    /**
     * 认证器 App 里显示的发行方名称。
     *
     * <p>可配置而不是写死：同一份代码将来可能被复制到别的项目，
     * 而"用户在 App 里看到一个自己不认识的发行方"是很实际的困惑来源。
     */
    private final String issuer;

    public TwoFactorService(UserMapper userMapper,
                            TotpRecoveryCodeMapper recoveryCodeMapper,
                            SnowflakeIdGenerator idGenerator,
                            RefreshTokenMapper refreshTokenMapper,
                            @Value("${duoduo.user.totp-issuer:多多宠物商店}") String issuer) {
        this.userMapper = userMapper;
        this.recoveryCodeMapper = recoveryCodeMapper;
        this.idGenerator = idGenerator;
        this.refreshTokenMapper = refreshTokenMapper;
        this.issuer = issuer;
    }

    // ---------------- 状态 ----------------

    /** 当前账号的两步验证状态（前端据此决定是否显示绑定引导） */
    public TotpStatusView status(Long userId) {
        UserEntity user = requireUser(userId);
        return new TotpStatusView(
                user.isTwoFactorEnabled(),
                user.getTotpBoundAt() == null ? null : user.getTotpBoundAt().toString(),
                remainingRecoveryCodes(userId));
    }

    // ---------------- 绑定 ----------------

    /**
     * 第一步：生成密钥并返回 otpauth 链接。**此时尚未启用**（{@code totp_enabled} 仍为 0）。
     *
     * <p>为什么要分两步：认证器 App 里可能存错、用户可能扫的是旧二维码。
     * 只有在用户用这个密钥成功算出一次正确的码之后（{@link #enable}），才真正启用 ——
     * 否则"绑错了密钥"会让用户直接把自己锁在门外，且他自己无法恢复。
     *
     * <p>重复调用会**覆盖**上一枚未启用的密钥（用户刷新二维码的正常路径）；
     * 但**已启用时拒绝**（见类注释第 3 条）。
     */
    @Transactional
    public TotpSetupView setup(Long userId) {
        UserEntity user = requireUser(userId);
        if (user.isTwoFactorEnabled()) {
            throw BizException.businessRule("两步验证已启用；如需更换认证设备，请先关闭两步验证");
        }
        String secret = Totp.randomSecret();
        UserEntity update = new UserEntity();
        update.setId(userId);
        update.setTotpSecret(secret);
        update.setTotpEnabled(false);
        update.setTotpBoundAt(null);
        update.setTotpLastStep(null);
        update.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(update);
        log.info("两步验证：已生成绑定密钥（尚未启用）userId={}", userId);
        return new TotpSetupView(secret, Totp.otpauthUri(issuer, maskPhone(user.getPhone()), secret));
    }

    /**
     * 第二步：用一次正确的动态码确认绑定，启用并**一次性**返回恢复码明文。
     *
     * <p>恢复码明文只有这一次机会拿到 —— 库里存的是 SHA-256，之后没有任何接口能再查。
     * 前端必须在这一步明确提示用户保存下来。
     */
    @Transactional
    public TotpEnableView enable(Long userId, String code) {
        UserEntity user = requireUser(userId);
        if (user.isTwoFactorEnabled()) {
            throw BizException.businessRule("两步验证已启用");
        }
        String secret = user.getTotpSecret();
        if (secret == null || secret.isBlank()) {
            throw BizException.businessRule("尚未生成绑定密钥，请先调用 setup 获取密钥");
        }
        long step = verifyCode(secret, code);
        if (step < 0) {
            throw BizException.totpInvalid("验证码不正确，请确认认证器 App 里的 6 位数字与服务器时间一致");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserEntity update = new UserEntity();
        update.setId(userId);
        update.setTotpEnabled(true);
        update.setTotpBoundAt(now);
        // 把"完成绑定的这一枚码"的时间步也记下：它已经用掉了，不能转身再拿去登录
        update.setTotpLastStep(step);
        update.setUpdatedAt(now);
        userMapper.updateById(update);

        // ★ 启用即吊销该账号的全部刷新令牌（包括本次登录刚拿到的那一枚）。
        //
        // 不吊销的话，"启用之前签发的刷新令牌"能把第二步绕开整整 7 天 ——
        // 它的 tfv 声明为 true，因为签发时该账号确实还没有这项要求。
        // 吊销之后用户必须重新登录一次（那次就会要求验证码）；
        // 这个"被打断一次"正是开启一道新的安全措施时应有的代价。
        revokeAllRefreshTokens(userId);

        List<String> plainCodes = issueRecoveryCodes(userId, now);
        log.info("两步验证：已启用 userId={}，下发恢复码 {} 枚（并已吊销存量刷新令牌）",
                userId, plainCodes.size());
        return new TotpEnableView(plainCodes);
    }

    /**
     * 解绑：动态码与恢复码二选一。
     *
     * <p>手机丢了、认证器没导出的人只能靠恢复码关掉它 —— 这正是恢复码存在的首要意义。
     * 两条路都走完就清空全部相关字段与恢复码，回到"未启用"的干净状态。
     */
    @Transactional
    public void disable(Long userId, String code, String recoveryCode) {
        UserEntity user = requireUser(userId);
        if (!user.isTwoFactorEnabled()) {
            throw BizException.businessRule("当前未启用两步验证");
        }
        boolean dynamicCodeGiven = code != null && !code.isBlank();
        boolean recoveryCodeGiven = recoveryCode != null && !recoveryCode.isBlank();
        if (!dynamicCodeGiven && !recoveryCodeGiven) {
            throw BizException.validation("请提供当前动态码或一枚恢复码");
        }

        if (dynamicCodeGiven) {
            assertAndAdvanceStep(user, code);
        } else {
            consumeRecoveryCode(user, recoveryCode);
        }

        UserEntity update = new UserEntity();
        update.setId(userId);
        update.setTotpEnabled(false);
        // 密钥与步号一起清掉：留着密钥等于留着一个"仍然有效的第二因子"，
        // 而用户已经明确表示不要它了（且下次 setup 会生成新的）。
        clearSecretFields(update);
        update.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(update);
        deleteAllRecoveryCodes(userId);
        // 解绑同样吊销：若这次解绑走的是"用恢复码关掉"的应急路径（手机丢了、
        // 无法进一步确认操作者身份），把手上的既有会话一并清掉是更保守的选择。
        revokeAllRefreshTokens(userId);
        log.warn("两步验证：已关闭 userId={}（方式={}，并已吊销存量刷新令牌）",
                userId, dynamicCodeGiven ? "动态码" : "恢复码");
    }

    /**
     * 管理员重置（最后手段）：两个因子都丢了（手机丢了、恢复码也丢了）时，由平台管理员清除。
     *
     * <p>调用方（controller）必须已校验"操作者是 ADMIN"，本方法只负责数据动作。
     * 刻意**不**在这里做权限判断：越权的判定只放在一个地方，两处各判一次迟早会分叉。
     */
    @Transactional
    public void resetByAdmin(Long targetUserId) {
        UserEntity user = userMapper.selectById(targetUserId);
        if (user == null) {
            throw BizException.notFound("账号");
        }
        UserEntity update = new UserEntity();
        update.setId(targetUserId);
        update.setTotpEnabled(false);
        clearSecretFields(update);
        update.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userMapper.updateById(update);
        deleteAllRecoveryCodes(targetUserId);
        log.warn("两步验证：已被管理员重置 targetUserId={}（重置前 enabled={}）",
                targetUserId, user.isTwoFactorEnabled());
    }

    // ---------------- 登录校验 ----------------

    /**
     * 登录时校验两步验证。**未启用时直接放行**（这是绝大多数账号的路径）。
     *
     * <p>失败一律抛异常（由调用方所在事务回滚），成功则推进"已用时间步"或消耗一枚恢复码。
     *
     * @param totpCode     动态码；可为空
     * @param recoveryCode 恢复码；与动态码二选一（两者都给时以动态码为准 —— 正常路径优先）
     */
    @Transactional
    public void assertSatisfiedAtLogin(UserEntity user, String totpCode, String recoveryCode) {
        if (!user.isTwoFactorEnabled()) {
            return;
        }
        boolean dynamicCodeGiven = totpCode != null && !totpCode.isBlank();
        boolean recoveryCodeGiven = recoveryCode != null && !recoveryCode.isBlank();
        if (!dynamicCodeGiven && !recoveryCodeGiven) {
            // 与"码错了"用不同的错误码：前者是"该输第二步了"，后者是"输错了"，
            // 前端的处置完全不同（换页面 vs 清空重输）。
            throw BizException.totpRequired("该账号已启用两步验证，请输入认证器 App 中的 6 位验证码");
        }
        if (dynamicCodeGiven) {
            assertAndAdvanceStep(user, totpCode);
        } else {
            consumeRecoveryCode(user, recoveryCode);
        }
    }

    // ---------------- 内部实现 ----------------

    /**
     * 校验动态码并**原子地**推进已用时间步。
     *
     * <p>为什么要条件更新而不是"先读再写"：两个并发请求拿着同一枚码同时到达时，
     * 先读再写会让两个请求都读到旧的 lastStep、都判断为通过，于是同一枚码被用了两次。
     * 条件写入把判定交给数据库的行锁（{@code WHERE last_step IS NULL OR last_step < ?}），
     * 影响行数为 0 就说明"有人抢先用了这一步"。
     */
    private void assertAndAdvanceStep(UserEntity user, String code) {
        String secret = user.getTotpSecret();
        if (secret == null || secret.isBlank()) {
            // 数据不一致（enabled=1 但没有密钥）：宁可报错也不能放行 —— 放行等于 2FA 静默失效
            log.error("两步验证：账号已启用但密钥为空，拒绝登录 userId={}", user.getId());
            throw new BizException(ErrorCode.INTERNAL, "两步验证配置异常，请联系平台管理员");
        }
        long step = verifyCode(secret, code);
        if (step < 0) {
            throw BizException.totpInvalid("验证码不正确或已失效");
        }
        int updated = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getTotpLastStep, step)
                .eq(UserEntity::getId, user.getId())
                .and(w -> w.isNull(UserEntity::getTotpLastStep).or().lt(UserEntity::getTotpLastStep, step)));
        if (updated == 0) {
            throw BizException.totpInvalid("该验证码已被使用，请等待认证器刷新后重试");
        }
    }

    /**
     * 消耗一枚恢复码。**只有校验通过才标记已用** ——
     * 否则任何人都可以用错误输入把某个账号的恢复码一枚枚耗光，那是比不设恢复码更糟的结果。
     */
    private void consumeRecoveryCode(UserEntity user, String recoveryCode) {
        String normalized = normalizeRecoveryCode(recoveryCode);
        if (normalized == null) {
            throw BizException.totpInvalid("恢复码格式不正确");
        }
        String hash = sha256(normalized);
        List<TotpRecoveryCodeEntity> candidates = recoveryCodeMapper.selectList(
                Wrappers.<TotpRecoveryCodeEntity>lambdaQuery()
                        .eq(TotpRecoveryCodeEntity::getUserId, user.getId())
                        .isNull(TotpRecoveryCodeEntity::getUsedAt));
        for (TotpRecoveryCodeEntity candidate : candidates) {
            if (!hash.equals(candidate.getCodeHash())) {
                continue;
            }
            // 同样用条件更新：并发提交同一枚恢复码时，只有一次能把 used_at 从 NULL 改掉
            int updated = recoveryCodeMapper.update(null,
                    Wrappers.<TotpRecoveryCodeEntity>lambdaUpdate()
                            .set(TotpRecoveryCodeEntity::getUsedAt, LocalDateTime.now(ZoneOffset.UTC))
                            .eq(TotpRecoveryCodeEntity::getId, candidate.getId())
                            .isNull(TotpRecoveryCodeEntity::getUsedAt));
            if (updated == 0) {
                throw BizException.totpInvalid("该恢复码已被使用");
            }
            log.warn("两步验证：已消耗一枚恢复码 userId={}，剩余 {} 枚",
                    user.getId(), Math.max(0, candidates.size() - 1));
            return;
        }
        throw BizException.totpInvalid("恢复码无效或已被使用");
    }

    private long verifyCode(String secret, String code) {
        return Totp.match(secret, code, System.currentTimeMillis() / 1000L,
                Totp.DEFAULT_STEP_SECONDS, Totp.DEFAULT_DIGITS, Totp.DEFAULT_WINDOW_STEPS);
    }

    /**
     * 生成并落库若干枚恢复码，返回明文（**调用方负责一次性交付给用户，之后无处可查**）。
     *
     * <p>生成前先删掉该账号的旧恢复码：否则反复"解绑→重绑"会让未用恢复码越积越多，
     * 而每一枚都是一个长期有效的后备入口。
     */
    private List<String> issueRecoveryCodes(Long userId, LocalDateTime now) {
        deleteAllRecoveryCodes(userId);
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            byte[] raw = new byte[RECOVERY_CODE_BYTES];
            SECURE_RANDOM.nextBytes(raw);
            String encoded = Base32.encode(raw);
            String grouped = encoded.substring(0, 4) + "-" + encoded.substring(4);
            plain.add(grouped);

            TotpRecoveryCodeEntity entity = new TotpRecoveryCodeEntity();
            entity.setId(idGenerator.nextId());
            entity.setUserId(userId);
            entity.setCodeHash(sha256(encoded));
            entity.setCreatedAt(now);
            recoveryCodeMapper.insert(entity);
        }
        return plain;
    }

    private int remainingRecoveryCodes(Long userId) {
        Long count = recoveryCodeMapper.selectCount(Wrappers.<TotpRecoveryCodeEntity>lambdaQuery()
                .eq(TotpRecoveryCodeEntity::getUserId, userId)
                .isNull(TotpRecoveryCodeEntity::getUsedAt));
        return count == null ? 0 : Math.toIntExact(count);
    }

    private void deleteAllRecoveryCodes(Long userId) {
        recoveryCodeMapper.delete(Wrappers.<TotpRecoveryCodeEntity>lambdaQuery()
                .eq(TotpRecoveryCodeEntity::getUserId, userId));
    }

    /**
     * 吊销该账号**全部**刷新令牌。
     *
     * <p>标记为已吊销而不是物理删除：留着才能回答"这枚令牌为什么失效"，
     * 与 {@code AuthService.logout} 同一口径。重复执行是幂等的（再把 1 写一遍），
     * 因此不需要先判状态。
     */
    private void revokeAllRefreshTokens(Long userId) {
        refreshTokenMapper.update(null, Wrappers.<RefreshTokenEntity>lambdaUpdate()
                .set(RefreshTokenEntity::getRevoked, 1)
                .eq(RefreshTokenEntity::getUserId, userId));
    }

    /**
     * 清空 TOTP 相关字段。
     *
     * <p>必须走 {@code LambdaUpdateWrapper.set(column, null)} —— MyBatis-Plus 的
     * {@code updateById} 默认只更新非 null 字段，<b>传 null 等于"这一列不动"</b>，
     * 于是"解绑"会静默失败：接口返回成功，库里密钥还在，用户以为自己关掉了。
     * 这是本项目已经踩过的坑（见 PLAN 铁律 6）。
     */
    private void clearSecretFields(UserEntity update) {
        userMapper.update(update, Wrappers.<UserEntity>lambdaUpdate()
                .eq(UserEntity::getId, update.getId())
                .set(UserEntity::getTotpSecret, null)
                .set(UserEntity::getTotpBoundAt, null)
                .set(UserEntity::getTotpLastStep, null));
    }

    /** 恢复码归一化：忽略分组符与大小写，与生成时"按 4-4 分组展示"的形态对齐 */
    private static String normalizeRecoveryCode(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("-", "").replace(" ", "").trim().toUpperCase();
        if (cleaned.length() != RECOVERY_CODE_BYTES * 8 / 5) {
            return null;
        }
        return cleaned;
    }

    private UserEntity requireUser(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("账号");
        }
        return user;
    }

    /** 与 {@code AuthService.sha256} 同一算法；这里独立实现，一致性由冒烟里的"同一枚恢复码不能两次通过"守住 */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.INTERNAL, "哈希算法不可用", ex);
        }
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "账号";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
