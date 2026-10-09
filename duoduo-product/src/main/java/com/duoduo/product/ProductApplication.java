package com.duoduo.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多多宠物商店 · 商品服务。
 *
 * <p>边界：类目树、品牌、SPU/SKU、商品检索、商品详情、首页聚合、C2C 闲置浏览。
 * 不含库存扣减（属交易服务）、不含价格计算与优惠（属营销/交易服务）。
 *
 * <p>本服务是典型的<b>读多写少</b>场景，缓存收益最大：
 * 生产形态下类目树与商品详情走「本地 Caffeine + Redis 两级缓存」，
 * 检索走 Elasticsearch（本阶段先用 SQL 实现，接口契约保持不变，便于后续替换）。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.duoduo.product.client")
@EnableScheduling
@MapperScan("com.duoduo.product.mapper")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
