package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.dto.CreditWriteRequest;
import com.duoduo.fulfilment.client.dto.CreditWriteResult;
import com.duoduo.fulfilment.client.dto.UserBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户域客户端：批量取公开资料（昵称 / 头像），用于评价列表补全展示。
 *
 * <p>只取展示必需的三个字段（id / 昵称 / 头像）：手机号、实名、角色一律不拉过来 ——
 * 跨域接口按调用方真正需要裁剪字段，是减少隐私扩散面最省事的一步。
 */
@FeignClient(name = "duoduo-user", path = "/api/users")
public interface UserClient {

    /**
     * 批量取昵称与头像；参数为逗号分隔的用户 ID。
     *
     * <p>批量而非逐条：评价列表一页 20 条，逐条调用户服务就是 20 次跨进程往返，
     * 页面一慢会被误判成「评价服务不行」，根因其实在调用姿势。
     *
     * <p>参数用字符串而不是 {@code List<Long>}：两端集合参数的编码规则
     * （重复 query vs 逗号分隔）在不同 Feign 版本下有过变化，固定成单值后行为确定。
     */
    @GetMapping("/internal/briefs")
    ApiResponse<List<UserBrief>> briefs(@RequestParam("ids") String userIds);

    /**
     * 服务间写：把算好的信用分回写到用户资料（A4）。
     *
     * <p>这是本域**唯一**的跨域写入口，语义是「设置」，不是「累加」——
     * 幂等由「值相同则不写」在用户域侧保证，重放不会把分数越加越大。
     *
     * <p>写失败必须让调用方知道（抛异常），不能吞掉：信用分是展示数据，
     * 静默失败会让页面上长期停留一个过期的分数，而没有任何迹象表明它没刷新。
     */
    @PostMapping("/internal/{userId}/credit")
    ApiResponse<CreditWriteResult> writeCredit(@PathVariable("userId") Long userId,
                                               @RequestBody CreditWriteRequest request);
}
