#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第四十八轮 · 问一问修复探针（自营商品 → 官方客服）
背景：自营（supply_type='self'）商品 seller_id=0 时「问一问」一律 422；
      增量 16 建官方客服账号并把自营商品归属过去后，全站商品均可咨询。
覆盖：
  夹具：测试买家注册并登录
  ① C 端详情卡 sellerId=官方客服 / ② ASK 自营商品 200 且服务端定人=官方客服 /
  ③ 官方客服账号可登录 / ④ 官方客服回复 200 /
  ⑤ 买家会话列表含官方客服 / ⑥ 回复后未读数=1 /
  ⑦ ASK 商家商品回归（收件人=归属卖家）
前置：容器 healthy（网关 :8080），06-duoduo-social.sql、
      incremental/15-ask-seller-backfill.sql 与
      incremental/16-official-cs-and-avatars.sql 已执行。
用法：python scripts/r48-ask-smoke.py
"""
import json
import random
import sys
import urllib.request
import urllib.error

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
CS = "13900000002"                 # 官方客服手机号
CS_ID = 710000000000000001         # 官方客服雪花 ID（保留段）
SELF_SPU = 1001                    # 自营商品
MERCHANT_SPU = 1009                # 商家商品（卖家 13550931548）
MERCHANT_SELLER_ID = "358936309047365632"

passed, failed = [], []


def check(name, ok, got=""):
    (passed if ok else failed).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else f"  实际={got}"))


def call(method, path, body=None, token=None):
    req = urllib.request.Request(GW + path, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, data=data, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:200]}


def data_of(res):
    return res.get("data") if isinstance(res, dict) else None


def main():
    print("=== 第四十八轮 · 问一问修复探针 ===")

    # ── 夹具 ──
    phone = f"138{random.randint(10**7, 10**8 - 1)}"
    st, r = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "买家验证48"})
    st2, r2 = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    tb = (data_of(r2) or {}).get("accessToken") or ""
    buyer_id = ((data_of(r2) or {}).get("user") or {}).get("id") or ""
    check("夹具：测试买家注册并登录", st == 200 and st2 == 200 and bool(tb) and bool(buyer_id), f"{st}/{st2}")

    # ① C 端详情卡归属
    st, r = call("GET", f"/api/products/{SELF_SPU}")
    seller = str(((data_of(r) or {}).get("card") or {}).get("sellerId") or 0)
    check("① 自营商品详情 sellerId=官方客服", seller == str(CS_ID), seller)

    # ② ASK 自营商品 → 200，服务端定人=官方客服（前端传 0 不被信任）
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": 0, "bizType": "ASK", "bizId": SELF_SPU, "content": "这款猫粮适合幼猫吗？"}, tb)
    recv = str((data_of(r) or {}).get("receiverId") or "")
    check("② ASK 自营商品 200 且收件人=官方客服", st == 200 and recv == str(CS_ID), f"{st} recv={recv}")

    # ③ 官方客服登录
    st, r = call("POST", "/api/auth/login/token", {"phone": CS, "password": PWD})
    tcs = (data_of(r) or {}).get("accessToken") or ""
    check("③ 官方客服可登录", st == 200 and bool(tcs), f"{st} {r.get('message')}")

    # ④ 官方客服回复
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": int(buyer_id), "bizType": "TEXT", "content": "您好，适合 2-12 月龄幼猫，有问题随时问～"}, tcs)
    check("④ 官方客服回复 200", st == 200, f"{st} {r.get('message')}")

    # ⑤⑥ 会话列表与未读
    st, r = call("GET", "/api/socials/messages/conversations?page=1&size=10", token=tb)
    items = ((data_of(r) or {}).get("items") or [])
    cs_conv = next((c for c in items if str(c.get("peerId")) == str(CS_ID)), None)
    check("⑤ 买家会话列表含官方客服", cs_conv is not None, json.dumps(items, ensure_ascii=False)[:100])
    check("⑥ 官方客服回复后未读数=1", bool(cs_conv) and str(cs_conv.get("unread")) == "1",
          str(cs_conv and cs_conv.get("unread")))

    # ⑦ 商家商品回归
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": 0, "bizType": "ASK", "bizId": MERCHANT_SPU, "content": "在吗"}, tb)
    recv9 = str((data_of(r) or {}).get("receiverId") or "")
    check("⑦ ASK 商家商品回归 200 且收件人=归属卖家", st == 200 and recv9 == MERCHANT_SELLER_ID,
          f"{st} recv={recv9}")

    print(f"\n结果：{len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：", failed)
        sys.exit(1)


if __name__ == "__main__":
    main()
