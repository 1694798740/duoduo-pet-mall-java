#!/usr/bin/env bash
#
# 六库备份 → 腾讯云 COS 私有桶（2026-10-28）
#
# 背景：生产是 Ubuntu 云主机，原有的 scripts/db-backup.ps1 只能在 Windows 跑；
# 备份方案已拍板「直接放 COS 桶」，所以这里给出 Linux 原生、零额外依赖的实现
# （只用 docker / curl / openssl / gzip，都是 Ubuntu 自带或已装）。
#
# 用法：
#   bash scripts/db-backup-cos.sh                 # 备份六库并上传
#   bash scripts/db-backup-cos.sh --check         # 只校验桶名与密钥（不写任何东西）
#   bash scripts/db-backup-cos.sh --upload FILE   # 手工上传单个文件
#   bash scripts/db-backup-cos.sh --stat KEY       # 查远端对象是否存在（校验备份是否落地）
#   bash scripts/db-backup-cos.sh --delete KEY     # 手工删除远端对象（人工清理用）
#
# 关键环境变量（默认从仓库根目录 .env 读取；同名 shell 变量优先）：
#   MYSQL_CONTAINER=duoduo-mysql   MYSQL_ROOT_PASSWORD=...
#   COS_SECRET_ID / COS_SECRET_KEY / COS_REGION / COS_BUCKET_PRIVATE
#   COS_PREFIX=db-backup           BACKUP_DIR=/var/backups/duoduo   KEEP_LOCAL_RUNS=7
#
# 设计纪律（沿用 db-backup.ps1 的教训）：
#   1. **空结果不算成功**：每个库先查 information_schema 的真实表数，
#      产物里 `CREATE TABLE` 的条数必须与之相等；gzip 必须能解压。
#   2. **两个来源校验**：上传后用签名 HEAD 读回远端 Content-Length，
#      与本地文件字节数逐一对齐；manifest 记录 sha256。
#   3. 失败退出码 1，cron 能据此告警；任何一步失败都不会「继续往下装作没事」。
#
# 远端布局：cos://<private-bucket>/db-backup/<YYYY-MM-DD_HHMMSS>/<db>.sql.gz + _manifest.txt
# 远端保留：脚本不删远端对象，请在桶上给 db-backup/ 前缀配生命周期规则（如 30 天），
#          避免脚本误删；本地默认只留最近 7 次。

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
ENV_FILE=${ENV_FILE:-$ROOT_DIR/.env}

# ---------- 读取 .env（保留 shell 里已显式设置的变量）----------
# 逐行解析而不是 `source`：Windows 上编辑过的 .env 可能是 CRLF，
# source 会把 \r 带进密钥导致签名莫名其妙地不对。
load_env() {
  [ -f "$ENV_FILE" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    local key=${line%%=*}
    local value=${line#*=}
    key=$(printf '%s' "$key" | tr -d ' ')
    [ -n "$key" ] || continue
    # 已显式设置的 shell 变量优先，避免 .env 覆盖运维临时指定的值
    if [ -z "${!key:-}" ]; then
      export "$key=$value"
    fi
  done < "$ENV_FILE"
}
load_env

MYSQL_CONTAINER=${MYSQL_CONTAINER:-duoduo-mysql}
MYSQL_ROOT_PASSWORD=${MYSQL_ROOT_PASSWORD:-duoduo_root}
DATABASES=${DATABASES:-duoduo_user duoduo_product duoduo_trade duoduo_fulfilment duoduo_marketing duoduo_social}
COS_SECRET_ID=${COS_SECRET_ID:-}
COS_SECRET_KEY=${COS_SECRET_KEY:-}
COS_REGION=${COS_REGION:-ap-guangzhou}
COS_BUCKET=${COS_BUCKET_PRIVATE:-}
COS_PREFIX=${COS_PREFIX:-db-backup}
BACKUP_DIR=${BACKUP_DIR:-/var/backups/duoduo}
KEEP_LOCAL_RUNS=${KEEP_LOCAL_RUNS:-7}

TS=$(date +%Y-%m-%d_%H%M%S)

die() { echo "[FAIL] $*" >&2; exit 1; }
info() { echo "[$(date +%H:%M:%S)] $*"; }

# ---------- COS 签名（v5 / sha1，与 CosSigner.java 同一算法）----------
hex() { od -An -v -tx1 | tr -d ' \n'; }

cos_host() { printf '%s.cos.%s.myqcloud.com' "$COS_BUCKET" "$COS_REGION"; }

cos_sign() {
  local method=$1 key=$2 host
  host=$(cos_host)
  local start end key_time sign_key http_string hashed sig
  local now
  now=$(date +%s)
  # 与 CosObjectStorage 完全一致：提前 60 秒容忍本机时钟回拨，有效期 900 秒。
  start=$((now - 60))
  end=$((now + 900))
  key_time="$start;$end"
  # SignKey 是十六进制字符串，且以它的 ASCII 字节作为第二次 HMAC 的 key
  sign_key=$(printf '%s' "$key_time" | openssl dgst -sha1 -hmac "$COS_SECRET_KEY" -binary | hex)
  # HttpString = method\n/path\n\nhost=...\n（params 为空、结尾必须有换行）
  # ★ 必须用 printf -v：`http_string=$(printf ...)` 会把结尾换行**吃掉**，
  #   而 HttpString 的最后一个字节正是 \n —— 少它服务端只回 SignatureDoesNotMatch。
  printf -v http_string '%s\n/%s\n\nhost=%s\n' "${method,,}" "$key" "$host"
  hashed=$(printf '%s' "$http_string" | openssl dgst -sha1 -binary | hex)
  sig=$(printf 'sha1\n%s\n%s\n' "$key_time" "$hashed" \
    | openssl dgst -sha1 -hmac "$sign_key" -binary | hex)
  printf 'q-sign-algorithm=sha1&q-ak=%s&q-sign-time=%s&q-key-time=%s&q-header-list=host&q-url-param-list=&q-signature=%s' \
    "$COS_SECRET_ID" "$key_time" "$key_time" "$sig"
}

# 备份目录名与库名都是 [A-Za-z0-9._/-]，不需要 URL 编码；
# 手工上传若带空格/中文会被这里拦下，而不是发一个签名对不上的请求。
assert_safe_key() {
  case "$1" in
    *[!A-Za-z0-9._/-]*) die "对象 key 只能包含 A-Z a-z 0-9 . _ / -：$1" ;;
  esac
}

cos_put() {
  local file=$1 key=$2 host auth code body
  assert_safe_key "$key"
  host=$(cos_host)
  auth=$(cos_sign PUT "$key")
  body=$(mktemp)
  code=$(curl -sS --max-time 120 -o "$body" -w '%{http_code}' -X PUT \
    -H "Authorization: $auth" -H 'Content-Type: application/octet-stream' \
    --upload-file "$file" "https://$host/$key" || true)
  if [ "$code" != "200" ]; then
    echo "[FAIL] 上传 $key -> HTTP ${code:-连接失败}" >&2
    head -c 400 "$body" >&2 || true
    echo >&2
    rm -f "$body"
    return 1
  fi
  rm -f "$body"
}

cos_size() {
  local key=$1 host auth code headers
  host=$(cos_host)
  auth=$(cos_sign HEAD "$key")
  headers=$(mktemp)
  code=$(curl -sS --max-time 30 -o /dev/null -D "$headers" -w '%{http_code}' -I \
    -H "Authorization: $auth" "https://$host/$key" || true)
  if [ "$code" != "200" ]; then
    rm -f "$headers"
    echo ""
    return 1
  fi
  local size
  size=$(grep -i '^Content-Length:' "$headers" | tr -d '\r' | awk '{print $2}')
  rm -f "$headers"
  printf '%s' "$size"
}

cos_delete() {
  local key=$1 host auth code
  assert_safe_key "$key"
  host=$(cos_host)
  auth=$(cos_sign DELETE "$key")
  code=$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' -X DELETE \
    -H "Authorization: $auth" "https://$host/$key" || true)
  [ "$code" = "204" ] || [ "$code" = "200" ]
}

require_cos_env() {
  [ -n "$COS_BUCKET" ] || die "COS_BUCKET_PRIVATE 未配置（检查 .env 或环境变量）"
  [ -n "$COS_SECRET_ID" ] || die "COS_SECRET_ID 未配置"
  [ -n "$COS_SECRET_KEY" ] || die "COS_SECRET_KEY 未配置"
}

# ---------- 子命令 ----------
do_check() {
  require_cos_env
  local key="$COS_PREFIX/.probe-$(date +%s)"
  info "校验签名：HEAD cos://$COS_BUCKET/$key（对象不存在应返回 404）"
  local host auth code
  host=$(cos_host)
  auth=$(cos_sign HEAD "$key")
  # 用 HEAD 而不是 GET：备份密钥只需要 PutObject / HeadObject / DeleteObject，
  # 不应为了自检额外要求 GetObject 权限。
  code=$(curl -sS --max-time 30 -o /dev/null -w '%{http_code}' -I \
    -H "Authorization: $auth" "https://$host/$key" || true)
  case "$code" in
    404) info "OK：桶可访问且签名有效（404 = 对象不存在，符合预期）" ;;
    200) info "OK：签名有效（探测对象已存在，仅说明桶可读）" ;;
    403) die "403：密钥无权限或签名不对（检查 COS_SECRET_ID / COS_SECRET_KEY 与桶名）" ;;
    *) die "HTTP ${code:-连接失败}：无法访问 COS（检查网络 / 桶名 / 地域）" ;;
  esac
}

do_upload() {
  require_cos_env
  local file=$1
  [ -f "$file" ] || die "文件不存在：$file"
  local key=${2:-"$COS_PREFIX/manual/$TS-$(basename "$file")"}
  local size remote
  size=$(wc -c < "$file" | tr -d ' ')
  info "上传 $file（$size 字节）→ cos://$COS_BUCKET/$key"
  cos_put "$file" "$key"
  remote=$(cos_size "$key") || die "远端 HEAD 失败：$key"
  [ "$remote" = "$size" ] || die "远端大小 $remote ≠ 本地 $size"
  info "OK：远端校验通过（$remote 字节）"
}

do_delete() {
  require_cos_env
  local key=$1
  info "删除 cos://$COS_BUCKET/$key"
  cos_delete "$key" || die "删除失败：$key"
  info "OK：已删除"
}

do_stat() {
  require_cos_env
  local key=$1 size
  if size=$(cos_size "$key"); then
    info "存在：cos://$COS_BUCKET/$key（$size 字节）"
  else
    info "不存在：cos://$COS_BUCKET/$key"
    exit 1
  fi
}

do_backup() {
  require_cos_env
  command -v docker >/dev/null 2>&1 || die "未找到 docker，无法执行 mysqldump"
  command -v openssl >/dev/null 2>&1 || die "未找到 openssl，无法签名"
  command -v curl >/dev/null 2>&1 || die "未找到 curl"

  local run_dir="$BACKUP_DIR/runs/$TS"
  mkdir -p "$run_dir"
  chmod 700 "$BACKUP_DIR/runs" "$run_dir" 2>/dev/null || true
  local manifest="$run_dir/_manifest.txt"
  : > "$manifest"
  echo "backup_time=$TS" >> "$manifest"
  echo "bucket=$COS_BUCKET" >> "$manifest"
  echo "prefix=$COS_PREFIX/$TS" >> "$manifest"

  docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1 || die "MySQL 容器不存在：$MYSQL_CONTAINER"

  local db expected actual file
  for db in $DATABASES; do
    file="$run_dir/$db.sql.gz"
    info "[dump] $db"
    # 先查真实表数：库不存在 / 连不上时这里就会失败，不会生成空文件还继续上传
    expected=$(docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
      mysql -N -B -uroot -e \
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$db' AND table_type='BASE TABLE'" \
      2>/dev/null | tr -d ' \r')
    case "$expected" in
      ''|0) die "$db：库不存在或没有任何基表（拒绝把空库当成功）" ;;
    esac

    if ! docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$MYSQL_CONTAINER" \
      mysqldump -uroot --single-transaction --quick --routines --triggers \
      --default-character-set=utf8mb4 --set-gtid-purged=OFF --databases "$db" \
      2>"$run_dir/$db.err" | gzip -9 > "$file"; then
      cat "$run_dir/$db.err" >&2 || true
      die "$db：mysqldump 失败"
    fi
    rm -f "$run_dir/$db.err"

    gzip -t "$file" || die "$db：产物 gzip 校验失败"
    actual=$(zgrep -c '^CREATE TABLE' "$file" || true)
    [ "$actual" = "$expected" ] || die "$db：产物表数 $actual ≠ 源库 $expected"
    info "[dump] $db OK：$expected 张表，$(wc -c < "$file" | tr -d ' ') 字节"

    local sha
    sha=$(sha256sum "$file" | awk '{print $1}')
    echo "file=$db.sql.gz size=$(wc -c < "$file" | tr -d ' ') sha256=$sha tables=$actual" >> "$manifest"
  done

  echo "manifest_sha256=$(sha256sum "$manifest" | awk '{print $1}')" >> "$manifest"

  # ---------- 上传 + 两个来源校验 ----------
  local base="cos://$COS_BUCKET/$COS_PREFIX/$TS"
  info "上传到 $base/"
  local f key remote local_size
  for f in "$run_dir"/*.sql.gz "$manifest"; do
    key="$COS_PREFIX/$TS/$(basename "$f")"
    local_size=$(wc -c < "$f" | tr -d ' ')
    cos_put "$f" "$key" || die "上传失败：$key"
    remote=$(cos_size "$key") || die "远端 HEAD 失败：$key"
    [ "$remote" = "$local_size" ] || die "$key：远端 $remote ≠ 本地 $local_size"
    info "[upload] $key OK（$remote 字节）"
  done

  # 本地滚动保留：只留最近 N 次，避免备份盘被历史撑满
  local old
  old=$(ls -1dt "$BACKUP_DIR"/runs/* 2>/dev/null | tail -n +$((KEEP_LOCAL_RUNS + 1)) || true)
  if [ -n "$old" ]; then
    printf '%s\n' "$old" | while IFS= read -r d; do rm -rf "$d"; info "[cleanup] 删除本地旧备份 $d"; done
  fi

  info "完成：6 库备份已上传（$base/），本地目录 $run_dir"
  echo "RESTORE_HINT: gunzip -c $run_dir/duoduo_user.sql.gz | docker exec -i -e MYSQL_PWD=\$MYSQL_ROOT_PASSWORD $MYSQL_CONTAINER mysql -uroot duoduo_user"
}

case "${1:-}" in
  --check) do_check ;;
  --upload) [ $# -ge 2 ] || die "用法：--upload FILE [KEY]"; do_upload "$2" "${3:-}" ;;
  --delete) [ $# -ge 2 ] || die "用法：--delete KEY"; do_delete "$2" ;;
  --stat) [ $# -ge 2 ] || die "用法：--stat KEY"; do_stat "$2" ;;
  '') do_backup ;;
  *) die "未知参数：$1（可用：--check / --upload FILE [KEY] / --stat KEY / --delete KEY）" ;;
esac
