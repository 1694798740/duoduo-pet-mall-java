package com.duoduo.ai.butler.service;

import com.duoduo.ai.butler.dto.ButlerViews.CouponItem;
import com.duoduo.ai.butler.dto.ButlerViews.CouponListData;
import com.duoduo.ai.client.CouponClient;
import com.duoduo.ai.client.dto.ButlerCouponDtos.CouponView;
import com.duoduo.ai.route.ResolvedRoute;
import com.duoduo.common.api.ApiResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 券包查询服务（阶段二 · get_user_coupons 的业务实现）。
 *
 * <p><b>金额原样透传</b>：门槛、满减额、折率、封顶全部是营销域算好的整数分。
 * 这里不做任何四舍五入或「元/分」换算 —— 卡片上的数字必须与结算页完全一致，
 * 而结算页用的就是同一份 {@code CouponView}。
 *
 * <p><b>不返回领券中心</b>：可领取的模板属于「营销活动」，与本工具「我的券包」是两回事。
 * 用户问「有什么券能领」时，正确动作是导航到 COUPONS 页面，而不是让 AI 复述活动列表 ——
 * 活动有库存与时间窗，AI 转述一份快照只会和页面上的事实不一致。
 */
@Service
public class CouponQueryService {

    private static final int MAX_LIMIT = 20;
    private static final Set<String> STATUSES = Set.of("UNUSED", "LOCKED", "USED", "EXPIRED");

    private final CouponClient couponClient;
    private final NavigationService navigation;

    public CouponQueryService(CouponClient couponClient, NavigationService navigation) {
        this.couponClient = couponClient;
        this.navigation = navigation;
    }

    /**
     * 查询我的券包。
     *
     * @param status UNUSED / LOCKED / USED / EXPIRED；null = 全部
     * @param limit  返回条数上限（1~20）
     */
    public CouponListData load(Long userId, String status, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        String normalized = normalizeStatus(status);

        List<CouponView> all = unwrap(couponClient.mine(userId, normalized), "我的券包");
        ResolvedRoute coupons = navigation.route("COUPONS", Map.of());

        List<CouponItem> items = all.stream()
                .limit(size)
                .map(view -> toItem(view, coupons))
                .toList();
        return new CouponListData(items, all.size());
    }

    private CouponItem toItem(CouponView view, ResolvedRoute coupons) {
        return new CouponItem(
                view.templateId(),
                view.code(),
                view.name(),
                view.type(),
                view.typeLabel(),
                view.thresholdAmount(),
                view.discountAmount(),
                view.discountPercent(),
                view.maxDiscountAmount(),
                view.status(),
                view.statusLabel(),
                view.expireAt(),
                // 我的券包里的券都已经领过，不可能再领一次
                false,
                coupons.webPath(),
                coupons.miniPath());
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new IllegalArgumentException("券状态不合法：" + status);
        }
        return value;
    }

    private static <T> T unwrap(ApiResponse<T> response, String what) {
        if (response == null || response.data() == null) {
            throw new IllegalStateException(what + "返回空数据");
        }
        return response.data();
    }
}
