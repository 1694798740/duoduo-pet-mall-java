package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.web.CsvExport;
import com.duoduo.user.dto.MerchantDtos.StaffBatchStatusRequest;
import com.duoduo.user.dto.MerchantDtos.StaffImportRequest;
import com.duoduo.user.dto.MerchantDtos.StaffView;
import com.duoduo.user.service.MerchantStaffService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 店主侧成员管理（E13）。挂在 {@code /api/seller/**}（商家经营面专属族，E8 入口边界 +
 * 服务端 OWNER 准入两层）。**OWNER-only**：店员调用一律 403 —— 店员能拉人进店，
 * 成员名单就不再是店主可控的范围。
 *
 * <p>E18 · 写端点带 {@link Audited}：店主对成员的操作也要留痕，
 * 否则平台在需要复盘「这个店员是谁停的、什么时候停的」时无从查起。
 * F-04 起权限配置也走同一套留痕（{@code UPDATE_STAFF_PERMISSIONS}）。
 * 动作标识与平台侧**刻意不同**（店主侧 ENABLE_STAFF / 平台侧 ADMIN_ENABLE_STAFF）——
 * 复盘时要能一眼分清是谁停的，这个区分是 E13 就定下的口径。
 */
@RestController
@RequestMapping("/api/seller/staff")
public class MerchantStaffController {

    private final MerchantStaffService staffService;

    public MerchantStaffController(MerchantStaffService staffService) {
        this.staffService = staffService;
    }

    /** 本店成员列表（含店主本人） */
    @GetMapping
    public ApiResponse<List<StaffView>> list() {
        return ApiResponse.ok(staffService.listForOwner(UserContext.requireUserId()));
    }

    /** 邀请店员（按手机号；一人一店，已在别店的账号拒绝） */
    @PostMapping
    @Audited(action = AuditAction.INVITE_STAFF)
    public ApiResponse<StaffView> invite(@RequestBody Map<String, String> body) {
        String phone = body == null ? null : body.get("phone");
        return ApiResponse.ok(staffService.invite(UserContext.requireUserId(), phone));
    }

    /**
     * 员工导出（2026-10-28 · 导入导出第一批）。
     *
     * <p>列里包含店主本人（OWNER）—— 对账时「这个店有几个账号」本来就要算店主。
     * 手机号是列表接口下发的掩码值：导出文件会被转发，真号不该出现在盘里。
     */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws java.io.IOException {
        List<StaffView> staff = staffService.listForOwner(UserContext.requireUserId());
        CsvExport.write(response, "员工-" + CsvExport.stamp(), HEADERS,
                staff.stream().map(MerchantStaffController::row).toList());
    }

    /**
     * 下载导入模板（2026-10-28）。
     *
     * <p>权限键直接写在表头里：模板是唯一一份「导出的说明书」，
     * 放进前端提示里的话，用户往往先下载、再回头找提示，最后凭猜填写。
     */
    @GetMapping("/import-template")
    public void importTemplate(HttpServletResponse response) throws java.io.IOException {
        CsvExport.write(response, "员工导入模板",
                List.of("手机号", "权限(可选；分号分隔：PRODUCT_EDIT;PRODUCT_PRICE;PRODUCT_STATUS;ORDER_SHIP;ORDER_REFUND;SUPPORT_REPLY；留空=默认全部)"),
                java.util.List.<List<String>>of());
    }

    /**
     * 员工批量导入（2026-10-28 · 导入导出第一批）。
     *
     * <p>请求体是前端解析 CSV 后的行（手机号 + 可空权限），逐行结果见 {@code BatchDtos.Result}；
     * 部分失败不回滚，前端按 {@code rowNo} 把失败原因标回原表。
     */
    @PostMapping("/import")
    @Audited(action = AuditAction.INVITE_STAFF)
    public ApiResponse<BatchDtos.Result> importStaff(@RequestBody(required = false) StaffImportRequest request) {
        return ApiResponse.ok(staffService.importStaff(UserContext.requireUserId(),
                request == null ? null : request.rows()));
    }

    /**
     * 批量启用（2026-10-28 · 批量操作第一批）。
     *
     * <p>与单条启用同一个动作标识（{@link AuditAction#ENABLE_STAFF}）—— 审计里能看出是批量，
     * 是因为一次请求留下多条目标；动作本身没有区别。
     */
    @PostMapping("/batch-enable")
    @Audited(action = AuditAction.ENABLE_STAFF)
    public ApiResponse<BatchDtos.Result> batchEnable(@RequestBody StaffBatchStatusRequest request) {
        if (request == null || request.memberIds() == null) {
            throw com.duoduo.common.exception.BizException.validation("memberIds 不能为空");
        }
        return ApiResponse.ok(staffService.setStaffStatusBatch(
                UserContext.requireUserId(), request.memberIds(), true));
    }

    /** 批量停用（同上；停用是即时生效的安全动作，单独一个动作标识便于复盘） */
    @PostMapping("/batch-disable")
    @Audited(action = AuditAction.DISABLE_STAFF)
    public ApiResponse<BatchDtos.Result> batchDisable(@RequestBody StaffBatchStatusRequest request) {
        if (request == null || request.memberIds() == null) {
            throw com.duoduo.common.exception.BizException.validation("memberIds 不能为空");
        }
        return ApiResponse.ok(staffService.setStaffStatusBatch(
                UserContext.requireUserId(), request.memberIds(), false));
    }

    /** 停用店员（即时生效） */
    @PostMapping("/{memberId}/disable")
    @Audited(action = AuditAction.DISABLE_STAFF)
    public ApiResponse<StaffView> disable(@PathVariable("memberId") long memberId) {
        return ApiResponse.ok(staffService.setStaffStatus(UserContext.requireUserId(), memberId, false));
    }

    /** 启用店员 */
    @PostMapping("/{memberId}/enable")
    @Audited(action = AuditAction.ENABLE_STAFF)
    public ApiResponse<StaffView> enable(@PathVariable("memberId") long memberId) {
        return ApiResponse.ok(staffService.setStaffStatus(UserContext.requireUserId(), memberId, true));
    }

    /** F-04 · 权限配置请求体：空数组 = 仅查看；缺少 permissions 字段 = 拒绝 */
    public record PermissionsRequest(List<String> permissions) {
    }

    /**
     * F-04 · 配置店员操作权限（店主专属）。
     *
     * <p>逐项校验（未知键 422），店主行不可配置，幂等重复提交不写库。
     */
    @PostMapping("/{memberId}/permissions")
    @Audited(action = AuditAction.UPDATE_STAFF_PERMISSIONS)
    public ApiResponse<StaffView> updatePermissions(@PathVariable("memberId") long memberId,
                                                    @RequestBody(required = false) PermissionsRequest request) {
        return ApiResponse.ok(staffService.updatePermissions(UserContext.requireUserId(), memberId,
                request == null ? null : request.permissions()));
    }

    // ---------------- CSV 导出列定义 ----------------

    private static final List<String> HEADERS = List.of(
            "成员ID", "用户ID", "手机号", "昵称", "角色", "状态", "权限", "邀请人", "加入时间");

    private static List<String> row(StaffView staff) {
        return List.of(
                text(staff.memberId()),
                text(staff.userId()),
                text(staff.phoneMasked()),
                text(staff.nickname()),
                text(staff.role()),
                text(staff.status()),
                staff.permissions() == null || staff.permissions().isEmpty()
                        ? "仅查看" : String.join(";", staff.permissions()),
                text(staff.invitedBy()),
                CsvExport.beijingTime(staff.createdAt()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
