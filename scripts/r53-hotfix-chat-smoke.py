#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R53 热修冒烟：聊天「消息发不出去 / 时间差 8 小时」三问题回归。

背景（用户真机验收报障）：
  ① 给官方客服发消息 → 提示「内容不存在或已删除」；
  ② 给跳蚤市场卖家发私信（已关注）→ 任何消息都发不出 + 控制台 404；
  ③ 北京时间 12:33 发出的消息显示 04:33（差 8 小时）。

根因（本轮实测定位）：
  ① ② 前端 api.js `message.send` 把 receiverId / bizId 做 `Number()` 强转 ——
       雪花 id 是 18~19 位，超出 JS Number 的 53 位有效精度：
       Number('710000000000000001') === 710000000000000000（末位被吞）。
       服务端按错 id 查库 → 404「用户不存在」→ 前端把 NOT_FOUND 文案化成
       「内容不存在或已删除」，于是表现为「消息怎么都发不出去」。
       注意：ASK / LISTING 首条由**服务端按商品/闲置归属定人**，掩盖了该缺陷；
       一旦落到 TEXT（沿用前端 receiverId）就必然 404 —— 这就是「之前能发、
       现在发不出去」的由来。
  ③ 后端时间契约是 UTC（LocalDateTime.now(ZoneOffset.UTC) +
       toInstant(ZoneOffset.UTC).toString() → 「…Z」），前端 shortTime 却把 `Z`
       主动删掉后裸切片，等于把 UTC 挂钟时间当北京时间显示 → 全站时间少 8 小时。

本探针覆盖：
  A. 咨询会话可正常收发（问题①②的正向回归 + 失败模式固定）
  B. 闲置咨询（LISTING）可正常收发 + 关注后仍可发
  C. 时间契约：下发串必须带时区标记，且写入值为 UTC 且与本地实时一致（问题③）

前置：14 容器健康。
用法：python scripts/r53-hotfix-chat-smoke.py
"""
import json
import random
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
OFFICIAL = "710000000000000001"   # 官方客服（18 位，精度必失）
CN = timezone(timedelta(hours=8))  # 北京时间

passed, failed = [], []


def check(name, ok, got=""):
    (passed if ok else failed).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else f"   实际={got}"))


def call(method, path, body=None, token=None, timeout=20):
    req = urllib.request.Request(GW + path, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except Exception:
                return resp.status, {"raw": raw[:300]}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}
    except Exception as e:
        return None, {"error": str(e)}


def d(r):
    return (r or {}).get("data") if isinstance(r, dict) else None


def js_number_corrupt(id_str):
    """模拟 JS `Number(id)`：IEEE754 双精度只有 53 位有效位，末位被吞成 0/其他值。"""
    return str(int(float(id_str)))


def phone_new():
    return "139" + "".join(random.choice("0123456789") for _ in range(8))


def signup(prefix, real_name=None):
    p = phone_new()
    call("POST", "/api/auth/register", {"phone": p, "password": PWD, "nickname": prefix})
    st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
    tok = d(r).get("accessToken") if d(r) else None
    uid = d(r).get("user", {}).get("id") if d(r) else None
    if real_name:
        call("POST", "/api/users/me/realname",
             {"realName": real_name, "idCard": "110101199001011234"}, token=tok)
        # 实名 claim 写在令牌里，必须换新令牌（设计如此）
        st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
        tok = d(r).get("accessToken") if d(r) else None
    return tok, uid, p


def page_items(r):
    data = d(r)
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("items") or data.get("records") or []
    return []


def thread(tok, peer_id, product_id=None, listing_id=None):
    path = f"/api/socials/messages/conversations/{peer_id}?page=1&pageSize=20"
    if product_id:
        path += f"&productId={product_id}"
    if listing_id:
        path += f"&listingId={listing_id}"
    return call("GET", path, token=tok)


def has_zone_mark(s):
    """时间串是否声明了时区（Z 或 ±HH:MM）——前端据此换算本地时间的前提"""
    if not isinstance(s, str) or len(s) < 11:
        return False
    return s.endswith("Z") or ("+" in s[10:]) or (s[10:].count("-") > 0)


def to_cn(s):
    """ISO(UTC) → 北京时间；用于验证「写入的是 UTC 而不是本地时间」"""
    t = s.replace("Z", "+00:00")
    dt = datetime.fromisoformat(t)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(CN)


# ═══════════════════════════════════════════════════════════════════════════
# A. 商品咨询会话：发得出、读得回（问题① 正向回归 + 失败模式固定）
# ═══════════════════════════════════════════════════════════════════════════
def sec_a():
    print("\n══════ A. 官方客服咨询会话：收发回归（问题①）══════")
    tok, uid, _ = signup("R53hx买A")

    # —— A1 首条按 ASK（服务端按商品归属定人 → 掩盖了前端 id 缺陷）——
    st, r = call("POST", "/api/socials/messages", {
        "receiverId": OFFICIAL, "content": "A1 首条咨询", "bizType": "ASK", "bizId": "1002",
    }, token=tok)
    check("A1 首条咨询发送成功（200）", st in (200, 201), f"{st} {str(r)[:160]}")

    # —— A2 同一会话内继续发 TEXT（★ 用户报障场景：此路径原本必 404）——
    st, r = call("POST", "/api/socials/messages", {
        "receiverId": OFFICIAL, "content": "A2 会话内续聊", "productId": "1002",
    }, token=tok)
    check("A2 会话内继续发 TEXT 成功（原 404 场景，200）", st in (200, 201), f"{st} {str(r)[:160]}")

    # —— A3 收件人完整性：18 位 id 不得被吞位 ——
    body = d(r) or {}
    check("A3 落库 receiverId 与官方客服 id 完全一致（无精度丢失）",
          str(body.get("receiverId")) == OFFICIAL, f"got={body.get('receiverId')}")

    # —— A4 线程读取：两条消息内容完整（不是「已删除」）——
    st, r = thread(tok, OFFICIAL, product_id="1002")
    items = page_items(r)
    contents = [m.get("content") for m in items]
    check("A4 线程可读且含刚发的两条（内容完整）",
          st == 200 and "A1 首条咨询" in contents and "A2 会话内续聊" in contents,
          f"{st} contents={contents}")

    # —— A5 会话列表 peerId 为字符串且完整（读路径精度）——
    st, r = call("GET", "/api/socials/messages/conversations?page=1&pageSize=20", token=tok)
    peers = [(it.get("peerId"), type(it.get("peerId")).__name__) for it in page_items(r)]
    check("A5 会话列表 peerId 为字符串且完整",
          st == 200 and any(p == OFFICIAL and t == "str" for p, t in peers), f"{st} {peers[:4]}")

    # —— A6 失败模式固定：精度丢失后的数字 id 仍应 404（证明根因确在 id 值）——
    bad = js_number_corrupt(OFFICIAL)
    check("A6 前置：模拟值确实不等于原值（JS Number 精度丢失）", bad != OFFICIAL, f"{bad}")
    st, r = call("POST", "/api/socials/messages", {
        "receiverId": bad, "content": "A6 精度丢失复现",
    }, token=tok)
    check("A6 精度丢失的 id 被拒 404（服务端查无此人，故前端禁止 Number 强转）",
          st == 404, f"{st} {str(r)[:140]}")


# ═══════════════════════════════════════════════════════════════════════════
# B. 闲置咨询会话：发得出、读得回（问题② 正向回归）
# ═══════════════════════════════════════════════════════════════════════════
def sec_b():
    print("\n══════ B. 跳蚤市场闲置咨询：收发回归（问题②）══════")
    seller_tok, seller_id, _ = signup("R53hx卖B", real_name="李四")
    buyer_tok, buyer_id, _ = signup("R53hx买B")

    st, r = call("POST", "/api/listings", {
        "title": "R53hx 闲置咨询探针", "description": "热修探针条目", "price": 5200,
        "conditionLevel": "GOOD", "city": "上海", "species": "cat",
    }, token=seller_tok)
    listing_id = (d(r) or {}).get("id")
    check("B1 卖家发布闲置成功（19 位 id，字符串下发）",
          st in (200, 201) and listing_id and len(str(listing_id)) == 18,
          f"{st} id={listing_id}")

    if not listing_id:
        return

    # —— B2 买家按 LISTING 首条咨询（服务端按闲置归属定收件人）——
    st, r = call("POST", "/api/socials/messages", {
        "receiverId": seller_id, "content": "B2 这件还在吗", "bizType": "LISTING", "bizId": listing_id,
    }, token=buyer_tok)
    check("B2 闲置首条咨询发送成功（200）", st in (200, 201), f"{st} {str(r)[:160]}")
    check("B2b 收件人=闲置归属卖家（服务端定人）",
          str((d(r) or {}).get("receiverId")) == str(seller_id),
          f"got={(d(r) or {}).get('receiverId')} want={seller_id}")

    # —— B3 ★ 用户报障：会话内任何消息都发不出 ——
    st, r = call("POST", "/api/socials/messages", {
        "receiverId": seller_id, "content": "B3 会话内续聊", "listingId": listing_id,
    }, token=buyer_tok)
    check("B3 会话内继续发 TEXT 成功（『任何消息都发不出』回归，200）",
          st in (200, 201), f"{st} {str(r)[:160]}")

    # —— B4 线程读取：闲置上下文完整 + 消息可读 ——
    st, r = thread(buyer_tok, seller_id, listing_id=listing_id)
    data = d(r) or {}
    contents = [m.get("content") for m in page_items(r)]
    check("B4 闲置会话可读（consulting + listingId 完整 + 内容完整）",
          st == 200 and data.get("consulting") is True
          and str(data.get("listingId")) == str(listing_id)
          and "B2 这件还在吗" in contents and "B3 会话内续聊" in contents,
          f"{st} consulting={data.get('consulting')} listingId={data.get('listingId')} contents={contents}")

    # —— B5 关注卖家后继续发（用户提到「已关注对方」）——
    call("POST", f"/api/socials/follows/{seller_id}", token=buyer_tok)
    st, r = call("POST", "/api/socials/messages", {
        "receiverId": seller_id, "content": "B5 关注后再发", "listingId": listing_id,
    }, token=buyer_tok)
    check("B5 关注卖家后再发仍成功（200）", st in (200, 201), f"{st} {str(r)[:160]}")

    # —— B6 失败模式固定：精度丢失的 listingId 会 404 ——
    # 注意：并非所有雪花 id 都会丢位 —— 18~19 位整数若恰为 64 的倍数，
    # 其有效位数 ≤53 位，双精度可精确表示（约 1/64 概率），此时 Number() 不丢位。
    # 故本用例按前置条件分支：真丢位才断言 404，否则显式跳过（不制造假绿）。
    bad_listing = js_number_corrupt(listing_id)
    if bad_listing == str(listing_id):
        print("    [SKIP] B6 该闲置 id 恰可被双精度精确表示（Number 强转不丢位），本用例不适用")
    else:
        st, r = call("POST", "/api/socials/messages", {
            "receiverId": seller_id, "content": "B6 精度丢失复现",
            "bizType": "LISTING", "bizId": bad_listing,
        }, token=buyer_tok)
        check("B6 精度丢失的闲置 id 被拒 404（前端禁止 Number 强转的另一处）",
              st == 404, f"bad={bad_listing} {st} {str(r)[:140]}")


# ═══════════════════════════════════════════════════════════════════════════
# C. 时间契约（问题③）
# ═══════════════════════════════════════════════════════════════════════════
def sec_c():
    print("\n══════ C. 消息时间：时区契约（问题③）══════")
    tok, uid, _ = signup("R53hx买C")
    call("POST", "/api/socials/messages", {
        "receiverId": OFFICIAL, "content": "C 时间契约", "bizType": "ASK", "bizId": "1002",
    }, token=tok)

    st, r = thread(tok, OFFICIAL, product_id="1002")
    items = page_items(r)
    latest = items[-1] if items else {}
    created = latest.get("createdAt")
    check("C1 消息 createdAt 带时区标记（Z 或 ±偏移）—— 前端换算本地时间的前提",
          has_zone_mark(created), f"createdAt={created!r}")

    st, r = call("GET", "/api/socials/messages/conversations?page=1&pageSize=20", token=tok)
    last_at = next((it.get("lastAt") for it in page_items(r)
                    if str(it.get("peerId")) == OFFICIAL), None)
    check("C2 会话列表 lastAt 带时区标记", has_zone_mark(last_at), f"lastAt={last_at!r}")

    if created:
        # 写入侧是 UTC：换算成北京时间后应等于「此刻北京」
        cn = to_cn(created)
        drift = abs((datetime.now(CN) - cn).total_seconds())
        check("C3 createdAt 为 UTC 且与本地实时一致（±180s）—— 证明后端写入 UTC、前端负责换算",
              drift <= 180, f"createdAt={created} → 北京 {cn:%Y-%m-%d %H:%M:%S}，偏差 {drift:.0f}s")
        check("C3b 若按裸切片展示会差 8 小时（即用户看到的 12:33→04:33）",
              created[len(created) - 6:len(created) - 1].replace("Z", "").strip() != f"{cn:%H:%M}",
              f"裸值={created[:16]} 北京={cn:%H:%M}")


def main():
    print("=" * 74)
    print("R53 热修冒烟：聊天消息发不出（404）+ 时间差 8 小时")
    print("=" * 74)
    sec_a()
    sec_b()
    sec_c()
    print("\n" + "=" * 74)
    print(f"结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print("  - " + f)
    print("=" * 74)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
