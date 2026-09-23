package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos.AddressSnapshot;
import com.duoduo.user.dto.UserDtos.AddressUpsertRequest;
import com.duoduo.user.dto.UserDtos.AddressView;
import com.duoduo.user.entity.UserAddressEntity;
import com.duoduo.user.mapper.UserAddressMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 收货地址服务（A8）。
 *
 * <p>三条业务规则，每条都有它要防的事：
 * <ol>
 *   <li><b>归属校验</b>（{@link #requireOwned}）：读 / 改 / 删 / 设默认一律带 {@code userId} 维度。
 *       他人地址与不存在的地址<b>返回同一个错误</b>（404）—— 若把「无权限」单独报 403，
 *       攻击者用 403 / 404 的差异就能枚举出「哪些 id 真实存在」，这是经典的越权探测手法。</li>
 *   <li><b>默认地址唯一</b>（{@link #clearDefault} + 各写方法）：同一用户至多一条 {@code is_default=1}。
 *       实现方式是「同一事务内先清旧、再置新」，不是数据库唯一索引 —— 原因见 DDL 注释：
 *       {@code UNIQUE(user_id, is_default)} 会把非默认地址也限制成至多一条，与需求相反。</li>
 *   <li><b>首个地址自动成为默认</b>：与宠物档案同一取舍。让用户建完地址还要再点一次「设为默认」
 *       纯属多余，而且一旦漏点，结算页会因为「没有默认地址」而无从预选。</li>
 * </ol>
 *
 * <p><b>删除默认地址时的顺延</b>：删掉默认项后把当前最早的一条顺延为默认，
 * 而不是留下「一个默认都没有」的状态。理由与宠物档案一致 ——
 * 「默认」是结算页预先勾选的依据，空着等于让用户每次都要手动选。
 */
@Service
public class AddressService {

    private static final Logger log = LoggerFactory.getLogger(AddressService.class);

    private final UserAddressMapper addressMapper;
    private final SnowflakeIdGenerator idGenerator;

    public AddressService(UserAddressMapper addressMapper, SnowflakeIdGenerator idGenerator) {
        this.addressMapper = addressMapper;
        this.idGenerator = idGenerator;
    }

    /** 地址簿列表：默认地址排最前，其余按创建时间正序（老的在前，符合「常用」直觉） */
    public List<AddressView> listByUser(Long userId) {
        return addressMapper.selectList(Wrappers.<UserAddressEntity>lambdaQuery()
                        .eq(UserAddressEntity::getUserId, userId)
                        .orderByDesc(UserAddressEntity::getIsDefault)
                        .orderByAsc(UserAddressEntity::getCreatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    /** 默认地址；没有默认地址时回退到最早创建的一条，再没有就返回 null（新用户） */
    public AddressView defaultOf(Long userId) {
        UserAddressEntity entity = findDefault(userId);
        if (entity == null) {
            List<UserAddressEntity> all = addressMapper.selectList(Wrappers.<UserAddressEntity>lambdaQuery()
                    .eq(UserAddressEntity::getUserId, userId)
                    .orderByAsc(UserAddressEntity::getCreatedAt));
            entity = all.isEmpty() ? null : all.get(0);
        }
        return entity == null ? null : toView(entity);
    }

    @Transactional
    public AddressView create(Long userId, AddressUpsertRequest request) {
        Long existingCount = addressMapper.selectCount(Wrappers.<UserAddressEntity>lambdaQuery()
                .eq(UserAddressEntity::getUserId, userId));
        // 首条自动默认：否则用户建完地址，结算页仍然没有可预选项
        boolean asDefault = existingCount == 0 || Boolean.TRUE.equals(request.isDefault());
        if (asDefault) {
            clearDefault(userId);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAddressEntity entity = new UserAddressEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        applyRequest(entity, request);
        entity.setIsDefault(asDefault ? 1 : 0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        addressMapper.insert(entity);
        log.info("收货地址创建 userId={} addressId={} default={}", userId, entity.getId(), asDefault);
        return toView(entity);
    }

    /**
     * 修改地址。
     *
     * <p>{@code isDefault=true} 时把默认转移到本地址；{@code false} / {@code null} 时<b>不动</b>默认归属 ——
     * 不存在「取消默认」这个动作，见 {@code AddressUpsertRequest} 的注释。
     */
    @Transactional
    public AddressView update(Long userId, Long addressId, AddressUpsertRequest request) {
        UserAddressEntity entity = requireOwned(userId, addressId);
        applyRequest(entity, request);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        if (Boolean.TRUE.equals(request.isDefault())) {
            clearDefault(userId);
            entity.setIsDefault(1);
        }

        addressMapper.updateById(entity);
        log.info("收货地址更新 userId={} addressId={}", userId, addressId);
        return toView(entity);
    }

    @Transactional
    public AddressView setDefault(Long userId, Long addressId) {
        requireOwned(userId, addressId);
        clearDefault(userId);
        UserAddressEntity entity = requireOwned(userId, addressId);
        entity.setIsDefault(1);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        addressMapper.updateById(entity);
        log.info("收货地址设为默认 userId={} addressId={}", userId, addressId);
        return toView(entity);
    }

    /** 删除地址；删掉的是默认项时，把当前最早的一条顺延为默认 */
    @Transactional
    public void delete(Long userId, Long addressId) {
        UserAddressEntity entity = requireOwned(userId, addressId);
        addressMapper.deleteById(addressId);

        if (entity.getIsDefault() != null && entity.getIsDefault() == 1) {
            List<UserAddressEntity> rest = addressMapper.selectList(Wrappers.<UserAddressEntity>lambdaQuery()
                    .eq(UserAddressEntity::getUserId, userId)
                    .orderByAsc(UserAddressEntity::getCreatedAt));
            if (!rest.isEmpty()) {
                UserAddressEntity next = rest.get(0);
                next.setIsDefault(1);
                next.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                addressMapper.updateById(next);
                log.info("收货地址删除后默认顺延 userId={} addressId={}", userId, next.getId());
            }
        }
        log.info("收货地址删除 userId={} addressId={}", userId, addressId);
    }

    /**
     * 服务间只读：按地址 ID 取下单快照。
     *
     * <p><b>这里的 userId 是「校验条件」而不是「归属来源」</b> ——
     * 与用户侧接口的区别必须说清楚：用户侧接口的 userId 取自登录态（客户端无法伪造），
     * 而这个方法由交易域调用，调用方传进来的 userId 来自它的登录态（经网关校验），
     * 用户域用它再校验一次地址归属。两处都校验，是因为它们各自都无法单独信任对方 ——
     * 交易域不能替用户域担保「这个地址是这人的」，用户域也不能假设交易域一定校验过。
     *
     * <p>返回 {@code null} 表示「地址不存在或不属于该用户」——
     * 对交易域而言这两种情况的处置完全一样（拒单并提示），因此不再细分错误码。
     */
    public AddressSnapshot snapshot(Long userId, Long addressId) {
        UserAddressEntity entity = addressMapper.selectById(addressId);
        if (entity == null || !entity.getUserId().equals(userId)) {
            log.warn("下单取地址快照失败：addressId={} 不存在或不属于 userId={}", addressId, userId);
            return null;
        }
        return new AddressSnapshot(entity.getId(), entity.getReceiverName(),
                entity.getReceiverPhone(), fullAddress(entity));
    }

    // ---------------- 内部 ----------------

    /**
     * 归属校验。他人地址与不存在的地址<b>抛出同一个异常</b>：
     * 区分 403 / 404 会让攻击者用返回码差异枚举出系统里真实存在的地址 ID。
     */
    private UserAddressEntity requireOwned(Long userId, Long addressId) {
        UserAddressEntity entity = addressMapper.selectById(addressId);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw BizException.notFound("收货地址");
        }
        return entity;
    }

    private UserAddressEntity findDefault(Long userId) {
        // 理论上至多一条；服务层已保证唯一，这里仍取 list 的首条而不假设「一定只有一条」——
        // 万一历史数据出现两条默认（人工改库、并发极端情况），查询也不该直接抛异常。
        List<UserAddressEntity> defaults = addressMapper.selectList(Wrappers.<UserAddressEntity>lambdaQuery()
                .eq(UserAddressEntity::getUserId, userId)
                .eq(UserAddressEntity::getIsDefault, 1)
                .orderByAsc(UserAddressEntity::getCreatedAt));
        return defaults.isEmpty() ? null : defaults.get(0);
    }

    /** 把当前用户的旧默认全部置 0；调用方必须与「置新默认」在同一事务内 */
    private void clearDefault(Long userId) {
        UserAddressEntity patch = new UserAddressEntity();
        patch.setIsDefault(0);
        addressMapper.update(patch, Wrappers.<UserAddressEntity>lambdaUpdate()
                .eq(UserAddressEntity::getUserId, userId)
                .eq(UserAddressEntity::getIsDefault, 1));
    }

    private void applyRequest(UserAddressEntity entity, AddressUpsertRequest request) {
        entity.setReceiverName(request.receiverName().trim());
        entity.setReceiverPhone(request.receiverPhone().trim());
        entity.setProvince(request.province().trim());
        entity.setCity(request.city().trim());
        entity.setDistrict(request.district().trim());
        entity.setDetail(request.detail().trim());
        entity.setLabel(trimToNull(request.label()));
    }

    /**
     * 拼接完整地址。**唯一定义处** —— 地址簿视图与下单快照都调它，
     * 保证「用户看到的地址」与「订单里存的地址」逐字相同。
     *
     * <p>拼接规则：省 + 市 + 区 + 明细之间直接相连不加空格（国内地址惯例），
     * 但当市名已包含在省名里时（直辖市，如「北京市 / 北京市」）只保留一次，
     * 否则会出现「北京市北京市朝阳区…」这种明显是程序拼错的地址。
     */
    public String fullAddress(UserAddressEntity entity) {
        StringBuilder sb = new StringBuilder();
        String province = safe(entity.getProvince());
        String city = safe(entity.getCity());
        String district = safe(entity.getDistrict());
        sb.append(province);
        if (!city.isEmpty() && !city.equals(province)) {
            sb.append(city);
        }
        sb.append(district).append(safe(entity.getDetail()));
        return sb.toString();
    }

    private AddressView toView(UserAddressEntity entity) {
        return new AddressView(
                entity.getId(),
                entity.getReceiverName(),
                entity.getReceiverPhone(),
                entity.getProvince(),
                entity.getCity(),
                entity.getDistrict(),
                entity.getDetail(),
                entity.getLabel(),
                fullAddress(entity),
                entity.getIsDefault() != null && entity.getIsDefault() == 1,
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
