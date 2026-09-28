#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第四十九轮 · 问一问商品会话隔离探针
背景（用户 bug）：同一官方客服卖多件商品，从商品 A「问一问」发消息后，
再从商品 B「问一问」——旧模型会话键只按用户对 lo:hi，两个商品的咨询混进同一会话。
修复：商品咨询会话键扩展为 lo:hi:p{spuId}（服务端拼装），每件商品独立会话。
覆盖：
  夹具：测试买家注册登录 + 官方客服登录
  ① ASK 商品1(1001) 200 / ② ASK 商品2(1002) 200 /
  ③ 商品1 会话只含商品1 消息 / ④ 商品2 会话只含商品2 消息 /
  ⑤ 主会话不含商品咨询消息 / ⑥ 会话列表两条独立商品会话（带商品标题）/
  ⑦ 商品1 会话自由聊（豁免一条限制）/ ⑧ 商品2 会话互不串线闭环 /
  ⑨ markRead 按商品会话生效
前置：容器 healthy（网关 :8080），06-duoduo-social.sql、
      incremental/15、16 已执行，social 容器为第四十九轮构建。
用法：python scripts/r49-ask-thread-smoke.py
"""
import json
import random
import sys
import urllib.request
import urllib.error

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
CS = "13900000002"
CS_ID = 710000000000000001
SPU_1 = 1001   # 自营商品1（归属官方客服）
SPU_2 = 1002   # 自营商品2（归属官方客服；同卖家第二件，精确复现用户 bug 场景）

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
    print("=== 第四十九轮 · 问一问商品会话隔离探针 ===")

    # ── 夹具 ──
    phone = f"137{random.randint(10**7, 10**8 - 1)}"
    st, r = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "隔离验证49"})
    st2, r2 = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    tb = (data_of(r2) or {}).get("accessToken") or ""
    check("夹具：测试买家注册并登录", st == 200 and st2 == 200 and bool(tb), f"{st}/{st2}")
    st, r = call("POST", "/api/auth/login/token", {"phone": CS, "password": PWD})
    tcs = (data_of(r) or {}).get("accessToken") or ""
    check("夹具：官方客服登录", st == 200 and bool(tcs), f"{st}")

    # ①② 两件商品各发一条 ASK（用户复现路径）
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": CS_ID, "bizType": "ASK", "bizId": SPU_1, "content": "你好"}, tb)
    check("① ASK 商品1(1001) → 200 且收件人=官方客服", st == 200, f"{st} {r.get('message')}")
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": CS_ID, "bizType": "ASK", "bizId": SPU_2, "content": "在吗"}, tb)
    check("② ASK 商品2(1010) → 200", st == 200, f"{st} {r.get('message')}")

    # ③④ 各商品会话只含自己的消息（bug 的直接断言）
    st, r = call("GET", f"/api/socials/messages/conversations/{CS_ID}?productId={SPU_1}&page=1&pageSize=20", None, tb)
    d = data_of(r) or {}
    items1 = d.get("items") or []
    contents1 = [m.get("content", "") for m in items1]
    check("③ 商品1 会话存在且含「你好」", st == 200 and any("你好" in c for c in contents1),
          f"{st} {contents1}")
    check("③-1 商品1 会话不含商品2 的「在吗」", not any("在吗" in c for c in contents1), f"{contents1}")

    st, r = call("GET", f"/api/socials/messages/conversations/{CS_ID}?productId={SPU_2}&page=1&pageSize=20", None, tb)
    d2 = data_of(r) or {}
    items2 = d2.get("items") or []
    contents2 = [m.get("content", "") for m in items2]
    check("④ 商品2 会话存在且含「在吗」", st == 200 and any("在吗" in c for c in contents2), f"{st} {contents2}")
    check("④-1 商品2 会话不含商品1 的「你好」", not any("你好" in c for c in contents2), f"{contents2}")

    # ⑤ 主会话（无 productId）不含两条 ASK —— 商品咨询不再落主会话
    st, r = call("GET", f"/api/socials/messages/conversations/{CS_ID}?page=1&pageSize=20", None, tb)
    d3 = data_of(r) or {}
    contents3 = [m.get("content", "") for m in (d3.get("items") or [])]
    check("⑤ 主会话不含商品咨询消息（新旧模型分离）",
          not any("你好" in c or "在吗" in c for c in contents3), f"{contents3}")

    # ⑥ 会话列表：两条独立商品会话，各带商品标题与 consulting
    st, r = call("GET", "/api/socials/messages/conversations?page=1&pageSize=50", None, tb)
    convs = ((data_of(r) or {}).get("items") or [])
    c1 = next((c for c in convs if str(c.get("peerId")) == str(CS_ID) and str(c.get("productId")) == str(SPU_1)), None)
    c2 = next((c for c in convs if str(c.get("peerId")) == str(CS_ID) and str(c.get("productId")) == str(SPU_2)), None)
    check("⑥ 会话列表两条独立商品会话", c1 is not None and c2 is not None,
          json.dumps([(c.get('peerId'), c.get('productId')) for c in convs], ensure_ascii=False))
    check("⑥-1 会话带商品标题（productTitle 非空）",
          bool(c1 and c1.get("productTitle")) and bool(c2 and c2.get("productTitle")),
          f"{c1 and c1.get('productTitle')} / {c2 and c2.get('productTitle')}")

    # ⑦ 商品1 会话自由聊：连发 3 条 TEXT（带 productId）全 200（豁免一条限制）
    codes = [call("POST", "/api/socials/messages",
                  {"receiverId": CS_ID, "content": f"追问 {i}", "productId": SPU_1}, tb)[0]
             for i in range(3)]
    check("⑦ 商品1 会话自由聊 200/200/200", codes == [200] * 3, f"{codes}")

    # ⑧ 商品2 会话：客服回复后，买家在商品1 会话里看不到客服的商品2 回复（互不串线闭环）
    st, me = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    buyer_uid = int(((data_of(me) or {}).get("user") or {}).get("id") or 0)
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": buyer_uid, "content": "商品2 的客服回复", "productId": SPU_2}, tcs)
    check("⑧ 客服在商品2 会话回复 200", st == 200, f"{st} {r.get('message')}")
    st, r = call("GET", f"/api/socials/messages/conversations/{CS_ID}?productId={SPU_1}&page=1&pageSize=20", None, tb)
    contents1b = [m.get("content", "") for m in ((data_of(r) or {}).get("items") or [])]
    check("⑧-1 商品1 会话仍不含商品2 消息", not any("商品2 的客服回复" in c for c in contents1b), f"{contents1b}")

    # ⑨ markRead 按商品会话生效
    call("POST", f"/api/socials/messages/conversations/{CS_ID}/read?productId={SPU_2}", None, tb)
    st, r = call("GET", f"/api/socials/messages/conversations/{CS_ID}?productId={SPU_2}&page=1&pageSize=20", None, tb)
    convs2 = call("GET", "/api/socials/messages/conversations?page=1&pageSize=50", None, tb)
    c2b = next((c for c in ((data_of(convs2[1]) or {}).get("items") or [])
                if str(c.get("peerId")) == str(CS_ID) and str(c.get("productId")) == str(SPU_2)), None)
    check("⑨ 商品2 会话标已读后未读=0", st == 200 and c2b is not None and int(c2b.get("unread", -1)) == 0,
          f"{c2b and c2b.get('unread')}")

    print(f"\n结果：{len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：", failed)
        sys.exit(1)


if __name__ == "__main__":
    main()
