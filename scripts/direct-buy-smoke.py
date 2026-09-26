#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""直购下单（立即购买）冒烟探针。

验证 POST /api/orders 的 buySkuId / buyQuantity 分支：
  1. 直购下单成功，订单明细 = 该 SKU × 数量，金额 = 单价 × 数量
  2. 直购**不动购物车**（下单前后购物车条目数一致）
  3. 购物车结算语义未被破坏（原路径仍可下单，且会清空已勾选条目）
  4. buySkuId 缺 buyQuantity → 400（参数校验）
  5. 不存在的 SKU → 4xx（快照取不到，拒单）

用法：python scripts/direct-buy-smoke.py [gateway]
默认网关：http://localhost:8080
"""
import json
import random
import sys
import urllib.error
import urllib.request

GATEWAY = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
PASSWORD = "Passw0rd!23"
PASSED = []
FAILED = []


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name)
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  | " + str(detail)[:220]) if detail else ""))


def call(method, path, body=None, token=None, expect=None):
    """返回 (status, payload)。不抛异常，便于断言错误码。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            payload = {"raw": raw}
        return e.code, payload


def main():
    phone = "135" + "".join(random.choice("0123456789") for _ in range(8))
    print("直购冒烟：网关 %s，夹具手机号 %s" % (GATEWAY, phone))

    # 0) 夹具：注册 + 登录 + 地址
    status, _ = call("POST", "/api/auth/register", {"phone": phone, "password": PASSWORD, "nickname": "直购冒烟"})
    check("夹具：注册（200/201）", status in (200, 201), status)
    status, body = call("POST", "/api/auth/login/token", {"phone": phone, "password": PASSWORD})
    token = ((body or {}).get("data") or {}).get("accessToken")
    check("夹具：拿到 accessToken", bool(token), status)
    if not token:
        return
    status, body = call("POST", "/api/addresses", {
        "receiverName": "直购测试", "receiverPhone": phone,
        "province": "广东省", "city": "深圳市", "district": "南山区",
        "detail": "科技园路 1 号", "isDefault": True,
    }, token)
    address_id = ((body or {}).get("data") or {}).get("id")
    check("夹具：新建收货地址", bool(address_id), status)

    # 选一个在售且有库存的 SKU（seed 数据 2001 = 129.00 元，320 件）
    sku_id, sku_price = 2001, 12900

    # 1) 直购下单：2 件
    status, body = call("POST", "/api/orders", {
        "addressId": address_id, "buySkuId": sku_id, "buyQuantity": 2,
        "idempotentKey": "direct-%d" % random.getrandbits(48),
    }, token)
    data = (body or {}).get("data") or {}
    orders = data.get("orders") or []
    first = orders[0] if orders else {}
    items = first.get("items") or []
    check("① 直购下单成功（200/201）", status in (200, 201), status)
    check("① 生成 1 个订单", len(orders) == 1, "orders=%d" % len(orders))
    check("① 明细 = 1 条且数量 2", len(items) == 1 and items[0].get("quantity") == 2, items)
    check("① 金额 = 单价 × 2", first.get("totalAmount") == sku_price * 2,
          "%s vs %s" % (first.get("totalAmount"), sku_price * 2))
    direct_order_no = first.get("orderNo")

    # 2) 直购不动购物车：加一件 → 直购 → 购物车条目数不变
    call("POST", "/api/cart/items", {"skuId": 2006, "quantity": 1}, token)
    status, body = call("GET", "/api/cart", None, token)
    before = len(((body or {}).get("data") or {}).get("items") or [])
    status, _ = call("POST", "/api/orders", {
        "addressId": address_id, "buySkuId": sku_id, "buyQuantity": 1,
        "idempotentKey": "direct-%d" % random.getrandbits(48),
    }, token)
    status, body = call("GET", "/api/cart", None, token)
    after = len(((body or {}).get("data") or {}).get("items") or [])
    check("② 直购不影响购物车条目数", before == after == 1, "before=%s after=%s" % (before, after))

    # 3) 购物车结算原路径仍可用，且清空已勾选条目
    call("PATCH", "/api/cart/items/2006", {"selected": True}, token)
    status, body = call("POST", "/api/orders", {
        "addressId": address_id, "idempotentKey": "cart-%d" % random.getrandbits(48),
    }, token)
    cart_orders = ((body or {}).get("data") or {}).get("orders") or []
    status, body = call("GET", "/api/cart", None, token)
    left = len(((body or {}).get("data") or {}).get("items") or [])
    check("③ 购物车结算仍可下单", status in (200, 201) and len(cart_orders) == 1, status)
    check("③ 购物车结算后已勾选条目被清空", left == 0, "left=%s" % left)

    # 4) buySkuId 缺 buyQuantity → 400
    status, body = call("POST", "/api/orders", {
        "addressId": address_id, "buySkuId": sku_id,
        "idempotentKey": "bad-%d" % random.getrandbits(48),
    }, token)
    check("④ 缺 buyQuantity 被拒（400）", status == 400, "%s %s" % (status, json.dumps(body, ensure_ascii=False)[:160]))

    # 5) 不存在的 SKU → 4xx
    status, body = call("POST", "/api/orders", {
        "addressId": address_id, "buySkuId": 999999, "buyQuantity": 1,
        "idempotentKey": "missing-%d" % random.getrandbits(48),
    }, token)
    check("⑤ 不存在的 SKU 被拒（4xx）", 400 <= status < 500, "%s %s" % (status, json.dumps(body, ensure_ascii=False)[:160]))

    # 6) 直购订单能正常查询（详情接口）
    if direct_order_no:
        status, body = call("GET", "/api/orders/" + str(direct_order_no), None, token)
        detail = (body or {}).get("data") or {}
        check("⑥ 直购订单可查详情", status == 200 and detail.get("orderNo") == direct_order_no, status)

    print("\n结果：%d 通过 / %d 失败" % (len(PASSED), len(FAILED)))
    if FAILED:
        print("失败项：" + "；".join(FAILED))
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
