package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.ListingCard;
import com.duoduo.product.dto.ProductDtos.ListingDetail;
import com.duoduo.product.dto.ProductDtos.ListingQuery;
import com.duoduo.product.service.ListingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C2C 闲置浏览接口（公开）。
 * 发布与下架属卖家侧，见 {@link com.duoduo.product.service.ListingService} 类注释。
 */
@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    @GetMapping
    public ApiResponse<PageResult<ListingCard>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String species,
            @RequestParam(required = false) String conditionLevel,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        ListingQuery query = new ListingQuery(keyword, city, species, conditionLevel,
                minPrice, maxPrice, sort, page, pageSize);
        return ApiResponse.ok(listingService.search(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<ListingDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(listingService.detail(id));
    }
}
