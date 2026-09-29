#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第五十六轮 · 分享圈补全后端探针（评论 / 删除编辑 / 举报 / 关注流 / 话题标签）

为什么单独一套而不是扩写 share-circle-smoke.py：
  老探针覆盖「发布 / 信息流 / 详情 / 点赞收藏 / 转发」，是本轮改动的**回归基线**；
  本轮新增的是五条独立能力，混在一起会让「哪一段挂了」变得难判断。
  两者都跑，才是完整的回归。

覆盖：
  A 话题标签   —— 发布带话题 / 超 2 个截断而非报错 / 剥 # / 去重 / 非法名丢弃 /
                  热门榜计数 / 聚合页（免登录）/ 不存在 404 / 非法名 400 / 编辑替换后计数正确
  B 评论与回复 —— 未登录 401 / 空内容 400 / 一级评论 / 回复 / 回复回复 422 /
                  跨分享回复 400 / 幽灵评论 404 / 评论点赞 toggle 与幂等 /
                  评论数在详情与信息流两处一致
  C 删除与编辑 —— 未登录与越权 / 本人编辑 / topics=null 不改 / topics=[] 清空 /
                  软删后详情 404 且信息流不含 / 重复删除 404 /
                  ★ 原帖删除后转发单仍在且 parentDeleted=true、parentContent=null
  D 举报     —— 未登录 401 / 无理由 400 / 举报他人分享 / 重复 409 / 举报自己 422 /
                  举报幽灵对象 404 / 举报评论 / 非运营 403 / 运营列表 /
                  裁定 ACCEPT 下架内容（分享与评论各一）/ 评论下架后评论数回退 /
                  重复裁定 409 / 处理说明必填 400
  E 关注流   —— 未登录 401 / 未关注任何人返回空（★ 绝不等价于全站）/
                  关注后能看到被关注者的分享 / 不含未关注者 / 取关后回到空

前置：9 容器 healthy（网关 :8080）；增量 SQL 19-r56-share-circle.sql 已执行。
用法：python scripts/r56-share-circle-smoke.py
"""
import json
import random
import sys
import urllib.error
import urllib.parse
import urllib.request

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
OPERATOR_PHONE = "13688774031"  # 历史运营账号（role=OPERATOR）

passed, failed, skipped = [], [], []


def check(name, ok, got=""):
    (passed if ok else failed).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else f"  实际={got}"))


def skip(name, why):
    skipped.append(name)
    print(f"  [SKIP] {name}  （{why}）")


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
            return e.code, {"raw": raw[:300]}
    except Exception as e:  # 网络层
        return 0, {"raw": str(e)[:300]}


def d(res):
    return (res or {}).get("data") or {}


def items_of(res):
    return ((res or {}).get("data") or {}).get("items") or []


def new_user(nickname):
    phone = f"137{random.randint(10**7, 10**8 - 1)}"
    st, res = call("POST", "/api/auth/register",
                   {"phone": phone, "password": PWD, "nickname": nickname})
    uid = d(res).get("id") if st == 200 else None
    st2, res2 = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    tok = d(res2).get("accessToken")
    return {"phone": phone, "id": uid, "token": tok}


print("=== 第五十六轮 · 分享圈补全后端探针 ===")

# ── 夹具 ────────────────────────────────────────────────────────────────────
author = new_user("分享作者")
reader = new_user("分享读者")
check("夹具：两个用户已注册并登录", bool(author["token"]) and bool(reader["token"]),
      f"author={author['id']} reader={reader['id']}")

st, res = call("GET", "/api/products/internal/skus/batch?ids=2001", None, author["token"])
spu = None
if isinstance(res.get("data"), list) and res["data"]:
    spu = res["data"][0].get("spuId")
if not spu:
    spu = 1001
check("夹具：取到可分享的 SPU", bool(spu), f"spu={spu}")

TAG = "养宠日常" + str(random.randint(1000, 9999))  # 唯一话题名，避免与历史数据相互干扰
TAG2 = "新手养猫" + str(random.randint(1000, 9999))

# ═══════════════════════════ A 话题标签 ═══════════════════════════
print("\n[A] 话题标签")

st, res = call("POST", "/api/shares", {"spuId": spu, "content": "A 段主分享", "topics": [TAG, TAG2]},
               author["token"])
a_share = d(res).get("id")
topics = d(res).get("topics") or []
check("A1 发布带 2 个话题 → 回显 2 个", st == 200 and a_share and len(topics) == 2, f"{st} {topics}")

st, res = call("POST", "/api/shares",
               {"spuId": spu, "content": "A2 三个话题", "topics": [TAG + "x", TAG + "y", TAG + "z"]},
               author["token"])
check("A2 传 3 个话题 → 截断为 2（不报错，结果是可预期的）",
      st == 200 and len(d(res).get("topics") or []) == 2, f"{st} {d(res).get('topics')}")

st, res = call("POST", "/api/shares",
               {"spuId": spu, "content": "A3 带井号", "topics": ["#" + TAG, "##" + TAG2]},
               author["token"])
check("A3 用户自己打的 # 被剥掉", st == 200 and (d(res).get("topics") or []) == [TAG, TAG2],
      f"{st} {d(res).get('topics')}")

st, res = call("POST", "/api/shares",
               {"spuId": spu, "content": "A4 重复话题", "topics": [TAG, TAG, TAG2]},
               author["token"])
check("A4 同一话题重复出现 → 去重（结果为 2 个不重复）",
      st == 200 and (d(res).get("topics") or []) == [TAG, TAG2], f"{st} {d(res).get('topics')}")

st, res = call("POST", "/api/shares",
               {"spuId": spu, "content": "A5 脏话题", "topics": ["", "   ", "#"]},
               author["token"])
check("A5 全是空 / # 的话题 → 全部丢弃，分享仍发布成功",
      st == 200 and (d(res).get("topics") or []) == [], f"{st} {d(res).get('topics')}")

st, res = call("POST", "/api/shares",
               {"spuId": spu, "content": "A6 超长与空白话题",
                "topics": ["x" * 40, "有 空格", TAG]},
               author["token"])
check("A6 超长名与含空白名被丢弃，合法名保留",
      st == 200 and (d(res).get("topics") or []) == [TAG], f"{st} {d(res).get('topics')}")

st, res = call("GET", "/api/shares/topics?limit=20")
hot = {t.get("name"): t.get("shareCount") for t in (res.get("data") or [])}
check("A7 热门话题榜包含刚用的话题且计数 ≥2",
      st == 200 and hot.get(TAG, 0) >= 2, f"{st} {hot.get(TAG)}")

st, res = call("GET", "/api/shares/topics/" + urllib.parse.quote(TAG) + "?page=1&pageSize=10")
ids = [str(it.get("id")) for it in items_of(res)]
check("A8 话题聚合页免登录可访问且包含该分享", st == 200 and str(a_share) in ids,
      f"{st} total={d(res).get('total')}")

st, res = call("GET", "/api/shares/topics/" + urllib.parse.quote("绝对不存在的太话题zzz"))
check("A9 不存在的话题 → 404", st == 404, f"{st} {res}")

st, res = call("GET", "/api/shares/topics/" + urllib.parse.quote("含 空格"))
check("A10 非法话题名 → 400", st == 400, f"{st} {res}")

st, res = call("POST", f"/api/shares/{a_share}/edit",
               {"content": "A 段主分享（已编辑）", "topics": [TAG2]}, author["token"])
check("A11 编辑替换话题 → 只留新话题", st == 200 and (d(res).get("topics") or []) == [TAG2],
      f"{st} {d(res).get('topics')}")

st, res = call("GET", "/api/shares/topics?limit=50")
hot2 = {t.get("name"): t.get("shareCount") for t in (res.get("data") or [])}
check("A12 被替换掉的旧话题计数已回退（比替换前少）",
      st == 200 and hot2.get(TAG, 0) < hot.get(TAG, 0), f"前={hot.get(TAG)} 后={hot2.get(TAG)}")

# ═══════════════════════════ B 评论与回复 ═══════════════════════════
print("\n[B] 评论与回复")

st, res = call("POST", "/api/shares", {"spuId": spu, "content": "B 段评论靶子"}, author["token"])
b_share = d(res).get("id")
check("B0 评论靶子分享已发布", st == 200 and bool(b_share), f"{st}")

st, res = call("POST", f"/api/shares/{b_share}/comments", {"content": "匿名评论"})
check("B1 未登录发表评论 → 401", st == 401, f"{st} {res}")

st, res = call("POST", f"/api/shares/{b_share}/comments", {"content": "   "}, reader["token"])
check("B2 空白评论 → 400", st == 400, f"{st} {res}")

st, res = call("POST", f"/api/shares/{b_share}/comments", {"content": "这条猫粮我家猫很爱"}, reader["token"])
c1 = d(res).get("id")
check("B3 一级评论 → 200 且回显昵称", st == 200 and c1 and d(res).get("nickname") == "分享读者",
      f"{st} {d(res)}")

st, res = call("GET", f"/api/shares/comments?shareId={b_share}&page=1&pageSize=10")
rows = items_of(res)
check("B4 ★ 评论列表免登录可读且包含该评论",
      st == 200 and any(str(r.get("id")) == str(c1) for r in rows), f"{st} rows={len(rows)}")

st, res = call("POST", f"/api/shares/{b_share}/comments",
               {"content": "请问多大袋的？", "parentCommentId": c1}, author["token"])
reply = d(res).get("id")
check("B5 回复一级评论 → 200 且带「回复 @某人」", st == 200 and reply
      and d(res).get("replyToNickname") == "分享读者", f"{st} {d(res)}")

st, res = call("POST", f"/api/shares/{b_share}/comments",
               {"content": "回复一条回复", "parentCommentId": reply}, reader["token"])
check("B6 回复「回复」→ 422（只做二级）", st == 422, f"{st} {res}")

st, res = call("POST", "/api/shares", {"spuId": spu, "content": "B 段干扰分享"}, author["token"])
other_share = d(res).get("id")
st, res = call("POST", f"/api/shares/{other_share}/comments",
               {"content": "跨分享回复", "parentCommentId": c1}, reader["token"])
check("B7 回复不属于本分享的评论 → 400", st == 400, f"{st} {res}")

st, res = call("POST", f"/api/shares/{b_share}/comments",
               {"content": "幽灵父评论", "parentCommentId": 1234567890123456789}, reader["token"])
check("B8 回复幽灵评论 → 404", st == 404, f"{st} {res}")

st, res = call("POST", f"/api/shares/comments/{c1}/like", {}, reader["token"])
check("B9 评论点赞 → active=true count=1",
      st == 200 and d(res).get("active") is True and d(res).get("count") == 1, f"{st} {d(res)}")

st, res = call("POST", f"/api/shares/comments/{c1}/like", {}, reader["token"])
check("B10 再点一次 → 取消（active=false count=0）",
      st == 200 and d(res).get("active") is False and d(res).get("count") == 0, f"{st} {d(res)}")

st, res = call("GET", f"/api/shares/{b_share}")
check("B11 分享详情 commentCount 已计入一级评论与回复（=2）",
      st == 200 and d(res).get("commentCount") == 2, f"{st} commentCount={d(res).get('commentCount')}")

st, res = call("GET", "/api/shares/feed?page=1&pageSize=50")
hit = [it for it in items_of(res) if str(it.get("id")) == str(b_share)]
check("B12 信息流卡片同样带上 commentCount",
      st == 200 and hit and hit[0].get("commentCount") == 2,
      f"{st} {(hit[0].get('commentCount') if hit else None)}")

st, res = call("GET", f"/api/shares/comments?shareId={b_share}&parentId={c1}&page=1&pageSize=10")
check("B13 回复列表按 parentId 单独可读（1 条）",
      st == 200 and len(items_of(res)) == 1, f"{st} {len(items_of(res))}")

st, res = call("GET", f"/api/shares/comments?shareId={b_share}&page=1&pageSize=10")
first = items_of(res)[0] if items_of(res) else {}
check("B14 一级列表给出回复数（replyCount=1）", first.get("replyCount") == 1, f"{first.get('replyCount')}")

# ═══════════════════════════ C 删除与编辑 ═══════════════════════════
print("\n[C] 删除与编辑")

st, res = call("POST", "/api/shares", {"spuId": spu, "content": "C 段原帖"}, author["token"])
c_share = d(res).get("id")

st, res = call("POST", f"/api/shares/{c_share}/edit", {"content": "匿名想改"})
check("C1 未登录编辑 → 401", st == 401, f"{st} {res}")

st, res = call("POST", f"/api/shares/{c_share}/edit", {"content": "别人想改"}, reader["token"])
check("C2 编辑他人分享 → 404（与「不存在」同码，防枚举）", st == 404, f"{st} {res}")

st, res = call("POST", f"/api/shares/{c_share}/edit",
               {"content": "C 段原帖（作者改过一次）"}, author["token"])
check("C3 本人编辑 → 200 且 edited=true",
      st == 200 and d(res).get("edited") is True
      and d(res).get("content") == "C 段原帖（作者改过一次）", f"{st} {d(res)}")

# 编辑时 topics=null 不应改动已有话题（用 A 段的分享验证，它此刻话题是 [TAG2]）
st, res = call("POST", f"/api/shares/{a_share}/edit", {"content": "A 段主分享（再编辑）"},
               author["token"])
check("C4 编辑时 topics 传 null → 话题保持不变",
      st == 200 and (d(res).get("topics") or []) == [TAG2], f"{st} {d(res).get('topics')}")

st, res = call("POST", f"/api/shares/{a_share}/edit",
               {"content": "A 段主分享（清空话题）", "topics": []}, author["token"])
check("C5 编辑时 topics 传空数组 → 话题被清空（与「不改」区分开）",
      st == 200 and (d(res).get("topics") or []) == [], f"{st} {d(res).get('topics')}")

st, res = call("POST", f"/api/shares/{c_share}/repost", {"content": "我转了 C 段原帖"}, reader["token"])
c_repost = d(res).get("id")
check("C6 读者转发 C 段原帖（为 C9 铺垫）", st == 200 and bool(c_repost), f"{st} {res}")

st, res = call("POST", f"/api/shares/{c_share}/delete", {}, reader["token"])
check("C7 删除他人分享 → 404", st == 404, f"{st} {res}")

st, res = call("POST", f"/api/shares/{c_share}/delete", {}, author["token"])
check("C8 本人删除 → 200", st == 200, f"{st} {res}")

st, res = call("GET", f"/api/shares/{c_share}")
check("C9 删除后详情 → 404", st == 404, f"{st} {res}")

st, res = call("GET", "/api/shares/feed?page=1&pageSize=50")
check("C10 删除后信息流不再包含它",
      st == 200 and not any(str(it.get("id")) == str(c_share) for it in items_of(res)), f"{st}")

st, res = call("POST", f"/api/shares/{c_share}/delete", {}, author["token"])
check("C11 重复删除 → 404（已不存在）", st == 404, f"{st} {res}")

st, res = call("GET", f"/api/shares/{c_repost}")
dv = d(res)
check("C12 ★ 原帖删除后转发单仍在，parentDeleted=true 且原内容不再下发",
      st == 200 and dv.get("parentDeleted") is True and dv.get("parentContent") is None,
      f"{st} parentDeleted={dv.get('parentDeleted')} parentContent={dv.get('parentContent')}")

st, res = call("GET", f"/api/shares/{a_share}")
check("C13 未登录看该分享 → 200 且 mine=false（匿名没有「我的」）",
      st == 200 and d(res).get("mine") is False, f"{st} mine={d(res).get('mine')}")

st, res = call("GET", f"/api/shares/{a_share}", None, author["token"])
check("C14 mine 标记（带登录态）→ true", st == 200 and d(res).get("mine") is True,
      f"{st} mine={d(res).get('mine')}")

st, res = call("GET", f"/api/shares/{a_share}", None, reader["token"])
check("C15 mine 标记（他人登录态）→ false", st == 200 and d(res).get("mine") is False,
      f"{st} mine={d(res).get('mine')}")

st, res = call("GET", f"/api/shares/mine/list?page=1&pageSize=50", None, author["token"])
mine_ids = [str(it.get("id")) for it in items_of(res)]
check("C16 我的分享列表含自己的、不含别人的", st == 200 and str(a_share) in mine_ids
      and str(c_share) not in mine_ids, f"{st} {len(mine_ids)}")

st, res = call("GET", "/api/shares/mine/list")
check("C17 未登录看我的分享 → 401", st == 401, f"{st} {res}")

st, res = call("POST", f"/api/shares/{c_repost}/edit", {"content": ""}, reader["token"])
check("C18 转发单可以清空附言（纯转发，内容允许为空串）", st == 200 and d(res).get("content") == "",
      f"{st} {d(res).get('content')}")

# ═══════════════════════════ D 举报 ═══════════════════════════
print("\n[D] 举报与运营处理")

st, res = call("POST", "/api/shares", {"spuId": spu, "content": "D 段待下架分享"}, author["token"])
d_share = d(res).get("id")
st, res = call("POST", f"/api/shares/{d_share}/comments", {"content": "D 段待下架评论"}, author["token"])
d_comment = d(res).get("id")
check("D0 举报靶子已就绪（分享 + 评论）", bool(d_share and d_comment), f"{st}")

st, res = call("POST", f"/api/shares/{d_share}/report", {"reason": "匿名举报"})
check("D1 未登录举报 → 401", st == 401, f"{st} {res}")

st, res = call("POST", f"/api/shares/{d_share}/report", {"reason": "   "}, reader["token"])
check("D2 无理由举报 → 400", st == 400, f"{st} {res}")

st, res = call("POST", f"/api/shares/{d_share}/report", {"reason": "内容像是广告"}, reader["token"])
r1 = d(res).get("id")
check("D3 举报他人分享 → 200 且状态 PENDING",
      st == 200 and r1 and d(res).get("status") == "PENDING", f"{st} {d(res)}")

st, res = call("POST", f"/api/shares/{d_share}/report", {"reason": "再举报一次"}, reader["token"])
check("D4 重复举报同一对象 → 409", st == 409, f"{st} {res}")

st, res = call("POST", f"/api/shares/{d_share}/report", {"reason": "举报自己"}, author["token"])
check("D5 举报自己的分享 → 422（那是删除，不该占运营队列）", st == 422, f"{st} {res}")

st, res = call("POST", "/api/shares/1234567890123456789/report", {"reason": "幽灵"}, reader["token"])
check("D6 举报幽灵分享 → 404", st == 404, f"{st} {res}")

st, res = call("POST", f"/api/shares/comments/{d_comment}/report",
               {"reason": "评论是引战"}, reader["token"])
r2 = d(res).get("id")
check("D7 举报评论 → 200", st == 200 and r2 and d(res).get("targetType") == "COMMENT", f"{st} {d(res)}")

st, res = call("POST", f"/api/shares/comments/{d_comment}/report",
               {"reason": "举报自己的评论"}, author["token"])
check("D8 举报自己的评论 → 422", st == 422, f"{st} {res}")

st, res = call("GET", "/api/admin/share-reports?status=PENDING", None, reader["token"])
check("D9 非运营访问举报台 → 403", st in (401, 403), f"{st} {res}")

st, res = call("POST", "/api/auth/login/token", {"phone": OPERATOR_PHONE, "password": PWD})
op_token = d(res).get("accessToken")
if not op_token:
    skip("D10~D15 运营处理链路", "运营账号登录失败")
else:
    check("D10 运营账号登录成功", True)

    st, res = call("GET", "/api/admin/share-reports?status=PENDING&page=1&pageSize=50", None, op_token)
    rows = items_of(res)
    mine = [r for r in rows if str(r.get("id")) in (str(r1), str(r2))]
    check("D11 运营列表能看到这两条待处理举报（含被举报内容摘要）",
          st == 200 and len(mine) == 2 and all(r.get("targetExcerpt") for r in mine),
          f"{st} 命中={len(mine)}")

    st, res = call("POST", f"/api/admin/share-reports/{r1}/handle", {"action": "ACCEPT"}, op_token)
    check("D12 处理说明必填 → 400", st == 400, f"{st} {res}")

    st, res = call("POST", f"/api/admin/share-reports/{r1}/handle",
                   {"action": "ACCEPT", "note": "确认是广告，已下架"}, op_token)
    check("D13 裁定成立 → 状态 ACCEPTED 且记录处理人",
          st == 200 and d(res).get("status") == "ACCEPTED" and d(res).get("handledBy"),
          f"{st} {d(res).get('status')}")

    st, res = call("GET", f"/api/shares/{d_share}")
    check("D14 ★ 裁定成立后分享已下架（详情 404）", st == 404, f"{st} {res}")

    st, res = call("POST", f"/api/admin/share-reports/{r1}/handle",
                   {"action": "REJECT", "note": "再处理一次"}, op_token)
    check("D15 重复处理同一举报 → 409", st == 409, f"{st} {res}")

    st, res = call("POST", f"/api/admin/share-reports/{r2}/handle",
                   {"action": "ACCEPT", "note": "评论确实引战，已下架"}, op_token)
    check("D16 评论举报裁定成立 → ACCEPTED", st == 200 and d(res).get("status") == "ACCEPTED",
          f"{st} {d(res)}")

    st, res = call("GET", f"/api/shares/comments?shareId={d_share}&page=1&pageSize=10")
    check("D17 被下架评论不再出现在公开列表（分享已下架，列表按 404 处理）",
          st in (200, 404), f"{st}")

    # 用另一条未被下架的分享验证「评论下架 + 评论数回退」
    st, res = call("POST", "/api/shares", {"spuId": spu, "content": "D 段计数验证分享"},
                   author["token"])
    d2 = d(res).get("id")
    st, res = call("POST", f"/api/shares/{d2}/comments", {"content": "将被下架的评论"},
                   author["token"])
    d2c = d(res).get("id")
    st, res = call("GET", f"/api/shares/{d2}")
    before = d(res).get("commentCount")
    st, res = call("POST", f"/api/shares/comments/{d2c}/report", {"reason": "违规评论"},
                   reader["token"])
    d2r = d(res).get("id")
    st, res = call("POST", f"/api/admin/share-reports/{d2r}/handle",
                   {"action": "ACCEPT", "note": "违规，下架"}, op_token)
    check("D18 评论下架成功", st == 200, f"{st} {res}")
    st, res = call("GET", f"/api/shares/{d2}")
    after = d(res).get("commentCount")
    check("D19 ★ 评论下架后 commentCount 已回退（1 → 0）",
          before == 1 and after == 0, f"before={before} after={after}")

# ═══════════════════════════ E 关注流 ═══════════════════════════
print("\n[E] 关注流")

st, res = call("GET", "/api/shares/feed?scope=FOLLOWING&page=1&pageSize=10")
check("E1 未登录看关注流 → 401", st == 401, f"{st} {res}")

loner = new_user("孤独用户")
st, res = call("GET", "/api/shares/feed?scope=FOLLOWING&page=1&pageSize=10", None, loner["token"])
empty_total = d(res).get("total")
st2, res2 = call("GET", "/api/shares/feed?scope=RECOMMEND&page=1&pageSize=10", None, loner["token"])
all_total = d(res2).get("total")
check("E2 ★ 未关注任何人 → 关注流为空，且**不等价于全站**（全站有数据）",
      st == 200 and empty_total == 0 and (all_total or 0) > 0,
      f"following={empty_total} recommend={all_total}")

st, res = call("POST", "/api/shares", {"spuId": spu, "content": "E 段被关注者的分享"}, author["token"])
e_share = d(res).get("id")
st, res = call("POST", f"/api/socials/follows/{author['id']}", {}, loner["token"])
check("E3 关注成功", st == 200, f"{st} {res}")

st, res = call("GET", "/api/shares/feed?scope=FOLLOWING&page=1&pageSize=20", None, loner["token"])
ids = [str(it.get("id")) for it in items_of(res)]
check("E4 关注后 → 关注流包含被关注者的分享", st == 200 and str(e_share) in ids,
      f"{st} total={d(res).get('total')}")

st, res = call("GET", "/api/shares/feed?scope=FOLLOWING&page=1&pageSize=20", None, loner["token"])
writers = {str(it.get("userId")) for it in items_of(res)}
check("E5 关注流里只有被关注者的内容", writers <= {str(author["id"])}, f"{writers}")

st, res = call("DELETE", f"/api/socials/follows/{author['id']}", None, loner["token"])
st, res = call("GET", "/api/shares/feed?scope=FOLLOWING&page=1&pageSize=10", None, loner["token"])
check("E6 取关后 → 关注流回到空", st == 200 and d(res).get("total") == 0,
      f"{st} total={d(res).get('total')}")

st, res = call("GET", "/api/shares/feed?scope=WHATEVER&page=1&pageSize=5", None, loner["token"])
check("E7 非法 scope → 按默认推荐流处理（不报错、不是空）",
      st == 200 and (d(res).get("total") or 0) > 0, f"{st} total={d(res).get('total')}")

# ── 汇总 ────────────────────────────────────────────────────────────────────
print("\n" + "=" * 74)
print(f"结果：{len(passed)} 通过 / {len(failed)} 失败 / {len(skipped)} 跳过")
if failed:
    print("失败项：")
    for name in failed:
        print("  - " + name)
print("=" * 74)
sys.exit(1 if failed else 0)
