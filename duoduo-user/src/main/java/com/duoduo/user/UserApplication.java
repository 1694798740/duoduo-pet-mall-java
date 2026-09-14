package com.duoduo.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 多多宠物商店 · 用户服务。
 *
 * <p>边界：账号、登录注册、令牌管理、实名认证、宠物档案、收货地址。
 * 不含商品、交易、售后 —— 这些属于各自服务，通过服务间调用协作，禁止跨库直连。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.duoduo.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
