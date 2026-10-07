#!/usr/bin/env bash
# S17：把「离线兜底」做成规范动作 —— 一条命令导出整仓离线包并校验
#
#   用法：bash scripts/backup-offline.sh
#
# 为什么需要：远端一旦不可用（网络/误删），本地必须有一个**校验过**的完整恢复件。
# 本轮实测：git format-patch 的补丁兜底**不可靠**（行尾符差异导致必然应用失败，已废弃），
# 所以唯一可信的兜底是整仓 bundle（git bundle verify 会校验完整性）。
#
# 行为：
#   ① 按 HEAD 导出 scripts/backup/r<标记>_main_<短哈希>.bundle
#   ② git bundle verify 校验「完整历史」
#   ③ 只保留最近 2 份 bundle（更早的自动清理，防 backup 目录无限膨胀）
set -euo pipefail
cd "$(dirname "$0")/.."

out="scripts/backup/main_$(git rev-parse --short HEAD).bundle"
echo "[1/3] 导出整仓 bundle → $out"
git bundle create "$out" main

echo "[2/3] 校验完整性（必须看到 complete history）"
git bundle verify "$out" | grep -E "complete history|refs/heads" || {
  echo "⚠️ 校验未通过 —— 这个兜底不可信，请检查"; exit 1; }

echo "[3/3] 只保留最近 2 份"
ls -1t scripts/backup/main_*.bundle 2>/dev/null | tail -n +3 | while read -r f; do
  rm -f "$f"; echo "  已清理旧包：$f"
done

echo
echo "✅ 兜底就绪：$out（$(du -h "$out" | cut -f1)）"
echo "   恢复方式：git clone $out myrepo   或   git fetch $out main:local-tmp"
