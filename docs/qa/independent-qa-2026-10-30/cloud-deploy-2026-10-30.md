# 云端部署记录（2026-10-30）

> 目标：阿里云 ECS `8.163.71.44`（`/opt/duoduo`，14 容器）
> 范围：只重建 **AI** 与 **商城 Web**；其他 12 个服务保持 2026-10-29 镜像。
> 前置：用户曾关机导致主机不可达；恢复后 22/80/8088 可用，8090/8091 仍只放行旧 IP（`120.229.81.126/32`），本机当前 `120.229.203.43`。

## 1. 部署物

| 项 | 值 |
| --- | --- |
| AI jar | `duoduo-ai-1.0.0.jar`，104,436,184 bytes，sha256 `f2fb3466634c65a9d6c6a4f14696e29dcd4cbcadf6fa93d3f9f535e34244425d` |
| 商城 dist | `frontend/apps/mall/dist` 33 个文件（含结构化澄清 UI） |
| nginx | `frontend/nginx.conf`（非流式 `/api/duoduo/chat` 180s） |
| compose | `docker-compose.yml`（新增 `DUODUO_AI_TURN_BUDGET_SECONDS:-75`） |
| `.env` | `DUODUO_AI_MODEL=qwen3.8-27b,qwen3.8-max-0902,kimi-k3`；`TIMEOUT_SECONDS=30`；`TURN_BUDGET_SECONDS=75` |

云端备份（可回滚）：
- 旧 jar：`duoduo-ai/target/duoduo-ai-1.0.0.jar.bak-20261030-154541`
- 旧 dist：`frontend/apps/mall/dist.bak-20261030-154541`
- 旧 nginx：`frontend/nginx.conf.bak-20261030-154541`
- 旧 `.env`：`.env.bak-ai-20261030-154541`
- 旧 compose：`docker-compose.yml.bak-20261030-compose`

## 2. 执行

1. SFTP 上传 jar → `.upload` → 服务端 sha256 比对一致 → 备份并原子替换。
2. 上传 dist 到 `dist.upload` → 备份并原子替换（避免旧 assets 残留）。
3. 上传 nginx.conf，备份并替换。
4. `.env` 备份后更新模型顺序/超时/整轮预算。
5. `docker compose -f docker-compose.yml -f docker-compose.prod.yml build duoduo-ai-app duoduo-web-app` → `up -d` 两个服务。

## 3. 验证（全部通过）

| 项 | 结果 |
| --- | --- |
| 容器 | 14 个全部 healthy（AI/Web 重建后） |
| 容器内 env | `TURN_BUDGET=75`、`TIMEOUT=30`、模型 27B 优先 |
| nginx | `/api/duoduo/chat` 与 `/api/duoduo/chat/stream` 均 `proxy_read_timeout 180s` |
| capabilities | `mode=llm`、`chatAvailable=true` |
| 澄清 | 「推荐一下」5.0s，`clarify` 4 个可点选项，`rec=0` |
| 预算约束 | 「预算 1 元以内的猫粮」19.1s，`rec=0` |
| 多轮上下文 | 「推荐猫粮」35.5s/rec=3 → 「那最便宜的是哪个？」38.1s/rec=2，回复正确比较 129/158 元 |
| 浏览器 | `http://8.163.71.44/ai` 发送「推荐一下」→ 4 个可点类目按钮、无商品卡、console 无报错 |
| 日志 | 8 分钟内无 ERROR/冷却/预算收口；模型均为 `qwen3.8-27b` |
| jar 校验 | 云端 sha256 与本地一致 |

证据：`evidence/cloud-deploy-upload.log`、`evidence/cloud-deploy-rebuild.log`、`evidence/cloud-verify.log`。

## 4. 回滚

```bash
cd /opt/duoduo
cp -p duoduo-ai/target/duoduo-ai-1.0.0.jar.bak-20261030-154541 duoduo-ai/target/duoduo-ai-1.0.0.jar
rm -rf frontend/apps/mall/dist && mv frontend/apps/mall/dist.bak-20261030-154541 frontend/apps/mall/dist
cp -p frontend/nginx.conf.bak-20261030-154541 frontend/nginx.conf
cp -p .env.bak-ai-20261030-154541 .env
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build duoduo-ai-app duoduo-web-app
```

## 5. 剩余说明

- 8090/8091 仍只放行旧 IP，本机当前 IP 变更，后台/商家端外网访问需在安全组补 `120.229.203.43/32`（不影响本次商城与 AI 验证）。
- 云端仍无域名/HTTPS/ICP；其他 12 个服务未重建。
- 本地 15 项缺陷已全部收口，云端只部署了本批修复涉及的 AI 与 Web。
