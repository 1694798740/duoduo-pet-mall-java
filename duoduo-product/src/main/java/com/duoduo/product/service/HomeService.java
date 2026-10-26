package com.duoduo.product.service;

import com.duoduo.product.dto.ProductDtos.BrandView;
import com.duoduo.product.dto.ProductDtos.CategoryNode;
import com.duoduo.product.dto.ProductDtos.HomeView;
import com.duoduo.product.dto.ProductDtos.ListingCard;
import com.duoduo.product.dto.ProductDtos.ProductCard;
import com.duoduo.product.dto.ProductDtos.SpeciesPicks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页聚合。
 *
 * <p>为什么由服务端聚合而不是让前端发 5 个请求：首屏是转化的关键路径，
 * 5 个并发请求在弱网下的尾延迟由最慢的一个决定，且会放大网关与下游的 QPS。
 * 服务端聚合后只返回一次，客户端一次往返拿到首屏全部数据。
 *
 * <p>生产形态下这份结果会整体缓存在 Redis 中（TTL 60s + 后台刷新），
 * 首页 QPS 再高也不会穿透到数据库。
 */
@Service
public class HomeService {

    private static final int CARD_LIMIT = 8;
    private static final int PICKS_LIMIT = 4;
    private static final int SECONDHAND_LIMIT = 6;

    private final CategoryService categoryService;
    private final ProductService productService;
    private final ListingService listingService;
    /** A-17 · 首页商品卡曝光统计 */
    private final ProductStatService productStatService;

    public HomeService(CategoryService categoryService,
                       ProductService productService,
                       ListingService listingService,
                       ProductStatService productStatService) {
        this.categoryService = categoryService;
        this.productService = productService;
        this.listingService = listingService;
        this.productStatService = productStatService;
    }

    public HomeView home() {
        List<CategoryNode> categories = categoryService.tree();
        List<ProductCard> recommended = productService.topBySales(CARD_LIMIT);
        List<ProductCard> freshArrivals = productService.newest(CARD_LIMIT);
        List<BrandView> brands = categoryService.brands();
        List<ListingCard> secondhand = listingService.latestApproved(SECONDHAND_LIMIT);
        List<SpeciesPicks> speciesPicks = List.of(
                new SpeciesPicks("cat", "猫咪精选", productService.bySpecies("cat", PICKS_LIMIT)),
                new SpeciesPicks("dog", "狗狗精选", productService.bySpecies("dog", PICKS_LIMIT)));

        // A-17：首页是最大的曝光面，按「出现在首屏卡片里」计一次曝光（同一请求去重）
        List<ProductCard> exposed = new ArrayList<>(recommended.size() + freshArrivals.size() + 8);
        exposed.addAll(recommended);
        exposed.addAll(freshArrivals);
        for (SpeciesPicks picks : speciesPicks) {
            exposed.addAll(picks.items());
        }
        productStatService.recordExposure(exposed);

        return new HomeView(categories, recommended, freshArrivals, brands, secondhand, speciesPicks);
    }
}
