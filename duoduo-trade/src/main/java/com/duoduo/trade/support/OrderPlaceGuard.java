package com.duoduo.trade.support;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 下单入口限流（A11 · Sentinel）。
 *
 * <p>资源「order-place」由同步端点（{@code POST /api/orders}）与削峰端点
 * （{@code POST /api/orders/queued}）<b>共用</b>：限的是「下单」这个业务动作的总量，
 * 不区分走哪条通道 —— 否则限流等于把用户从同步端点赶到队列端点绕开，形同虚设。
 *
 * <p>规则用代码定义（QPS 来自环境变量），不依赖 dashboard：本地 / CI / 生产
 * 不需要多跑一个控制台容器，规则随配置变更重启生效。触发限流翻译成全站既有的
 * {@code RATE_LIMITED}(429)，不新造错误语义。
 */
@Component
public class OrderPlaceGuard {

    private static final Logger log = LoggerFactory.getLogger(OrderPlaceGuard.class);

    public static final String RESOURCE = "order-place";

    private final double qpsLimit;

    public OrderPlaceGuard(@Value("${duoduo.trade.place-qps-limit:100}") double qpsLimit) {
        this.qpsLimit = qpsLimit;
    }

    @PostConstruct
    public void loadRules() {
        FlowRule rule = new FlowRule(RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qpsLimit);
        FlowRuleManager.loadRules(List.of(rule));
        log.info("下单限流规则已加载 resource={} qpsLimit={}", RESOURCE, qpsLimit);
    }

    /** 在限流保护下执行动作；被限流抛 RATE_LIMITED(429) */
    public <T> T call(Supplier<T> action) {
        Entry entry = null;
        try {
            entry = SphU.entry(RESOURCE);
            return action.get();
        } catch (BlockException e) {
            throw new BizException(ErrorCode.RATE_LIMITED, "下单太频繁啦，请稍等片刻再试");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
