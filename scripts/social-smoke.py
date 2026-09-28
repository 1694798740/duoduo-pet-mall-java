#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第四十七轮 · 社交域（关注 / 私信）后端探针
覆盖：
  关注：
  ① 未登录关注 401 / ② 关注自己 422 / ③ 关注幽灵用户 404 /
  ④ 关注成功 + 状态 / ⑤ 重复关注幂等 / ⑥ 互关判定 mutual /
  ⑦⑧ following / followers 列表 + 互关角标 / ⑨ 取关幂等 /
  ⑩ 计数 / ⑪ 他人主页聚合（含看自己）/ ⑫ 未登录主页 401
  私信：
  ⑬ 未登录发消息 401 / ⑭ 非互关第一条 200 / ⑮ 非互关第二条 422 /
  ⑯ 对方回一条 200 / ⑰ 对方第二条 422 / ⑱ 互关后自由聊 /
  ⑲ SHARE / PROFILE_CARD 422「即将开放」/ ⑳ ASK 自营商品 200（第四十八轮起归属官方客服）/
  ㉑ ASK 商家商品 200 + 收件人=商品归属卖家（服务端定人，前端传值不被信任）/
  ㉒ ASK 幽灵商品 404 / ㉓ 商品会话豁免「一条」限制+主会话仍受限 / ㉔ 空内容 4xx /
  ㉕ 给自己发 422 / ㉖ 发给幽灵用户 404 / ㉗ 频控 429 /
  ㉘㉙ 会话列表（未读数 / consulting / canSend）/ ㉚ 会话消息正序 + 头部状态 /
  ㉛ 标已读后未读归零
前置：容器 healthy（网关 :8080），06-duoduo-social.sql、incremental/15-ask-seller-backfill.sql
      与 incremental/16-official-cs-and-avatars.sql 已执行。
用法：python scripts/social-smoke.py
"""
import json
import random
import sys
import time
import urllib.request

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
SELF_SPU = 1001    # seller_id=0 的自营商品
MERCHANT_SPU = 1009  # 已回填卖家的商家商品
MERCHANT_SPU_2 = 1015  # 另一卖家商品（频控测试用，隔离会话）
OFFICIAL_CS_ID = "710000000000000001"  # 官方客服（第四十八轮，增量 16 创建，自营商品归属）

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


print("=== 第四十七轮 · 社交域探针 ===")

# ── 夹具：注册三个用户 ────────────────────────────────────────────────────────
tokens, ids = {}, {}
for tag in ("A", "B", "C"):
    phone = f"137{random.randint(10**7, 10**8 - 1)}"
    st, res = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": f"社交测试{tag}"})
    st2, res2 = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    d = data_of(res2) or {}
    tokens[tag] = d.get("accessToken")
    ids[tag] = (d.get("user") or {}).get("id")
    check(f"夹具：用户 {tag} 注册并登录", st == 200 and st2 == 200 and tokens[tag] and ids[tag], f"{st}/{st2} {res2}")

A, B, C = ids["A"], ids["B"], ids["C"]
ta, tb, tc = tokens["A"], tokens["B"], tokens["C"]

# 商品 1009 的归属卖家（公开详情卡带 sellerId，第四十七轮回填后 > 0）
st, res = call("GET", f"/api/products/{MERCHANT_SPU}")
SELLER = ((data_of(res) or {}).get("card") or {}).get("sellerId")
check("夹具：商品 1009 归属卖家 > 0", st == 200 and SELLER and SELLER != 0, f"{st} {SELLER}")

st, res = call("GET", f"/api/products/{MERCHANT_SPU_2}")
SELLER2 = ((data_of(res) or {}).get("card") or {}).get("sellerId")
check("夹具：商品 1015 归属卖家 > 0", st == 200 and SELLER2 and SELLER2 != 0, f"{st} {SELLER2}")

# ══════════════════════════ 关注 ══════════════════════════

# ① 未登录
st, res = call("POST", f"/api/socials/follows/{B}")
check("① 未登录关注 → 401", st == 401, f"{st} {res}")

# ② 关注自己
st, res = call("POST", f"/api/socials/follows/{A}", None, ta)
check("② 关注自己 → 422", st == 422, f"{st} {res}")

# ③ 幽灵用户
st, res = call("POST", "/api/socials/follows/999999999", None, ta)
check("③ 关注幽灵用户 → 404", st == 404, f"{st} {res}")

# ④ 关注成功 + 状态
st, res = call("POST", f"/api/socials/follows/{B}", None, ta)
d = data_of(res) or {}
check("④ A 关注 B → 200 following=true", st == 200 and d.get("following") and not d.get("followedBy"), f"{st} {res}")

# ⑤ 重复关注幂等
st, res = call("POST", f"/api/socials/follows/{B}", None, ta)
d = data_of(res) or {}
check("⑤ 重复关注幂等 → 200", st == 200 and d.get("following"), f"{st} {res}")

# ⑥ 互关判定：B 回关 A
st, res = call("POST", f"/api/socials/follows/{A}", None, tb)
d = data_of(res) or {}
check("⑥ B 回关后 mutual=true", st == 200 and d.get("mutual"), f"{st} {res}")

# ⑦⑧ 列表 + 互关角标
st, res = call("GET", "/api/socials/follows/following?page=1&pageSize=20", None, ta)
items = (data_of(res) or {}).get("items") or []
row = next((it for it in items if str(it.get("userId")) == str(B)), None)
check("⑦ A 的关注列表含 B 且 mutual 角标", st == 200 and row and row.get("mutual"), f"{st} {res}")

st, res = call("GET", "/api/socials/follows/followers?page=1&pageSize=20", None, ta)
items = (data_of(res) or {}).get("items") or []
row = next((it for it in items if str(it.get("userId")) == str(B)), None)
check("⑧ A 的粉丝列表含 B", st == 200 and bool(row), f"{st} {res}")

# ⑨ 取关幂等（先取关再取关，都应 200）
st, res = call("DELETE", f"/api/socials/follows/{B}", None, ta)
check("⑨a A 取关 B → 200", st == 200 and not (data_of(res) or {}).get("following"), f"{st} {res}")
st, res = call("DELETE", f"/api/socials/follows/{B}", None, ta)
check("⑨b 重复取关幂等 → 200", st == 200, f"{st} {res}")

# ⑩ 计数（T-39：全局 Long→字符串序列化，long 计数同样以字符串下发）
st, res = call("GET", f"/api/socials/follows/{A}/counts", None, ta)
d = data_of(res) or {}
check("⑩ A 的粉丝数 ≥ 1（B 关注着 A）", st == 200 and int(d.get("followers", 0)) >= 1, f"{st} {res}")

# ⑪ 他人主页聚合 + 看自己
st, res = call("GET", f"/api/socials/users/{A}/profile", None, ta)
d = data_of(res) or {}
check("⑪a 看自己：关系字段恒 false", st == 200 and str(d.get("userId")) == str(A)
      and not d.get("viewerFollowing") and not d.get("mutual"), f"{st} {res}")

st, res = call("GET", f"/api/socials/users/{B}/profile", None, ta)
d = data_of(res) or {}
# 此刻 A 已取关 B、B 仍关注 A：viewerFollowing=False、viewerFollowedBy=True、following≥1
check("⑪b 主页聚合：昵称 + 计数 + 关系", st == 200 and d.get("nickname")
      and d.get("viewerFollowedBy") and int(d.get("following", 0)) >= 1, f"{st} {res}")

st, res = call("GET", f"/api/socials/users/{B}/profile")
check("⑫ 未登录主页 → 401", st == 401, f"{st} {res}")

# ══════════════════════════ 私信 ══════════════════════════

# ⑬ 未登录
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "content": "hi"})
check("⑬ 未登录发消息 → 401", st == 401, f"{st} {res}")

# ⑭ 非互关第一条（当前 A 未关注 B，B 关注 A → 非互关）
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "content": "你好，关注你啦"}, ta)
d = data_of(res) or {}
check("⑭ A→B 非互关第一条 → 200", st == 200 and str(d.get("receiverId")) == str(B), f"{st} {res}")

# ⑮ A 第二条 → 422
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "content": "再聊一句"}, ta)
check("⑮ A→B 非互关第二条 → 422", st == 422, f"{st} {res}")

# ⑯ B 回一条 → 200（B 的第一条额度）
st, res = call("POST", "/api/socials/messages", {"receiverId": A, "content": "你好呀"}, tb)
check("⑯ B→A 回复 → 200", st == 200, f"{st} {res}")

# ⑰ B 第二条 → 422
st, res = call("POST", "/api/socials/messages", {"receiverId": A, "content": "还想再聊"}, tb)
check("⑰ B→A 第二条 → 422", st == 422, f"{st} {res}")

# ⑱ 互关后自由聊：A 重新关注 B（B 仍关注 A）
call("POST", f"/api/socials/follows/{B}", None, ta)
ok1 = call("POST", "/api/socials/messages", {"receiverId": B, "content": "互关了，畅聊第 1 条"}, ta)[0]
ok2 = call("POST", "/api/socials/messages", {"receiverId": B, "content": "互关了，畅聊第 2 条"}, ta)[0]
check("⑱ 互关后连续发送 → 200/200", ok1 == 200 and ok2 == 200, f"{ok1}/{ok2}")

# ⑲ 卡片类型第二批开放前：SHARE / PROFILE_CARD → 422
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "bizType": "SHARE", "bizId": 123}, ta)
check("⑲a SHARE → 422 即将开放", st == 422, f"{st} {res}")
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "bizType": "PROFILE_CARD", "bizId": B}, ta)
check("⑲b PROFILE_CARD → 422 即将开放", st == 422, f"{st} {res}")

# ⑳ ASK 自营商品（第四十八轮起归属官方客服，语义=咨询客服；收件人服务端定人不信前端）
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "bizType": "ASK", "bizId": SELF_SPU, "content": "咨询"}, ta)
d20 = data_of(res) or {}
check("⑳ ASK 自营商品 → 200 且收件人=官方客服", st == 200 and str(d20.get("receiverId")) == OFFICIAL_CS_ID,
      f"{st} {res}")

# ㉑ ASK 商家商品：收件人=归属卖家（服务端定人，前端传 C 也不被信任）
st, res = call("POST", "/api/socials/messages",
               {"receiverId": C, "bizType": "ASK", "bizId": MERCHANT_SPU, "content": "这个零食猫咪能吃吗"}, ta)
d = data_of(res) or {}
check("㉑ ASK 商家商品 → 200 且收件人=卖家", st == 200 and str(d.get("receiverId")) == str(SELLER),
      f"{st} {res}")

# ㉒ ASK 幽灵商品
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "bizType": "ASK", "bizId": 999999}, ta)
check("㉒ ASK 幽灵商品 → 404", st == 404, f"{st} {res}")

# ㉓ ASK 会话豁免：A 未互关卖家，ASK 后在**同一商品会话**内可连续发 TEXT
# （第四十九轮起 TEXT 必须带 productId 才落回商品咨询会话；不带则落主会话、仍受一条限制）
ok1 = call("POST", "/api/socials/messages", {"receiverId": SELLER, "content": " thank you", "productId": MERCHANT_SPU}, ta)[0]
ok2 = call("POST", "/api/socials/messages", {"receiverId": SELLER, "content": "再问一句", "productId": MERCHANT_SPU}, ta)[0]
check("㉓ 商品咨询会话豁免一条限制 → 200/200", ok1 == 200 and ok2 == 200, f"{ok1}/{ok2}")

# ㉓-2 主会话一条限制与商品会话豁免互不影响：主会话（无 productId）第一条 200、第二条 422
ok3 = call("POST", "/api/socials/messages", {"receiverId": SELLER, "content": "主会话第一条"}, ta)[0]
ok4 = call("POST", "/api/socials/messages", {"receiverId": SELLER, "content": "主会话第二条"}, ta)[0]
check("㉓-2 主会话仍按一条限制 → 200/422", ok3 == 200 and ok4 == 422, f"{ok3}/{ok4}")

# ㉔ 空内容 TEXT → 4xx
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "content": "   "}, ta)
check("㉔ 空内容 → 4xx", st in (400, 422), f"{st} {res}")

# ㉕ 给自己发
st, res = call("POST", "/api/socials/messages", {"receiverId": A, "content": "自言自语"}, ta)
check("㉕ 给自己发 → 422", st == 422, f"{st} {res}")

# ㉖ 发给幽灵用户
st, res = call("POST", "/api/socials/messages", {"receiverId": 999999999, "content": "hi"}, ta)
check("㉖ 幽灵收件人 → 404", st == 404, f"{st} {res}")

# ㉗ 频控：C 在与卖家2 的商品咨询会话里连发 11 条，第 11 条 → 429
# （第四十九轮起 TEXT 带 productId 落商品会话，频控按会话计）
codes = []
call("POST", "/api/socials/messages", {"receiverId": SELLER2, "bizType": "ASK", "bizId": MERCHANT_SPU_2, "content": "咨询"}, tc)
for i in range(10):
    st, res = call("POST", "/api/socials/messages", {"receiverId": SELLER2, "content": f"刷屏第 {i + 1} 条", "productId": MERCHANT_SPU_2}, tc)
    codes.append(st)
check("㉗ 同会话 60s 内第 11 条 → 429", codes[:9] == [200] * 9 and codes[9] == 429, f"{codes}")

# ㉘ 会话列表（B 视角）：A 的未读 ≥ 3、canSend、卖家咨询会话 consulting
st, res = call("GET", "/api/socials/messages/conversations?page=1&pageSize=50", None, tb)
conv = {str(c.get("peerId")): c for c in ((data_of(res) or {}).get("items") or [])}
row_a = conv.get(str(A))
check("㉘a B 会话列表含 A 且未读 ≥ 3", st == 200 and row_a and int(row_a.get("unread", 0)) >= 3, f"{st} {row_a}")
check("㉘b B→A 互关 canSend=true", bool(row_a and row_a.get("canSend")), f"{row_a}")

st, res = call("GET", "/api/socials/messages/conversations?page=1&pageSize=50", None, ta)
conv_a = {str(c.get("peerId")): c for c in ((data_of(res) or {}).get("items") or [])}
row_s = conv_a.get(str(SELLER))
check("㉘c A 会话列表：卖家会话 consulting=true", bool(row_s and row_s.get("consulting")), f"{row_s}")

# C→B：C 发过一条且未互关 → B 侧视角 canSend 取决于 C 是否还能发……
# 这里直接验证 C 自己的头部状态：thread 里 canSend=false
st, res = call("POST", "/api/socials/messages", {"receiverId": B, "content": "C 的唯一一条"}, tc)
check("㉘d C→B 第一条 → 200", st == 200, f"{st} {res}")
st, res = call("GET", f"/api/socials/messages/conversations/{B}?page=1&pageSize=20", None, tc)
d = data_of(res) or {}
check("㉘e C 视角 thread canSend=false（已用掉唯一一条）", st == 200 and d.get("canSend") is False,
      f"{st} canSend={d.get('canSend')}")

# ㉚ 会话消息正序 + 总数（A-B：A1 + B回复 + A2 + A3 = 4 条）
st, res = call("GET", f"/api/socials/messages/conversations/{B}?page=1&pageSize=20", None, ta)
d = data_of(res) or {}
items = d.get("items") or []
order_ok = len(items) == 4 and items[0].get("mine") and not items[1].get("mine") and items[2].get("mine")
check("㉚ A-B 会话 4 条且时间正序", st == 200 and int(d.get("total", 0)) == 4 and order_ok,
      f"{st} total={d.get('total')} n={len(items)}")

# ㉛ 标已读：B 打开与 A 的会话 → 未读归零
st, res = call("POST", f"/api/socials/messages/conversations/{A}/read", None, tb)
st2, res2 = call("GET", "/api/socials/messages/conversations?page=1&pageSize=50", None, tb)
conv2 = {str(c.get("peerId")): c for c in ((data_of(res2) or {}).get("items") or [])}
unread_after = (conv2.get(str(A)) or {}).get("unread")
check("㉛ 标已读后未读归零", st == 200 and unread_after in (0, "0", None), f"{st} unread={unread_after}")

# ── 汇总 ─────────────────────────────────────────────────────────────────────
print(f"\n=== 结果：{len(passed)} 通过 / {len(failed)} 失败 ===")
if failed:
    print("失败项：")
    for name in failed:
        print("  - " + name)
    sys.exit(1)
