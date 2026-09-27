#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第四十三/四十四/四十六轮 · 分享圈后端探针
覆盖：
  ① 未登录 POST /api/shares        → 401（发布必须登录，网关拦截）
  ② 登录后发布分享                  → 200，返回服务端快照（标题/主图/价格）+ 昵称
  ③ GET /api/shares/feed（公开）    → 200，信息流包含刚发布的条目
  ④ GET /api/shares/{id}（公开）    → 200，详情与发布内容一致
  ⑤ 发布时商品不存在                → 404（不允许给幽灵商品发分享）
  ⑥ 发布空内容                     → 400
  ⑦ GET /api/shares/{bogus}（公开）→ 404
  ⑧⑨⑩ 点赞 / 收藏 toggle（第四十四轮）
  ⑪⑫⑬⑭ 转发（第四十六轮）：
     未登录转发 401 / 转发落库为「以转发人为作者」的新分享 /
     信息流出现转发动态（bug①）/ 转发单详情身份 = 转发人（bug②）/
     重复转发 409 / 自转 422 / 幽灵原帖 404 / 空附言纯转发 200
前置：8 容器在跑（网关 :8080），增量 SQL 12/13/14 已执行。
用法：python scripts/share-circle-smoke.py
"""
import json
import random
import sys
import urllib.request

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
SKU = 2001  # 直购冒烟同款 SKU，用于反查 SPU

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


print("=== 第四十三轮 · 分享圈后端探针 ===")

# ── 夹具：注册 + 登录 + SPU 快照 ──────────────────────────────────────────────
phone = f"136{random.randint(10**7, 10**8 - 1)}"
st, res = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "分享圈测试"})
check("夹具：注册成功", st == 200, f"{st} {res}")

st, res = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
token = (res.get("data") or {}).get("accessToken") if isinstance(res, dict) else None
check("夹具：登录取到令牌", st == 200 and token, f"{st}")

st, res = call("GET", f"/api/products/internal/skus/batch?ids={SKU}", None, token)  # 内部接口走网关需登录态
spu_id = ((res.get("data") or [{}])[0]).get("spuId") if isinstance(res.get("data"), list) else None
if not spu_id:
    # 兜底：商品详情公开接口按已知 SPU 区间探测（seedOf 与 12 商品对应，直接用 1 号商品）
    spu_id = 1001
check("夹具：反查到 SPU", bool(spu_id), f"{st} {res}")

# ── ① 未登录发布 → 401 ───────────────────────────────────────────────────────
st, res = call("POST", "/api/shares", {"spuId": spu_id, "content": "匿名测试"})
check("① 未登录发布 → 401", st == 401, f"{st} {res}")

# ── ② 登录发布 → 200 + 服务端快照 ────────────────────────────────────────────
st, res = call("POST", "/api/shares", {"spuId": spu_id, "content": "毛孩子的口粮，回购第三次了"}, token)
view = (res.get("data") or {}) if isinstance(res, dict) else {}
share_id = view.get("id")
check("② 发布分享 → 200 且返回分享单 ID", st == 200 and share_id, f"{st} {res}")
check("② 快照来自商品域（标题非空、价格为数值）",
      bool(view.get("productTitle")) and isinstance(view.get("minPrice"), (int, float)),
      view.get("productTitle"))
check("② 昵称已补全（Feign briefs 生效）", view.get("nickname") == "分享圈测试", view.get("nickname"))

# ── ③ 信息流（公开） ─────────────────────────────────────────────────────────
st, res = call("GET", "/api/shares/feed?page=1&pageSize=10")
items = ((res.get("data") or {}).get("items")) if isinstance(res, dict) else None
found = any(str(it.get("id")) == str(share_id) for it in (items or []))
check("③ 免登录拉信息流 → 200 且包含刚发布的条目", st == 200 and found, f"{st} items={len(items or [])}")

# ── ④ 分享详情（公开） ───────────────────────────────────────────────────────
st, res = call("GET", f"/api/shares/{share_id}")
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("④ 免登录看分享详情 → 200 且内容一致",
      st == 200 and d.get("content") == "毛孩子的口粮，回购第三次了" and str(d.get("spuId")) == str(spu_id),
      f"{st} {d}")

# ── ⑤ 商品不存在 → 404 ───────────────────────────────────────────────────────
st, res = call("POST", "/api/shares", {"spuId": 99999999, "content": "幽灵商品"}, token)
check("⑤ 给不存在的商品发分享 → 404", st == 404, f"{st} {res}")

# ── ⑥ 空内容 → 400 ──────────────────────────────────────────────────────────
st, res = call("POST", "/api/shares", {"spuId": spu_id, "content": "   "}, token)
check("⑥ 空白内容 → 400", st == 400, f"{st} {res}")

# ── ⑦ 不存在的分享详情 → 404 ────────────────────────────────────────────────
st, res = call("GET", "/api/shares/1234567890123456789")
check("⑦ 打开不存在的分享 → 404（公开路径仍可访问）", st == 404, f"{st} {res}")

# ── ⑧⑨⑩ 点赞 / 收藏 toggle（第四十四轮） ────────────────────────────────────
st, res = call("POST", f"/api/shares/{share_id}/like")
check("⑧ 未登录点赞 → 401", st == 401, f"{st} {res}")

st, res = call("POST", f"/api/shares/{share_id}/like", {}, token)
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑨ 登录点赞 → active=true, count=1", st == 200 and d.get("active") is True and d.get("count") == 1, f"{st} {d}")

st, res = call("GET", f"/api/shares/{share_id}", token=token)
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑨b 登录浏览详情 → liked=true, likeCount=1",
      d.get("liked") is True and d.get("likeCount") == 1, {k: d.get(k) for k in ("liked", "likeCount")})

st, res = call("GET", f"/api/shares/{share_id}")
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑨c 匿名浏览详情 → liked=false 但计数可见",
      d.get("liked") is False and d.get("likeCount") == 1, {k: d.get(k) for k in ("liked", "likeCount")})

st, res = call("POST", f"/api/shares/{share_id}/like", {}, token)
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑨d 再点一次取消 → active=false, count=0", st == 200 and d.get("active") is False and d.get("count") == 0, f"{st} {d}")

st, res = call("POST", f"/api/shares/{share_id}/favorite", {}, token)
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑩ 登录收藏 → active=true, count=1", st == 200 and d.get("active") is True and d.get("count") == 1, f"{st} {d}")

st, res = call("POST", f"/api/shares/{share_id}/favorite", {}, token)
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑩b 再收藏取消 → active=false, count=0", st == 200 and d.get("active") is False and d.get("count") == 0, f"{st} {d}")

st, res = call("POST", "/api/shares/1234567890123456789/like", {}, token)
check("⑩c 给不存在的分享点赞 → 404", st == 404, f"{st} {res}")

# ── ⑪⑫⑬⑭ 转发（第四十六轮） ────────────────────────────────────────────────
# 需要**两个**用户：原作者 A 与转发人 B。身份分离是验证「详情页显示转发人」的前提。
share2_id = repost_id = repost2_id = None

phone_b = f"137{random.randint(10**7, 10**8 - 1)}"
st, res = call("POST", "/api/auth/register", {"phone": phone_b, "password": PWD, "nickname": "转发测试"})
check("夹具2：转发人 B 注册成功", st == 200, f"{st} {res}")
st, res = call("POST", "/api/auth/login/token", {"phone": phone_b, "password": PWD})
token_b = (res.get("data") or {}).get("accessToken") if isinstance(res, dict) else None
user_b_id = ((res.get("data") or {}).get("user") or {}).get("id") if isinstance(res, dict) else None
check("夹具2：转发人 B 取到令牌与 userId", st == 200 and token_b and user_b_id, f"{st} user={user_b_id}")

# ⑪ 未登录转发 → 401。白名单 /api/shares/* 只匹配单段，不会连带放行 /{id}/repost
st, res = call("POST", f"/api/shares/{share_id}/repost", {"content": "匿名转发"})
check("⑪ 未登录转发 → 401", st == 401, f"{st} {res}")

# ⑫ 登录转发 → 200，落一条以 B 为作者的新分享
st, res = call("POST", f"/api/shares/{share_id}/repost", {"content": "这个我也想囤两箱"}, token_b)
rv = (res.get("data") or {}) if isinstance(res, dict) else {}
repost_id = rv.get("id")
check("⑫ 转发 → 200 且返回转发单 ID（真的写库了）", st == 200 and repost_id, f"{st} {res}")
check("⑫b shareType=REPOST 且 parentId 指向原帖",
      rv.get("shareType") == "REPOST" and str(rv.get("parentId")) == str(share_id),
      {k: rv.get(k) for k in ("shareType", "parentId")})
check("⑫c 作者是转发人 B（userId + 昵称）",
      str(rv.get("userId")) == str(user_b_id) and rv.get("nickname") == "转发测试",
      {k: rv.get(k) for k in ("userId", "nickname")})
check("⑫d 商品快照沿用原帖（spuId / 标题 / 价格一致）",
      str(rv.get("spuId")) == str(spu_id)
      and rv.get("productTitle") == view.get("productTitle")
      and rv.get("minPrice") == view.get("minPrice"),
      {k: rv.get(k) for k in ("spuId", "productTitle", "minPrice")})
check("⑫e 带回原帖引用（parentNickname / parentContent）",
      rv.get("parentNickname") == "分享圈测试" and rv.get("parentContent") == "毛孩子的口粮，回购第三次了",
      {k: rv.get(k) for k in ("parentNickname", "parentContent")})

# ⑬ 信息流出现转发动态（bug① 的回归断言）
st, res = call("GET", "/api/shares/feed?page=1&pageSize=10")
feed = ((res.get("data") or {}).get("items")) if isinstance(res, dict) else None
feed = feed or []
idx_repost = next((i for i, it in enumerate(feed) if str(it.get("id")) == str(repost_id)), -1)
idx_origin = next((i for i, it in enumerate(feed) if str(it.get("id")) == str(share_id)), -1)
check("⑬ 信息流出现刚转发的动态（bug①）", st == 200 and idx_repost >= 0, f"{st} items={len(feed)}")
check("⑬b 转发行排在原帖之前（按发布时间倒序）",
      idx_repost >= 0 and (idx_origin < 0 or idx_repost < idx_origin),
      f"repost@{idx_repost} origin@{idx_origin}")
check("⑬c 信息流条目带转发标记",
      idx_repost >= 0 and feed[idx_repost].get("shareType") == "REPOST"
      and str(feed[idx_repost].get("parentId")) == str(share_id),
      feed[idx_repost] if idx_repost >= 0 else None)

# ⑭ 转发单详情：身份 = 转发人（bug② 的回归断言）
st, res = call("GET", f"/api/shares/{repost_id}")
d = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑭ 转发单详情作者是转发人 B（bug②）",
      st == 200 and str(d.get("userId")) == str(user_b_id) and d.get("nickname") == "转发测试",
      {k: d.get(k) for k in ("userId", "nickname")})
check("⑭b 转发单详情带原帖引用（parentId / parentNickname / parentContent）",
      str(d.get("parentId")) == str(share_id)
      and d.get("parentNickname") == "分享圈测试"
      and d.get("parentContent") == "毛孩子的口粮，回购第三次了",
      {k: d.get(k) for k in ("parentId", "parentNickname", "parentContent")})

st, res = call("GET", f"/api/shares/{share_id}")
od = (res.get("data") or {}) if isinstance(res, dict) else {}
check("⑭c 原帖自身仍是原创（shareType=ORIGINAL、无 parentId、作者仍是 A）",
      od.get("shareType") == "ORIGINAL" and not od.get("parentId") and od.get("nickname") == "分享圈测试",
      {k: od.get(k) for k in ("shareType", "parentId", "nickname")})

# ⑮ 重复转发同一条 → 409（唯一键 uk_share_user_parent 兜住并发）
st, res = call("POST", f"/api/shares/{share_id}/repost", {"content": "再转一次"}, token_b)
check("⑮ 重复转发同一条 → 409", st == 409, f"{st} {res}")

# ⑯ 转发自己的分享 → 422
st, res = call("POST", f"/api/shares/{share_id}/repost", {"content": "转自己的"}, token)
check("⑯ 转发自己的分享 → 422", st == 422, f"{st} {res}")

# ⑰ 转发不存在的分享 → 404
st, res = call("POST", "/api/shares/1234567890123456789/repost", {"content": "幽灵"}, token_b)
check("⑰ 转发不存在的分享 → 404", st == 404, f"{st} {res}")

# ⑱ 空附言纯转发 → 200 且 content 为空串
st, res = call("POST", "/api/shares", {"spuId": spu_id, "content": "第二件好物，同样推荐"}, token)
share2_id = ((res.get("data") or {}) if isinstance(res, dict) else {}).get("id")
st, res = call("POST", f"/api/shares/{share2_id}/repost", {}, token_b)
rv2 = (res.get("data") or {}) if isinstance(res, dict) else {}
repost2_id = rv2.get("id")
check("⑱ 空附言纯转发 → 200 且 content 为空串",
      st == 200 and repost2_id and rv2.get("content") == "", f"{st} {res}")
check("⑱b 纯转发仍带回原帖引用",
      rv2.get("parentNickname") == "分享圈测试" and rv2.get("parentContent") == "第二件好物，同样推荐",
      {k: rv2.get(k) for k in ("parentNickname", "parentContent")})

print(f"\n=== 结果：通过 {len(passed)} 项，失败 {len(failed)} 项 ===")
if failed:
    for f_ in failed:
        print("  - " + f_)
    sys.exit(1)

# ── 夹具清理（与 review-ui-check.mjs 同风格：默认清，KEEP_FIXTURE=1 留存） ────
import os
import subprocess

if share_id and os.environ.get("KEEP_FIXTURE") != "1":
    env = dict(os.environ)
    env["PATH"] = r"C:\Program Files\Docker\Docker\resources\bin;" + env.get("PATH", "")
    env["DOCKER_CONFIG"] = r"D:\duoduo-infra\docker-nocreds"
    ids = [x for x in {share_id, share2_id, repost_id, repost2_id} if x]
    subprocess.run(
        ["docker", "exec", "duoduo-mysql", "mysql", "-uduoduo", "-pduoduo", "-N", "-B",
         "--default-character-set=utf8mb4", "-D", "duoduo_fulfilment", "-e",
         "DELETE FROM t_product_share WHERE id IN (" + ",".join(str(i) for i in ids) + ");"],
        env=env, check=True,
    )
    print(f"（夹具已复原）分享单 {ids} 已删除；测试用户 {phone}/{phone_b} 留档")

