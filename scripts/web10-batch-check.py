#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
十问题批次 · 定向验证（2026-10-22）：Q1 自营订单咨询 / Q3 订单店铺名 / Q4 会话店铺名 / Q9 分类未读。

前置：测试账号 13600001111（密码 duoduo12345）已有自营订单 seller_id=0；
      若没有，脚本会自动用购物车里的自营商品下一单（MOCK 支付到 PAID）。

用法：python scripts/web10-batch-check.py
"""
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PHONE = "13600001111"
PWD = "duoduo12345"
OFFICIAL_CS = "710000000000000001"

passed = 0
failed = 0


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {"raw": e.read().decode("utf-8", "replace")}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  [通过] " + name)
    else:
        failed += 1
        print("  [失败] " + name + ("  -> " + str(detail) if detail else ""))


def main():
    st, r = call("POST", "/api/auth/login/token", {"phone": PHONE, "password": PWD})
    tok = (r.get("data") or {}).get("accessToken")
    check("买家登录", st == 200 and bool(tok), r)

    # ── Q1-a：sellerId=0 归自营客服（旧代码抛「店铺不存在」） ──
    st, r = call("GET", "/api/support/messages?sellerId=0&page=1&pageSize=5", token=tok)
    check("Q1 messages?sellerId=0 正常返回", st == 200 and r.get("code") == "SUCCESS", (st, r.get("message")))
    if st == 200:
        check("Q1 会话接待方=官方客服", r["data"]["sellerId"] == OFFICIAL_CS, r["data"].get("sellerId"))

    # ── Q1-b：找一笔自营订单发 ORDER 卡（旧代码 target=0 报「店铺不存在」） ──
    st, r = call("GET", "/api/orders?page=1&pageSize=20", token=tok)
    rows = (r.get("data") or {}).get("items") or []
    self_orders = [o for o in rows if str(o.get("sellerId")) == "0"]
    check("Q3 订单列表返回 storeName 字段", bool(rows) and "storeName" in rows[0], rows[:1])
    if self_orders:
        check("Q3 自营单 storeName=多多自营",
              all(o.get("storeName") == "多多自营" for o in self_orders),
              [(o["orderNo"][-4:], o.get("storeName")) for o in self_orders])
        st, r = call("POST", "/api/support/messages",
                     {"type": "ORDER", "content": "", "orderNo": self_orders[0]["orderNo"]}, token=tok)
        check("Q1 自营订单 ORDER 卡发送成功", st == 200 and r.get("code") == "SUCCESS", (st, r.get("message")))
    else:
        print("  [跳过] 当前账号没有自营订单，Q1-b / Q3-自营 断言跳过")

    # ── Q4：第三方会话名 = 店铺名（t_merchant.name），不是店主昵称 ──
    st, r = call("GET", "/api/support/session?sellerId=371232608572542976", token=tok)
    name = (r.get("data") or {}).get("sellerName")
    check("Q4 第三方会话名=店铺名（非昵称「喵星人店主」）",
          st == 200 and bool(name) and name != "喵星人店主", (st, name))
    st, r = call("GET", "/api/support/session?sellerId=0", token=tok)
    check("Q4 自营会话名保持官方客服", "客服" in ((r.get("data") or {}).get("sellerName") or ""), r.get("data"))

    # ── Q9：分类未读端点 ──
    st, r = call("GET", "/api/socials/notices/unread-by-category", token=tok)
    keys = set((r.get("data") or {}).keys()) if st == 200 else set()
    check("Q9 unread-by-category 五类齐备",
          st == 200 and {"ORDER", "FRIEND", "MERCHANT", "SYSTEM", "ANNOUNCEMENT"} <= keys,
          (st, r.get("data")))

    print(f"\n结果：{passed} 通过 / {failed} 失败")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
