package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.exception.BizException;
import com.duoduo.user.client.AccountDeletionClients;
import com.duoduo.user.client.dto.AccountDeletionDtos.PersonalDataCleanupResult;
import com.duoduo.user.dto.UserDtos.BlockedItemView;
import com.duoduo.user.dto.UserDtos.DeleteAccountView;
import com.duoduo.user.dto.UserDtos.DeleteBlockersView;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMemberEntity;
import com.duoduo.user.entity.PetProfileEntity;
import com.duoduo.user.entity.TotpRecoveryCodeEntity;
import com.duoduo.user.entity.UserAddressEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMemberMapper;
import com.duoduo.user.mapper.PetProfileMapper;
import com.duoduo.user.mapper.RefreshTokenMapper;
import com.duoduo.user.mapper.TotpRecoveryCodeMapper;
import com.duoduo.user.mapper.UserAddressMapper;
import com.duoduo.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * C7 · 账号注销。
 *
 * <h2>设计基线：软注销 + 匿名化，不做物理删号</h2>
 * 《用户隐私保护指引》4.2 承诺了注销，但删号这件事在本项目里**不能做成物理删除**：
 * <ul>
 *   <li>《电子商务法》要求交易信息自交易完成起留存不少于三年 —— 订单、支付、售后、
 *       审计日志都要按 {@code user_id} 关联，物理删行会把这些记录一起毁掉；</li>
 *   <li>店铺主体（{@code t_merchant.owner_user_id}）、评价与分享内容都挂在 user id 上。</li>
 * </ul>
 * 因此注销的语义是「<b>人没了，记录还在</b>」：PII（手机号、姓名、身份证掩码、openid、
 * TOTP 密钥、口令）一律清空或改写，交易与审计行本体保留。
 *
 * <h2>两段式状态机</h2>
 * <pre>
 *   ACTIVE ──申请（密码校验 + 预检通过）──► CANCELLING ──冷静期到点 + 五域清理成功──► DELETED
 *      ▲                                      │
 *      └──────────撤销（手机号 + 密码）─────────┘
 * </pre>
 * 冷静期默认 7 天（{@code duoduo.account-deletion.grace-days}），期间登录停用 ——
 * 「注销了还能继续买东西」是明显的逻辑漏洞，但撤销不能因此要求登录态（那时已无会话），
 * 所以撤销端点用「手机号 + 密码」重新验证身份。
 *
 * <h2>为什么先清理、后置 DELETED</h2>
 * 执行顺序刻意是「五域个人数据清理 → <b>全部成功</b> → 才把本行置 DELETED」。
 * 反过来（先置 DELETED 再清理）一旦清理失败，行已标记为完成，下一轮调度不会再扫到它，
 * 个人数据就永远留在五个库里 —— 而且没有任何日志能提示这件事。
 * 现在的顺序下，失败只是「保持 CANCELLING + 记 delete_error + 下轮重试」，
 * {@code account_status} 是唯一的总开关，天然幂等、可重入、可断点恢复。
 *
 * <h2>并发：撤销 vs 到期执行</h2>
 * 两者都可能在同一秒发生（用户第 7 天 23:59 点撤销，调度 00:00 执行）。
 * 双方都以 {@code account_status} 的 CAS 更新为前提：撤销只接受
 * {@code CANCELLING→ACTIVE}，执行只接受 {@code CANCELLING→DELETED}，
 * 后到者更新 0 行即视为对方已处理，直接返回，不报错、不覆盖。
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    public static final String ACCOUNT_ACTIVE = "ACTIVE";
    public static final String ACCOUNT_CANCELLING = "CANCELLING";
    public static final String ACCOUNT_DELETED = "DELETED";

    /**
     * 已注销账号的手机号改写前缀。
     *
     * <p>两个约束共同决定了这个写法：
     * <ul>
     *   <li>必须<b>非空且唯一</b>：{@code uk_user_phone} 是唯一索引，留空会让多个已注销
     *       账号互相撞键；</li>
     *   <li>必须<b>放得进 VARCHAR(20)</b>：雪花 id 最长 19 位，加一个字符正好 20 ——
     *       所以用 {@code 'x'} 而不是 {@code "del_"}（后者 4 + 19 = 23 会溢出，
     *       MySQL 严格模式下直接报错，且这个错只会在执行注销时暴露）。</li>
     * </ul>
     * 副作用是同一个手机号在注销后可重新注册 —— 这正是《个人信息保护法》对
     * 「删除」的预期：数据没了，标识应当可复用。
     */
    private static final String DELETED_PHONE_PREFIX = "x";

    private static final String DELETED_NICKNAME = "已注销用户";

    private final UserMapper userMapper;
    private final UserAddressMapper addressMapper;
    private final PetProfileMapper petProfileMapper;
    private final TotpRecoveryCodeMapper recoveryCodeMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantMemberMapper merchantMemberMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountDeletionClients clients;

    @Value("${duoduo.account-deletion.grace-days:7}")
    private int graceDays;

    public AccountDeletionService(UserMapper userMapper,
                                  UserAddressMapper addressMapper,
                                  PetProfileMapper petProfileMapper,
                                  TotpRecoveryCodeMapper recoveryCodeMapper,
                                  RefreshTokenMapper refreshTokenMapper,
                                  MerchantMapper merchantMapper,
                                  MerchantMemberMapper merchantMemberMapper,
                                  PasswordEncoder passwordEncoder,
                                  AccountDeletionClients clients) {
        this.userMapper = userMapper;
        this.addressMapper = addressMapper;
        this.petProfileMapper = petProfileMapper;
        this.recoveryCodeMapper = recoveryCodeMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.merchantMapper = merchantMapper;
        this.merchantMemberMapper = merchantMemberMapper;
        this.passwordEncoder = passwordEncoder;
        this.clients = clients;
    }

    // ------------------------------------------------------------------ 查询

    /** 当前注销状态；从未申请过返回 {@link #ACCOUNT_ACTIVE} 的空视图（scheduledAt 为 null） */
    public DeleteAccountView status(Long userId) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.unauthorized("账号不存在");
        }
        return new DeleteAccountView(
                accountStatusOf(entity),
                entity.getDeleteScheduledAt() == null ? null : entity.getDeleteScheduledAt().toString(),
                graceDays,
                List.of());
    }

    /**
     * 预检：有哪些事没做完会导致注销被挡下。
     *
     * <p>全部走 {@code dryRun}，不写任何数据 —— 这是「提交前先告诉用户卡在哪」的
     * 唯一实现方式，也是端侧「注销前自检」弹窗的数据来源。
     */
    public DeleteBlockersView precheck(Long userId) {
        return new DeleteBlockersView(!blockedItems(userId).isEmpty(), blockedItems(userId));
    }

    // ------------------------------------------------------------------ 申请

    /**
     * 提交注销申请。
     *
     * @return 成功时 {@code accountStatus=CANCELLING} 且带到期时间；
     *         被业务挡住时 {@code accountStatus=ACTIVE} 且 {@code blockedItems} 非空
     *         （控制器据此回 409，服务层不抛异常 —— 阻塞是正常业务结果，不是错误）
     */
    @Transactional
    public DeleteAccountView request(Long userId, String rawPassword, String reason) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.unauthorized("账号不存在");
        }
        if (ACCOUNT_DELETED.equals(accountStatusOf(entity))) {
            throw BizException.conflict("账号已注销，无法重复操作");
        }
        if (ACCOUNT_CANCELLING.equals(accountStatusOf(entity))) {
            // 幂等：重复提交不报错，直接回当前状态（用户刷新页面的场景）
            return new DeleteAccountView(ACCOUNT_CANCELLING,
                    entity.getDeleteScheduledAt() == null ? null : entity.getDeleteScheduledAt().toString(),
                    graceDays, List.of());
        }
        // 密码必须校验：注销不可逆，只凭登录态就能注销，等于没锁屏的手机被人点两下就没了
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, entity.getPasswordHash())) {
            throw BizException.unauthorized("密码不正确");
        }

        List<BlockedItemView> blockers = blockedItems(userId);
        if (!blockers.isEmpty()) {
            return new DeleteAccountView(ACCOUNT_ACTIVE, null, graceDays, blockers);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime scheduled = now.plusDays(Math.max(1, graceDays));
        // CAS：只接受 ACTIVE → CANCELLING，挡住「两个页面同时点申请」与「撤销竞态」
        int updated = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getAccountStatus, ACCOUNT_CANCELLING)
                .set(UserEntity::getDeleteRequestedAt, now)
                .set(UserEntity::getDeleteScheduledAt, scheduled)
                .set(UserEntity::getDeleteReason, reason)
                .set(UserEntity::getUpdatedAt, now)
                .eq(UserEntity::getId, userId)
                .eq(UserEntity::getAccountStatus, ACCOUNT_ACTIVE));
        if (updated == 0) {
            throw BizException.conflict("账号状态刚发生变化，请刷新后重试");
        }

        // 立即掐断所有端的长会话：access 令牌 15 分钟自然过期，refresh 全部吊销。
        // 不清小程序 storage 由端侧负责（见 miniprogram clearSession）。
        int revoked = refreshTokenMapper.revokeAllByUser(userId);
        log.info("账号注销申请已受理 userId={} 冷静期至 {} 吊销刷新令牌 {} 枚", userId, scheduled, revoked);
        return new DeleteAccountView(ACCOUNT_CANCELLING, scheduled.toString(), graceDays, List.of());
    }

    // ------------------------------------------------------------------ 撤销

    /**
     * 撤销注销申请（冷静期内）。
     *
     * <p><b>不要求登录态</b>：提交注销后登录已被停用，用户手里没有任何有效会话；
     * 若这里要求登录态，注销就成了不可逆的。改用「手机号 + 密码」重新验证身份，
     * 错误提示与登录完全一致（不区分账号不存在与密码错误），避免手机号枚举。
     */
    @Transactional
    public void cancel(String phone, String rawPassword) {
        UserEntity entity = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getPhone, phone)
                .last("LIMIT 1"));
        if (entity == null || rawPassword == null
                || !passwordEncoder.matches(rawPassword, entity.getPasswordHash())) {
            throw BizException.unauthorized("手机号或密码不正确");
        }
        cancelByUserId(entity.getId(), rawPassword);
    }

    /**
     * 已登录态下的撤销（便捷入口）：密码校验 + 状态 CAS。
     *
     * <p>与 {@link #cancel(String, String)} 共用同一段 CAS，差别只在身份来源 ——
     * 一个来自手机号 + 密码，一个来自登录态。密码校验两边都做：登录态可能是
     * 注销前签发的，不能因为"还拿着旧令牌"就免掉二次确认。
     */
    @Transactional
    public void cancelByUserId(Long userId, String rawPassword) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            throw BizException.unauthorized("账号不存在");
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, entity.getPasswordHash())) {
            throw BizException.unauthorized("密码不正确");
        }
        if (!ACCOUNT_CANCELLING.equals(accountStatusOf(entity))) {
            throw BizException.conflict("账号当前不在注销流程中，无需撤销");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getAccountStatus, ACCOUNT_ACTIVE)
                .set(UserEntity::getDeleteRequestedAt, null)
                .set(UserEntity::getDeleteScheduledAt, null)
                .set(UserEntity::getDeleteReason, null)
                .set(UserEntity::getUpdatedAt, now)
                .eq(UserEntity::getId, entity.getId())
                .eq(UserEntity::getAccountStatus, ACCOUNT_CANCELLING));
        if (updated == 0) {
            // 只有一种可能：调度在同一刻已把它执行成 DELETED
            throw BizException.conflict("账号已完成注销，无法撤销；可用原手机号重新注册");
        }
        log.info("账号注销申请已撤销 userId={}", entity.getId());
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 扫一遍到期的注销申请并执行（{@code AccountDeletionJob} 调用）。
     *
     * <p><b>单个失败不影响其他用户</b>：一个用户清理失败只是他自己的行保持
     * {@code CANCELLING} 下轮重试，不能让它把整轮扫描带崩 —— 那会让后面所有人的
     * 注销都停摆，而且异常被吞在调度里，表面看任务还在跑。
     *
     * @return 本轮成功执行注销的用户数
     */
    public int executeDue() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<UserEntity> due = userMapper.selectList(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getAccountStatus, ACCOUNT_CANCELLING)
                .le(UserEntity::getDeleteScheduledAt, now)
                .last("LIMIT 100"));
        int done = 0;
        for (UserEntity user : due) {
            try {
                if (executeOne(user)) {
                    done++;
                }
            } catch (RuntimeException ex) {
                // 记 error 但不改状态：状态是总开关，改了就会丢掉重试依据
                log.error("账号注销执行失败 userId={}（下轮调度将重试）", user.getId(), ex);
            }
        }
        return done;
    }

    /**
     * 执行单个用户的注销。幂等，可重复调用。
     *
     * @return true = 本次真正执行了注销；false = 期间被撤销，跳过
     */
    @Transactional
    public boolean executeOne(UserEntity user) {
        Long userId = user.getId();

        // ① 五域个人数据清理。任一域失败直接抛异常 → 外层保持 CANCELLING，下轮重试。
        PersonalDataCleanupResult trade = clients.trade().cleanupPersonalData(userId, false).data();
        PersonalDataCleanupResult afterSale = clients.afterSale().cleanupPersonalData(userId, false).data();
        PersonalDataCleanupResult coupon = clients.coupon().cleanupPersonalData(userId, false).data();
        PersonalDataCleanupResult social = clients.social().cleanupPersonalData(userId, false).data();
        PersonalDataCleanupResult listing = clients.listing().cleanupPersonalData(userId, false).data();

        // ② 本模块数据：纯个人主动数据，物理删
        int addresses = addressMapper.delete(Wrappers.<UserAddressEntity>lambdaQuery()
                .eq(UserAddressEntity::getUserId, userId));
        int pets = petProfileMapper.delete(Wrappers.<PetProfileEntity>lambdaQuery()
                .eq(PetProfileEntity::getUserId, userId));
        int recoveryCodes = recoveryCodeMapper.delete(Wrappers.<TotpRecoveryCodeEntity>lambdaQuery()
                .eq(TotpRecoveryCodeEntity::getUserId, userId));
        // ACTIVE 的成员身份在申请阶段就被预检挡下，这里只兜历史脏数据
        int members = merchantMemberMapper.update(null, Wrappers.<MerchantMemberEntity>lambdaUpdate()
                .set(MerchantMemberEntity::getStatus, "DISABLED")
                .eq(MerchantMemberEntity::getUserId, userId)
                .eq(MerchantMemberEntity::getStatus, "ACTIVE"));

        // ③ 匿名化 + 置 DELETED（CAS）
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .set(UserEntity::getAccountStatus, ACCOUNT_DELETED)
                .set(UserEntity::getDeleteExecutedAt, now)
                .set(UserEntity::getUpdatedAt, now)
                // PII 清空 / 改写（手机号放唯一索引，口令置随机不可用值）
                .set(UserEntity::getPhone, DELETED_PHONE_PREFIX + userId)
                .set(UserEntity::getNickname, DELETED_NICKNAME)
                .set(UserEntity::getAvatar, null)
                .set(UserEntity::getRealName, null)
                .set(UserEntity::getIdCardMasked, null)
                .set(UserEntity::getWxOpenid, null)
                .set(UserEntity::getTotpSecret, null)
                .set(UserEntity::getTotpEnabled, false)
                .set(UserEntity::getPasswordHash, UUID.randomUUID().toString())
                .eq(UserEntity::getId, userId)
                .eq(UserEntity::getAccountStatus, ACCOUNT_CANCELLING));
        if (updated == 0) {
            // 撤销在同一刻赢了：五域清理是幂等的（DELETE/UPDATE），多做一次无副作用
            log.info("账号注销执行时发现已被撤销，跳过 userId={}", userId);
            return false;
        }

        log.info("账号注销已执行 userId={} 删地址 {} 宠物档案 {} 恢复码 {} 停用成员 {}；"
                        + "五域清理 trade(del={},mask={}) fulfilment(del={}) marketing(del={}) "
                        + "social(del={}) product(del={})",
                userId, addresses, pets, recoveryCodes, members,
                trade.deleted(), trade.masked(), afterSale.deleted(), coupon.deleted(),
                social.deleted(), listing.deleted());
        return true;
    }

    // ------------------------------------------------------------------ 内部

    /** 汇总本地 + 五域的阻塞项。全部只读（dryRun），不产生任何写操作。 */
    private List<BlockedItemView> blockedItems(Long userId) {
        List<BlockedItemView> items = new ArrayList<>();

        // 店铺主体身份：注销店主 = 店铺失去主体，必须先转让或关店
        MerchantEntity merchant = merchantMapper.selectOne(Wrappers.<MerchantEntity>lambdaQuery()
                .eq(MerchantEntity::getOwnerUserId, userId)
                .last("LIMIT 1"));
        if (merchant != null) {
            items.add(new BlockedItemView("MERCHANT", merchant.getName(),
                    "您是店铺「" + merchant.getName() + "」的店主，请先转让店铺或关店后再注销"));
        }
        MerchantMemberEntity member = merchantMemberMapper.selectOne(Wrappers.<MerchantMemberEntity>lambdaQuery()
                .eq(MerchantMemberEntity::getUserId, userId)
                .eq(MerchantMemberEntity::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (member != null) {
            MerchantEntity shop = merchantMapper.selectById(member.getMerchantId());
            String shopName = shop == null ? String.valueOf(member.getMerchantId()) : shop.getName();
            items.add(new BlockedItemView("MERCHANT", shopName,
                    "您还是店铺「" + shopName + "」的店员，请先退出店铺后再注销"));
        }

        // 五域 dryRun：只有 blocking 计数参与判定，deleted/masked 仅用于日志
        items.addAll(blockersOf(clients.trade().cleanupPersonalData(userId, true).data(),
                "ORDER", "笔未完成的订单（待付款 / 已付款 / 已发货）", "请先完成或取消这些订单"));
        items.addAll(blockersOf(clients.afterSale().cleanupPersonalData(userId, true).data(),
                "AFTER_SALE", "笔进行中的售后 / 退款", "请等售后结案后再注销"));
        items.addAll(blockersOf(clients.listing().cleanupPersonalData(userId, true).data(),
                "LISTING", "件在售闲置", "请先下架这些闲置"));
        // 营销 / 社交域不阻塞注销，这里仍然调用一次：让「清理会不会失败」在申请阶段就暴露
        clients.coupon().cleanupPersonalData(userId, true);
        clients.social().cleanupPersonalData(userId, true);
        return items;
    }

    private List<BlockedItemView> blockersOf(PersonalDataCleanupResult result, String type,
                                             String countSuffix, String advice) {
        if (result == null || result.blocking() <= 0) {
            return List.of();
        }
        return List.of(new BlockedItemView(type, result.blocking() + " " + countSuffix, advice));
    }

    /** 老库可能没有该列（迁移未执行时读出来是 null）——统一按 ACTIVE 处理 */
    private static String accountStatusOf(UserEntity entity) {
        return entity.getAccountStatus() == null || entity.getAccountStatus().isBlank()
                ? ACCOUNT_ACTIVE : entity.getAccountStatus();
    }
}
