#!/usr/bin/env bash
# S1 + E6b：一条命令把**三个站点**的正式版全部起起来
#
#   用法：bash scripts/web-up.sh
#
# 做三件事（与本项目各服务镜像的约定一致：**宿主构建产物 → COPY 进镜像**）：
#   ① 在宿主机构建前端产物（tsc 类型检查 + vite 打包出 index.html / admin.html / merchant.html）
#   ② 打三个站点镜像（同一份产物，只是各自的 nginx 配置与入口不同）
#   ③ 起三个容器并逐个自检
#
# 三个站点**必须用不同主机名**（E6：Cookie 是主机级作用域且**不区分端口**，只换端口隔离不了会话）：
#   商城      http://localhost:8088
#   平台后台  http://admin.localhost:8090     （短地址 http://admin.localhost:8090/console）
#   商家平台  http://merchant.localhost:8091  （短地址 http://merchant.localhost:8091/workspace）
# 起好之后浏览器直接打开即可，**不需要任何命令行窗口常驻**。
# 改了前端代码后重跑本脚本即可（约 40 秒）。
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[1/4] 构建前端产物（E7 monorepo：cd frontend && npm run build → 转发到 apps/mall 跑 tsc + vite）…"
(cd frontend && npm run build)

echo "[2/4] 打三个站点镜像（同源产物 + 各自 nginx 配置）…"
docker compose build duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app

echo "[3/4] 启动三个站点容器…"
docker compose up -d duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app

sleep 4

echo "[4/4] 逐个自检…"
# -L 跟随重定向：后台/商家站点的 / 会 302 到各自的入口 HTML（`$http_host` 生成，端口不丢）
check() {
  local port="$1" name="$2"
  local code
  code=$(curl -s -L --noproxy '*' -m 10 -o /dev/null -w '%{http_code}' "http://localhost:${port}/" || true)
  echo "   ${name}  http://localhost:${port}/  HTTP=${code}"
  [ "$code" = "200" ] || return 1
}
ok=1
check 8088 '商城      ' || ok=0
check 8090 '平台后台  ' || ok=0
check 8091 '商家平台  ' || ok=0

echo
if [ "$ok" = "1" ]; then
  echo "✅ 三个站点均已就绪："
  echo "   商城      http://localhost:8088/                      （运营账号 13684295254 / duoduo12345）"
  echo "   平台后台  http://admin.localhost:8090/console          （仅平台运营 / 管理员，账号同上）"
  echo "   商家平台  http://merchant.localhost:8091/workspace     （第三方卖家账号登录）"
  echo
  echo "   注意：三端会话**互相独立** —— 在商城登录不会带到后台/商家站点，反之亦然。"
else
  echo "⚠️ 有站点未返回 200，逐个查看：docker compose logs duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app"
  exit 1
fi
