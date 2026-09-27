package com.duoduo.social;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 多多宠物商店 · 社交服务（第四十七轮）。
 *
 * <p>边界：关注关系、私信与卡片消息、通知中心（系统消息 / 全站公告 / 好友消息 / 商家消息 /
 * 订单与交易通知）。<b>交易事实不在这里</b> —— 订单状态、售后状态、物流节点分别由交易域
 * 与履约域拥有，社交域只接收它们推送的「通知」并按收件人聚合展示。
 *
 * <p>为什么单独成服务而不是并入用户域：
 * <ul>
 *   <li>通知中心要<b>跨域聚合</b>（交易域 / 履约域 / 私信 / 公告），按「收件人」重组数据，
 *       天然是一个独立的消息域；</li>
 *   <li>聊天是<b>高频读写</b>（轮询），隔离在独立库与独立进程，不拖累承载登录鉴权的用户域主链路；</li>
 *   <li>用户域是全站安全敏感面（JWT 签发、微信绑定、密码哈希），社交功能不该与它长在一起。</li>
 * </ul>
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>互相关注 = 两个方向各一行</b>（{@code t_social_follow} 复合主键），一条 COUNT 判定；
 *       不引入「关系状态」列 —— 状态列要双向同步，是 bug 温床；</li>
 *   <li><b>会话键 {@code conversation_id = "lo:hi"}</b>（双方 userId 数值小的在前）：
 *       双向聊天只需一个索引即可按会话拉取；</li>
 *   <li><b>卡片消息摘要以服务端事实为准</b>（与分享圈同一纪律）：请求体只带 bizType + bizId，
 *       商品卡 / 分享卡 / 个人卡的展示字段由本域去事实源现取；</li>
 *   <li><b>非互关会话每人限发一条</b>；商品咨询会话（ASK）豁免 —— 否则「问一问」不可用。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.duoduo.social.mapper")
public class SocialApplication {

    public static void main(String[] args) {
        SpringApplication.run(SocialApplication.class, args);
    }
}
