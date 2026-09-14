package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.product.dto.ProductDtos.BrandView;
import com.duoduo.product.dto.ProductDtos.DictItem;
import com.duoduo.product.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 字典与品牌接口（公开）。
 *
 * <p>把枚举字典放在服务端而不是前端硬编码：筛选器的取值必须与后端的校验白名单
 * 同源，否则前端新增一个选项而后端不认识，就会出现「界面上可选、一选就报错」。
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final CategoryService categoryService;

    public MetaController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/brands")
    public ApiResponse<List<BrandView>> brands() {
        return ApiResponse.ok(categoryService.brands());
    }

    @GetMapping("/dictionaries")
    public ApiResponse<Map<String, List<DictItem>>> dictionaries() {
        return ApiResponse.ok(Map.of(
                "species", List.of(
                        new DictItem("cat", "猫咪"),
                        new DictItem("dog", "狗狗"),
                        new DictItem("other", "其他宠物")),
                "lifeStage", List.of(
                        new DictItem("BABY", "幼年"),
                        new DictItem("ADULT", "成年"),
                        new DictItem("SENIOR", "老年")),
                "conditionLevel", List.of(
                        new DictItem("NEW", "全新未拆"),
                        new DictItem("LIKE_NEW", "几乎全新"),
                        new DictItem("GOOD", "成色良好"),
                        new DictItem("FAIR", "有明显使用痕迹")),
                "supplyType", List.of(
                        new DictItem("self", "自营保真"),
                        new DictItem("merchant", "第三方商家")),
                "sort", List.of(
                        new DictItem("default", "综合"),
                        new DictItem("sales", "销量优先"),
                        new DictItem("price_asc", "价格从低到高"),
                        new DictItem("price_desc", "价格从高到低"),
                        new DictItem("rating", "评分优先"),
                        new DictItem("newest", "最新上架"))));
    }
}
