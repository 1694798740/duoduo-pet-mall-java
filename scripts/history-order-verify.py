'''足迹排序与时间下发专项验证（2026-10-22）。

覆盖：
  1. 依次浏览 3 个商品（间隔 2 秒，保证 viewedAt 可区分）；
  2. /api/history/products 返回倒序（最后浏览的排最前）；
  3. 每条足迹带 viewedAt（UTC ISO，非空）且时间递减。

用法：python scripts/history-order-verify.py
'''
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PWD = "Passw0rd!"
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


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def main():
    ts = str(int(time.time()))
    phone = "197" + ts[1:9]  # 11 位手机号（197 开头 + 8 位）
    st, r = call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "足迹排序验证"})
    if st != 200:
        # 已存在则直接登录
        print("  (注册返回 %s，尝试直接登录)" % st)
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    check("临时账号登录", st == 200 and (r.get("data") or {}).get("accessToken"))
    token = (r.get("data") or {}).get("accessToken")

    # 依次浏览 3 个在架商品：1002 → 1101 → 1102
    order = ["1002", "1101", "1102"]
    for pid in order:
        st, r = call("GET", "/api/products/" + pid, token=token)
        check("浏览商品 %s" % pid, st == 200)
        time.sleep(2)

    st, r = call("GET", "/api/history/products?page=1&pageSize=10", token=token)
    items = (r.get("data") or {}).get("items", [])
    check("足迹接口 200", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    ids = [str(i.get("id")) for i in items]
    check("倒序：最后浏览的排最前", ids[:3] == ["1102", "1101", "1002"], ids[:5])

    stamps = [i.get("viewedAt") for i in items[:3]]
    check("每条足迹都带 viewedAt", all(stamps), stamps)
    check("viewedAt 是 UTC ISO（带 Z）", all(s and s.endswith("Z") for s in stamps), stamps)
    check("viewedAt 时间递减（倒序）", all(stamps[i] >= stamps[i + 1] for i in range(len(stamps) - 1)), stamps)

    print("结果：", passed, "通过 /", failed, "失败")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
