package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.security.ip.IpRules;
import com.duoduo.common.security.ip.MerchantIpWhitelist;
import com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistRequest;
import com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistView;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.support.MerchantIpWhitelistPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * E-06 · 商家端 IP 白名单（店铺维度，店主自助配置）。
 *
 * <h2>这个功能最容易造成的伤害是什么</h2>
 * 不是「没拦住」，而是<b>把店主自己锁在门外</b>：店主在办公室配好白名单，
 * 回家或换个网络就再也进不了工作台，而修复入口恰好就长在那个进不去的页面里。三条防线：
 * <ol>
 *   <li><b>保存前自检</b>：新名单不包含当前请求来源 IP 时，必须显式 {@code confirmLockout=true}，
 *       否则 422 并直接告诉店主「保存后你会从当前网络进不来」（{@link #save}）；</li>
 *   <li><b>展示 currentIp</b>：页面把服务端看到的 IP 直接回显，不靠店主猜；</li>
 *   <li><b>平台救援通道</b>：{@link #clearByAdmin} 让运营能远程清空（留痕），
 *       这是唯一「页面进不去时仍能自救」的路径。</li>
 * </ol>
 *
 * <h2>为什么启用时必须至少一条规则</h2>
 * 网关对「键存在但值为空」是<b>失败关闭</b>（拒绝全部商家端访问）。若允许保存空名单，
 * 一次漏填就会把整家店挡在门外，而且页面上看起来「白名单已启用」——属于最难排查的那类故障。
 * 因此这里直接拒绝空名单启用；网关那道 fail-closed 只作为防御，不指望它兜业务错误。
 */
@Service
public class MerchantIpWhitelistService {

    /** 单店规则上限：够用（一个办公室出口 + 几个家庭 IP），也避免把 500 字符的列写爆 */
    static final int MAX_RULES = 20;

    private static final Logger log = LoggerFactory.getLogger(MerchantIpWhitelistService.class);

    private final MerchantMapper merchantMapper;
    private final MerchantIpWhitelistPublisher publisher;

    public MerchantIpWhitelistService(MerchantMapper merchantMapper,
                                      MerchantIpWhitelistPublisher publisher) {
        this.merchantMapper = merchantMapper;
        this.publisher = publisher;
    }

    /** 查看本店白名单（店主。） */
    public IpWhitelistView view(Long ownerUserId, String currentIp) {
        MerchantEntity store = requireOwnedStore(ownerUserId);
        return toView(store, currentIp, true);
    }

    /**
     * 保存本店白名单。
     *
     * @param currentIp 服务端看到的本次来源地址（网关 X-Real-IP）；用于锁死自检
     */
    @Transactional
    public IpWhitelistView save(Long ownerUserId, IpWhitelistRequest request, String currentIp) {
        MerchantEntity store = requireOwnedStore(ownerUserId);
        if (request == null || request.enabled() == null) {
            throw BizException.validation("必须明确是否启用 IP 白名单");
        }
        boolean enabled = request.enabled();
        List<String> rules = normalizeRules(request.rules());
        if (enabled && rules.isEmpty()) {
            throw BizException.businessRule(
                    "启用 IP 白名单至少要有一条规则（否则本店所有商家端访问都会被拒绝）");
        }
        if (enabled && currentIp != null && !currentIp.isBlank()
                && !IpRules.matchesAny(rules, currentIp)
                && !Boolean.TRUE.equals(request.confirmLockout())) {
            throw BizException.businessRule(
                    "当前网络（IP " + currentIp.trim() + "）不在新名单中，保存后你将无法进入商家端；"
                            + "请先把它加入名单，或确认后用「我了解风险」再次提交");
        }

        int before = store.getIpWhitelistEnabled() == null ? 0 : store.getIpWhitelistEnabled();
        String rulesText = enabled ? MerchantIpWhitelist.format(rules) : null;
        persist(store, enabled, rulesText);

        boolean published = publisher.publish(store.getId(), enabled, rules);
        if (!published) {
            // 库已经写成功（权威值已变），只是网关暂时读不到。如实返回，由页面提示「稍后生效」，
            // 不做「假装成功」—— 店主会以为已经拦住了，其实没有。
            log.warn("商家 IP 白名单已入库但未广播到网关 merchantId={} enabled={} rules={}",
                    store.getId(), enabled, rules);
        }
        log.info("店主 {} 修改商家 IP 白名单 merchantId={} {}→{}（{} 条规则，来源 {}）",
                ownerUserId, store.getId(), before, store.getIpWhitelistEnabled(), rules.size(), currentIp);
        return toView(store, currentIp, published);
    }

    /**
     * 平台救援：清空某店铺的白名单并关闭开关。
     *
     * <p>唯一用途是「店主把自己锁在门外」时的恢复。审计由控制器上的 {@code @Audited} 落库，
     * 这里额外带 note，写明是平台介入而不是店主自己关的。
     */
    @Transactional
    public IpWhitelistView clearByAdmin(Long merchantId) {
        MerchantEntity store = merchantMapper.selectById(merchantId);
        if (store == null) {
            throw BizException.notFound("商家");
        }
        store.setIpWhitelistEnabled(0);
        store.setIpWhitelist(null);
        persist(store, false, null);
        boolean published = publisher.publish(store.getId(), false, List.of());
        log.info("平台清空商家 IP 白名单 merchantId={}（网关副本{}）",
                merchantId, published ? "已同步" : "未同步，稍后由启动重发修复");
        return toView(store, null, published);
    }

    // ---------------- 内部 ----------------

    /**
     * 显式写回两个字段。
     *
     * <p><b>为什么不用 {@code updateById}</b>：MyBatis-Plus 默认字段策略是「null 不参与更新」，
     * 于是「关闭白名单」时 {@code ip_whitelist = null} 会被**静默跳过** ——
     * 库里留着旧规则，虽然 enabled=0 时网关不看它，但下次启用会把陈年 IP 一并放回来。
     * 关闭就必须真的清空，这里用 UpdateWrapper 显式 SET。
     *
     * <p>同时补上审计归属：{@code @Audited} 拦截器只能看到请求头里的租户（商家端常常没有），
     * 不显式写就会落成 tenant_id=0 —— 按店检索审计时查不到这条改动。
     */
    private void persist(MerchantEntity store, boolean enabled, String rulesText) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 用显式列名而不是 lambda：这段更新只涉及三列，LambdaUpdateWrapper 在无 Spring 上下文的
        // 单测里需要先初始化 TableInfo（否则抛 "can not find lambda cache"），
        // 为了一个三列的更新去给测试加一段 MyBatis 初始化并不值得。
        merchantMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MerchantEntity>()
                .eq("id", store.getId())
                .set("ip_whitelist_enabled", enabled ? 1 : 0)
                .set("ip_whitelist", rulesText)
                .set("updated_at", now));
        store.setIpWhitelistEnabled(enabled ? 1 : 0);
        store.setIpWhitelist(rulesText);
        store.setUpdatedAt(now);
        AuditTrail.tenant(store.getOwnerUserId());
        AuditTrail.target(store.getId(), "MERCHANT");
    }

    /**
     * 取「当前用户作为店主」的店铺。
     *
     * <p>店员调用一律 403：白名单决定「这张网能不能进店」，属于店主级安全边界，
     * 与店员权限（F-04 的操作级权限键）不是一回事。
     */
    private MerchantEntity requireOwnedStore(Long ownerUserId) {
        if (ownerUserId == null) {
            throw BizException.unauthorized("请先登录");
        }
        MerchantEntity store = merchantMapper.selectOne(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getOwnerUserId, ownerUserId)
                .last("LIMIT 1"));
        if (store == null) {
            throw BizException.forbidden("只有店主可以配置本店 IP 白名单");
        }
        return store;
    }

    /** 去空白、去重、校验形状；超过上限或形状不合法一律 400 并指出是哪一条 */
    private static List<String> normalizeRules(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String rule : raw) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String value = rule.trim();
            if (!IpRules.isWellFormed(value)) {
                throw BizException.validation("IP 规则格式不正确：" + value
                        + "（支持 1.2.3.4 / 10.0.0.0/8 / 192.168.1.*）");
            }
            unique.add(value);
        }
        if (unique.size() > MAX_RULES) {
            throw BizException.validation("IP 规则最多 " + MAX_RULES + " 条");
        }
        return new ArrayList<>(unique);
    }

    private static IpWhitelistView toView(MerchantEntity store, String currentIp, boolean published) {
        boolean enabled = store.getIpWhitelistEnabled() != null && store.getIpWhitelistEnabled() == 1;
        List<String> rules = MerchantIpWhitelist.parse(store.getIpWhitelist());
        return new IpWhitelistView(enabled, rules,
                currentIp == null || currentIp.isBlank() ? null : currentIp.trim(), published);
    }
}
