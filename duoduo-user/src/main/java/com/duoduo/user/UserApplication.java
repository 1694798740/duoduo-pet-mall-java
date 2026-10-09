package com.duoduo.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 多多宠物商店 · 用户服务。
 *
 * <p>边界：账号、登录注册、令牌管理、实名认证、宠物档案、收货地址。
 * 不含商品、交易、售后 —— 这些属于各自服务，通过服务间调用协作，禁止跨库直连。
 *
 * <p>{@code @EnableScheduling} 是 E15 的资质到期任务要用的（{@code MerchantLicenseJob}）：
 * 到期提醒与「过期自动停业」必须由系统自己发生，不能等谁登录才触发。
 * 其余业务不受影响 —— 用户域此前没有任何定时任务。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.duoduo.user.client")
@EnableScheduling
@MapperScan("com.duoduo.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
