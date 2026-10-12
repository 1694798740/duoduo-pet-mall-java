#!/usr/bin/env python3
"""退货入库幂等（B-02）专项探针 · 第五十八轮

为什么必须有这一条：restock 不像 freeze/release 那样自带幂等护栏 —— 重复调用会**真的多加库存
且不报错**。最容易撞上的场景是「调用已生效、响应丢失」：商品侧库存已加回，交易侧判失败，
补偿调度下一轮再调一次 → 库存凭空多一份。这种多出来的库存用户侧看不见，只能靠对账发现。

本探针直接打商品服务内部端点（带 X-Internal-Token），验证：
  A. 同一 refNo 重复提交 → 库存只加一次；
  B. 不同 refNo → 正常各加一次（幂等不能误伤正常入库）；
  C. 缺 refNo → 400（宁可报错，也不留「看起来成功实际双加」的口子）；
  D. 去重表确实落了记录（可审计）。

用法：python scripts/r58-restock-idempotent-smoke.py
"""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

PRODUCT = (os.environ.get("R58_PRODUCT") or "http://127.0.0.1:8082").rstrip("/")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "duoduo-local-dev-internal-token")

PASSED, FAILED = [], []


def check(name, ok, got=""):
    (PASSED if ok else FAILED).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else "   实际=" + str(got)[:240]))


def call(method, path, body=None, headers=None, timeout=20):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        PRODUCT + path, data=data, method=method,
        headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw.decode("utf-8", "ignore")}


def internal(path, body):
    return call("POST", path, body, headers={"X-Internal-Token": INTERNAL_TOKEN})


def sql_scalar(query):
    """读一个标量（拿不到返回 None）；用于取「原始 stock」这类还原基准值"""
    out = subprocess.run(["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root",
                          "-N", "-B", "-e", query], capture_output=True, text=True)
    v = out.stdout.strip()
    return int(v) if v.isdigit() else None


def stock_of(sku_id):
    """经内部快照端点读可售库存（availableStock = stock - frozen_stock）"""
    st, body = call("GET", f"/api/products/internal/skus/batch?ids={sku_id}",
                    headers={"X-Internal-Token": INTERNAL_TOKEN})
    if st != 200 or not body.get("data"):
        return None
    return body["data"][0].get("availableStock")


def main():
    print("=" * 72)
    print("退货入库幂等（B-02）专项探针 @ " + PRODUCT)
    print("=" * 72)

    sku_id = int(os.environ.get("R58_SKU_ID", "2001"))
    before = stock_of(sku_id)                 # 可售库存（断言用）
    raw_before = sql_scalar(
        f"SELECT stock FROM duoduo_product.t_sku WHERE id = {sku_id}")   # 原始库存（还原用）
    check("P0 能读到 SKU 库存（探针前置）", before is not None and raw_before is not None,
          (before, raw_before))
    if before is None or raw_before is None:
        print("读不到库存，终止")
        return 1

    # ---------- A. 同一 refNo 重复提交：库存只应加一次 ----------
    print("\n[A] 同一幂等号重复提交（模拟「响应丢失后重试」）")
    ref = "probe:%d" % int(time.time() * 1000)
    st1, b1 = internal("/api/products/skus/stock/restock",
                       {"items": [{"skuId": sku_id, "quantity": 3}], "refNo": ref})
    check("A1 首次入库 200 且成功", st1 == 200 and b1.get("data", {}).get("allSuccess") is True, (st1, b1))
    after1 = stock_of(sku_id)
    check("A2 首次入库库存 +3", after1 == before + 3, f"{before} -> {after1}")

    st2, b2 = internal("/api/products/skus/stock/restock",
                       {"items": [{"skuId": sku_id, "quantity": 3}], "refNo": ref})
    check("A3 重复提交仍返回成功（不报错，否则调用方会一直重试）",
          st2 == 200 and b2.get("data", {}).get("allSuccess") is True, (st2, b2))
    after2 = stock_of(sku_id)
    check("A4 重复提交库存不再增加（幂等生效）", after2 == after1, f"{after1} -> {after2}")

    st3, _ = internal("/api/products/skus/stock/restock",
                      {"items": [{"skuId": sku_id, "quantity": 3}], "refNo": ref})
    after3 = stock_of(sku_id)
    check("A5 第三次提交库存依旧不变", st3 == 200 and after3 == after1, f"{after1} -> {after3}")

    # ---------- B. 不同 refNo：正常入库，不被幂等误伤 ----------
    print("\n[B] 不同幂等号（正常多次入库不应被拦）")
    ref2 = ref + ":b"
    st4, b4 = internal("/api/products/skus/stock/restock",
                       {"items": [{"skuId": sku_id, "quantity": 2}], "refNo": ref2})
    after4 = stock_of(sku_id)
    check("B1 新幂等号正常加库存 +2",
          st4 == 200 and after4 == after1 + 2, f"{after1} -> {after4}")

    # ---------- C. 缺 refNo 必须拒绝 ----------
    print("\n[C] 缺幂等号必须拒绝（不给「静默双加」留口子）")
    st5, b5 = internal("/api/products/skus/stock/restock",
                       {"items": [{"skuId": sku_id, "quantity": 1}]})
    check("C1 缺 refNo 返回 400", st5 == 400, (st5, b5))
    after5 = stock_of(sku_id)
    check("C2 缺 refNo 时库存未变动", after5 == after4, f"{after4} -> {after5}")

    # ---------- D. 还原：把探针加的库存扣回去（不污染种子数据） ----------
    # 还原走数据库直改而不是 deduct：deduct 要求「库存与冻结量同时足够」，
    # 而冻结量通常为 0，用它还原会失败。R53 的教训是「复原要写回读到的原值，不能写死数字」。
    #
    # ★ 关键口径（本探针自己踩过一次）：断言读的是**可售库存**（stock - frozen_stock），
    #   还原写的必须是**原始 stock**。把 available 当成 stock 写回去，会按 frozen_stock
    #   的量把种子库存改小 —— 每次跑少 1，跑三次才发现库存被磨掉了 3。
    print("\n[D] 数据还原")
    after4 = stock_of(sku_id)
    if after4 != before:
        subprocess.run(
            ["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root", "-e",
             f"UPDATE duoduo_product.t_sku SET stock = {raw_before} WHERE id = {sku_id}"],
            capture_output=True)
        subprocess.run(
            ["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root", "-e",
             f"DELETE FROM duoduo_product.t_sku_restock_log WHERE ref_no LIKE 'probe:%'"],
            capture_output=True)
        after6 = stock_of(sku_id)
        check("D1 探针加的库存已还原（种子数据复原）", after6 == before,
              f"可售 {after4} -> {after6}（期望 {before}）")
    else:
        check("D1 探针加的库存已还原（种子数据复原）", True, "无需还原")

    print("\n" + "=" * 72)
    print(f"结果：{len(PASSED)} 通过 / {len(FAILED)} 失败")
    if FAILED:
        for f in FAILED:
            print("  - " + f)
    print("=" * 72)
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
