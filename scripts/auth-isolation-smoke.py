#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
三端登录态隔离 · 端到端冒烟（2026-10-21）

守四件事：
  1. 商城登录只写 `dd_at`，商家端登录只写 `dd_at_m`（平台后台是 `dd_at_a`）；
  2. 同一个 Cookie 罐里两枚令牌可**并存**，各自 `/api/users/me` 返回不同身份；
  3. 商家端**不回退**到商城令牌：只有 `dd_at` 时访问 /api/seller/** 必须 401；
  4. 商城入口仍可回退基础名（兼容改造前的会话）。

背景：Cookie 按主机名隔离但**不区分端口**，`localhost:8088` 与 `localhost:8091`
共用同一个 Cookie 罐。三端共用 `dd_at` 时，登录一端会把另一端顶掉 ——
表现为「商家工作台登录了 A，点个人中心却看到买家 B」。

用法：python scripts/auth-isolation-smoke.py
前置：容器已起（网关 8080）；需要一个可登录的商家/运营账号与一个商城账号。
"""
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
MERCHANT_PHONE = "13684295254"   # OPERATOR（有商家工作台准入）
MERCHANT_PWD = "duoduo12345"
MALL_PWD = "duoduo12345"

passed = 0
failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  [通过] " + name)
    else:
        failed += 1
        print("  [失败] " + name + ("  -> " + str(detail) if detail else ""))


class Jar:
    """独立 Cookie 罐：可以看到每枚 Cookie 的名字，用于断言写入的是哪个名字。"""

    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, body=None, entry=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if entry:
            req.add_header("X-Duoduo-Entry", entry)
        try:
            with self.opener.open(req, timeout=15) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8")
            try:
                return e.code, json.loads(raw)
            except Exception:
                return e.code, {"raw": raw}

    def names(self):
        return sorted(c.name for c in self.jar)

    def drop(self, name):
        for c in list(self.jar):
            if c.name == name:
                self.jar.clear(c.domain, c.path, c.name)


def main():
    jar = Jar()

    print("== 1. 商城注册（entry=mall）只写 dd_at ==")
    phone = "197" + str(int(time.time()))[-8:]
    st, r = jar.call("POST", "/api/auth/register",
                     {"phone": phone, "password": MALL_PWD, "nickname": "隔离冒烟" + phone[-4:]},
                     entry="mall")
    check("商城注册成功", st in (200, 201) and r.get("code") == "SUCCESS", r)
    names = jar.names()
    check("写入 dd_at / dd_rt", "dd_at" in names and "dd_rt" in names, names)
    check("未写入商家令牌 dd_at_m", "dd_at_m" not in names, names)

    st, r = jar.call("GET", "/api/users/me", entry="mall")
    mall_id = (r.get("data") or {}).get("id")
    check("商城身份可读", st == 200 and bool(mall_id), r)

    print("== 2. 商家登录（entry=merchant）写 dd_at_m，且不覆盖商城令牌 ==")
    st, r = jar.call("POST", "/api/auth/login",
                     {"phone": MERCHANT_PHONE, "password": MERCHANT_PWD}, entry="merchant")
    check("商家登录成功", st == 200 and r.get("code") == "SUCCESS", r)
    names = jar.names()
    check("写入 dd_at_m / dd_rt_m", "dd_at_m" in names and "dd_rt_m" in names, names)
    check("商城令牌 dd_at 仍在（未被覆盖）", "dd_at" in names, names)

    st, r = jar.call("GET", "/api/users/me", entry="merchant")
    merchant_id = (r.get("data") or {}).get("id")
    check("商家身份可读", st == 200 and bool(merchant_id), r)
    check("两端身份不同（同一浏览器、两张脸）", bool(mall_id) and mall_id != merchant_id,
          (mall_id, merchant_id))

    print("== 3. 商家入口不回退商城令牌 ==")
    jar.drop("dd_at_m")
    jar.drop("dd_rt_m")
    st, r = Jar().call("GET", "/api/users/me")  # 空罐，仅确认基线
    st, r = jar.call("GET", "/api/seller/support/sessions", entry="merchant")
    check("只有 dd_at 时访问商家接口 → 401", st == 401, (st, r.get("code")))
    st, r = jar.call("GET", "/api/users/me", entry="mall")
    check("商城入口仍正常（回退基础名）", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    print("\n结果：%d 通过 / %d 失败" % (passed, failed))
    return failed


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
