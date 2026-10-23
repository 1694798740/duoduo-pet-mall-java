#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
客服已读回执 / 未读筛选 / 自动重开 · 端到端冒烟（第七十九轮 A 批）

守六件事：
  1. 未读筛选只返回有未读的会话；
  2. 标记已读后未读归零，且读游标推进；
  3. 已读回执：对方未读时 `myUnreadCount > 0`，读完变 0；
  4. 未读数是数字语义（接口按 Long 序列化成字符串，端侧必须能直接比较）；
  5. 商家关闭会话后，买家再发消息 → 自动重开；
  6. 关闭状态下商家不能回复（服务端仍拒绝）。

用法：python scripts/support-read-smoke.py
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
BUYER = "13600001111"
CS = "13900000002"
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


def login(phone):
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    return (r.get("data") or {}).get("accessToken") if st == 200 else None


def main():
    buyer = login(BUYER)
    seller = login(CS)
    check("买家/客服均可登录", bool(buyer) and bool(seller))
    if not buyer or not seller:
        return failed

    stamp = str(int(time.time()))[-6:]

    # 1) 买家发消息 → 商家有未读
    st, r = call("POST", "/api/support/messages", {"content": "已读冒烟 " + stamp}, token=buyer)
    check("买家发送成功", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/seller/support/sessions?onlyUnread=true&page=1&pageSize=50", token=seller)
    items = (r.get("data") or {}).get("items") or []
    mine = [x for x in items if str(x.get("userName", "")).startswith("买家小A")]
    check("未读筛选命中该会话", bool(mine), [x.get("userName") for x in items][:5])
    if not mine:
        return failed
    session = mine[0]
    sid = session["id"]
    check("未读数为正且可参与数字比较", float(session.get("unread") or 0) > 0, session.get("unread"))

    # 2) 商家点开会话（标记已读）→ 商家侧未读清零、未读筛选剔除
    st, r = call("POST", "/api/seller/support/sessions/%s/read" % sid, token=seller)
    check("商家标记已读成功", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/seller/support/sessions?page=1&pageSize=50", token=seller)
    row = next((x for x in (r.get("data") or {}).get("items", []) if x["id"] == sid), None)
    check("商家点开后未读已清零", row is not None and float(row.get("unread") or 0) == 0,
          None if row is None else row.get("unread"))

    st, r = call("GET", "/api/seller/support/sessions?onlyUnread=true&page=1&pageSize=50", token=seller)
    ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("未读筛选不再返回已读会话", sid not in ids, ids[:5])

    # 3) 商家回复 → 卖家视角 myUnreadCount = 1（买家还没读）
    st, r = call("POST", "/api/seller/support/sessions/%s/messages" % sid,
                 {"content": "客服回复 " + stamp}, token=seller)
    check("商家回复成功", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/seller/support/sessions/%s/messages?page=1&pageSize=30" % sid, token=seller)
    thread = r.get("data") or {}
    check("商家侧：对方未读 → myUnreadCount > 0", int(thread.get("myUnreadCount") or 0) > 0,
          thread.get("myUnreadCount"))

    # 3b) 客服回复必须出现在通知中心「商家」分类，且点击可直达客服页
    st, r = call("GET", "/api/socials/notices?category=MERCHANT&page=1&pageSize=10", token=buyer)
    notices = (r.get("data") or {}).get("items") or []
    support_notices = [n for n in notices if n.get("bizType") == "SUPPORT"]
    check("通知中心出现客服消息（MERCHANT/SUPPORT）", bool(support_notices),
          [n.get("bizType") for n in notices][:5])
    check("客服通知带网页落地页 /support",
          bool(support_notices) and str(support_notices[0].get("webPath") or "").startswith("/support"),
          None if not support_notices else support_notices[0].get("webPath"))

    st, r = call("GET", "/api/socials/notices/unread-summary", token=buyer)
    summary = r.get("data") or {}
    # 客服未读只算一次：notice 已排除 SUPPORT，total = notice + message + support
    check("未读口径不重复计数客服",
          int(summary.get("total") or 0) == int(summary.get("notice") or 0)
          + int(summary.get("message") or 0) + int(summary.get("support") or 0), summary)

    # 4) 买家打开会话（标记已读）→ 商家侧 myUnreadCount 归零
    st, r = call("POST", "/api/support/read?sessionId=%s" % sid, token=buyer)
    check("买家标记已读成功", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/seller/support/sessions/%s/messages?page=1&pageSize=30" % sid, token=seller)
    thread = r.get("data") or {}
    check("买家读完后：商家侧 myUnreadCount = 0", int(thread.get("myUnreadCount") or 0) == 0,
          thread.get("myUnreadCount"))

    st, r = call("GET", "/api/socials/notices?category=MERCHANT&page=1&pageSize=10", token=buyer)
    # 只看**本会话**的通知：同一账号还有其它客服会话，它们的通知本来就可能是未读的，
    # 用「页面里所有 SUPPORT 都读」当判据会假红（2026-10-23 实测踩到）
    my_notices = [n for n in ((r.get("data") or {}).get("items") or [])
                  if n.get("bizType") == "SUPPORT" and str(n.get("bizId")) == str(sid)]
    check("买家打开会话后客服通知同步已读",
          bool(my_notices) and all(n.get("read") for n in my_notices),
          [n.get("read") for n in my_notices])

    # 5) 关闭规则（2026-10-23）：只有买家能关；客服关不掉；关闭后客服不能回复
    st, r = call("POST", "/api/seller/support/sessions/%s/status?value=CLOSED" % sid, token=seller)
    check("客服无权关闭会话（403）", st == 403 and r.get("code") == "FORBIDDEN", (st, r.get("code")))

    st, r = call("POST", "/api/support/close?sessionId=%s" % sid, token=buyer)
    check("买家结束会话成功", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/support/messages?sellerId=%s&page=1&pageSize=5" % session.get("sellerId"),
                 token=buyer)
    check("买家侧接口不回会话状态（status=null）", (r.get("data") or {}).get("status") is None,
          (r.get("data") or {}).get("status"))

    st, r = call("POST", "/api/seller/support/sessions/%s/messages" % sid,
                 {"content": "关闭后回复"}, token=seller)
    check("关闭后商家回复被拒", st in (400, 422), (st, r.get("code")))

    st, r = call("GET", "/api/seller/support/sessions?filter=CLOSED&page=1&pageSize=50", token=seller)
    closed_ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("商家「已关闭」筛选包含该会话", sid in closed_ids, closed_ids[:5])

    # 6) 买家再发消息 → 自动重开
    st, r = call("POST", "/api/support/messages", {"content": "关闭后再问 " + stamp}, token=buyer)
    check("关闭后买家仍可发送", st == 200 and r.get("code") == "SUCCESS", r)

    st, r = call("GET", "/api/seller/support/sessions?page=1&pageSize=50", token=seller)
    row = next((x for x in (r.get("data") or {}).get("items", []) if x["id"] == sid), None)
    check("买家发消息后会话自动重开", row is not None and row.get("status") == "OPEN",
          None if row is None else row.get("status"))
    check("自动重开后商家侧又出现未读", row is not None and float(row.get("unread") or 0) > 0,
          None if row is None else row.get("unread"))

    # 7) 已处理筛选：读完后没有待回 → 命中 ANSWERED
    call("POST", "/api/seller/support/sessions/%s/read" % sid, token=seller)
    st, r = call("GET", "/api/seller/support/sessions?filter=ANSWERED&page=1&pageSize=50", token=seller)
    answered_ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("商家「已处理」筛选包含已读会话", sid in answered_ids, answered_ids[:5])

    # 收尾：把未读读掉，避免污染人工验收
    call("POST", "/api/support/read?sessionId=%s" % sid, token=buyer)
    call("POST", "/api/seller/support/sessions/%s/read" % sid, token=seller)

    print("\n结果：%d 通过 / %d 失败" % (passed, failed))
    return failed


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
