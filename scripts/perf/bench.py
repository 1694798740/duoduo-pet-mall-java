#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
R60 · 核心接口性能压测（纯标准库，无需 JMeter）

为什么不用 JMeter：本机没有 JMeter 运行时，下载安装会引入一个与环境强耦合的依赖，
而这个脚本要能被 CI 直接跑起来。压测器的核心是可控并发 + 精确的耗时分布，
标准库（urllib + threading）足够；JMeter 已产出的 .jmx 保留在 scripts/perf/ 供人工复核。

指标口径（业界通用，脚本内写死为基线，可在 CLI 覆盖）：
  - 读接口：avg <= 300ms、P95 <= 600ms、P99 <= 1000ms、错误率 < 0.1%
  - 写接口：avg <= 800ms、P95 <= 1500ms、P99 <= 3000ms、错误率 < 0.1%
  - 并发梯度 10 / 25 / 50 / 100，逐步加压直到 QPS 不再增长（拐点）

用法：
  python scripts/perf/bench.py                 # 跑默认梯度
  python scripts/perf/bench.py --concurrency 20 --duration 15
  python scripts/perf/bench.py --check          # 只判定是否达标（退出码 0/1，给 CI 用）
"""
import argparse
import http.client
import json
import math
import os
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"

# 读 / 写两套基线：写接口跨服务且有事务，天然比读慢，用同一套数字会误判所有写接口
BASELINE = {
    "read": {"avg": 300, "p95": 600, "p99": 1000, "err_rate": 0.001},
    "write": {"avg": 800, "p95": 1500, "p99": 3000, "err_rate": 0.001},
}

# 核心接口（按「用户实际会走的路径」选，不是按实现方便选）。
# 路径取自冒烟脚本真实调用过的那几条，写错路径会测到一个恒 500 的接口 ——
# 那不是性能数据（第一次跑就踩了这个坑：/api/products/home 并不存在，
# 被 /{spuId} 捕获成「把 home 转 Long」→ 500，压测器报出一堆「错误率 100%」）。
CASES = [
    {"name": "商品列表", "kind": "read", "method": "GET", "path": "/api/products?pageSize=20", "auth": False},
    {"name": "商品搜索", "kind": "read", "method": "GET",
     "path": "/api/products?keyword=%E7%8C%AB%E7%B2%AE&pageSize=20", "auth": False},
    {"name": "商品详情", "kind": "read", "method": "GET", "path": "/api/products/1001", "auth": False},
    {"name": "类目树", "kind": "read", "method": "GET", "path": "/api/categories", "auth": False},
    {"name": "闲置列表", "kind": "read", "method": "GET",
     "path": "/api/listings/search?page=1&pageSize=20", "auth": False},
    {"name": "结算预览", "kind": "write", "method": "POST", "path": "/api/orders/preview",
     "auth": True, "body": {"buySkuId": None, "buyQuantity": 1}},
]


def make_conn():
    return http.client.HTTPConnection("127.0.0.1", 8080, timeout=10)


def http_call(method, path, body=None, token=None, timeout=10, conn=None):
    """单次请求。conn 为空时自建短连接（用于登录等一次性调用）。"""
    own = conn is None
    c = conn or make_conn()
    try:
        headers = {}
        data = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        start = time.perf_counter()
        c.request(method, path, body=data, headers=headers)
        resp = c.getresponse()
        resp.read()
        status = resp.status
        return status, (time.perf_counter() - start) * 1000.0
    except Exception:
        return 0, 0.0
    finally:
        if own:
            c.close()


def ensure_user(phone):
    """
    取得压测令牌：先登录，登录不上就换一个随机手机号注册后再登录。

    <p>压测账号可能已被别的脚本用别的密码注册过（实测 13945673533 就是），
    登录失败会让写接口全变 401 —— 而 401 不属于 5xx，压测器会把它统计成「成功」，
    测出来的「QPS 4400、P99 5ms」其实是鉴权失败的性能，是彻头彻尾的假绿。
    """
    token = login(phone)
    if token:
        return token
    for _ in range(3):
        alt = "139" + "".join(str(random.randint(0, 9)) for _ in range(8))
        try:
            body = json.dumps({"phone": alt, "password": PWD, "nickname": "压测账号"}).encode("utf-8")
            req = urllib.request.Request(GW + "/api/auth/register", data=body, method="POST")
            req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=10) as resp:
                resp.read()
        except urllib.error.HTTPError:
            pass
        except Exception:
            pass
        token = login(alt)
        if token:
            print(f"改用新注册的压测账号 {alt}")
            return token
    return None


def login(phone):
    body = json.dumps({"phone": phone, "password": PWD}).encode("utf-8")
    req = urllib.request.Request(GW + "/api/auth/login/token", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return ((data.get("data") or {}).get("accessToken"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            data = json.loads(raw)
            return ((data.get("data") or {}).get("accessToken"))
        except Exception:
            return None


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * pct / 100.0
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi:
        return sorted_values[int(k)]
    return sorted_values[lo] * (hi - k) + sorted_values[hi] * (k - lo)


def run_case(case, concurrency, duration, token):
    """固定并发 + 固定时长，统计 RT 分布 / QPS / 错误率。"""
    latencies = []
    errors = []
    codes = {}
    lock = threading.Lock()
    stop_at = time.time() + duration

    def worker():
        # 每个线程持有一条长连接（keep-alive）：压测器每请求新建 TCP 连接会先把
        # 本机的临时端口耗尽，到那时测到的「错误」是压测器自己的，不是服务的。
        conn = make_conn()
        while time.time() < stop_at:
            status, ms = http_call(case["method"], case["path"],
                                   case.get("body"), token if case["auth"] else None,
                                   conn=conn)
            if status == 0:
                try:
                    conn.close()
                except Exception:
                    pass
                conn = make_conn()
            with lock:
                if ms > 0:
                    latencies.append(ms)
                codes[status] = codes.get(status, 0) + 1
                # 非 2xx 一律计为失败：401/422 也是「这次请求没办成」，
                # 只把 5xx 算错误会让鉴权失败、参数校验失败被统计成成功（假绿的经典来源）。
                ok200 = 200 <= status < 300
                if not ok200:
                    errors.append((status, ms))

    threads = [threading.Thread(target=worker) for _ in range(concurrency)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.time() - t0

    ordered = sorted(latencies)
    total = len(ordered)
    ok = total - len(errors)
    return {
        "concurrency": concurrency,
        "requests": total,
        "qps": round(total / elapsed, 2) if elapsed > 0 else 0.0,
        "avg_ms": round(statistics.mean(ordered), 1) if ordered else 0.0,
        "p50_ms": round(percentile(ordered, 50), 1),
        "p95_ms": round(percentile(ordered, 95), 1),
        "p99_ms": round(percentile(ordered, 99), 1),
        "max_ms": round(ordered[-1], 1) if ordered else 0.0,
        "errors": len(errors),
        "err_rate": round(len(errors) / total, 5) if total else 1.0,
        "codes": codes,
    }


def judge(case, stat, baseline):
    """按基线判定（达标 / 超标），返回问题列表。"""
    problems = []
    if stat["requests"] == 0:
        return ["无有效样本"]
    if stat["avg_ms"] > baseline["avg"]:
        problems.append(f"平均 {stat['avg_ms']}ms > 基线 {baseline['avg']}ms")
    if stat["p95_ms"] > baseline["p95"]:
        problems.append(f"P95 {stat['p95_ms']}ms > 基线 {baseline['p95']}ms")
    if stat["p99_ms"] > baseline["p99"]:
        problems.append(f"P99 {stat['p99_ms']}ms > 基线 {baseline['p99']}ms")
    if stat["err_rate"] > baseline["err_rate"]:
        problems.append(f"错误率 {stat['err_rate']:.4f} > 基线 {baseline['err_rate']}")
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--concurrency", default="10,25,50", help="并发梯度，逗号分隔")
    ap.add_argument("--duration", type=int, default=10, help="每档持续秒数")
    ap.add_argument("--phone", default="13945673533")
    ap.add_argument("--out", default="logs/perf/r60-bench.json")
    ap.add_argument("--check", action="store_true", help="只判定达标，退出码 0/1")
    args = ap.parse_args()

    token = ensure_user(args.phone)
    if token:
        print("已取得测试令牌")
    else:
        print("未取到令牌 —— 需要鉴权的接口会以 401 返回，测到的是鉴权失败而非真实性能，终止")
        return 2

    # 结算预览需要真实 SKU：从商品详情里取第一个 skuId（取不到就必须报错退出，
    # 否则压测的是一个必失败的校验分支，测出来的「全绿」是假的）
    sku_id = None
    status, body_raw = 0, None
    try:
        req = urllib.request.Request(GW + "/api/products/1001")
        with urllib.request.urlopen(req, timeout=10) as resp:
            status = resp.status
            body_raw = json.loads(resp.read().decode("utf-8"))
    except Exception:
        pass
    if status == 200 and body_raw:
        skus = ((body_raw.get("data") or {}).get("skus") or [])
        if skus:
            sku_id = skus[0].get("skuId") or skus[0].get("id")
    if sku_id is None:
        print("取不到商品 SKU —— 结算预览会全部走参数校验失败分支，数据无意义，终止")
        return 2
    for case in CASES:
        if case.get("body") and "buySkuId" in case["body"]:
            case["body"]["buySkuId"] = sku_id
    print(f"压测用 SKU：{sku_id}")

    gradients = [int(x) for x in args.concurrency.split(",")]
    report = {"gateway": GW, "gradients": gradients, "duration": args.duration, "cases": {}}
    failures = 0

    for case in CASES:
        baseline = BASELINE[case["kind"]]
        print(f"\n=== {case['name']} [{case['kind']}] {case['method']} {case['path']}")
        series = []
        for c in gradients:
            stat = run_case(case, c, args.duration, token)
            problems = judge(case, stat, baseline)
            if problems:
                failures += 1
            series.append(stat)
            flag = "OK  " if not problems else "FAIL"
            print(f"  [{flag}] 并发={c:<3} QPS={stat['qps']:<8} avg={stat['avg_ms']:<7} "
                  f"P95={stat['p95_ms']:<7} P99={stat['p99_ms']:<7} 错误率={stat['err_rate']}")
            if problems:
                for p in problems:
                    print(f"         - {p}")
        report["cases"][case["name"]] = {"kind": case["kind"], "series": series}

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n结果已写入 {args.out}")

    # 拐点：QPS 增幅低于 10% 即认为到拐点（再加压只会堆延迟）
    print("\n=== 拐点观察（QPS 随并发的增幅）")
    for name, payload in report["cases"].items():
        qs = [s["qps"] for s in payload["series"]]
        note = "、".join(f"{g}->{q}" for g, q in zip(gradients, qs))
        growth = ""
        if len(qs) >= 2 and qs[0] > 0:
            growth = f"（末档较首档 +{(qs[-1] / qs[0] - 1) * 100:.0f}%）"
        print(f"  {name}: {note} {growth}")

    if args.check:
        print("\n=== 基线判定：" + ("全部达标" if failures == 0 else f"{failures} 项超标"))
        return 0 if failures == 0 else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
