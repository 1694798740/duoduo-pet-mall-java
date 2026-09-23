package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.UserDtos.AddressSnapshot;
import com.duoduo.user.dto.UserDtos.AddressUpsertRequest;
import com.duoduo.user.dto.UserDtos.AddressView;
import com.duoduo.user.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址接口（A8）。
 *
 * <p>用户侧（{@code /api/addresses}）的所有操作都以当前登录用户为维度，
 * 身份一律取自 {@link UserContext}（网关校验令牌后透传），
 * <b>绝不接受客户端传入 userId</b>；越权访问他人地址返回 404
 * （不区分「不存在」与「无权限」，避免用返回码差异枚举他人地址 ID）。
 *
 * <p>服务间接口（{@code /internal/**}）由 {@code InternalTokenFilter} 校验
 * {@code X-Internal-Token} 把关，供交易域下单时取地址快照 ——
 * 它不进网关路由，因此即便有人带着登录态也走不到这里。
 */
@RestController
@RequestMapping("/api/addresses")
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    /** 地址簿列表（默认地址排最前） */
    @GetMapping
    public ApiResponse<List<AddressView>> list() {
        return ApiResponse.ok(addressService.listByUser(UserContext.requireUserId()));
    }

    /** 默认地址；新用户尚无地址时返回 {@code data = null}（前端按「去添加地址」渲染） */
    @GetMapping("/default")
    public ApiResponse<AddressView> defaultAddress() {
        return ApiResponse.ok(addressService.defaultOf(UserContext.requireUserId()));
    }

    @PostMapping
    public ApiResponse<AddressView> create(@Valid @RequestBody AddressUpsertRequest request) {
        return ApiResponse.ok(addressService.create(UserContext.requireUserId(), request));
    }

    @PutMapping("/{addressId}")
    public ApiResponse<AddressView> update(@PathVariable Long addressId,
                                           @Valid @RequestBody AddressUpsertRequest request) {
        return ApiResponse.ok(addressService.update(UserContext.requireUserId(), addressId, request));
    }

    @PostMapping("/{addressId}/default")
    public ApiResponse<AddressView> setDefault(@PathVariable Long addressId) {
        return ApiResponse.ok(addressService.setDefault(UserContext.requireUserId(), addressId));
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Void> delete(@PathVariable Long addressId) {
        addressService.delete(UserContext.requireUserId(), addressId);
        return ApiResponse.ok();
    }

    /**
     * 服务间只读：按下单用户 + 地址 ID 取地址快照（供交易域写入订单收货信息）。
     *
     * <p>两个参数都需要：
     * <ul>
     *   <li>{@code userId} —— 让用户域独立校验一次归属。交易域虽然也校验了，
     *       但用户域不能假设「调用方一定校验过」，防御不能只做在一侧。</li>
     *   <li>{@code addressId} —— 被取的地址。</li>
     * </ul>
     *
     * <p>地址不存在或不属于该用户时返回 404，由交易域转成下单请求的校验失败。
     */
    @GetMapping("/internal/snapshot")
    public ApiResponse<AddressSnapshot> snapshot(@RequestParam("userId") Long userId,
                                                 @RequestParam("addressId") Long addressId) {
        AddressSnapshot snapshot = addressService.snapshot(userId, addressId);
        if (snapshot == null) {
            return ApiResponse.fail("NOT_FOUND", "收货地址不存在或不属于该用户");
        }
        return ApiResponse.ok(snapshot);
    }
}
