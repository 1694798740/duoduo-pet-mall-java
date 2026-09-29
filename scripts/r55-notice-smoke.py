#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R55 冒烟：通知体系补全（生产者 + 合并未读 + 内部推送端点）。

第五十五轮体检结论（本脚本要守住的正是这些）：
  * 此前全仓**没有任何业务通知生产者** —— t_social_notice 只有三条种子公告，
    收到私信不产生通知；设计稿里的 trade/fulfilment 推送端点从未实现；
  * 未读角标只在通知页 onShow 刷新，用户在首页/分享圈永远看不到数字；
  * 前端只能拉一页会话自己求私信未读，会话超过一页时角标偏小。

覆盖：
  A. 内部推送端点（设计稿里写了却一直没实现的那个入口）
     A1 无凭据 → 403；A2 错凭据 → 403；A3 正确凭据 → 200 且 accepted=1
     A4 非法分类 → 400；A5 拼错的落地页 → 400；A6 空列表 → 400；A7 空标题 → 400
     A8 两阶段落库：一批里第 2 条非法 → 整批不写（第 1 条也进不去）
     A9 落地页带 query → 通过（只校验路径段）
     A10 单批超上限（>100 条）→ 400
  B. 私信 → 通知（真正的生产者）
     B0 发消息成功；B1 收件人收到 FRIEND 通知且未读 +1
     B2 同会话连发第二条 → 通知条数与未读数都不增加（未读合并）
     B3 收件人读完后对方再发 → 重新派生一条（合并边界是「未读」而非「永久一条」）
     B4 商家侧发送 → MERCHANT 分类（客服账号，不可用时 SKIP）
  C. 合并未读口径（tabBar 角标的唯一数据源）
     C1 total == notice + message；C2 与两个分项接口逐个一致；C3 未登录 → 401
  D. 已读幂等与越权
     D1 标自己的通知 → 200；D2 重复标记 → 200（幂等）；D3 标他人通知 → 404
     D4 read-all → 200；D5 read-all 后通知未读归零
  E. 订单通知（交易域 → 社交域，经 Feign + 内部凭据）
     E1 直购下单 → ORDER_PLACED（bizId = 订单号，落地页指向订单详情）
     E2 支付成功 → ORDER_PAID
     E3 幂等重放不再重复推（同 idempotentKey 再付一次，ORDER_PAID 仍只有一条）
     E4 发货 → ORDER_SHIPPED（内部凭据直连交易域；不可用时 SKIP）
  F. 物流签收通知（履约域 → 社交域）
     F1 上报 SIGNED 节点 → LOGISTICS_SIGNED
     F2 重复上报同一节点（同发生时间）→ 不重复推（判据是「本次是否真写入」）

前置：social / trade / fulfilment 三容器已重建且 healthy。
用法：python scripts/r55-notice-smoke.py
"""
import datetime
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request

GW = (os.environ.get("R55_GATEWAY") or "http://127.0.0.1:8080").rstrip("/")
SOCIAL = (os.environ.get("R55_SOCIAL") or "http://127.0.0.1:8087").rstrip("/")
TRADE = (os.environ.get("R55_TRADE") or "http://127.0.0.1:8083").rstrip("/")
FULFILMENT = (os.environ.get("R55_FULFILMENT") or "http://127.0.0.1:8084").rstrip("/")
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "duoduo-local-dev-internal-token")
PWD = "duoduo12345"
CS_PHONE = "13900000002"

PASSED, FAILED, SKIPPED = [], [], []


def check(name, ok, got=""):
    (PASSED if ok else FAILED).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else "   实际=" + str(got)[:240]))


def skip(name, why):
    SKIPPED.append(name + "（" + why + "）")
    print("  [SKIP] " + name + "   " + why)


def call(method, path, body=None, token=None, base=GW, headers=None, timeout=20):
    req = urllib.request.Request(base + path, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except Exception:
                return resp.status, {"raw": raw[:300]}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}
    except Exception as e:
        return None, {"error": str(e)}


def d(r):
    return (r or {}).get("data") if isinstance(r, dict) else None


def items_of(r):
    data = d(r)
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("items") or data.get("records") or []
    return []


def phone_new():
    return "139" + "".join(random.choice("0123456789") for _ in range(8))


def signup(prefix):
    """注册 + 登录，返回 (token, userId)"""
    p = phone_new()
    call("POST", "/api/auth/register", {"phone": p, "password": PWD, "nickname": prefix})
    st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
    dd = d(r) or {}
    return dd.get("accessToken"), (dd.get("user") or {}).get("id")


def login(phone):
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    dd = d(r) or {}
    return dd.get("accessToken"), (dd.get("user") or {}).get("id")


def summary(token):
    st, r = call("GET", "/api/socials/notices/unread-summary", token=token)
    return d(r) or {}


def notices(token, category=None, size=50):
    path = "/api/socials/notices?page=1&pageSize=%d" % size + (("&category=" + category) if category else "")
    st, r = call("GET", path, token=token)
    return items_of(r) if st == 200 else []


def find_notice(token, biz_type, timeout=8.0):
    """等一条指定 bizType 的通知（事件是 AFTER_COMMIT 同步推送，通常一次就命中）"""
    deadline = time.time() + timeout
    last = []
    while True:
        last = notices(token)
        hit = next((x for x in last if x.get("bizType") == biz_type), None)
        if hit or time.time() >= deadline:
            return hit, last
        time.sleep(0.4)


def internal_push(items, token=INTERNAL_TOKEN, base=SOCIAL, path="/api/socials/internal/notices"):
    return call("POST", path, {"items": items}, base=base, headers={"X-Internal-Token": token})


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def base_item(**over):
    item = {
        "userId": 0,
        "category": "ANNOUNCEMENT",
        "title": "R55 冒烟公告",
        "content": "内部端点连通性检查",
        "bizType": None,
        "bizId": None,
        "jumpPage": None,
    }
    item.update(over)
    return item


# ---------------- A：内部端点凭据与契约 ----------------

def part_a():
    print("\n[A] 内部推送端点：凭据与契约校验")
    st, _ = call("POST", "/api/socials/internal/notices", {"items": [base_item()]}, base=SOCIAL)
    check("A1 无凭据 → 403", st == 403, st)

    st, _ = call("POST", "/api/socials/internal/notices", {"items": [base_item()]},
                 base=SOCIAL, headers={"X-Internal-Token": "not-the-token"})
    check("A2 错凭据 → 403", st == 403, st)

    title_ok = "R55 冒烟公告 " + str(random.randint(1000, 9999))
    st, body = internal_push([base_item(title=title_ok)])
    check("A3 正确凭据 → 200 且 accepted=1",
          st == 200 and (d(body) or {}).get("accepted") == 1, "%s %s" % (st, body))

    st, _ = internal_push([base_item(category="NOT_A_CATEGORY")])
    check("A4 非法分类 → 400", st == 400, st)

    st, _ = internal_push([base_item(jumpPage="/pages/order-detail/index")])
    check("A5 拼错的落地页 → 400（白名单拦住「点了没反应」的链接）", st == 400, st)

    st, _ = internal_push([])
    check("A6 空列表 → 400", st == 400, st)

    st, _ = internal_push([base_item(title="   ")])
    check("A7 空白标题 → 400", st == 400, st)

    # A9 先做：带 query 的落地页必须通过（只校验路径段）
    st, _ = internal_push([base_item(title="R55 带参落地页 " + str(random.randint(1000, 9999)),
                                     bizType="MANUAL", bizId="1",
                                     jumpPage="/pages/order-detail/order-detail?orderNo=R55TEST")])
    check("A9 落地页带 query → 通过（只校验路径段）", st == 200, st)

    # A8 两阶段落库：整批不写
    probe_tok, _ = signup("R55两阶段")
    marker = "R55 两阶段断言 " + str(random.randint(1000, 9999))
    before = [x.get("title") for x in notices(probe_tok)]
    st, _ = internal_push([base_item(title=marker), base_item(category="NOT_A_CATEGORY", title="非法那条")])
    after = [x.get("title") for x in notices(probe_tok)]
    check("A8 一批里有一条非法 → 整批不写（400 且第 1 条未落库）",
          st == 400 and marker not in before and marker not in after, "%s %s" % (st, after[:5]))

    # A10 单批上限
    st, _ = internal_push([base_item(title="批量上限 %d" % i) for i in range(101)])
    check("A10 单批超过 100 条 → 400", st == 400, st)


# ---------------- B：私信 → 通知 ----------------

def part_b():
    print("\n[B] 私信 → 通知（生产者）")
    tok_a, uid_a = signup("R55甲")
    tok_b, uid_b = signup("R55乙")
    if not (tok_a and tok_b and uid_a and uid_b):
        check("B 夹具：两个测试用户注册登录", False, "token/uid 缺失")
        return None
    # 互关：互关后才能连发多条（非互关每人限一条）
    call("POST", "/api/socials/follows/" + str(uid_b), token=tok_a)
    call("POST", "/api/socials/follows/" + str(uid_a), token=tok_b)

    before = summary(tok_b)
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": str(uid_b), "content": "R55 你好"}, token=tok_a)
    check("B0 发消息成功（200/201）", st in (200, 201), "%s %s" % (st, str(r)[:200]))

    rows_b = notices(tok_b, "FRIEND")
    msg_rows = [x for x in rows_b if x.get("bizType") == "MESSAGE"]
    check("B1 收件人收到 FRIEND 通知（bizType=MESSAGE）", len(msg_rows) == 1,
          "FRIEND 通知 %d 条：%s" % (len(msg_rows), str(rows_b[:2])[:200]))

    after = summary(tok_b)
    check("B1b 未读总数 +1（私信按条计；私信通知不计入通知分项，避免同一条消息数两次）",
          (after.get("total") or 0) - (before.get("total") or 0) == 1,
          "%s → %s" % (before, after))
    check("B1c 通知分项不含私信通知（notice 不变）",
          after.get("notice") == before.get("notice"),
          "notice %s → %s" % (before.get("notice"), after.get("notice")))

    # B2 同会话连发第二条 → 通知合并（不新增通知行）；私信未读仍按条数增长
    mid = summary(tok_b)
    call("POST", "/api/socials/messages", {"receiverId": str(uid_b), "content": "R55 第二条"}, token=tok_a)
    rows_b2 = [x for x in notices(tok_b, "FRIEND") if x.get("bizType") == "MESSAGE"]
    after2 = summary(tok_b)
    check("B2 同会话连发 → 通知条数不变（未读合并）", len(rows_b2) == 1, "条数=%d" % len(rows_b2))
    check("B2b 通知未读分项也不变（一条消息只算一次，且不计入通知）",
          after2.get("notice") == mid.get("notice"),
          "notice %s → %s" % (mid.get("notice"), after2.get("notice")))
    check("B2c 私信未读 +1（消息按条计，与主流角标口径一致）",
          (after2.get("message") or 0) - (mid.get("message") or 0) == 1,
          "message %s → %s" % (mid.get("message"), after2.get("message")))
    check("B2d 合并后内容为最新一条", "第二条" in str(rows_b2[0].get("content")) if rows_b2 else False,
          str(rows_b2[:1])[:160])

    # B3 读完之后再来消息 → 重新派生（合并的边界是「未读」，不是「永久只留一条」）
    call("POST", "/api/socials/notices/read-all", token=tok_b)
    base = summary(tok_b)
    call("POST", "/api/socials/messages", {"receiverId": str(uid_b), "content": "R55 第三条"}, token=tok_a)
    after3 = summary(tok_b)
    check("B3 已读后再来消息 → 未读 +1", (after3.get("total") or 0) - (base.get("total") or 0) == 1,
          "%s → %s" % (base, after3))
    msg_rows3 = [x for x in notices(tok_b) if x.get("bizType") == "MESSAGE"]
    check("B3b 该会话重新派生出未读条目（已存在一条已读 + 一条未读）",
          len(msg_rows3) == 2 and any(not x.get("read") for x in msg_rows3),
          "条数=%d 已读=%s" % (len(msg_rows3), [x.get("read") for x in msg_rows3]))

    # B4 商家侧（客服账号）→ MERCHANT 分类
    tok_cs, _ = login(CS_PHONE)
    if not tok_cs:
        skip("B4 商家消息分类", "客服账号 %s 登录失败" % CS_PHONE)
    else:
        call("POST", "/api/socials/messages",
             {"receiverId": str(uid_b), "content": "R55 客服消息"}, token=tok_cs)
        merchant = notices(tok_b, "MERCHANT")
        check("B4 商家/客服发来的消息 → MERCHANT 分类", len(merchant) >= 1,
              str(merchant[:1])[:200])

    return {"tok_b": tok_b, "uid_b": uid_b, "tok_a": tok_a, "uid_a": uid_a}


# ---------------- C：合并未读口径 ----------------

def part_c(ctx):
    print("\n[C] 合并未读口径（tabBar 角标的数据源）")
    tok_b = ctx["tok_b"]
    s = summary(tok_b)
    check("C1 total == notice + message",
          s.get("total") == (s.get("notice") or 0) + (s.get("message") or 0), s)

    st, r = call("GET", "/api/socials/notices/unread-count", token=tok_b)
    check("C2a 与 /notices/unread-count 一致（通知分项）",
          st == 200 and (d(r) or {}).get("count") == s.get("notice"), "%s vs %s" % (d(r), s))

    st, r = call("GET", "/api/socials/messages/unread-count", token=tok_b)
    check("C2b 与 /messages/unread-count 一致（私信分项）",
          st == 200 and (d(r) or {}).get("count") == s.get("message"), "%s vs %s" % (d(r), s))

    st, _ = call("GET", "/api/socials/notices/unread-summary")
    check("C3 未登录 → 401", st == 401, st)


# ---------------- D：已读幂等与越权 ----------------

def part_d(ctx):
    print("\n[D] 已读幂等与越权")
    tok_b, tok_a = ctx["tok_b"], ctx["tok_a"]
    rows = notices(tok_b)
    if not rows:
        check("D 夹具：收件人有可标记的通知", False, "通知列表为空")
        return
    target = rows[0]["id"]

    st, _ = call("POST", "/api/socials/notices/%s/read" % target, token=tok_b)
    check("D1 标记自己的通知已读 → 200", st == 200, st)

    st, _ = call("POST", "/api/socials/notices/%s/read" % target, token=tok_b)
    check("D2 重复标记 → 200（幂等）", st == 200, st)

    st, _ = call("POST", "/api/socials/notices/%s/read" % target, token=tok_a)
    check("D3 标记他人通知 → 404（不区分「不存在」与「无权限」）", st == 404, st)

    st, _ = call("POST", "/api/socials/notices/read-all", token=tok_b)
    check("D4 全部已读 → 200", st == 200, st)
    check("D5 read-all 后通知未读归零", (summary(tok_b).get("notice") or 0) == 0, summary(tok_b))


# ---------------- E：订单通知 ----------------

def part_e():
    print("\n[E] 订单通知（交易域 → 社交域）")
    tok, _ = signup("R55买家")
    if not tok:
        check("E 夹具：买家注册登录", False, "无 token")
        return None

    st, r = call("POST", "/api/orders", {
        "receiverName": "R55买家", "receiverPhone": "13900001111",
        "receiverAddress": "广东省深圳市南山区科技园路 1 号",
        "buySkuId": 2001, "buyQuantity": 1,
        "idempotentKey": "r55-" + str(random.randint(1, 10 ** 9)),
    }, token=tok)
    orders = (d(r) or {}).get("orders") or []
    check("E0 直购下单成功（200/201）", st in (200, 201) and len(orders) == 1, "%s %s" % (st, str(r)[:200]))
    if not orders:
        return None
    order_no = orders[0].get("orderNo")

    hit, all_rows = find_notice(tok, "ORDER_PLACED")
    check("E1 下单 → ORDER_PLACED 通知", hit is not None,
          "通知 bizType：%s" % [x.get("bizType") for x in all_rows])
    if hit:
        check("E1b bizId = 订单号（字符串口径）", str(hit.get("bizId")) == str(order_no),
              "%s vs %s" % (hit.get("bizId"), order_no))
        check("E1c 落地页指向订单详情", str(hit.get("jumpPage") or "").startswith("/pages/order-detail/order-detail"),
              hit.get("jumpPage"))
        check("E1d 分类为 ORDER", hit.get("category") == "ORDER", hit.get("category"))

    pay_key = "r55pay-" + str(random.randint(1, 10 ** 9))
    st, r = call("POST", "/api/orders/%s/pay" % order_no,
                 {"channel": "MOCK", "idempotentKey": pay_key}, token=tok)
    check("E2a 支付成功", st == 200, "%s %s" % (st, str(r)[:200]))
    hit_paid, all_paid = find_notice(tok, "ORDER_PAID")
    check("E2b 支付 → ORDER_PAID 通知", hit_paid is not None,
          "通知 bizType：%s" % [x.get("bizType") for x in all_paid])

    # E3 幂等重放不重复推
    call("POST", "/api/orders/%s/pay" % order_no, {"channel": "MOCK", "idempotentKey": pay_key}, token=tok)
    paid_rows = [x for x in notices(tok) if x.get("bizType") == "ORDER_PAID"]
    check("E3 幂等重放不再重复推（ORDER_PAID 仍只有 1 条）", len(paid_rows) == 1,
          "条数=%d" % len(paid_rows))

    # E4 发货（内部凭据直连交易域）
    st, r = call("POST", "/api/orders/%s/ship" % order_no, base=TRADE,
                 headers={"X-Internal-Token": INTERNAL_TOKEN}, body={})
    if st in (200, 201):
        hit_ship, all_ship = find_notice(tok, "ORDER_SHIPPED")
        check("E4 发货 → ORDER_SHIPPED 通知", hit_ship is not None,
              "通知 bizType：%s" % [x.get("bizType") for x in all_ship])
    elif st in (401, 403):
        skip("E4 发货 → ORDER_SHIPPED 通知", "发货端点不接受内部凭据直连（HTTP %s）" % st)
    else:
        check("E4 发货 → ORDER_SHIPPED 通知", False, "%s %s" % (st, str(r)[:200]))

    return {"token": tok, "orderNo": order_no}


# ---------------- F：物流签收通知 ----------------

def part_f(ctx_e):
    print("\n[F] 物流签收通知（履约域 → 社交域）")
    if not ctx_e:
        skip("F 物流签收通知", "无可用订单（E 段未产出）")
        return
    token, order_no = ctx_e["token"], ctx_e["orderNo"]
    occurred = now_iso()

    st, r = call("POST", "/api/logistics/internal/%s/tracks" % order_no,
                 {"source": "COURIER", "nodes": [
                     {"nodeCode": "SIGNED", "nodeText": "已签收，感谢使用", "occurredAt": occurred, "raw": None}]},
                 base=FULFILMENT, headers={"X-Internal-Token": INTERNAL_TOKEN})
    check("F0 上报 SIGNED 节点成功", st == 200, "%s %s" % (st, str(r)[:200]))

    hit, all_rows = find_notice(token, "LOGISTICS_SIGNED")
    check("F1 签收 → LOGISTICS_SIGNED 通知", hit is not None,
          "通知 bizType：%s" % [x.get("bizType") for x in all_rows])

    # F2 重复上报同一节点（同 occurredAt）：唯一索引跳过 → 不重复推
    call("POST", "/api/logistics/internal/%s/tracks" % order_no,
         {"source": "COURIER", "nodes": [
             {"nodeCode": "SIGNED", "nodeText": "已签收，感谢使用", "occurredAt": occurred, "raw": None}]},
         base=FULFILMENT, headers={"X-Internal-Token": INTERNAL_TOKEN})
    signed_rows = [x for x in notices(token) if x.get("bizType") == "LOGISTICS_SIGNED"]
    check("F2 重复上报同一节点 → 不重复推通知", len(signed_rows) == 1, "条数=%d" % len(signed_rows))


def main():
    print("R55 通知冒烟：网关 %s / social %s / trade %s / fulfilment %s" % (GW, SOCIAL, TRADE, FULFILMENT))
    part_a()
    ctx_b = part_b()
    if ctx_b:
        part_c(ctx_b)
        part_d(ctx_b)
    else:
        skip("C/D 合并未读与已读", "B 段夹具未就绪")
    ctx_e = part_e()
    part_f(ctx_e)

    print("\n结果：%d 通过 / %d 失败 / %d 跳过" % (len(PASSED), len(FAILED), len(SKIPPED)))
    if FAILED:
        print("失败项：" + "；".join(FAILED))
    if SKIPPED:
        print("跳过项：" + "；".join(SKIPPED))
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
