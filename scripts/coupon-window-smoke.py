#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
券领取窗口不变式探针（R62 新增）

钉住一条**不变式**而不是某个具体数字：

    领券中心列表里出现的**每一条**券，都必须是「现在就能领成功」的。

这条不变式比「列表里有几张券」值钱得多。列表与领取是两段独立代码
（`selectClaimable` 与 `claimOne` 各自的 WHERE），只要两者的时间条件有一点点
不一致，就会出现「看得见、点下去领不到」—— 用户视角是「这 App 坏了」，
而接口日志里每一条都是合法的 4xx，最难归因。

探针做两层判定：
  1. 弱判据（看字段）：列表里每条的 startAt <= 现在 <= endAt；
  2. 强判据（真去领）：换一个全新买家，把列表里每一条都领一遍，必须全部成功。
     强判据不依赖任何字段命名或时间格式，是这条不变式的最终依据。

自还原：只新建一个买家账号，不删任何既有券模板/券实例，不改库。
用法：python scripts/coupon-window-smoke.py [gateway]
"""
import json
import random
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

GW = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
PWD = "duoduo12345"

passed = 0
failed = []


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    global passed
    if cond:
        passed += 1
        print("  [PASS] %s" % name)
    else:
        failed.append(name)
        print("  [FAIL] %s\n        %s" % (name, detail))


def parse_utc(s):
    """'2026-10-15T04:53:52Z' → 带时区的 datetime；解析不了返回 None。"""
    if not s:
        return None
    try:
        return datetime.fromisoformat(str(s).replace("Z", "+00:00"))
    except ValueError:
        return None


def main():
    print("\n> 券领取窗口不变式：列表可见 ⇒ 当下必可领")

    st, r = call("GET", "/api/coupons/templates")
    check("券模板列表接口可用（200）", st == 200, "status=%s body=%s" % (st, r))
    if st != 200:
        print("\n=== 券窗口探针：通过 %d 项，失败 %d 项 ===" % (passed, len(failed)))
        sys.exit(1)
    listed = r.get("data") or []
    print("        列表当前 %d 条：%s" % (len(listed), [t.get("id") for t in listed]))

    # —— 弱判据：时间字段本身必须落在窗口内 ——
    now = datetime.now(timezone.utc)
    # 给 1 分钟容差：容器与宿主机之间、以及请求往返都可能有秒级偏移
    skew = timedelta(minutes=1)
    outside = []
    for t in listed:
        s, e = parse_utc(t.get("startAt")), parse_utc(t.get("endAt"))
        if s is None or e is None:
            outside.append((t.get("id"), "时间字段解析失败", t.get("startAt"), t.get("endAt")))
        elif not (s - skew <= now <= e + skew):
            outside.append((t.get("id"), "不在领取窗口内", t.get("startAt"), t.get("endAt")))
    check(
        "★ 列表里每条的 startAt <= 现在 <= endAt（未开抢的券不得提前出现）",
        not outside,
        "越界条目：%s" % outside,
    )

    # —— 强判据：真用全新买家逐条领 ——
    phone = "138" + "".join(random.choice("0123456789") for _ in range(8))
    st, r = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "券窗口探针"})
    check("探针买家注册成功", st in (200, 201), "status=%s body=%s" % (st, r))
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    token = (r.get("data") or {}).get("accessToken")
    check("探针买家拿到访问令牌", bool(token), "status=%s body=%s" % (st, r))
    if not token:
        print("\n=== 券窗口探针：通过 %d 项，失败 %d 项 ===" % (passed, len(failed)))
        sys.exit(1)

    unusable = []
    for t in listed:
        tid = t.get("id")
        st, r = call("POST", "/api/coupons/templates/%s/claim" % tid, {}, token=token)
        if st not in (200, 201):
            unusable.append((tid, t.get("startAt"), t.get("endAt"), st, r.get("message")))
    check(
        "★ 列表里每一条券当下都能领成功（可见 ⇒ 可领）",
        not unusable,
        "以下券在列表里可见却领不到：%s" % unusable,
    )
    check("列表非空（否则上面两条是空断言，等于没测）", len(listed) > 0, "list=%s" % listed)

    print("\n=== 券窗口探针：通过 %d 项，失败 %d 项 ===" % (passed, len(failed)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
