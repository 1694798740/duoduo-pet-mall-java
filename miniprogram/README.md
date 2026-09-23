# A13 · 买家端微信小程序（骨架 · 测试号阶段）

> 目标：主交易闭环（浏览 → 加购 → 下单 → 支付 → 订单）复用既有网关契约，**不改任何后端代码**。
> AppID 到位前的边界：微信 `code2Session` 登录的**后端已就绪**（`POST /api/auth/wechat` + `/bind`，
> 本地走 mock 通道），小程序侧 `config.js` 的 `WECHAT_LOGIN_READY` 仍为 `false`——
> 打开后走 `wx.login` 微信登录，失败自动回退「手机号 + 密码」（复用既有 `/api/auth/login`）。

## 开发者工具跑起来（3 步）

1. 微信开发者工具 → 导入项目 → 目录选本 `miniprogram/`，AppID 选「测试号」（`project.config.json` 已填 `touristappid`）。
2. 确保本机全栈在跑（`docker compose up -d`，网关 `http://localhost:8080`）。
   详情设置 → 本地设置 → 勾选「**不校验合法域名**」（localhost 不在小程序合法域名白名单）。
3. 编译预览：首页商品列表来自 `GET /api/products`；登录后可加购 → 购物车页「结算」→
   自动取默认地址 `addressId` 下单 → 订单页「去支付」走沙箱预下单 + 轮询。

## 目录

```
miniprogram/
├── project.config.json   appid=touristappid（测试号占位；真实 AppID 到位后改这里）
├── config.js             BASE_URL + WECHAT_LOGIN_READY 开关
├── app.js / app.json / app.wxss
├── utils/api.js          请求封装：包络解包 / Bearer 令牌 / 401 跳登录 / 错误文案映射（与 Web 端 client.ts 同口径）
└── pages/
    ├── index/            商品检索（公开接口）
    ├── product/          详情 + 选规格 + 加购
    ├── cart/             购物车 + 结算（取默认地址 addressId 下单，服务端写快照 —— A8 语义）
    ├── orders/           订单列表 + 沙箱支付（预下单 → 展示收银台链接 → 轮询 3s×20）
    └── login/            登录（手机号回退；WECHAT_LOGIN_READY=true 时展示微信一键登录）
```

## 约定（与 Web 端一致，勿破坏）

- **ID 一律字符串**：后端 T-39 修复后全局把 64 位标识按字符串下发，小程序端只拼 URL / 相等比较，**不做数值运算**。
- **令牌不进 URL**：`Authorization: Bearer` 头 + storage（小程序无 Cookie，与 Web 端 httpOnly 不同，这是平台差异）。
- **错误文案**：包络 `code` → 人话（`utils/api.js` 的 `friendlyMessage`），4xx 不重试。
- **订单对内用 `orderNo`**：不出现内部雪花 id（同 Web 端契约）。

## 下一轮待办（A13 收尾）

1. ~~后端 `POST /api/auth/wechat`~~ ✅ 已实现（第十五轮后端补齐）：
   `code2Session`（appid/secret 走环境变量 `WECHAT_APPID` / `WECHAT_SECRET`，未配置返回 503 `DEGRADED`）
   + `t_user.wx_openid` 唯一绑定（`uk_user_wxopenid`）+ `POST /api/auth/wechat/bind`；
   本地/CI 由 `DUODUO_WECHAT_MOCK_ENABLED=true` 开 mock 通道（code 形如 `mock:<openid>`），生产禁开；
   冒烟 `scripts/wechat-auth-smoke.ps1` 26/26 全绿。
2. `config.js` 打开 `WECHAT_LOGIN_READY`（真实 AppID/Secret 配好后），删除手机号回退入口。
3. 真机预览（**待用户提供 AppID**）。
