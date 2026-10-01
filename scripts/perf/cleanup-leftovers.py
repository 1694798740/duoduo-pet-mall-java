#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试环境「清场」工具：把指定账号名下的**待付款订单**全部取消掉。

为什么需要它（第六十轮踩出来的坑）：
    下单会冻结库存，待付款订单要等 30 分钟超时才被补偿调度关掉并回补库存。
    压测 / 冒烟一旦中途失败或被人为中断，这些订单就留在库里。后果有两个：

      1. 库存被冻住 —— 下一个脚本撞「库存不足 422」，报错的是**下一个脚本**，最难归因；
      2. 三套冒烟（order / payment / settlement）里有「库里不应残留 FROZEN 冻结行」
         这类**全局不变式**断言，残留一多就直接失败，看着像代码回归，其实是数据脏。

    实测连跑几轮压测后库里攒了 754 笔待付款订单 + 752 条 FROZEN，三套冒烟全红。

怎么用：
    # 1) 先体检（看看到底脏不脏、脏在哪些账号）
    docker exec duoduo-mysql mysql -uroot -pduoduo_root -N -B -e "
      SELECT user_id, COUNT(*) FROM duoduo_trade.t_order
       WHERE status='PENDING_PAYMENT' GROUP BY user_id ORDER BY 2 DESC;"

    # 2) 拿到手机号后清理
    python scripts/perf/cleanup-leftovers.py --phones 13948183318,13911151185

    # 密码不是默认口令时
    python scripts/perf/cleanup-leftovers.py --phones 13948183318 --password 你的口令

只做一件事：登录 → 列出待付款 → 逐笔取消。全程走**业务接口**，不直接改库，
因此库存回补、冻结台账释放、状态日志都由服务端按正常逻辑完成，不会留下半截状态。
"""
import argparse
import os
import sys

# 复用 bench.py 里的登录 / 请求 / 清理实现，避免同一套逻辑写两份
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bench  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phones", required=True, help="待清理账号的手机号，逗号分隔")
    ap.add_argument("--password", default=bench.PWD)
    ap.add_argument("--gateway", default=bench.GW)
    args = ap.parse_args()

    bench.PWD = args.password
    bench.GW = args.gateway

    phones = [p.strip() for p in args.phones.split(",") if p.strip()]
    total_cancelled = 0
    failed_accounts = []

    for phone in phones:
        token = bench.login(phone)
        if not token:
            print(f"[{phone}] 登录失败 —— 账号不存在或口令不对，跳过")
            failed_accounts.append(phone)
            continue
        cancelled, failed = bench.drain_pending_orders(token, label=phone)
        total_cancelled += cancelled
        if failed:
            failed_accounts.append(f"{phone}({len(failed)} 笔未取消)")

    print(f"\n清场完成：共取消 {total_cancelled} 笔待付款订单")
    if failed_accounts:
        print(f"未处理干净的账号：{failed_accounts}")
        print("（若返回 422「已关闭不允许取消」，通常是补偿调度刚好把这批关掉了，无需处理）")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
