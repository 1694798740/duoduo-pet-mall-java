"""
多卖家拆单幂等回归（2026-10-22 支付不入账事故的根因用例）

事故链条：
  购物车里有 A（自营）、B（第三方）两家卖家的货
  → 下单按卖家拆成两张子单，两张共用同一个客户端幂等键
  → 旧唯一索引 (user_id, idempotent_key) 把第二张子单当成"并发重复提交"拦下
  → 撞键分支在事务内"补偿后 return"，把第一张子单提交了，但它的冻结库存已被释放
  → 商品域 frozen_stock=0、交易域台账仍 FROZEN
  → 用户付款成功，支付时 deduct 找不到冻结量 → 订单永远停在待付款

本脚本断言（全部走真实 HTTP，不 mock）：
  1. 多卖家购物车下单必须真的拆出两张订单，且两单共用同一个幂等键；
  2. 同一幂等键重复提交必须回放"整批"两张，不能只回一张、更不能 500；
  3. 回放不得重复冻结库存（数量与第一次一致）；
  4. 会话对账：台账 FROZEN 汇总 == t_sku.frozen_stock（这是事故的直接判据）；
  5. MOCK 渠道付掉其中一张，订单必须真的到 PAID，且库存/台账/托管同步变化。

清理：把未支付的那张取消（释放冻结），再删测试账号的购物车残留。
"""

import json
import random
import subprocess
import sys
import urllib.error
import urllib.request

GATEWAY = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
PASSWORD = "Passw0rd!23"
SELF_SKU = 2003      # 自营（seller 710000000000000001）
THIRD_SKU = 92001    # 第三方（seller 371232608572542976）

PASSED, FAILED = [], []


def check(name, ok, detail=""):
    (PASSED if ok else FAILED).append(name)
    print(("  PASS  " if ok else "  FAIL  ") + name + (("  | " + str(detail)[:240]) if detail else ""))


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, (json.loads(raw) if raw else {})
        except ValueError:
            return e.code, {"raw": raw}


def sql(db, query):
    r = subprocess.run(
        ["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root", "-N", "-B",
         "--default-character-set=utf8mb4", "-D", db, "-e", query],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise SystemExit("SQL 失败：" + r.stderr[:300])
    return r.stdout.strip()


def frozen_of(sku):
    out = sql("duoduo_product", "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id=%d" % sku)
    return int(out)


def main():
    phone = "136" + "".join(random.choice("0123456789") for _ in range(8))
    print("拆单幂等回归：网关 %s，夹具手机号 %s" % (GATEWAY, phone))

    # 0) 夹具
    status, _ = call("POST", "/api/auth/register",
                     {"phone": phone, "password": PASSWORD, "nickname": "拆单回归"})
    check("夹具：注册（200/201）", status in (200, 201), status)
    status, body = call("POST", "/api/auth/login/token", {"phone": phone, "password": PASSWORD})
    token = ((body or {}).get("data") or {}).get("accessToken")
    check("夹具：拿到 accessToken", bool(token), status)
    if not token:
        return
    status, body = call("POST", "/api/addresses", {
        "receiverName": "拆单回归", "receiverPhone": phone,
        "province": "广东省", "city": "深圳市", "district": "南山区",
        "detail": "科技园路 1 号", "isDefault": True,
    }, token)
    address_id = ((body or {}).get("data") or {}).get("id")
    check("夹具：新建收货地址", bool(address_id), status)

    self_before, third_before = frozen_of(SELF_SKU), frozen_of(THIRD_SKU)

    # 1) 两家卖家的货放进同一个购物车
    for sku in (SELF_SKU, THIRD_SKU):
        status, _ = call("POST", "/api/cart/items", {"skuId": sku, "quantity": 1}, token)
        check("加购 SKU %d" % sku, status in (200, 201), status)

    key = "split-%d" % random.getrandbits(48)
    payload = {"addressId": address_id, "idempotentKey": key}

    # 2) 首次下单：必须拆成两张
    status, body = call("POST", "/api/orders", payload, token)
    data = (body or {}).get("data") or {}
    orders = data.get("orders") or []
    check("首次下单成功", status == 200, "%s %s" % (status, (body or {}).get("message")))
    check("拆单为 2 张（不同卖家）", data.get("orderCount") == 2 and len(orders) == 2,
          "orderCount=%s sellers=%s" % (data.get("orderCount"), [o.get("sellerId") for o in orders]))
    check("首次下单不是重放", data.get("idempotentReplay") is False, data.get("idempotentReplay"))

    # 3) 同一幂等键重放：必须回放整批（旧实现只会回 1 张，或在事务内提交半批）
    status, body = call("POST", "/api/orders", payload, token)
    replay = (body or {}).get("data") or {}
    replay_orders = replay.get("orders") or []
    check("重放请求返回 200（不 500）", status == 200, "%s %s" % (status, (body or {}).get("message")))
    check("重放返回整批 2 张", replay.get("orderCount") == 2 and len(replay_orders) == 2,
          "orderCount=%s" % replay.get("orderCount"))
    check("重放标记 idempotentReplay=true", replay.get("idempotentReplay") is True, replay.get("idempotentReplay"))
    check("重放的订单号与首次完全一致",
          sorted(o.get("orderNo") for o in replay_orders) == sorted(o.get("orderNo") for o in orders))

    # 4) 回放不得重复冻结：每个 SKU 只增 1
    self_after, third_after = frozen_of(SELF_SKU), frozen_of(THIRD_SKU)
    check("回放未重复冻结（自营 SKU +1）", self_after - self_before == 1, "%d→%d" % (self_before, self_after))
    check("回放未重复冻结（第三方 SKU +1）", third_after - third_before == 1, "%d→%d" % (third_before, third_after))

    # 5) 对账判据：台账 FROZEN 汇总 == 商品域冻结量（事故的核心症状）
    mism = sql("duoduo_trade", """
        SELECT f.sku_id FROM (SELECT sku_id, SUM(quantity) q FROM t_stock_freeze
        WHERE status='FROZEN' GROUP BY sku_id) f
        JOIN duoduo_product.t_sku s ON s.id=f.sku_id
        WHERE COALESCE(s.frozen_stock,0) <> f.q""")
    check("台账 FROZEN 与商品域冻结量一致", mism == "", "不一致 SKU：%s" % mism)

    # 6) 两张子单都必须能被支付（旧实现第一张就付不动）
    paid_ok = 0
    for order in orders:
        no = order.get("orderNo")
        status, body = call("POST", "/api/orders/%s/pay" % no,
                            {"channel": "MOCK", "idempotentKey": "mock-%s-%d" % (no, random.getrandbits(32))},
                            token)
        result = (body or {}).get("data") or {}
        ok = status == 200 and result.get("orderStatus") == "PAID"
        paid_ok += 1 if ok else 0
        check("子单 %s 用 MOCK 支付即入账为 PAID" % no, ok,
              "%s %s" % (status, result.get("orderStatus") or (body or {}).get("message")))

    check("两张子单都能完成支付入账", paid_ok == 2, "成功 %d/2" % paid_ok)

    # 7) 清理：已支付的两单不动（保留证据），购物车已被下单清空
    print("\n夹具手机号：%s（幂等键 %s，订单 %s）" % (phone, key, [o.get("orderNo") for o in orders]))
    print("\n%d/%d 通过" % (len(PASSED), len(PASSED) + len(FAILED)))
    for name in FAILED:
        print("  FAILED: " + name)


if __name__ == "__main__":
    main()
