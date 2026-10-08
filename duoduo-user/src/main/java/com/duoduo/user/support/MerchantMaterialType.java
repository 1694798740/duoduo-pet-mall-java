package com.duoduo.user.support;

import java.util.Arrays;

/**
 * 入驻材料清单（E10：6 类，提交时逐类校验齐全）。
 *
 * <p>材料校验是<b>存在性</b>校验（每类必须有一份），不做内容识别 ——
 * 图片内容是否与类目相符属于运营的人审职责（E11 审核台），服务端越俎代庖
 * 反而会造成"机器放行"的假安全感。
 */
public enum MerchantMaterialType {

    BUSINESS_LICENSE("营业执照"),
    LEGAL_ID_FRONT("法人身份证人像面"),
    LEGAL_ID_BACK("法人身份证国徽面"),
    BANK_ACCOUNT("对公银行账户证明"),
    OPERATION_COMMITMENT("经营承诺书"),
    INDUSTRY_PERMIT("行业经营许可证");

    /** 提交时必须齐全的清单 = 全部 6 类 */
    public static final MerchantMaterialType[] REQUIRED = values();

    private final String label;

    MerchantMaterialType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析客户端传入的类型名；未知类型直接拒绝（不宽容 —— 清单是封闭的） */
    public static MerchantMaterialType parse(String raw) {
        for (MerchantMaterialType t : values()) {
            if (t.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的材料类型: " + raw + "，允许值: "
                + Arrays.toString(values()));
    }
}
