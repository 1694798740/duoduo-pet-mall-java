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
import itertools
import json
import math
import os
import queue
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"

# 写用例的**总量上限**（不是按时间跑）。
#
# 为什么写接口不能像读接口那样「按时间随便打」：下单会**真实冻结库存**，库存打光的那一刻
# 后面全是 422 STOCK_INSUFFICIENT —— 而压测器把 422 计为错误，那测到的就不是性能，
# 是「库存被我自己打光了」。总量受控 + 每档跑完立刻取消（库存回补），才能既测到真实写路径
# 又不把库打脏。
WRITE_BUDGET = 120

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

    # ---- 真实写库链路（R60 补齐）----
    # 结算预览本质还是读（不落订单、不冻库存），只测它等于「写链路零覆盖」。
    # 下面两条会真的 INSERT：加购写 t_cart、下单写 t_order + t_order_item + 冻结库存。
    {"name": "加入购物车", "kind": "write", "method": "POST", "path": "/api/cart/items",
     "auth": True, "budget": WRITE_BUDGET, "cart": True},
    {"name": "下单（直购）", "kind": "write", "method": "POST", "path": "/api/orders",
     # 注意：下单响应是 `data.orders[0].orderNo`（一次结算可能拆成多笔），
     # 不是 `data.orderNo`。取错字段会得到 None，订单收集不到 → 收尾取消不到 → 库存被挂住。
     #
     # rps=80：下单受 Sentinel「order-place」限流保护（默认 100 QPS，OrderPlaceGuard）。
     # 压测若按最大速率猛打，命中的是限流而不是性能瓶颈 —— 一堆 429 会被计成错误，
     # 报表看起来像「下单接口错误率 17%」，其实是限流在正常工作。
     # 所以这里主动把供给速率压到阈值以下，测的是**限流阈值内的真实耗时**。
     "auth": True, "budget": WRITE_BUDGET, "collect": "orders.0.orderNo", "rps": 80},
    {"name": "支付发起", "kind": "write", "method": "POST", "path": "/api/orders/{orderNo}/pay",
     "auth": True, "budget": WRITE_BUDGET, "from_pool": True},
]


def make_conn():
    return http.client.HTTPConnection("127.0.0.1", 8080, timeout=10)


def http_call(method, path, body=None, token=None, timeout=10, conn=None, want_body=False):
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
        raw = resp.read()
        status = resp.status
        parsed = None
        if want_body:
            try:
                parsed = json.loads(raw.decode("utf-8")).get("data")
            except Exception:
                parsed = None
        return status, (time.perf_counter() - start) * 1000.0, parsed
    except Exception:
        return 0, 0.0, None
    finally:
        if own:
            c.close()


def call_once(method, path, body=None, token=None):
    """一次性调用（建连即关），返回 (status, data)。"""
    status, _, data = http_call(method, path, body, token, want_body=True)
    return status, data


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


class Pacer:
    """
    全局限速器：把请求的**发放速率**压到 rps 以下。

    不控制它的话，受限流的接口（下单 100 QPS）会被压测自己打出一堆 429，
    而 429 属于「请求没办成」，会被计进错误率 —— 于是报表写的是
    「下单错误率 17%」，真相却是限流在正常工作。
    """

    def __init__(self, rps):
        self.interval = 1.0 / float(rps)
        self.lock = threading.Lock()
        self.next_at = time.time()

    def acquire(self):
        with self.lock:
            now = time.time()
            at = max(self.next_at, now)
            self.next_at = at + self.interval
        delay = at - now
        if delay > 0:
            time.sleep(delay)


def dig(data, path):
    """按 'a.0.b' 这种点路径取值（支持数组下标）。取不到返回 None。"""
    cur = data
    for part in str(path).split("."):
        if cur is None:
            return None
        if isinstance(cur, list):
            try:
                cur = cur[int(part)]
            except (ValueError, IndexError):
                return None
        elif isinstance(cur, dict):
            cur = cur.get(part)
        else:
            return None
    return cur


def run_case(case, concurrency, duration, token, ctx):
    """
    固定并发跑一个用例，统计 RT 分布 / QPS / 错误率。

    ctx 里带着写用例要用到的夹具（地址 id、SKU、待支付订单池等）。
    写用例**按总量而非按时长**收尾（见 WRITE_BUDGET 的注释），跑完把副作用交回给调用方清理。
    """
    latencies = []
    errors = []
    codes = {}
    collected = []
    lock = threading.Lock()
    stop_at = time.time() + duration
    budget = case.get("budget")
    pool = ctx.get("pool")
    counter = itertools.count(1)
    pacer = Pacer(case["rps"]) if case.get("rps") else None
    sample_error = []

    def worker(tid):
        conn = make_conn()
        while time.time() < stop_at:
            if budget is not None:
                n = next(counter)
                if n > budget:
                    break
            path = case["path"]
            body = dict(case.get("body") or {})

            if case.get("cart"):
                # 加购：同 SKU 累加数量，不新增行 —— 所以不需要每次换 SKU
                body = {"skuId": ctx["sku"], "quantity": 1}
            elif case.get("from_pool"):
                # 支付发起：每个订单只被支付一次（从池里取，取完即止）
                try:
                    order_no = pool.get_nowait()
                except Exception:
                    break
                path = path.replace("{orderNo}", order_no)
                body = {"channel": "sandbox", "idempotentKey": f"bench-{tid}-{n}"}
                # ⚠️ 取出来的订单号必须**回收进 collected**，否则本档跑完没人取消它。
                # 踩过的坑：支付发起只「消耗」池子不「归还」，339 笔订单全部停在待付款、
                # 库存一直冻着，只有收尾兜底那一次全库扫描才捞得出来 —— 那时要等 30 分钟
                # 超时才释放，中间跑的任何脚本都会撞「库存不足」。
                # 「用掉」不等于「清掉」：订单付没付成，它都还占着库存。
                collected.append(order_no)
            elif case.get("collect"):
                # 下单直购：幂等键必须每条唯一，否则第二条起命中幂等、测的是回放分支
                body = {
                    "addressId": ctx["address_id"],
                    # 幂等键必须**跨并发档也唯一**：只写 f"bench-{tid}-{n}" 的话，
                    # 第二档会复用第一档的键 → 命中幂等重放 → 拿到第一档**已取消**的订单号，
                    # 于是收尾取消时报 422「已关闭不允许取消」，看起来像清理失败，
                    # 真相是这一档有 25 笔根本没真正建单。
                    "idempotentKey": f"bench-{next(ctx['seq'])}",
                    "buySkuId": ctx["sku"],
                    "buyQuantity": 1,
                }

            if pacer:
                pacer.acquire()
            status, ms, data = http_call(case["method"], path, body or None,
                                         token if case["auth"] else None,
                                         conn=conn, want_body=True)
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
                    # 留一条错误报文：只报「422 有 40 个」等于没报，
                    # 不知道是限流、库存不足还是参数校验，排查方向会整个跑偏
                    if not sample_error:
                        msg = (data or {}).get("message") if isinstance(data, dict) else None
                        sample_error.append(f"{status} {msg or ''}")
                elif ok200 and case.get("collect") and isinstance(data, dict):
                    got = dig(data, case["collect"])
                    if got:
                        collected.append(str(got))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
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
        "collected": collected,
        "sample_error": sample_error[:1],
    }


def pick_sku_with_stock():
    """
    挑**可售余量最大**的 SKU，返回 (skuId, 余量)。

    为什么不再写「库存 >= 阈值」：写用例会真实冻结库存（订单池 + 每档下单 + 每档加购），
    而商品详情里的 stock 是「可售余量 = 真实库存 − 已被冻结」—— 跑过几轮之后余量会被压低，
    写死的阈值很容易一个 SKU 都选不出来，脚本直接退出。
    改成「挑最大的那个，再按余量反推预算」，脚本在任何库存水位下都能跑出有意义的数据。
    """
    best = (None, 0)
    page = 1
    while page <= 5:
        status, data = call_once("GET", f"/api/products?page={page}&pageSize=20")
        items = (data or {}).get("items") or []
        if not items:
            break
        for card in items:
            spu = card.get("id")
            if not spu:
                continue
            st, detail = call_once("GET", f"/api/products/{spu}")
            if st != 200:
                continue
            for sku in (detail or {}).get("skus") or []:
                if not sku.get("sellable"):
                    continue
                stock = int(sku.get("stock") or 0)
                if stock > best[1]:
                    best = (sku.get("id"), stock)
        page += 1
    return best


def prepare_write_fixture(token, sku_id, pool_size):
    """
    准备写链路夹具：一个收货地址 + 一批「待支付」订单池。

    地址是下单的硬前置（没有它下单必然 422，测到的是参数校验失败）。
    订单池是「支付发起」用例的原料 —— 支付必须作用在**真实存在的订单**上，
    而每笔订单只能被支付一次，所以提前按预算建好，用完即止。
    池子大小要按「预算 × 并发档数」给：只给一档的量，第二档就取不到订单，
    表现是「第二档 0 样本、错误率 100%」，看着像服务挂了，其实是夹具不够。
    """
    ctx = {"sku": sku_id, "address_id": None, "pool": queue.Queue(), "seq": itertools.count(1)}

    status, data = call_once("POST", "/api/addresses", {
        "receiverName": "压测收货", "receiverPhone": "13900000000",
        "province": "上海市", "city": "上海市", "district": "浦东新区",
        "detail": "张江路 1 号", "isDefault": True,
    }, token)
    if isinstance(data, dict) and data.get("id"):
        ctx["address_id"] = data["id"]
    if not ctx["address_id"]:
        # 也可能该账号早就有默认地址了：取一条来用
        status, data = call_once("GET", "/api/addresses/default", None, token)
        if isinstance(data, dict) and data.get("id"):
            ctx["address_id"] = data["id"]
    if not ctx["address_id"]:
        print("拿不到收货地址 —— 下单会全走 422 分支，数据无意义，终止")
        return None

    made = 0
    pacer = Pacer(80)  # 建池也要避开 100 QPS 的限流，否则池子建不满
    for i in range(pool_size):
        pacer.acquire()
        st, d = call_once("POST", "/api/orders", {
            "addressId": ctx["address_id"],
            "idempotentKey": f"bench-pool-{i}",
            "buySkuId": sku_id,
            "buyQuantity": 1,
        }, token)
        if 200 <= st < 300:
            no = dig(d or {}, "orders.0.orderNo")
            if no:
                ctx["pool"].put(str(no))
                made += 1
    if made == 0:
        print("订单池为空 —— 「支付发起」会全部 404，数据无意义，终止")
        return None
    print(f"写链路夹具：地址 1 个、待支付订单池 {made} 笔")
    return ctx


def drain_pending_orders(token, label=""):
    """
    把当前账号名下**所有**待付款订单取消掉。

    <p>为什么必须做（探针自还原）：下单会冻结库存，待付款订单要等 30 分钟超时
    才会被补偿调度关掉并回补库存。压测一旦中途失败、被中断、或某笔取消没成功，
    这些订单就会留在库里 —— 下一次跑压测、乃至下一个冒烟脚本都会撞上
    「库存不足」，而报错的是**下一个脚本**，最难归因。

    <p>实测教训：连跑几轮压测后库里攒了 754 笔待付款订单 + 752 条 FROZEN 冻结行，
    直接让三套冒烟的「无残留冻结」全局不变式失败。所以这里在**开跑之前**先扫一遍
    自己账号的历史遗留，跑完再兜一次底 —— 让脚本无论怎么中断都能自愈。
    """
    cancelled = 0
    failed = []
    # ⚠️ 每轮都重新翻**第 1 页**，不要用 page++ 往下翻。
    # 分页游标 + 边翻边删 = 经典错位：取消掉第 1 页的 50 笔后，它们立刻从
    # 「待付款」这个过滤条件里出列，原本第 2 页的内容前移到了第 1 页 ——
    # 于是 page=2 拿到的是原第 3 页，每轮漏掉一半。实测 182 笔只清掉 100 笔。
    # 每轮都从第 1 页取，取消一批就出列一批，才保证不漏。
    for _ in range(100):  # 兜底：异常分页形状时不要死循环
        status, data = call_once("GET", "/api/orders?status=PENDING_PAYMENT&page=1&pageSize=50", None, token)
        if status != 200 or not isinstance(data, dict):
            break
        rows = data.get("items") or data.get("records") or data.get("list") or []
        if not rows:
            break
        progress = False
        for row in rows:
            no = row.get("orderNo")
            if not no:
                continue
            st, _ = call_once("POST", f"/api/orders/{no}/cancel", {"reason": "压测自愈清理"}, token)
            if 200 <= st < 300:
                cancelled += 1
                progress = True
            else:
                failed.append((no, st))
        if not progress:
            # 整页都没取消成功（多半是状态已经不允许），再翻也是一样的结果
            break
    tag = f"（{label}）" if label else ""
    if cancelled or failed:
        print(f"自愈清理{tag}：取消历史待付款订单 {cancelled} 笔"
              + (f"，{len(failed)} 笔未取消（状态={[s for _, s in failed[:5]]}）" if failed else ""))
    return cancelled, failed


def cleanup_write_fixture(ctx, token, extra_orders, drain_pool=False, clear_cart=True):
    """
    收尾：取消压测建的订单，并清空加进购物车的那条。

    为什么必须做：下单会冻结库存，不取消的话这些待付款订单要等 30 分钟超时才释放；
    下一次跑（或下一个冒烟脚本）就会撞上「库存不足」，而报错的是**下一个脚本**，最难归因。

    `drain_pool` 只在最终收尾时置 True。
    踩过的坑：每档跑完连订单池一起取消，结果「支付发起」用例打到的是**已被取消的订单**，
    错误率 100% —— 而且取消已取消的订单还会返回失败，把真正的信号淹掉。
    """
    # 去重：同一订单号只取消一次。重复取消会拿到 422「已关闭不允许取消」，
    # 报出来像是「清理失败」，实际只是自己取消了两次 —— 把真信号淹掉。
    seen = set()
    targets = [o for o in extra_orders if not (o in seen or seen.add(o))]
    if drain_pool and ctx:
        while True:
            try:
                targets.append(ctx["pool"].get_nowait())
            except Exception:
                break
    cancelled = 0
    failed = []
    for order_no in targets:
        st, _ = call_once("POST", f"/api/orders/{order_no}/cancel", {"reason": "压测收尾"}, token)
        if 200 <= st < 300:
            cancelled += 1
        else:
            failed.append((order_no, st))
    if clear_cart and ctx:
        call_once("DELETE", f"/api/cart/items/{ctx['sku']}", None, token)
    print(f"已清理：取消订单 {cancelled} 笔" + (f"（{len(failed)} 笔未取消，状态={[s for _, s in failed[:5]]}）" if failed else ""))
    return failed


def cart_quantity_of(token, sku_id):
    """购物车里某个 SKU 的当前件数（用于对账）。"""
    status, data = call_once("GET", "/api/cart", None, token)
    if status != 200:
        return None
    for item in (data or {}).get("items") or []:
        if str(item.get("skuId")) == str(sku_id):
            return item.get("quantity")
    return 0


def verify_cart_conservation(ctx, token, ok_count):
    """
    加购的**数量守恒**对账：成功 N 次，购物车就该多 N 件。

    <p>为什么单独做这一条：加购在并发下最危险的失效方式不是报错，而是**静默丢更新** ——
    十个事务读到同一个旧值再各自写回，实测 200 次请求**全部返回 200，总数却只有 46**。
    错误率 / P99 / QPS 三项全绿，只有对账才看得见。所以这里钉的是「成功次数 == 数量增量」
    这条不变性质，而不是「现在有几件」（写死数字会在任何一次顺序调整后假红）。
    """
    got = cart_quantity_of(token, ctx["sku"])
    if got is None:
        return ["购物车对账失败：读不到购物车"]
    if got != ok_count:
        return [f"★ 加购数量不守恒：成功 {ok_count} 次，购物车只多了 {got} 件（并发丢失更新）"]
    return []


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

    # 开跑前先自愈：上一轮（或上一次被中断的运行）留下的待付款订单会把库存冻住，
    # 不先清掉的话本轮写链路会撞「库存不足」，测到的是库存而不是性能。
    drain_pending_orders(token, label="开跑前")

    gradients = [int(x) for x in args.concurrency.split(",")]

    # 取一个**库存充足**的 SKU：下单会冻结库存，拿库存小的 SKU 会在压测中途打光，
    # 后半程全是 422 STOCK_INSUFFICIENT —— 那时测到的就不是性能，是「库存被我自己打光」。
    # 库存要扛住**全部**压力：订单池 + 每档下单 + 每档加购，三者冻的是同一个 SKU 的库存。
    # 所以预算不是写死的，而是按「最大可售余量 ÷ 总压力份数」反推 —— 库存水位低的时候
    # 自动少跑一些，也强过跑出一堆 422 库存不足（那测到的是库存，不是性能）。
    sku_id, sku_stock = pick_sku_with_stock()
    if not sku_id or sku_stock <= 0:
        print("找不到可售的 SKU —— 下单会全部走校验失败分支，数据无意义，终止")
        return 2
    pressure = 3 * len(gradients) + 1
    budget = max(20, min(WRITE_BUDGET, sku_stock // pressure))
    for case in CASES:
        if case.get("budget"):
            case["budget"] = budget
    print(f"压测用 SKU：{sku_id}（可售余量 {sku_stock}）→ 写用例每档预算 {budget} 条")
    for case in CASES:
        if case.get("body") and "buySkuId" in case["body"]:
            case["body"]["buySkuId"] = sku_id
    print(f"压测用 SKU：{sku_id}")

    ctx = prepare_write_fixture(token, sku_id, budget * len(gradients))
    if ctx is None:
        return 2
    created_orders = []

    report = {"gateway": GW, "gradients": gradients, "duration": args.duration, "cases": {}}
    failures = 0

    try:
        for case in CASES:
            baseline = BASELINE[case["kind"]]
            print(f"\n=== {case['name']} [{case['kind']}] {case['method']} {case['path']}")
            series = []
            # 对账用的成功计数：加购用例跑完要拿它跟购物车增量核对（查丢更新）
            ok_total = 0
            for c in gradients:
                stat = run_case(case, c, args.duration, token, ctx)
                ok_total += stat["codes"].get(200, 0)
                # 写用例跑完立刻收尾：库存回补后再跑下一档，否则档与档之间会互相饿死
                if stat["collected"]:
                    created_orders.extend(stat["collected"])
                    cleanup_write_fixture(ctx, token, stat["collected"])
                    stat["collected"] = []
                if case.get("cart"):
                    # 加购用例每档跑完就清空：购物车一直累加会先撞上库存上限，
                    # 后面的 422 是「库存不足」而不是性能问题，会把真实数据淹掉
                    cleanup_write_fixture(ctx, token, [], clear_cart=True)
                problems = judge(case, stat, baseline)
                if problems:
                    failures += 1
                    print(f"         状态码分布={stat['codes']}  样例={stat.get('sample_error')}")
                series.append(stat)
                flag = "OK  " if not problems else "FAIL"
                print(f"  [{flag}] 并发={c:<3} QPS={stat['qps']:<8} avg={stat['avg_ms']:<7} "
                      f"P95={stat['p95_ms']:<7} P99={stat['p99_ms']:<7} 错误率={stat['err_rate']}")
                for p in problems:
                    print(f"         - {p}")

            if case.get("cart"):
                # 清空 → 跑之前先归零，保证「增量」就是本次压测造成的
                call_once("DELETE", f"/api/cart/items/{ctx['sku']}", None, token)
                # 上面那轮已经把条目删了，这里重跑一次纯对账用的小批量并发
                verify_stat = run_case(case, 10, 3, token, ctx)
                vproblems = verify_cart_conservation(ctx, token, verify_stat["codes"].get(200, 0))
                if vproblems:
                    failures += 1
                    print("  [FAIL] 加购数量守恒对账")
                    for p in vproblems:
                        print(f"         - {p}")
                else:
                    print(f"  [PASS] 加购数量守恒对账：成功 {verify_stat['codes'].get(200, 0)} 次，购物车增量一致")

            report["cases"][case["name"]] = {"kind": case["kind"], "series": series}
    finally:
        # 无论中途有没有异常，剩余订单池都要退干净（否则库存被挂住）
        leftover = cleanup_write_fixture(ctx, token, [], drain_pool=True)
        # 兜底：以「库里这个账号还剩多少待付款」为准再扫一遍。
        # 上面那轮只取消脚本**知道**的订单号；中途失败 / 响应没解析出订单号的那些，
        # 只有按状态回查才捞得出来 —— 这才是「自还原」该有的口径。
        _, still = drain_pending_orders(token, label="收尾兜底")
        if leftover or still:
            print(f"警告：仍有 {len(leftover) + len(still)} 笔订单未取消"
                  f"（可能已支付/状态不允许），请人工核对")

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
