# 域名与 HTTPS 切换手册（审核通过后 10 分钟完成）

> 当前状态：域名 / 备案审核中。代码侧已预留三站点入口、`X-Duoduo-Entry` 与同源反代；
> 审核通过后只需替换 Nginx 配置与 `.env` 中的域名/证书路径，不改业务代码。

## 1. 需要准备的三组域名

| 站点 | 变量 | 示例 | 说明 |
|---|---|---|---|
| 买家商城 | `MALL_DOMAIN` | `www.duoduo-pet.com` | 主域，可再加裸域 301 |
| 商家工作台 | `MERCHANT_DOMAIN` | `merchant.duoduo-pet.com` | 只放商家入口 |
| 平台后台 | `ADMIN_DOMAIN` | `admin.duoduo-pet.com` | 建议叠加 IP 白名单 |

三站点登录态互相独立（Cookie 名 `dd_at` / `dd_at_m` / `dd_at_a`），不要合并成同一域。

## 2. HTTPS 强制跳转（每个站点一份）

```nginx
# HTTP → HTTPS（保留路径与查询串）
server {
    listen 80;
    server_name www.duoduo-pet.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name www.duoduo-pet.com;

    ssl_certificate     /etc/nginx/certs/www.duoduo-pet.com/fullchain.pem;
    ssl_certificate_key /etc/nginx/certs/www.duoduo-pet.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:SSL:10m;

    # HSTS 先短后长：确认全站 HTTPS 正常后再改成 31536000
    add_header Strict-Transport-Security "max-age=86400" always;

    root /usr/share/nginx/html;
    index index.html;

    # 以下 location 与现有 frontend/nginx.conf 完全一致：
    #   /uploads/  → 网关（相对路径图片）
    #   /api/      → 网关，并注入 X-Duoduo-Entry mall
    #   /sandbox/  → 网关
    #   location / { try_files $uri /index.html; }
    # ★ 注意 Host 必须用 $http_host（带端口），X-Forwarded-Proto 必须为 https，
    #   否则网关的 Origin/Host 同源判定会 403。
}
```

- 商家端把 `X-Duoduo-Entry` 改成 `merchant`，后台改成 `admin`；
- 后台与商家端可分别追加 `allow <办公 IP>; deny all;`（与网关 IP 白名单不冲突，纵深防御）；
- 裸域（`duoduo-pet.com`）单独 301 到 `https://www...`。

## 3. 证书获取与续期

- 方案 A（推荐）：云厂商免费 DV 证书，控制台申请 + 自动续期；
- 方案 B：`certbot` 自动签发/续期，证书目录挂载到 nginx 容器；
- 续期后执行 `nginx -s reload`（容器内 `docker exec duoduo-app-mall-web nginx -s reload`）。

## 4. 应用侧要改的配置

| 位置 | 变量 | 改法 |
|---|---|---|
| `.env` | `DUODUO_PUBLIC_BASE_URL` / 回调域名 | 换成 `https://www...` |
| `.env` | 微信/支付宝回调 | 域名审核后再改；当前微信支付已关闭 |
| `application.yml` | CORS `allowed-origins` | 加三个 https 域名 |
| 小程序 | `request` 合法域名 | 加 `https://www...`（微信公众平台配置） |

## 5. 切换后 10 分钟验收

1. `http://` 访问三个域名 → 301 到 `https://`；
2. 三站点登录互不覆盖；刷新 SPA 路由不 404；
3. `/api/categories` 200；`/uploads/...` 200；
4. 上传一张图、下单、沙箱支付各一次；
5. 后台 IP 白名单再次确认未把当前出口 IP 锁在外面；
6. 证书链 `openssl s_client -connect www...:443 -servername www...` 无告警。

## 6. 回滚

保留旧 `listen 80` 配置与证书文件；异常时把 443 server 块注释、恢复 80 直服，
再 `nginx -s reload`。回滚不需要重启 Java 服务。
