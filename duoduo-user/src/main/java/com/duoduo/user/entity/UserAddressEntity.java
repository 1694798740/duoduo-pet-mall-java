package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 收货地址（A8）。
 *
 * <p>设计说明：
 * <ul>
 *   <li><b>省 / 市 / 区拆开存</b>，不做成一个 {@code address} 大字符串：
 *       下单时确实只要一段拼好的文本，但地址簿是「长期资产」，
 *       拆开存才支持后续按城市算运费、按省筛选等能力。
 *       拼接统一走 {@link com.duoduo.user.service.AddressService#fullAddress}，不散落到各调用方。</li>
 *   <li><b>{@code isDefault} 是「标记」而非「外键」</b>：同一用户至多一条为 1，
 *       由服务层在同一事务内「先清旧、再置新」保证（见 {@code AddressService#clearDefault}）。
 *       不建唯一索引的原因见 {@code docs/sql/01-duoduo-user.sql} 的注释 ——
 *       {@code UNIQUE(user_id, is_default)} 会反过来把非默认地址限制成只有一条。</li>
 *   <li><b>手机号存明文、展示时脱敏</b>：收货人的电话是给快递员打电话用的，
 *       商家侧发货也必须拿到完整号码，因此与 {@code t_user.phone} 一样落明文，
 *       脱敏只发生在对外响应里（由服务层决定，不依赖前端自觉）。</li>
 * </ul>
 */
@TableName("t_user_address")
public class UserAddressEntity {

    @TableId
    private Long id;
    private Long userId;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detail;
    /** 家 / 公司 / 学校，仅供展示 */
    private String label;
    private Integer isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
