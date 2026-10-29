package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistRequest;
import com.duoduo.user.dto.MerchantSecurityDtos.IpWhitelistView;
import com.duoduo.user.service.MerchantIpWhitelistService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家端「账号安全 · IP 白名单」（E-06）。
 *
 * <p>挂在 {@code /api/seller/**}（商家经营面专属族）：网关会对这一族做来源地址判定，
 * 这正是本功能生效的地方 —— 若把配置接口放在买家路径下，店主被锁死后反而能改，
 * 白名单也就名存实亡了。
 *
 * <p><b>currentIp 取 {@code X-Real-IP}</b>：该头由网关在剥离客户端伪造值之后写入
 * （见 {@code JwtAuthGlobalFilter}），是唯一可信的来源事实；直接从
 * {@code request.getRemoteAddr()} 拿到的会是网关自己的地址（容器网络里的 IP），
 * 展示给店主只会误导他去加一个错误的白名单。
 */
@RestController
@RequestMapping("/api/seller/security/ip-whitelist")
public class MerchantSecurityController {

    private static final String HEADER_REAL_IP = "X-Real-IP";

    private final MerchantIpWhitelistService service;

    public MerchantSecurityController(MerchantIpWhitelistService service) {
        this.service = service;
    }

    /** 查看本店白名单（店主专属；含服务端看到的当前 IP，便于「把我也加进去」） */
    @GetMapping
    public ApiResponse<IpWhitelistView> view(HttpServletRequest request) {
        return ApiResponse.ok(service.view(UserContext.requireUserId(), clientIp(request)));
    }

    /**
     * 保存本店白名单（店主专属）。
     *
     * <p>当前 IP 不在新名单里时必须带 {@code confirmLockout=true} —— 防「配完就把自己锁在门外」。
     * 每次保存都留痕（含关闭白名单：它整条摘掉一条安全边界）。
     */
    @PutMapping
    @Audited(action = AuditAction.UPDATE_MERCHANT_IP_WHITELIST)
    public ApiResponse<IpWhitelistView> save(@RequestBody(required = false) IpWhitelistRequest request,
                                             HttpServletRequest httpRequest) {
        return ApiResponse.ok(service.save(UserContext.requireUserId(), request, clientIp(httpRequest)));
    }

    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader(HEADER_REAL_IP);
        return realIp == null || realIp.isBlank() ? null : realIp.trim();
    }
}
