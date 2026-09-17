package com.duoduo.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多多宠物商店 · 交易服务。
 *
 * <p>边界：购物车、下单与拆单、库存冻结/释放、订单状态机、支付回调幂等、
 * C2C 担保交易（资金托管与放款）、卖家中心。
 * <b>不含</b>商品主数据（属商品服务）与库存主数据（属商品服务）——
 * 本服务只持有「冻结台账」与「订单快照」，库存真实扣减通过商品服务接口完成。
 *
 * <p>关键设计：
 * <ul>
 *   <li>下单用「幂等键 + 唯一索引」防重复提交，不依赖分布式锁；</li>
 *   <li>支付回调用「渠道 + 幂等键」唯一索引保证只入账一次；</li>
 *   <li>待付款超时关单由 {@code @Scheduled} 扫描 {@code expire_at}，幂等且可重入；</li>
 *   <li>金额一律整数「分」，时间一律 UTC。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.duoduo.trade.client")
@EnableScheduling
@MapperScan("com.duoduo.trade.mapper")
public class TradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}
