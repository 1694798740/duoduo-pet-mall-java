package com.duoduo.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多多宠物商店 · 营销服务（A5 · 优惠券）。
 *
 * <p>边界：券模板、领券、下单时的券校验与锁定、支付成功核销、关单 / 超时解锁。
 * <b>钱不在这里</b> —— 营销域只回答「这张券能不能用、能抵多少」并管住券自身的状态；
 * 应付金额与资金托管仍由交易域计算与落账（折扣金额由交易域写入 {@code t_order.discount_amount}）。
 *
 * <p>为什么单独成服务而不是塞进交易域：
 * <ul>
 *   <li>券的读（领券中心、结算页「可用券」）是典型的<b>营销流量</b>，峰值和下单峰值不同步，
 *       混进交易域会让订单服务被券列表查询拖住；</li>
 *   <li>券模板是运营长期维护的主数据，与订单表的生命周期完全不同；</li>
 *   <li>交易域<b>单向依赖</b>营销域（下单来锁券），营销域不反向依赖，依赖方向干净。</li>
 * </ul>
 *
 * <p>关键设计（与项目既有的资金纪律一致）：
 * <ul>
 *   <li><b>幂等靠唯一索引</b>：同一用户同一券模板只能领一张（{@code uk_coupon_user_tpl}），
 *       不靠「先查再插」；</li>
 *   <li><b>并发锁券靠 CAS 条件更新</b>（判断写进 WHERE），<b>禁止分布式锁</b> ——
 *       并发下单同一张券只有一单能把它从 {@code UNUSED} 改成 {@code LOCKED}；</li>
 *   <li><b>解锁有两条路</b>：交易域关单 / 取消时主动调 {@code release}，
 *       外加一个按 TTL 扫描的补偿任务兜底（进程重启、对端调用失败都不会把券永久锁死）。</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} 是为解锁兜底任务准备的（默认开启，见 {@code CouponReleaseJob}）。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.duoduo.marketing.mapper")
@EnableScheduling
public class MarketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingApplication.class, args);
    }
}
