#!/usr/bin/env bash
#
# 生产 / 预生产只读冒烟（2026-10-28）
#
# 为什么需要它：项目主要冒烟脚本是 PowerShell（Windows 开发机），云主机是 Ubuntu，
# 部署验收时没法直接跑。这里只做**只读**健康检查 —— 不登录、不写库、不造数据，
# 因此可以安全地在上线验收和每次发布后执行。
#
# 用法：
#   bash scripts/smoke-linux.sh
#   GATEWAY=http://127.0.0.1:8080 MALL=http://127.0.0.1:8088 bash scripts/smoke-linux.sh
#
# 退出码：0 全通过；1 有失败项（可直接接进部署流水线）。
# 需要：curl（Ubuntu 自带）；有 docker 时额外检查容器健康，没有则跳过并提示。

set -u

GATEWAY=${GATEWAY:-http://127.0.0.1:8080}
MALL=${MALL:-http://127.0.0.1:8088}
MERCHANT=${MERCHANT:-http://127.0.0.1:8091}
ADMIN=${ADMIN:-http://127.0.0.1:8090}

PASS=0
FAIL=0
FAILED_NAMES=()
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

pass() { PASS=$((PASS + 1)); echo "  [PASS] $1"; }
fail() {
  FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1")
  echo "  [FAIL] $1  -> $2"
}

# GET 并断言 HTTP 状态码（支持 `200|302` 这种多选）；响应体写到 $TMP_DIR/body 供后续断言
check_code() {
  local name=$1 url=$2 expect=${3:-200}
  local code
  code=$(curl -sS --max-time 8 -o "$TMP_DIR/body" -w '%{http_code}' "$url" 2>"$TMP_DIR/err" || true)
  case "|$expect|" in
    *"|$code|"*) pass "$name (HTTP $code)" ;;
    *) fail "$name" "期望 $expect，实际 ${code:-连接失败} $(head -c 200 "$TMP_DIR/err" 2>/dev/null)" ;;
  esac
}

# GET 并断言响应体包含关键字（用于健康检查的 status=UP）
check_contains() {
  local name=$1 url=$2 needle=$3
  local code
  code=$(curl -sS --max-time 8 -o "$TMP_DIR/body" -w '%{http_code}' "$url" 2>/dev/null || true)
  if [ "$code" = "200" ] && grep -q "$needle" "$TMP_DIR/body"; then
    pass "$name"
  else
    fail "$name" "HTTP ${code:-连接失败}，响应体未包含「$needle」"
  fi
}

echo ""
echo "=== 容器健康 ==="
if command -v docker >/dev/null 2>&1; then
  local_bad=$(docker ps --filter "name=duoduo-" --format '{{.Names}} {{.Status}}' 2>/dev/null \
    | grep -Ei 'unhealthy|Restarting' || true)
  local_count=$(docker ps --filter "name=duoduo-" --format '{{.Names}}' 2>/dev/null | wc -l | tr -d ' ')
  if [ "${local_count:-0}" -eq 0 ]; then
    fail "存在运行中的 duoduo 容器" "docker ps 没有找到任何 duoduo-* 容器"
  elif [ -n "$local_bad" ]; then
    fail "容器无 unhealthy/重启中" "$local_bad"
  else
    pass "容器健康（$local_count 个运行中，无 unhealthy/重启中）"
  fi
else
  echo "  [SKIP] 未安装 docker，跳过容器健康检查"
fi

echo ""
echo "=== 网关与核心读接口 ==="
check_contains "网关健康检查 status=UP" "$GATEWAY/actuator/health" '"status":"UP"'
check_code "首页聚合 /api/home" "$GATEWAY/api/home"
check_code "类目树 /api/categories" "$GATEWAY/api/categories"
check_code "商品列表 /api/products" "$GATEWAY/api/products?page=1&pageSize=1"

echo ""
echo "=== 三个前端入口 ==="
check_code "商城首页" "$MALL/"
# 商家 / 后台未登录会 302 到登录页，属于正常入口；200 说明已带登录态
check_code "商家工作台（200 或 302 登录跳转）" "$MERCHANT/" "200|302"
check_code "运营后台（200 或 302 登录跳转）" "$ADMIN/" "200|302"

echo ""
echo "=== 结果 ==="
echo "  通过 $PASS / 失败 $FAIL"
if [ "$FAIL" -ne 0 ]; then
  printf '  失败项：%s\n' "${FAILED_NAMES[*]}"
  exit 1
fi
echo "  全部通过"
