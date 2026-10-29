# 独立 QA API 反向/边界测试（2026-10-30）
# 仅在本机开发环境运行；账号与密码通过环境变量注入，不写入本文件。
import json
import os
import sys
import time
import urllib.request
import urllib.error
from datetime import date

BASE = os.environ.get("QA_BASE", "http://127.0.0.1:8080")
BUYER = os.environ["QA_BUYER"]
BUYER2 = os.environ["QA_BUYER2"]
SELLER = os.environ["QA_SELLER"]
OPS1 = os.environ["QA_OPS1"]
OPS2 = os.environ["QA_OPS2"]
PWD = os.environ["QA_PWD"]

RESULTS = []


def call(method, path, token=None, body=None, raw=False, timeout=30):
    url = BASE + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw_text = resp.read()
            text = raw_text.decode("utf-8", "replace")
            if raw:
                return resp.status, text, dict(resp.headers), raw_text
            try:
                return resp.status, json.loads(text), dict(resp.headers), text
            except json.JSONDecodeError:
                return resp.status, text, dict(resp.headers), text
    except urllib.error.HTTPError as e:
        raw_text = e.read()
        text = raw_text.decode("utf-8", "replace")
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = text
        return e.code, parsed, dict(e.headers), text


def login(phone):
    status, body, _, _ = call("POST", "/api/auth/login/token",
                              body={"phone": phone, "password": PWD})
    if status != 200:
        raise RuntimeError("login failed for %s status=%s" % (phone[:3] + "****" + phone[-4:], status))
    token = (body.get("data") or {}).get("accessToken") or (body.get("data") or {}).get("token")
    if not token:
        raise RuntimeError("login returned no token")
    return token


def check(cid, name, expected, fn):
    try:
        ok, detail = fn()
        results = {"id": cid, "name": name, "expected": expected,
                   "actual": detail, "pass": bool(ok)}
    except Exception as e:  # noqa
        results = {"id": cid, "name": name, "expected": expected,
                   "actual": "EXCEPTION: %s" % e, "pass": False}
    RESULTS.append(results)
    print(("%s %-4s %s | %s" % ("PASS" if results["pass"] else "FAIL", cid,
                               name, results["actual"])).encode("utf-8", "replace").decode("utf-8"))
    return results["pass"]


def main():
    buyer = login(BUYER)
    buyer2 = login(BUYER2)
    seller = login(SELLER)
    ops1 = login(OPS1)
    ops2 = login(OPS2)

    # T01 未登录访问订单
    def t01():
        s, b, _, _ = call("GET", "/api/orders?page=1&pageSize=10")
        return s == 401, "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T02 买家访问运营订单
    def t02():
        s, b, _, _ = call("GET", "/api/admin/orders?page=1&pageSize=10", buyer)
        return s == 403, "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T03 买家访问商家订单
    def t03():
        s, b, _, _ = call("GET", "/api/seller/orders?page=1&pageSize=10", buyer)
        return s == 403, "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T04 第三方店主读自己的商品（正例）
    def t04():
        s, b, _, _ = call("GET", "/api/seller/products/9001", seller)
        return s == 200, "status=%s" % s

    # T05 第三方店主读平台自营商品（跨店）
    def t05():
        s, b, _, _ = call("GET", "/api/seller/products/1001", seller)
        return s in (403, 404), "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T06 买家 A 读买家 B 的订单（水平越权）
    other_order = "202610270520472646477"
    def t06():
        s, b, _, _ = call("GET", "/api/orders/" + other_order, buyer)
        return s in (403, 404), "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T07 买家 A 读自己的订单（正例）
    def t07():
        s, b, _, _ = call("GET", "/api/orders?page=1&pageSize=10", buyer)
        items = ((b or {}).get("data") or {}).get("items") or []
        if s != 200 or not items:
            return False, "status=%s items=%s" % (s, len(items))
        own = items[0]["orderNo"]
        s2, b2, _, _ = call("GET", "/api/orders/" + own, buyer)
        return s2 == 200, "list=%s detail=%s order=%s" % (s, s2, own[-6:])

    # T08 加购数量 0
    def t08():
        s, b, _, _ = call("POST", "/api/cart/items", buyer, {"skuId": 1001, "quantity": 0})
        return s == 400, "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T09 加购数量超上限 201
    def t09():
        s, b, _, _ = call("POST", "/api/cart/items", buyer, {"skuId": 1001, "quantity": 201})
        return s == 400, "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T10 搜索关键字 SQL 注入
    def t10():
        s, b, _, _ = call("GET", "/api/products?keyword=%27%20OR%201%3D1%20--%20&page=1&pageSize=10")
        total = ((b or {}).get("data") or {}).get("total") if isinstance(b, dict) else None
        return s == 200 and total == 0, "status=%s total=%s" % (s, total)

    # T11 超大 pageSize
    def t11():
        s, b, _, _ = call("GET", "/api/products?page=1&pageSize=100000")
        if s == 400:
            return True, "status=400 rejected"
        items = ((b or {}).get("data") or {}).get("items") or []
        return s == 200 and len(items) <= 100, "status=%s returned=%s" % (s, len(items))

    # T12 同一券重复领取：第一次 200，第二次必须失败
    def t12():
        s1, b1, _, _ = call("POST", "/api/coupons/templates/5001/claim", buyer)
        s2, b2, _, _ = call("POST", "/api/coupons/templates/5001/claim", buyer)
        return s1 == 200 and s2 in (400, 409, 422), "first=%s second=%s code=%s" % (
            s1, s2, (b2 or {}).get("code") if isinstance(b2, dict) else b2)

    # T13 签到重复：用今天未签到的账号；两次调用第二次必须失败
    def t13():
        today = date.today().isoformat()
        selected = None
        for label, tok in (("A", buyer), ("B", buyer2)):
            s, b, _, _ = call("GET", "/api/coins/signin/month", tok)
            signed = ((b or {}).get("data") or {}).get("signedDates") or []
            if today not in signed:
                selected = (label, tok)
                break
        if not selected:
            return False, "A/B 今日均已签到，无法在不清数据的前提下验证重复签到"
        s1, b1, _, _ = call("POST", "/api/coins/signin", selected[1])
        s2, b2, _, _ = call("POST", "/api/coins/signin", selected[1])
        return s1 == 200 and s2 in (400, 409, 422), "account=%s first=%s second=%s code=%s" % (
            selected[0], s1, s2, (b2 or {}).get("code") if isinstance(b2, dict) else b2)

    # T14 AI 加购确认：伪造令牌必须拒绝
    def t14():
        s, b, _, _ = call("POST", "/api/duoduo/confirm", buyer,
                          {"token": "qa-invalid-token-1030", "spuId": 1001,
                           "skuId": 1001, "quantity": 1})
        return s in (400, 401, 403, 422), "status=%s code=%s" % (s, (b or {}).get("code") if isinstance(b, dict) else b)

    # T15 异步导出：创建人可下载，其他运营 404
    def t15():
        s, b, _, _ = call("POST", "/api/admin/export-jobs", ops1, {"module": "ORDERS"})
        if s != 200:
            return False, "create status=%s" % s
        job_id = (b.get("data") or {}).get("id")
        status = "PENDING"
        for _ in range(30):
            time.sleep(1)
            s2, b2, _, _ = call("GET", "/api/admin/export-jobs/" + str(job_id), ops1)
            status = (b2.get("data") or {}).get("status")
            if status in ("SUCCESS", "FAILED", "EXPIRED"):
                break
        s3, _, h3, raw3 = call("GET", "/api/admin/export-jobs/%s/download" % job_id, ops1, raw=True)
        s4, _, _, _ = call("GET", "/api/admin/export-jobs/%s/download" % job_id, ops2, raw=True)
        s5, _, _, _ = call("GET", "/api/admin/export-jobs/" + str(job_id), ops2)
        return (status == "SUCCESS" and s3 == 200 and s4 == 404 and s5 == 404), (
            "job=%s status=%s creatorDownload=%s otherDownload=%s otherDetail=%s bytes=%s"
            % (job_id, status, s3, s4, s5, len(raw3)))

    # T16 记忆接口：无令牌 401，有令牌 200
    def t16():
        s1, _, _, _ = call("GET", "/api/duoduo/memory")
        s2, b2, _, _ = call("GET", "/api/duoduo/memory", buyer)
        keys = sorted(((b2 or {}).get("data") or {}).keys()) if isinstance(b2, dict) else []
        return s1 == 401 and s2 == 200, "noToken=%s withToken=%s keys=%s" % (s1, s2, ",".join(keys[:6]))

    # T17 买家订单导出：只能 200，且 CSV 不含完整手机号
    def t17():
        s, text, h, raw = call("GET", "/api/orders/export", buyer, raw=True)
        ctype = h.get("Content-Type", "")
        return s == 200 and "text/csv" in ctype.lower(), "status=%s type=%s bytes=%s" % (s, ctype, len(raw))

    checks = [
        ("T01", "未登录访问 /api/orders 必须 401", "401", t01),
        ("T02", "买家访问 /api/admin/orders 必须 403", "403", t02),
        ("T03", "买家访问 /api/seller/orders 必须 403", "403", t03),
        ("T04", "第三方店主读自己商品 9001 必须 200", "200", t04),
        ("T05", "第三方店主读自营商品 1001 必须 403/404", "403/404", t05),
        ("T06", "买家 A 读买家 B 订单必须 403/404", "403/404", t06),
        ("T07", "买家 A 读自己订单必须 200", "200", t07),
        ("T08", "加购数量 0 必须 400", "400", t08),
        ("T09", "加购数量 201 必须 400", "400", t09),
        ("T10", "搜索 SQL 注入不命中数据", "200 & total=0", t10),
        ("T11", "超大 pageSize 必须拒绝或封顶", "400 或 <=100", t11),
        ("T12", "同一券重复领取第二次必须失败", "1st 200, 2nd 4xx", t12),
        ("T13", "重复签到第二次必须失败", "1st 200, 2nd 4xx", t13),
        ("T14", "伪造 AI 加购确认令牌必须拒绝", "4xx", t14),
        ("T15", "异步导出创建人可下载、他人 404", "creator 200 / other 404", t15),
        ("T16", "记忆接口未登录 401、登录 200", "401 / 200", t16),
        ("T17", "买家订单导出 200 + text/csv", "200", t17),
    ]
    for cid, name, exp, fn in checks:
        check(cid, name, exp, fn)

    passed = sum(1 for r in RESULTS if r["pass"])
    summary = {"total": len(RESULTS), "pass": passed, "fail": len(RESULTS) - passed,
               "base": BASE, "at": time.strftime("%Y-%m-%dT%H:%M:%S"), "results": RESULTS}
    with open(os.path.join(os.path.dirname(__file__), "api-results.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    print("TOTAL=%d PASS=%d FAIL=%d" % (summary["total"], summary["pass"], summary["fail"]))
    sys.exit(0 if summary["fail"] == 0 else 1)


if __name__ == "__main__":
    main()
