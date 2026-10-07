#!/usr/bin/env bash
# S1：一条命令把网页端「正式版」起起来
#
#   用法：bash scripts/web-up.sh
#
# 做三件事（与本项目各服务镜像的约定一致：**宿主构建产物 → COPY 进镜像**）：
#   ① 在宿主机构建前端产物（tsc 类型检查 + vite 打包）
#   ② 打镜像（nginx + 静态产物 + 同源反代 /api）
#   ③ 起容器（对外端口 8088）
#
# 起好之后：浏览器打开 http://localhost:8088/ ，**不需要任何命令行窗口常驻**。
# 改了前端代码后重跑本脚本即可（约 30 秒）。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[1/3] 构建前端产物（tsc --noEmit && vite build）…"
(cd frontend && npm run build)

echo "[2/3] 打镜像 duoduo/duoduo-web:1.0.0 …"
docker compose build duoduo-web-app

echo "[3/3] 启动容器 duoduo-app-web（8088 → 容器 80）…"
docker compose up -d duoduo-web-app

sleep 4
code=$(curl -s --noproxy '*' -m 10 -o /dev/null -w '%{http_code}' http://localhost:8088/ || true)
echo
echo "自检：首页 HTTP=${code}"
if [ "$code" = "200" ]; then
  echo "✅ 已就绪：浏览器打开 http://localhost:8088/"
  echo "   （运营账号 13684295254 / duoduo12345；登录后右上角头像菜单可进管理后台）"
else
  echo "⚠️ 首页未返回 200，请看 docker compose logs duoduo-web-app"
  exit 1
fi
