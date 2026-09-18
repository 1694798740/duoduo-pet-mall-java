package com.duoduo.fulfilment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 多多宠物商店 · 履约服务。
 *
 * <p>边界（对齐 {@code docs/ARCHITECTURE.md} 的服务拆分表）：发货 / 物流轨迹 / 售后 / 纠纷仲裁 /
 * <b>评价与晒单</b> / 信用分。当前落地第一个纵切：**评价与晒单**。
 *
 * <p>为什么评价单独成服务而不是塞进交易域：
 * <ul>
 *   <li>写多读多且**允许异步**（架构里明确「评价服务异步写入，不影响主流程」），
 *       与订单状态机的强一致要求不同，混在一起会让订单服务的伸缩被评价流量拖住；</li>
 *   <li>评价要读订单事实（是否已完成、是否本人），这是**跨域只读**，
 *       用内部凭据单向依赖交易域即可，不需要反向依赖；</li>
 *   <li>信用分要消费评价结果，同域内演进比跨域改动便宜。</li>
 * </ul>
 *
 * <p>关键设计：
 * <ul>
 *   <li>一单一评靠 <b>唯一索引</b>（order_id + user_id）兜底，不靠应用层「先查再插」；</li>
 *   <li>评价权限只认「登录态 + 订单归属」，接口不接受 userId 参数；</li>
 *   <li>匿名评价在**读取时**脱敏，原始 user_id 保留（信用分与风控需要），不落「匿名副本」。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.duoduo.fulfilment.client")
@MapperScan("com.duoduo.fulfilment.mapper")
public class FulfilmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfilmentApplication.class, args);
    }
}
