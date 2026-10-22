#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
客服消息独立化 · 端到端冒烟（第七十八轮）

守四件事：
  1. 客服会话不再出现在私信列表（数据层分离，不是查询过滤）；
  2. 自营客服账号登录商家工作台，能看到并回复买家会话；
  3. 用户侧连发多条**不被**"非互关限一条"挡住（客服不适用社交规则）；
  4. 普通用户调商家接待接口被 403（越权边界）。

用法：python scripts/support-split-smoke.py
前置：容器已起（网关 8080）、增量 37 已执行、官方客服账号存在。
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
CS_PHONE = "13900000002"
CS_PWD = "duoduo12345"
PWD = "duoduo12345"

passed = 0
failed = 0


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  [通过] " + name)
    else:
        failed += 1
        print("  [失败] " + name + ("  -> " + str(detail) if detail else ""))


def login(phone, pwd):
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": pwd})
    if st != 200 or r.get("code") != "SUCCESS":
        return None
    return (r.get("data") or {}).get("accessToken")


def main():
    print("== 1. 自营客服账号登录商家工作台 ==")
    seller_token = login(CS_PHONE, CS_PWD)
    check("客服账号可登录", bool(seller_token))
    if not seller_token:
        print("登录失败，终止")
        return failed

    st, r = call("GET", "/api/seller/support/sessions?page=1&pageSize=20", token=seller_token)
    items = (r.get("data") or {}).get("items") or []
    check("商家可读取客服会话列表", st == 200 and r.get("code") == "SUCCESS", r)
    check("会话列表非空（历史已迁入）", len(items) > 0, len(items))
    if items:
        s = items[0]
        check("自营店铺名称正确（回源用户域）", s.get("sellerName") == "多多官方客服", s.get("sellerName"))
        check("会话归属为自营客服账号", s.get("sellerId") == "710000000000000001", s.get("sellerId"))

        st2, r2 = call("POST", "/api/seller/support/sessions/%s/messages" % s["id"],
                       {"content": "冒烟回复 %d" % int(time.time())}, token=seller_token)
        check("商家回复成功", st2 == 200 and r2.get("code") == "SUCCESS", r2)
        check("回复人角色为 SELLER", ((r2.get("data") or {}).get("senderRole")) == "SELLER", r2)

    print("== 2. 新用户：客服通道不受关注限制 ==")
    phone = "197" + str(int(time.time()))[-8:]
    st, r = call("POST", "/api/auth/register",
                 {"phone": phone, "password": PWD, "nickname": "客服冒烟" + phone[-4:]})
    check("注册临时买家", st in (200, 201) and r.get("code") == "SUCCESS", r)
    buyer_token = login(phone, PWD)
    check("买家可登录", bool(buyer_token))
    if not buyer_token:
        return failed

    st, r = call("GET", "/api/support/session", token=buyer_token)
    check("取/建自营客服会话", st == 200 and r.get("code") == "SUCCESS", r)
    check("自营客服入口落到官方客服账号",
          (r.get("data") or {}).get("sellerId") == "710000000000000001", r.get("data"))
    buyer_session_id = (r.get("data") or {}).get("id")

    st, r = call("GET", "/api/support/sessions", token=buyer_token)
    check("买家客服会话列表可读", st == 200 and r.get("code") == "SUCCESS", r)

    ok_all = True
    for i in range(3):
        st, r = call("POST", "/api/support/messages", {"content": "第 %d 条咨询" % (i + 1)}, token=buyer_token)
        if not (st == 200 and r.get("code") == "SUCCESS"):
            ok_all = False
            print("    第 %d 条失败：%s" % (i + 1, r))
    check("连发 3 条均成功（无「非互关限一条」）", ok_all)

    print("== 3. 私信列表不再出现客服 ==")
    st, r = call("GET", "/api/socials/messages/conversations?page=1&pageSize=50", token=buyer_token)
    convs = (r.get("data") or {}).get("items") or []
    peers = [c.get("peerId") for c in convs]
    check("私信列表可读", st == 200 and r.get("code") == "SUCCESS", r)
    check("私信列表中无官方客服", "710000000000000001" not in [str(p) for p in peers], peers)

    print("== 4. 未读与越权 ==")
    # 让商家回复**这个新买家**的会话，再看未读是否落到买家身上
    st, r = call("POST", "/api/seller/support/sessions/%s/messages" % buyer_session_id,
                 {"content": "您好，请问有什么可以帮您"}, token=seller_token)
    check("商家回复新买家会话成功", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/support/unread-count", token=buyer_token)
    check("客服未读数可读", st == 200 and r.get("code") == "SUCCESS", r)
    check("客服未读 >= 1（刚收到商家回复）", int(r.get("data") or 0) >= 1, r.get("data"))

    st, r = call("GET", "/api/socials/notices/unread-summary", token=buyer_token)
    data = r.get("data") or {}
    check("未读汇总含 support 分项", "support" in data, data)
    check("total = notice + message + support",
          int(data.get("total", -1)) == int(data.get("notice", 0)) + int(data.get("message", 0)) + int(data.get("support", 0)),
          data)

    st, r = call("GET", "/api/seller/support/sessions", token=buyer_token)
    check("普通用户调商家接待接口被拒（403）", st == 403, (st, r.get("code")))

    print("== 5. 闲置不走客服（保持私信） ==")
    st, r = call("GET", "/api/support/sessions", token=buyer_token)
    rows = r.get("data") or []
    check("客服会话列表只含接待方维度（无闲置会话键）",
          all(str(x.get("sellerId", "")).isdigit() for x in rows), rows)

    print("\n结果：%d 通过 / %d 失败" % (passed, failed))
    return failed


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
