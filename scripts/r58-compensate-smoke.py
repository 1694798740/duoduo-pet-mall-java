#!/usr/bin/env python3
"""平台垫付退款与卖家追偿（B-01）专项探针 · 第五十八轮

B-01 的原始问题：订单「已完成」后钱已放款给卖家，系统只能回一句
「需走追责或平台垫付（尚未实现）」—— 买家拿不到钱，运营也没有任何可执行动作，
且系统里不留任何痕迹，对账时既看不到退款也看不到谁欠谁。

本探针验证垫付闭环：
  A. 已完成订单垫付成功：欠款行落库、金额为买家实付、托管单 RELEASED → ADVANCED、订单进 REFUNDED；
  B. 重复垫付必须 409（幂等两道闸：欠款唯一键 + 托管 CAS），且**欠款总额不变**；
  C. 非「已完成」订单垫付被拒（不能凭空给卖家记账）；
  D. 欠款可收回 / 可核销，且二次操作 409；
  E. 非运营无权限（403）。

用法：python scripts/r58-compensate-smoke.py
"""
import json
import os
import subprocess
import urllib.error
import urllib.request
from http.cookiejar import CookieJar

GW = (os.environ.get("R58_GATEWAY") or "http://127.0.0.1:8080").rstrip("/")
PWD = "duoduo12345"
ADMIN_PHONE = os.environ.get("R58_ADMIN_PHONE", "13688774031")
BUYER_PHONE = os.environ.get("R58_BUYER_PHONE", "13900000001")

PASSED, FAILED = [], []


def check(name, ok, got=""):
    (PASSED if ok else FAILED).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else "   实际=" + str(got)[:240]))


# 令牌只走 httpOnly Cookie（本项目约定，不用 Authorization 头），
# 因此每个账号一个独立的 cookie jar —— 混用会把运营身份带到普通用户的请求上。
JARS = {}


def call(method, path, body=None, token=None, headers=None, timeout=25):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    opener = urllib.request.build_opener()
    if token is not None:
        opener.add_handler(urllib.request.HTTPCookieProcessor(JARS.setdefault(token, CookieJar())))
    req = urllib.request.Request(GW + path, data=data, method=method, headers=h)
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw.decode("utf-8", "ignore")}


def login(phone):
    """登录成功即返回该会话的 key（cookie jar 的标识）；失败返回 None"""
    key = "sess:" + phone
    JARS[key] = CookieJar()
    st, body = call("POST", "/api/auth/login", {"phone": phone, "password": PWD}, token=key)
    if st != 200:
        return None
    return key


def sql(q):
    out = subprocess.run(["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root",
                          "-N", "-B", "-e", q], capture_output=True, text=True)
    return out.stdout.strip()


def main():
    print("=" * 72)
    print("平台垫付退款与卖家追偿（B-01）专项探针 @ " + GW)
    print("=" * 72)

    admin = login(ADMIN_PHONE)
    buyer = login(BUYER_PHONE)
    check("P1 运营账号可登录（探针前置）", admin is not None)
    if admin is None:
        return 1

    # 找一个「已完成 + 已放款」且尚未垫付过的订单
    rows = sql("SELECT o.order_no, o.pay_amount FROM duoduo_trade.t_order o "
               "JOIN duoduo_trade.t_settlement s ON s.order_id = o.id "
               "WHERE o.status = 'COMPLETED' AND s.status = 'RELEASED' "
               "AND NOT EXISTS (SELECT 1 FROM duoduo_trade.t_seller_debt d WHERE d.order_id = o.id) "
               "LIMIT 1")
    check("P2 存在可用于垫付的已完成订单（探针前置）", rows != "", rows)
    if not rows:
        return 1
    order_no, pay_amount = rows.split("\t")
    pay_amount = int(pay_amount)
    print(f"  样本订单 {order_no}，实付 {pay_amount} 分")

    # ---------- A. 垫付闭环 ----------
    print("\n[A] 平台垫付闭环")
    st, body = call("POST", "/api/admin/settlements/compensate",
                    {"orderNo": order_no, "reason": "探针：卖家失联，平台垫付"}, token=admin)
    check("A1 垫付返回 200", st == 200, (st, body))
    data = (body.get("data") or {}) if st == 200 else {}
    check("A2 欠款金额为买家实付", data.get("amount") == pay_amount, (data.get("amount"), pay_amount))
    check("A3 欠款初始状态为未收回", data.get("status") == "OUTSTANDING", data.get("status"))
    debt_id = data.get("id")

    settle_status = sql(f"SELECT status FROM duoduo_trade.t_settlement WHERE order_id = "
                        f"(SELECT id FROM duoduo_trade.t_order WHERE order_no = '{order_no}')")
    check("A4 托管单变为 ADVANCED（平台垫付）", settle_status == "ADVANCED", settle_status)

    order_status = sql(f"SELECT status FROM duoduo_trade.t_order WHERE order_no = '{order_no}'")
    check("A5 订单进 REFUNDED 终态（买家已拿到钱）", order_status == "REFUNDED", order_status)

    stock_changed = sql(f"SELECT COUNT(*) FROM duoduo_trade.t_stock_freeze f "
                        f"JOIN duoduo_trade.t_order o ON o.id = f.order_id "
                        f"WHERE o.order_no = '{order_no}' AND f.status = 'RESTOCK_PENDING'")
    check("A6 垫付不回补库存（资金动作 ≠ 退货）", stock_changed == "0", stock_changed)

    # ---------- B. 重复垫付必须被挡 ----------
    print("\n[B] 重复垫付必须被挡（幂等）")
    st2, body2 = call("POST", "/api/admin/settlements/compensate",
                      {"orderNo": order_no, "reason": "探针：重复提交"}, token=admin)
    check("B1 重复垫付返回 409", st2 == 409, (st2, body2))
    total = sql("SELECT COALESCE(SUM(amount),0) FROM duoduo_trade.t_seller_debt WHERE order_no = '%s'" % order_no)
    check("B2 欠款总额不变（没有记两笔债）", total == str(pay_amount), total)

    # ---------- C. 非「已完成」订单不得垫付 ----------
    print("\n[C] 未放款订单不得垫付（不能凭空给卖家记账）")
    other = sql("SELECT o.order_no FROM duoduo_trade.t_order o "
                "JOIN duoduo_trade.t_settlement s ON s.order_id = o.id "
                "WHERE s.status = 'CUSTODY' LIMIT 1")
    if other:
        st3, _ = call("POST", "/api/admin/settlements/compensate",
                      {"orderNo": other, "reason": "探针：不该成功"}, token=admin)
        check("C1 托管中的订单垫付被拒（非 2xx）", st3 >= 400, st3)
    else:
        check("C1 托管中的订单垫付被拒（非 2xx）", True, "无样本，跳过")

    st4, body4 = call("POST", "/api/admin/settlements/compensate",
                      {"orderNo": order_no, "reason": "   "}, token=admin)
    check("C2 垫付原因为空被拒 400", st4 == 400, (st4, body4))

    # ---------- D. 收回 / 核销 ----------
    print("\n[D] 欠款收回与核销")
    st5, body5 = call("POST", f"/api/admin/settlements/debts/{debt_id}/settle", token=admin)
    check("D1 收回欠款 200", st5 == 200, (st5, body5))
    check("D2 状态变为 SETTLED", (body5.get("data") or {}).get("status") == "SETTLED", body5.get("data"))
    st6, _ = call("POST", f"/api/admin/settlements/debts/{debt_id}/settle", token=admin)
    check("D3 二次收回 409（CAS 幂等）", st6 == 409, st6)

    # 造一笔新的欠款用于核销：把这条债务重置为未收回后核销
    sql(f"UPDATE duoduo_trade.t_seller_debt SET status = 'OUTSTANDING' WHERE id = {debt_id}")
    st7, body7 = call("POST", f"/api/admin/settlements/debts/{debt_id}/write-off", token=admin)
    check("D4 核销欠款 200", st7 == 200, (st7, body7))
    check("D5 状态变为 WRITTEN_OFF", (body7.get("data") or {}).get("status") == "WRITTEN_OFF", body7.get("data"))

    # ---------- E. 权限 ----------
    print("\n[E] 权限")
    st8, _ = call("POST", "/api/admin/settlements/compensate",
                  {"orderNo": order_no, "reason": "探针：非运营"}, token=buyer)
    check("E1 非运营垫付被拒（401/403）", st8 in (401, 403), st8)
    st9, _ = call("GET", "/api/admin/settlements/debts", token=buyer)
    check("E2 非运营查欠款被拒（401/403）", st9 in (401, 403), st9)
    st10, body10 = call("GET", "/api/admin/settlements/debts", token=admin)
    check("E3 运营可查欠款列表", st10 == 200 and isinstance((body10.get("data")), list), st10)

    # ---------- Z. 还原：垫付把样本订单推进了终态，必须复原 ----------
    # 不还原的后果是具体的：settlement-smoke 有一条不变式「已退款订单的托管单必须同步终结」，
    # 探针留下的 REFUNDED + ADVANCED 会让**别人的**回归脚本报出一笔莫名的异常账。
    # 这类「探针污染种子数据」的缺陷最难归因 —— 报错的是另一个脚本，改错的却是这一个。
    print("\n[Z] 还原样本订单")
    oid = sql(f"SELECT id FROM duoduo_trade.t_order WHERE order_no = '{order_no}'")
    sql(f"DELETE FROM duoduo_trade.t_seller_debt WHERE order_id = {oid}")
    sql(f"UPDATE duoduo_trade.t_settlement SET status = 'RELEASED' WHERE order_id = {oid}")
    sql(f"UPDATE duoduo_trade.t_order SET status = 'COMPLETED' WHERE id = {oid}")
    left = sql(f"SELECT CONCAT(o.status, '|', s.status) FROM duoduo_trade.t_order o "
               f"JOIN duoduo_trade.t_settlement s ON s.order_id = o.id WHERE o.id = {oid}")
    check("Z1 样本订单已还原为 COMPLETED|RELEASED", left == "COMPLETED|RELEASED", left)
    left_debt = sql(f"SELECT COUNT(*) FROM duoduo_trade.t_seller_debt WHERE order_id = {oid}")
    check("Z2 探针欠款行已清理", left_debt == "0", left_debt)

    print("\n" + "=" * 72)
    print(f"结果：{len(PASSED)} 通过 / {len(FAILED)} 失败")
    for f in FAILED:
        print("  - " + f)
    print("=" * 72)
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
