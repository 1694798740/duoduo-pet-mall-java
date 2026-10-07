#!/usr/bin/env bash
# 一条命令配好「GitHub SSH 推送通道」（走 443 端口，绕开常被拦的 22 端口）
#
#   用法：bash scripts/ssh-push-setup.sh
#
# 为什么需要：本机到 github.com 的 **git HTTPS 通道被中间网络干扰**（直连重置 / 代理 502），
# 而 `ssh.github.com:443` 实测**能完成握手**。配好 SSH 后推送就变成稳定动作。
#
# 脚本做什么：
#   ① 没有密钥就生成一把（默认路径 ~/.ssh/id_ed25519，**不会覆盖已有文件**）
#   ② 打印**公钥**（贴到 GitHub 用；私钥永远不出本机、脚本也不会打印它）
#   ③ 尝试 `ssh -T` 验证：
#        · 已认证 → 把 origin 切到 SSH（走 443）并报告
#        · 未认证 → 打印注册地址与"贴完再跑一次"的提示（**此时不动 origin**，避免留下坏的远端）
set -euo pipefail
cd "$(dirname "$0")/.."

KEY="$HOME/.ssh/id_ed25519"
CFG="$HOME/.ssh/config"
REPO_SSH="git@github.com:1694798740/duoduo-pet-mall-java.git"

echo "[1/4] 检查密钥"
if [ -f "$KEY" ]; then
  echo "      已存在 $KEY（不覆盖）"
else
  ssh-keygen -t ed25519 -f "$KEY" -N "" -C "$(whoami)@github" >/dev/null
  echo "      已生成 $KEY（无口令；如需口令可执行 ssh-keygen -p -f $KEY 后补）"
fi

echo "[2/4] 写入 ~/.ssh/config（让 github.com 自动走 443）"
if grep -q "Host github.com" "$CFG" 2>/dev/null; then
  echo "      已存在 Host github.com 配置，跳过"
else
  [ -f "$CFG" ] && cp "$CFG" "$CFG.bak-$(date +%Y%m%d%H%M%S)"
  cat >> "$CFG" <<'EOF'

# 由 scripts/ssh-push-setup.sh 添加：GitHub 走 ssh.github.com:443（22 端口常被拦）
Host github.com
    HostName ssh.github.com
    Port 443
    User git
    IdentityFile ~/.ssh/id_ed25519
    IdentitiesOnly yes
EOF
  echo "      已写入（原文件已备份）"
fi

echo "[3/4] 验证通道"
set +e
out=$(ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -T git@github.com 2>&1)
code=$?
set -e
if [ "$code" = "1" ] && echo "$out" | grep -q "successfully authenticated"; then
  echo "      ✅ $out"
  authed=1
else
  authed=0
  echo "      ⚠️ 尚未认证通过：$out"
fi

echo "[4/4] 结果"
if [ "$authed" = "1" ]; then
  git remote set-url origin "$REPO_SSH"
  echo "      已把 origin 切到 SSH：$REPO_SSH"
  echo "      现在可以直接推送：git push origin main"
else
  echo "      本机公钥（请整行复制到 GitHub）："
  echo
  cat "$KEY.pub"
  echo
  echo "      注册地址：https://github.com/settings/ssh/new"
  echo "      （Title 随便填，如 duoduo-dev；Key type 选 Authentication Key；粘贴上面整行后 Add SSH key）"
  echo "      贴完再跑一次本脚本即可自动切换 origin 并验证。"
  echo "      提示：加 -v 可以看到握手细节；若报 Permission denied (publickey) = 通道通、只是公钥还没注册。"
fi
