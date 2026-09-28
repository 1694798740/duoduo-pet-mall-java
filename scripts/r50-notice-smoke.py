#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第五十轮 · 通知中心接口探针
背景：tabBar「分类」换「通知」（居中），全页面右上角通知图标进通知页；
      通知数据 = 定向通知（user_id=我）+ 全站公告（user_id=0），
      已读统一记在集合表 t_social_notice_read（表上行没有已读列）。
覆盖：
  夹具：测试买家注册登录
  ① 通知列表 200 且总量=定向+公告 /
  ② 未登录 401 /
  ③ 公告行的 read 初值为 false /
  ④ 未读数 > 0 且等于总量（新用户全未读） /
  ⑤ 标记单条已读 200 / ⑥ 重复标记幂等 200 / ⑦ 未读数减一 /
  ⑧ 全部已读 200 / ⑨ 未读数归零 / ⑩ 列表全部 read=true /
  ⑪ 他人通知（不存在的 id）标已读 → 404（归属校验）
前置：social 容器为第五十轮构建；incremental/17 已执行（3 条种子公告）。
用法：python scripts/r50-notice-smoke.py
"""
import json
import random
import urllib.request
import urllib.error

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"

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
    print("=== 第五十轮 · 通知中心接口探针 ===")

    # ── 夹具：新注册用户（全未读，未读数可直接对账） ──
    phone = f"137{random.randint(10**7, 10**8 - 1)}"
    st, r = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "通知验证50"})
    st2, r2 = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    tb = (data_of(r2) or {}).get("accessToken") or ""
    check("夹具：测试买家注册并登录", st == 200 and st2 == 200 and bool(tb), f"{st}/{st2}")

    # ② 未登录 401（通知是收件人数据）
    st, r = call("GET", "/api/socials/notices?page=1&pageSize=20", None, None)
    check("② 未登录拉通知 → 401", st == 401, f"{st}")

    # ① 列表 200 且含公告
    st, r = call("GET", "/api/socials/notices?page=1&pageSize=50", None, tb)
    d = data_of(r) or {}
    items = d.get("items") or []
    total = d.get("total") or 0
    announcements = [it for it in items if it.get("category") == "ANNOUNCEMENT"]
    check("① 通知列表 200 且 total>=3（种子公告 3 条）", st == 200 and total >= 3, f"{st} total={total}")
    check("①-1 列表含全站公告（category=ANNOUNCEMENT）", len(announcements) >= 3, f"{len(announcements)}")

    # ③ 公告行 read 初值 false（新用户没读过）
    check("③ 新用户的公告 read 初值 = false", all(it.get("read") is False for it in announcements),
          f"{[it.get('read') for it in announcements]}")

    # ④ 未读数 = 总量（新用户全未读）；④-0 断言 count 是**数字**不是字符串
    st, r = call("GET", "/api/socials/notices/unread-count", None, tb)
    uc = (data_of(r) or {}).get("count")
    check("④-0 count 按 JSON 数字下发（非字符串；record 组件已标注数字序列化器）",
          isinstance(uc, int) and not isinstance(uc, bool), f"type={type(uc).__name__} value={uc!r}")
    check("④ 新用户未读数 = total（全未读）", st == 200 and uc == total, f"{st} unread={uc} total={total}")

    # ⑤ 标记单条已读
    target = announcements[0]
    st, r = call("POST", f"/api/socials/notices/{target['id']}/read", None, tb)
    check("⑤ 标记单条已读 → 200", st == 200, f"{st} {r.get('message')}")

    # ⑥ 重复标记幂等
    st, r = call("POST", f"/api/socials/notices/{target['id']}/read", None, tb)
    check("⑥ 重复标记同一条 → 200（幂等）", st == 200, f"{st} {r.get('message')}")

    # ⑦ 未读数减一
    st, r = call("GET", "/api/socials/notices/unread-count", None, tb)
    uc2 = (data_of(r) or {}).get("count")
    check("⑦ 未读数减一", st == 200 and uc2 == uc - 1, f"{st} unread={uc2} expect={uc-1}")

    # ⑦-1 单条已读在列表里生效（read=true）
    st, r = call("GET", "/api/socials/notices?page=1&pageSize=50", None, tb)
    rows = ((data_of(r) or {}).get("items") or [])
    row = next((it for it in rows if str(it.get("id")) == str(target["id"])), None)
    check("⑦-1 已读的那条在列表中 read=true", row is not None and row.get("read") is True,
          f"{row.get('read') if row else 'row not found'}")

    # ⑪ 他人/不存在的通知 → 404（归属校验，不区分不存在与无权限）
    st, r = call("POST", "/api/socials/notices/999999999999999999/read", None, tb)
    check("⑪ 不存在的通知标已读 → 404（归属校验）", st == 404, f"{st}")

    # ⑧ 全部已读
    st, r = call("POST", "/api/socials/notices/read-all", None, tb)
    check("⑧ 全部已读 → 200", st == 200, f"{st} {r.get('message')}")

    # ⑨ 未读数归零
    st, r = call("GET", "/api/socials/notices/unread-count", None, tb)
    uc3 = (data_of(r) or {}).get("count")
    check("⑨ 全部已读后未读数 = 0", st == 200 and uc3 == 0, f"{st} unread={uc3}")

    # ⑩ 列表全部 read=true
    st, r = call("GET", "/api/socials/notices?page=1&pageSize=50", None, tb)
    rows2 = ((data_of(r) or {}).get("items") or [])
    check("⑩ 全部已读后列表所有 read=true", rows2 and all(it.get("read") is True for it in rows2),
          f"{[it.get('read') for it in rows2]}")

    # ⑬ record 组件的 total/pageSize 也是数字（同一接口族类型一致）
    check("⑬ 列表 total / pageSize 为 JSON 数字",
          isinstance(total, int) and isinstance(d.get("pageSize"), int),
          f"total={type(total).__name__} pageSize={type(d.get('pageSize')).__name__}")

    # ⑭ 关注计数（R47 遗留 record）也已按数字下发
    st, r = call("GET", "/api/socials/follows/1/counts", None, tb)
    fc = data_of(r) or {}
    check("⑭ FollowCounts.followers/following 为 JSON 数字（R47 遗留缺陷已修）",
          st == 200 and isinstance(fc.get("followers"), int) and isinstance(fc.get("following"), int),
          f"{st} {fc}")

    # ⑫ 分页参数边界（pageSize 超上限被夹到 50）
    st, r = call("GET", "/api/socials/notices?page=1&pageSize=999", None, tb)
    ps = (data_of(r) or {}).get("pageSize")
    check("⑫ pageSize 超上限被夹到 50", st == 200 and ps == 50, f"{st} pageSize={ps}")

    print(f"\n结果：{len(passed)} 通过 / {len(failed)} 失败")
    if failed:
        print("失败项：")
        for f in failed:
            print("  - " + f)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
