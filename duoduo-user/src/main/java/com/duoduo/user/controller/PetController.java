package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.UserDtos.PetUpsertRequest;
import com.duoduo.user.dto.UserDtos.PetView;
import com.duoduo.user.service.PetProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 宠物档案接口。
 * 所有操作都以当前登录用户为维度，越权访问他人档案会返回 404（不区分「不存在」与「无权限」，避免信息泄露）。
 */
@RestController
@RequestMapping("/api/pets")
public class PetController {

    private final PetProfileService petProfileService;

    public PetController(PetProfileService petProfileService) {
        this.petProfileService = petProfileService;
    }

    @GetMapping
    public ApiResponse<List<PetView>> list() {
        return ApiResponse.ok(petProfileService.listByUser(UserContext.requireUserId()));
    }

    @PostMapping
    public ApiResponse<PetView> create(@Valid @RequestBody PetUpsertRequest request) {
        return ApiResponse.ok(petProfileService.create(UserContext.requireUserId(), request));
    }

    @PutMapping("/{petId}")
    public ApiResponse<PetView> update(@PathVariable Long petId,
                                       @Valid @RequestBody PetUpsertRequest request) {
        return ApiResponse.ok(petProfileService.update(UserContext.requireUserId(), petId, request));
    }

    @PostMapping("/{petId}/default")
    public ApiResponse<PetView> setDefault(@PathVariable Long petId) {
        return ApiResponse.ok(petProfileService.setDefault(UserContext.requireUserId(), petId));
    }

    @DeleteMapping("/{petId}")
    public ApiResponse<Void> delete(@PathVariable Long petId) {
        petProfileService.delete(UserContext.requireUserId(), petId);
        return ApiResponse.ok();
    }
}
