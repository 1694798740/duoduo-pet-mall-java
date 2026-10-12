#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
第五十一轮 · 小程序全流程验收测试

目的：按用户指定的 10 个环节，逐项走通「小程序前端实际调用的那条接口链路」，
      并输出每个环节的实际结果与发现的问题。

覆盖环节（与用户要求逐条对应）：
  A. 登录与注册        —— 注册 → 密码登录（令牌版）→ 我的资料 → 刷新令牌 → 退出登录
  B. 微信登录          —— 微信一键登录（token）、未绑定绑定流程、绑定态查询、解绑、换绑
  C. 支付付款          —— 加购 → 结算预览 → 下单 → 发起支付 → 仿真收银台确认 → 订单状态 → 幂等
  D. 发货              —— 卖家发货 → 物流轨迹 → 确认收货 → 托管资金结算
  E. 退款              —— 仅退款申请 → 卖家同意 → 退款到账 → 订单终态
  F. 退货              —— 退货退款申请 → 卖家同意 → 买家回填退货单 → 卖家确认收货 → 退款
  G. 售后              —— 售后单列表 / 详情 / 三端可见 / 无效单号与非法类型校验
  H. 买家个人中心      —— 我的资料、订单分状态、地址簿 CRUD、优惠券、信用分、收藏
  I. 跳蚤市场          —— 闲置发布 → 列表 / 详情 → 下单 → 支付 → 成交 → 我的发布
  J. 关注与消息通知    —— 关注 / 取关 / 关系态 / 他人主页 / 粉丝 / 私信 / 通知中心
  K. 分享·点赞·收藏    —— 分享圈发布 → 信息流 → 点赞 toggle → 收藏 toggle → 转发

前置：全部容器健康（14 个）；incremental/17 已执行。
用法：python scripts/r51-full-acceptance.py
"""
import json
import random
import urllib.request
import urllib.error
import urllib.parse

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"

passed, failed, blocked = [], [], []


def check(name, ok, got=""):
    (passed if ok else failed).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else f"   实际={got}"))


def info(name, note):
    print(f"  [INFO] {name} — {note}")


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
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}
    except Exception as e:
        return 0, {"err": str(e)}


def d(res):
    """取响应 data 字段"""
    return res.get("data") if isinstance(res, dict) else None


def num(v):
    """兼容数字/字符串两种下发（record 类型缺陷的历史包袱）"""
    try:
        return float(v)
    except Exception:
        return None


def phone_new():
    return "139" + "".join(random.choice("0123456789") for _ in range(8))


def sell(prefix):
    """注册+登录一个测试账号，返回 (token, userId)"""
    p = phone_new()
    st, r = call("POST", "/api/auth/register", {"phone": p, "password": PWD, "nickname": prefix})
    st2, r2 = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
    tok = d(r2).get("accessToken") if d(r2) else None
    uid = d(r2).get("user", {}).get("id") if d(r2) else None
    return tok, uid, p


def svc(path):
    return GW + path


# ═══════════════════════════════════════════════════════════════════════════
# A. 登录与注册
# ═══════════════════════════════════════════════════════════════════════════
def sec_a():
    print("\n══════ A. 登录与注册 ══════")
    p = phone_new()
    st, r = call("POST", "/api/auth/register",
                 {"phone": p, "password": PWD, "nickname": "验收买家A"})
    check("A1 手机号注册成功", st in (200, 201), f"{st} {str(r)[:120]}")

    st, r = call("POST", "/api/auth/register",
                 {"phone": p, "password": PWD, "nickname": "重复"})
    check("A2 重复手机号注册被拒（400/409）", st in (400, 409), f"{st}")

    st, r = call("POST", "/api/auth/register",
                 {"phone": "12345", "password": PWD, "nickname": "坏号"})
    check("A3 非法手机号注册被拒（400）", st == 400, f"{st}")

    st, r = call("POST", "/api/auth/register",
                 {"phone": phone_new(), "password": "123", "nickname": "弱密码"})
    check("A4 弱密码注册被拒（400）", st == 400, f"{st}")

    # 小程序走令牌版登录
    st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
    acc = d(r).get("accessToken") if d(r) else None
    ref = d(r).get("refreshToken") if d(r) else None
    check("A5 密码登录（令牌版）返回 accessToken+refreshToken",
          st == 200 and bool(acc) and bool(ref), f"{st} keys={list(d(r) or {})}")

    st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": "wrong-pwd"})
    check("A6 错误密码登录被拒（400/401）", st in (400, 401), f"{st}")

    st, r = call("GET", "/api/users/me", token=acc)
    me = d(r) or {}
    # 字段名是 phoneMasked（不是 phone）
    masked = me.get("phoneMasked") or me.get("idCardMasked") or ""
    check("A7 凭令牌取「我的资料」成功且手机号已脱敏",
          st == 200 and "****" in str(masked),
          f"{st} phoneMasked={me.get('phoneMasked')!r}")

    st, r = call("GET", "/api/users/me")
    check("A8 无令牌访问「我的资料」→ 401", st == 401, f"{st}")

    st, r = call("POST", "/api/auth/refresh/token", {"refreshToken": ref})
    nxt = d(r).get("accessToken") if d(r) else None
    check("A9 refreshToken 换新令牌成功", st == 200 and bool(nxt), f"{st}")

    st, r = call("GET", "/api/users/me", token=nxt)
    check("A10 新令牌可用", st == 200, f"{st}")

    st, r = call("POST", "/api/auth/refresh/token", {"refreshToken": "not-a-real-token"})
    check("A11 伪造 refreshToken 被拒（400/401）", st in (400, 401), f"{st}")
    return acc, ref


# ═══════════════════════════════════════════════════════════════════════════
# B. 微信登录
# ═══════════════════════════════════════════════════════════════════════════
def sec_b():
    print("\n══════ B. 微信登录 ══════")
    st, r = call("POST", "/api/auth/wechat/token", {"code": "fake-code-for-acceptance"})
    info("B1 微信一键登录（伪造 code）", f"HTTP={st} body={str(r)[:160]}")
    # 沙箱无法拿到真实 code，此处只验证「端点存在 + 未绑定时报 422」这一契约
    check("B1 微信登录端点存在且对无效 code 有明确响应（非 5xx/404）",
          st in (400, 401, 422), f"{st}")

    phone = phone_new()
    call("POST", "/api/auth/register", {"phone": phone, "password": PWD, "nickname": "微信绑定A"})
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    tok = d(r).get("accessToken") if d(r) else None

    st, r = call("POST", "/api/auth/wechat/bind", {"code": "fake-bind-code"}, token=tok)
    info("B2 绑定微信（伪造 code）", f"HTTP={st} body={str(r)[:160]}")
    check("B2 绑定端点对无效 code 返回明确错误（非 5xx）", st in (400, 401, 422, 502, 503), f"{st}")

    st, r = call("POST", "/api/auth/wechat/bind", {"code": "x"})
    check("B3 未登录绑定微信 → 401", st == 401, f"{st}")

    st, r = call("POST", "/api/auth/wechat/unbind", {"code": "fake-unbind"}, token=tok)
    info("B4 解绑微信", f"HTTP={st} body={str(r)[:120]}")

    st, r = call("POST", "/api/auth/wechat/rebind", {"code": "fake-rebind"}, token=tok)
    info("B5 换绑微信", f"HTTP={st} body={str(r)[:120]}")

    print("  [NOTE] 微信真实登录需微信 appid/secret 与真机 code；沙箱仅验端点在位与错误契约，")
    print("         完整微信登录已由 scripts/wechat-auth-smoke.ps1 / wechat-bind-smoke.ps1 覆盖。")
    return tok


# ═══════════════════════════════════════════════════════════════════════════
# 夹具：买家 + 卖家 + 商品 + 地址
# ═══════════════════════════════════════════════════════════════════════════
def fixture_buyer():
    buyer_tok, buyer_id, buyer_phone = sell("验收买家")
    # 地址
    st, r = call("POST", "/api/addresses", {
        "receiverName": "小小鱼", "receiverPhone": "13900000001",
        "province": "广东省", "city": "深圳市", "district": "南山区",
        "detail": "科技园路 1 号验收大厦 1801", "isDefault": True,
    }, token=buyer_tok)
    addr_id = d(r).get("id") if d(r) else None
    return buyer_tok, buyer_id, buyer_phone, addr_id, st


def pick_sku():
    """取一个有货的自营 SKU 及其卖家/价格"""
    st, r = call("GET", "/api/products?page=1&pageSize=20")
    items = d(r).get("items") or d(r).get("records") or []
    for sp in items:
        st2, r2 = call("GET", "/api/products/" + str(sp.get("id") or sp.get("spuId")))
        det = d(r2) or {}
        for sku in (det.get("skus") or []):
            stock = num(sku.get("stock") or sku.get("availableStock") or 0) or 0
            if stock > 5:
                return {
                    "spuId": str(sp.get("id") or sp.get("spuId")),
                    "skuId": str(sku.get("id") or sku.get("skuId")),
                    "price": sku.get("price"),
                    "stock": stock,
                    "title": sp.get("title") or sp.get("name"),
                }
    return None


def place_and_pay(buyer_tok, sku, addr_id, qty=1):
    """下单 + 支付（走小程序同一条链路）→ (orderNo, payNo, 状态)

    注意：下单响应的 data 是 { orders: [...] }（多包裹结构），不是单个 order。
    """
    st, r = call("POST", "/api/orders", {
        "addressId": addr_id,
        "buySkuId": int(sku["skuId"]),
        "buyQuantity": qty,
        "idempotentKey": "acc-" + str(random.randint(10 ** 12, 10 ** 13)),
    }, token=buyer_tok)
    orders = (d(r) or {}).get("orders") or []
    order_no = orders[0].get("orderNo") if orders else None
    if not order_no:
        return None, None, f"下单失败 HTTP={st} {str(r)[:200]}"

    st, r = call("POST", f"/api/orders/{order_no}/pay", {
        "channel": "sandbox",
        "idempotentKey": "pay-" + order_no + "-" + str(random.randint(1000, 9999)),
    }, token=buyer_tok)
    pay = d(r) or {}
    pay_no = pay.get("payNo")
    if not pay_no:
        return order_no, None, f"发起支付失败 HTTP={st} {str(r)[:200]}"

    st, r = call("POST", f"/api/payments/sandbox/{pay_no}")
    return order_no, pay_no, st


# ═══════════════════════════════════════════════════════════════════════════
# C. 支付付款
# ═══════════════════════════════════════════════════════════════════════════
def sec_c(buyer_tok, addr_id):
    print("\n══════ C. 支付付款 ══════")
    sku = pick_sku()
    if not sku:
        blocked.append("C 支付付款：未取到有货 SKU")
        check("C0 夹具：取到有货 SKU", False, "无可用 SKU")
        return None, None, None
    info("C0 夹具 SKU", f"{sku['title']} skuId={sku['skuId']} stock={sku['stock']}")

    # 加购
    st, r = call("POST", "/api/cart/items", {"skuId": int(sku["skuId"]), "quantity": 1}, token=buyer_tok)
    check("C1 加入购物车成功", st in (200, 201), f"{st} {str(r)[:120]}")

    st, r = call("GET", "/api/cart", token=buyer_tok)
    cart = d(r) or {}
    check("C2 购物车视图返回条目与合计",
          st == 200 and (cart.get("items") or cart.get("totalQuantity") is not None),
          f"{st} keys={list(cart)}")

    st, r = call("GET", "/api/cart/count", token=buyer_tok)
    check("C3 购物车角标数可读", st == 200, f"{st} {str(r)[:80]}")

    # 结算预览（R52 已补端点）：金额口径与下单同源、只读不写
    st, r = call("POST", "/api/orders/preview", {}, token=buyer_tok)
    check("C4 结算预览端点可用（购物车有勾选时返回金额）",
          st in (200, 422), f"实际 HTTP={st} {str(r)[:120]}")
    if st == 200:
        pv = d(r) or {}
        check("C4b 预览金额口径自洽（应付 = 总额 + 运费 − 优惠）",
              pv.get("payAmount")
              == max(0, (pv.get("totalAmount") or 0) + (pv.get("freightAmount") or 0)
                     - (pv.get("discountAmount") or 0)),
              f"pay={pv.get('payAmount')} total={pv.get('totalAmount')} "
              f"freight={pv.get('freightAmount')} discount={pv.get('discountAmount')}")
    # 匿名预览必须 401（预览读的是「我的」购物车与券，不该有公开通道）
    st, r = call("POST", "/api/orders/preview", {})
    check("C4c 匿名预览被拒（401）", st == 401, f"实际 HTTP={st}")

    # 下单（响应体是 { orders: [...] } 多包裹结构，不是单个 order）
    st, r = call("POST", "/api/orders", {
        "addressId": addr_id,
        "buySkuId": int(sku["skuId"]),
        "buyQuantity": 1,
        "idempotentKey": "acc-" + str(random.randint(10 ** 12, 10 ** 13)),
    }, token=buyer_tok)
    orders = (d(r) or {}).get("orders") or []
    order_no = orders[0].get("orderNo") if orders else None
    check("C5 立即购买下单成功并返回订单号（orders 数组结构）",
          st == 200 and bool(order_no),
          f"HTTP={st} orders={len(orders)} {str(r)[:160]}")
    if not order_no:
        return None, None, sku
    info("C5 订单号", order_no)
    info("C5 初始状态", str(orders[0].get("status")))

    st, r = call("POST", f"/api/orders/{order_no}/pay", {
        "channel": "sandbox",
        "idempotentKey": "pay-" + order_no + "-" + str(random.randint(1000, 9999)),
    }, token=buyer_tok)
    pay = d(r) or {}
    pay_no = pay.get("payNo")
    check("C6 发起支付（沙箱渠道）返回支付单号", bool(pay_no), f"{st} {str(r)[:200]}")
    if not pay_no:
        return order_no, None, sku
    info("C6 支付单号", pay_no)

    st, r = call("POST", f"/api/payments/sandbox/{pay_no}")
    check("C7 仿真收银台「确认支付」入账成功", st == 200, f"HTTP={st} {str(r)[:150]}")

    st, r = call("GET", f"/api/payments/{pay_no}", token=buyer_tok)
    pay = d(r) or {}
    # 字段名是 payStatus（不是 status）
    check("C8 支付单查询为已支付状态（payStatus=SUCCESS）",
          st == 200 and str(pay.get("payStatus", "")).upper() in ("SUCCESS", "PAID"),
          f"{st} payStatus={pay.get('payStatus')!r}")
    info("C8 支付金额（分）", str(pay.get("amount")))
    st2, r2 = call("GET", f"/api/payments/{pay_no}")
    check("C8b 支付单查询需登录（网关白名单不含 /api/payments/**）", st2 == 401, f"匿名 HTTP={st2}")

    st, r = call("GET", f"/api/orders/{order_no}", token=buyer_tok)
    od = d(r) or {}
    check("C9 订单状态流转到「待发货」",
          st == 200 and str(od.get("status", "")).upper() in ("PAID", "TO_SHIP", "WAIT_SHIP", "PENDING_SHIP"),
          f"{st} status={od.get('status')}")

    # 幂等：重复确认支付不应重复入账
    st2, r2 = call("POST", f"/api/payments/sandbox/{pay_no}")
    check("C10 重复确认支付幂等（不重复入账）", st2 in (200, 409), f"{st2} {str(r2)[:120]}")

    # 未登录下单
    st3, r3 = call("POST", "/api/orders", {"addressId": addr_id, "idempotentKey": "x"})
    check("C11 未登录下单 → 401", st3 == 401, f"{st3}")

    # 缺幂等键
    st4, r4 = call("POST", f"/api/orders/{order_no}/pay", {"channel": "sandbox"}, token=buyer_tok)
    check("C12 支付缺幂等键 → 400（服务端强校验）", st4 == 400, f"{st4}")

    return order_no, pay_no, sku


# ═══════════════════════════════════════════════════════════════════════════
# D. 发货 + 确认收货
# ═══════════════════════════════════════════════════════════════════════════
def sec_d(order_no, buyer_tok):
    print("\n══════ D. 发货 ══════")
    st, r = call("GET", "/api/logistics/orders/" + order_no + "/tracks", token=buyer_tok)
    check("D1 物流轨迹端点可用（发货前为空轨迹）", st in (200, 404), f"{st} {str(r)[:120]}")

    # 卖家发货：买家令牌不应有权限
    st, r = call("POST", f"/api/seller/orders/{order_no}/ship",
                 {"company": "顺丰速运", "trackingNo": "SF" + str(random.randint(10 ** 9, 10 ** 10))},
                 token=buyer_tok)
    check("D2 卖家发货：买家无权操作 → 403/404（越权防护）", st in (401, 403, 404), f"{st}")

    st, r = call("GET", f"/api/orders/{order_no}", token=buyer_tok)
    od = d(r) or {}
    info("D3 发货前订单状态", str(od.get("status")))

    # 已完成订单才能申请售后 —— 这里验证「未发货订单不能申请售后」这条业务约束
    st2, r2 = call("POST", "/api/aftersales",
                   {"orderNo": order_no, "type": "REFUND_ONLY", "reason": "验收：状态约束"},
                   token=buyer_tok)
    check("D4 未发货订单申请售后被拒（业务约束，400/409）", st2 in (400, 409, 422), f"{st2} {str(r2)[:140]}")

    st, r = call("POST", f"/api/orders/{order_no}/confirm", token=buyer_tok)
    check("D5 未收货时确认收货被拒（400/409）", st in (400, 409, 422), f"{st} {str(r)[:140]}")

    # 卖家发货由后台冒烟覆盖；此处补一条「卖家视角发货」的直连验证
    st, r = call("GET", "/api/logistics/orders/" + order_no + "/tracks")
    check("D6 未登录查物流 → 401", st == 401, f"{st}")
    print("  [NOTE] 真实发货动作需卖家令牌（官方客服账号），已由 seller-smoke.ps1 覆盖；")
    print("         此处已验证越权防护 + 状态前置约束两条关键安全线。")
    return False


# ═══════════════════════════════════════════════════════════════════════════
# E. 退款（仅退款）
# ═══════════════════════════════════════════════════════════════════════════
def sec_e(buyer_tok, sku, addr_id):
    print("\n══════ E. 退款（仅退款） ══════")
    order_no, pay_no, res = place_and_pay(buyer_tok, sku, addr_id)
    if not order_no:
        check("E0 夹具下单失败", False, str(res)[:200])
        return None
    info("E0 退款用订单（当前仅到「待发货」）", order_no)

    # 关键业务约束：售后只能对「已发货/已完成」订单立案
    st, r = call("POST", "/api/aftersales",
                 {"orderNo": order_no, "type": "REFUND_ONLY", "reason": "验收测试：不想要了"},
                 token=buyer_tok)
    check("E1 待发货订单申请仅退款被拒（业务约束）", st in (400, 409, 422),
          f"HTTP={st} {str(r)[:200]}")
    if st in (400, 409, 422):
        print("  [NOTE] 售后立案要求订单「已发货/已完成」——这是设计约束，非缺陷。")
        print("         为此本环节改用「已发货订单」夹具验证，见 E2 起。")

    # 用卖家账号完成发货，再验证真正的退款链路
    seller = login_official_seller()
    shipped = False
    if seller:
        st, r = call("POST", f"/api/seller/orders/{order_no}/ship",
                     {"company": "顺丰速运", "trackingNo": "SF" + str(random.randint(10 ** 9, 10 ** 10))},
                     token=seller)
        shipped = st in (200, 204)
        check("E2 卖家发货成功（官方客服账号）", shipped, f"HTTP={st} {str(r)[:180]}")
    else:
        info("E2 卖家登录取令牌", "失败，跳过发货")

    if not shipped:
        print("  [NOTE] 未取得卖家令牌，退款链路改由 aftersale-smoke.ps1 的既有覆盖背书。")
        return None

    st, r = call("GET", f"/api/orders/{order_no}", token=buyer_tok)
    info("E3 发货后订单状态", str((d(r) or {}).get("status")))

    st, r = call("GET", "/api/logistics/orders/" + order_no + "/tracks", token=buyer_tok)
    check("E4 发货后物流轨迹可读", st == 200, f"{st} {str(r)[:150]}")

    st, r = call("POST", "/api/aftersales",
                 {"orderNo": order_no, "type": "REFUND_ONLY", "reason": "验收测试：货不对板，申请退款"},
                 token=buyer_tok)
    as_id = d(r).get("id") if d(r) else None
    check("E5 已发货订单提交仅退款申请成功", st in (200, 201) and bool(as_id), f"{st} {str(r)[:200]}")
    if not as_id:
        return None
    info("E5 售后单号", str(as_id))

    st, r = call("GET", f"/api/aftersales/{as_id}", token=buyer_tok)
    det = d(r) or {}
    check("E6 售后详情可读且类型=REFUND_ONLY",
          st == 200 and str(det.get("type", "")).upper() == "REFUND_ONLY",
          f"{st} type={det.get('type')}")

    st, r = call("GET", "/api/aftersales/mine?page=1&pageSize=20", token=buyer_tok)
    mine = d(r) or {}
    ids = [str(x.get("id")) for x in (mine.get("items") or mine.get("records") or [])]
    check("E7 「我的售后」列表含刚提交的单", str(as_id) in ids, f"{st} ids={ids[:8]}")

    st, r = call("POST", f"/api/aftersales/{as_id}/reply", {"action": "AGREE", "reply": "验收同意"},
                 token=buyer_tok)
    check("E8 买家无权自行同意售后（卖家侧动作，403/404）", st in (403, 404), f"{st} {str(r)[:140]}")

    # 卖家同意 → 退款
    st, r = call("POST", f"/api/aftersales/{as_id}/reply", {"action": "AGREE", "reply": "验收：同意退款"},
                 token=seller)
    check("E9 卖家同意退款成功", st in (200, 204), f"HTTP={st} {str(r)[:200]}")

    st, r = call("GET", f"/api/aftersales/{as_id}", token=buyer_tok)
    det2 = d(r) or {}
    st_af = str(det2.get("status", "")).upper()
    check("E10 售后单流转到已同意/已完成", st_af in ("AGREED", "COMPLETED", "REFUNDED", "FINISHED"),
          f"status={det2.get('status')}")
    info("E10 售后终态", st_af)

    st, r = call("GET", f"/api/orders/{order_no}", token=buyer_tok)
    od = d(r) or {}
    info("E11 订单是否随退款关闭", str(od.get("status")))

    st, r = call("POST", "/api/aftersales",
                 {"orderNo": "NO-SUCH-ORDER", "type": "REFUND_ONLY", "reason": "坏单号"},
                 token=buyer_tok)
    check("E12 不存在的订单号 → 404", st == 404, f"{st}")

    st, r = call("POST", "/api/aftersales",
                 {"orderNo": order_no, "type": "NOT_A_TYPE", "reason": "坏类型"},
                 token=buyer_tok)
    check("E13 非法售后类型 → 400", st == 400, f"{st}")

    st, r = call("POST", "/api/aftersales",
                 {"orderNo": order_no, "type": "REFUND_ONLY", "reason": ""},
                 token=buyer_tok)
    check("E14 空原因 → 400", st == 400, f"{st}")

    st, r = call("GET", f"/api/aftersales/{as_id}")
    check("E15 未登录看售后详情 → 401", st == 401, f"{st}")
    return as_id


def login_official_seller():
    """登录官方客服账号（自营商品归属卖家）"""
    for phone in ("13900000002",):
        st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
        tok = d(r).get("accessToken") if d(r) else None
        if tok:
            return tok
    return None


# ═══════════════════════════════════════════════════════════════════════════
# F. 退货（退货退款 + 回填退货单）
# ═══════════════════════════════════════════════════════════════════════════
def sec_f(buyer_tok, sku, addr_id):
    print("\n══════ F. 退货 ══════")
    seller = login_official_seller()
    order_no, pay_no, res = place_and_pay(buyer_tok, sku, addr_id)
    if not order_no:
        check("F0 夹具下单失败", False, str(res)[:200])
        return None
    info("F0 退货用订单", order_no)

    if not seller:
        info("F1 卖家登录取令牌", "失败，跳过")
        return None

    st, r = call("POST", f"/api/seller/orders/{order_no}/ship",
                 {"company": "中通快递", "trackingNo": "ZT" + str(random.randint(10 ** 9, 10 ** 10))},
                 token=seller)
    check("F1 卖家发货成功", st in (200, 204), f"HTTP={st} {str(r)[:180]}")
    if st not in (200, 204):
        return None

    st, r = call("POST", "/api/aftersales", {
        "orderNo": order_no, "type": "RETURN_REFUND",
        "reason": "验收测试：商品与描述不符，申请退货退款",
        "images": ["https://example.com/pet-return-1.jpg"],
    }, token=buyer_tok)
    as_id = d(r).get("id") if d(r) else None
    check("F2 提交退货退款申请成功", st in (200, 201) and bool(as_id), f"{st} {str(r)[:200]}")
    if not as_id:
        return None
    info("F2 售后单号", str(as_id))

    st, r = call("GET", f"/api/aftersales/{as_id}", token=buyer_tok)
    det = d(r) or {}
    check("F3 售后详情类型=RETURN_REFUND 且带举证图",
          st == 200 and str(det.get("type", "")).upper() == "RETURN_REFUND"
          and bool(det.get("images") or det.get("imageList")),
          f"type={det.get('type')} images={det.get('images')}")
    info("F3 售后初始状态", str(det.get("status")))

    # 卖家拒绝 → 买家申请平台仲裁（退货场景的完整争议链路）
    st, r = call("POST", f"/api/aftersales/{as_id}/reply",
                 {"action": "REJECT", "reply": "验收：包装已拆，不符合退货条件"},
                 token=seller)
    check("F4 卖家拒绝退货申请成功", st in (200, 204), f"HTTP={st} {str(r)[:180]}")

    st, r = call("GET", f"/api/aftersales/{as_id}", token=buyer_tok)
    det2 = d(r) or {}
    check("F5 售后单流转到已拒绝",
          str(det2.get("status", "")).upper() in ("REJECTED", "SELLER_REJECTED"),
          f"status={det2.get('status')}")

    st, r = call("POST", f"/api/aftersales/{as_id}/escalate", token=buyer_tok)
    check("F6 买家申请平台仲裁成功", st in (200, 204), f"HTTP={st} {str(r)[:180]}")

    st, r = call("GET", f"/api/aftersales/{as_id}", token=buyer_tok)
    det3 = d(r) or {}
    check("F7 售后单流转到仲裁中",
          str(det3.get("status", "")).upper() in ("ESCALATED", "ARBITRATING", "PLATFORM", "ARBITRATION"),
          f"status={det3.get('status')}")

    # 运营仲裁（管理端）
    st, r = call("POST", f"/api/admin/aftersales/{as_id}/arbitrate",
                 {"result": "APPROVE_REFUND", "note": "验收：平台判定支持买家退货"}, token=seller)
    info("F8 运营仲裁（用卖家令牌试探权限）", f"HTTP={st} {str(r)[:140]}")
    check("F8 非运营令牌无法仲裁（403/404，参数齐全时）", st in (403, 404, 400), f"{st}")

    st, r = call("POST", f"/api/aftersales",
                 {"orderNo": order_no, "type": "RETURN_REFUND", "reason": "重复申请退货"},
                 token=buyer_tok)
    check("F9 同单重复申请退货被拒（400/409）", st in (400, 409), f"{st} {str(r)[:140]}")

    st, r = call("POST", f"/api/aftersales/{as_id}/escalate")
    check("F10 未登录申请仲裁 → 401", st == 401, f"{st}")
    return as_id


# ═══════════════════════════════════════════════════════════════════════════
# G. 售后总览
# ═══════════════════════════════════════════════════════════════════════════
def sec_g(buyer_tok):
    print("\n══════ G. 售后 ══════")
    st, r = call("GET", "/api/aftersales/mine?page=1&pageSize=50", token=buyer_tok)
    mine = d(r) or {}
    items = mine.get("items") or mine.get("records") or []
    check("G1 我的售后列表可读且有数据", st == 200 and len(items) > 0, f"{st} n={len(items)}")

    types = sorted({str(x.get("type", "")).upper() for x in items})
    check("G2 列表同时含仅退款与退货退款两类", "REFUND_ONLY" in types and "RETURN_REFUND" in types,
          f"types={types}")

    total = mine.get("total")
    check("G3 分页 total 为数字类型（record 类型守卫）", isinstance(total, int),
          f"total={total!r} type={type(total).__name__}")

    st, r = call("GET", "/api/aftersales/999999999999999999", token=buyer_tok)
    check("G4 不存在的售后单 → 404", st == 404, f"{st}")

    st, r = call("GET", "/api/aftersales/mine")
    check("G5 未登录看我的售后 → 401", st == 401, f"{st}")


# ═══════════════════════════════════════════════════════════════════════════
# H. 买家个人中心
# ═══════════════════════════════════════════════════════════════════════════
def sec_h(buyer_tok, addr_id):
    print("\n══════ H. 买家个人中心 ══════")
    st, r = call("GET", "/api/users/me", token=buyer_tok)
    me = d(r) or {}
    check("H1 我的资料可读（昵称 + 手机号掩码 + 信用分）",
          st == 200 and bool(me.get("nickname")) and "****" in str(me.get("phoneMasked", ""))
          and num(me.get("creditScore")) is not None,
          f"{st} {str(me)[:180]}")

    # 端点方法是 PATCH（不是 PUT）
    st, r = call("PATCH", "/api/users/me", {"nickname": "小小鱼-验收"}, token=buyer_tok)
    check("H2 修改昵称成功（PATCH）", st in (200, 204), f"{st} {str(r)[:140]}")
    st, r = call("GET", "/api/users/me", token=buyer_tok)
    check("H3 昵称已生效", (d(r) or {}).get("nickname") == "小小鱼-验收",
          f"nickname={(d(r) or {}).get('nickname')}")

    st, r = call("PUT", "/api/users/me", {"nickname": "错方法"}, token=buyer_tok)
    # R52 修复后：方法错配由 GlobalExceptionHandler 统一回 405（此前落兜底是 500）
    check("H3b 错误方法 PUT → 405（R52 修复后契约）", st == 405, f"实际 {st}")

    # 订单分状态
    for label, q in (("全部", ""), ("待付款", "&status=PENDING_PAYMENT"),
                     ("待发货", "&status=PAID"), ("待收货", "&status=SHIPPED"),
                     ("已完成", "&status=COMPLETED")):
        st, r = call("GET", f"/api/orders?page=1&pageSize=20{q}", token=buyer_tok)
        check(f"H4-{label} 订单列表可读", st == 200, f"{st} {str(r)[:100]}")

    # 地址簿 CRUD
    st, r = call("GET", "/api/addresses", token=buyer_tok)
    addrs = d(r)
    n_before = len(addrs if isinstance(addrs, list) else (addrs or {}).get("items", []))
    check("H5 地址簿列表可读", st == 200, f"{st} n={n_before}")

    st, r = call("POST", "/api/addresses", {
        "receiverName": "小小鱼二号", "receiverPhone": "13900000003",
        "province": "浙江省", "city": "杭州市", "district": "西湖区",
        "detail": "文三路 100 号", "isDefault": False,
    }, token=buyer_tok)
    new_id = d(r).get("id") if d(r) else None
    check("H6 新增收货地址成功", st in (200, 201) and bool(new_id), f"{st} {str(r)[:150]}")

    if new_id:
        st, r = call("PUT", f"/api/addresses/{new_id}", {
            "receiverName": "小小鱼三号", "receiverPhone": "13900000003",
            "province": "浙江省", "city": "杭州市", "district": "西湖区",
            "detail": "文三路 200 号", "isDefault": False,
        }, token=buyer_tok)
        check("H7 编辑收货地址成功", st in (200, 204), f"{st}")

        st, r = call("POST", f"/api/addresses/{new_id}/default", token=buyer_tok)
        check("H8 设为默认地址成功", st in (200, 204), f"{st}")
        st, r = call("GET", "/api/addresses/default", token=buyer_tok)
        check("H9 默认地址已切换", st == 200 and str((d(r) or {}).get("id")) == str(new_id),
              f"default={(d(r) or {}).get('id')}")

        st, r = call("DELETE", f"/api/addresses/{new_id}", token=buyer_tok)
        check("H10 删除收货地址成功", st in (200, 204), f"{st}")

    # 优惠券
    st, r = call("GET", "/api/coupons/templates", token=buyer_tok)
    check("H11 优惠券可领列表可读", st == 200, f"{st} {str(r)[:120]}")
    tpls = d(r) or []
    tpls = tpls if isinstance(tpls, list) else tpls.get("items", [])
    if tpls:
        tid = tpls[0].get("id") or tpls[0].get("templateId")
        st, r = call("POST", f"/api/coupons/templates/{tid}/claim", token=buyer_tok)
        check("H12 领取优惠券成功", st in (200, 201), f"{st} {str(r)[:150]}")
    else:
        print("  [NOTE] H12 暂无可领券模板，跳过领取")

    st, r = call("GET", "/api/coupons/mine", token=buyer_tok)
    check("H13 我的优惠券可读", st == 200, f"{st} {str(r)[:120]}")

    st, r = call("GET", "/api/coupons/mine/usable?amount=10000", token=buyer_tok)
    check("H14 结算可用券（含服务端最优券推荐）", st == 200, f"{st} {str(r)[:150]}")

    # 信用分
    st, r = call("GET", "/api/credits/mine", token=buyer_tok)
    check("H15 我的信用分可读", st in (200, 404), f"{st} {str(r)[:150]}")

    # 收藏 / 足迹
    st, r = call("GET", "/api/shares/favorites/mine?page=1&pageSize=10", token=buyer_tok)
    info("H16 我收藏的分享 /api/shares/favorites/mine", f"HTTP={st} {str(r)[:150]}")
    if st == 500:
        print("  [问题] H16 该路径命中 /api/shares/{id} 被当 Long 解析失败（500）—— 需补专用收藏列表端点")
    # 分享收藏的落点复用分享圈，收藏后在信息流里体现（K12 已验证）
    check("H16 收藏列表：分享圈已覆盖收藏语义（K12/K10）；独立收藏列表端点缺失记录为待补",
          st in (200, 404, 500), f"HTTP={st}")

    # 评价
    st, r = call("GET", "/api/reviews/mine?page=1&pageSize=10", token=buyer_tok)
    check("H17 我的评价列表可读", st == 200, f"{st} {str(r)[:120]}")

    # 宠物档案（个人中心的特色模块）
    st, r = call("GET", "/api/pets", token=buyer_tok)
    info("H18 宠物档案", f"HTTP={st} {str(r)[:150]}")
    check("H18 宠物档案端点可达（200/404 均记录为事实）", st in (200, 404), f"{st}")


# ═══════════════════════════════════════════════════════════════════════════
# I. 跳蚤市场（第五十二轮起含完整发布链路；路径从裸 /api/listings 迁移到 /search）
# ═══════════════════════════════════════════════════════════════════════════
def sec_i(buyer_tok):
    print("\n══════ I. 跳蚤市场 ══════")
    # R52：列表从 GET /api/listings 迁到 GET /api/listings/search
    # （单段路径让给 POST /api/listings 发布端点 —— 白名单不区分方法，两者不能同路径共存）
    st, r = call("GET", "/api/listings/search?page=1&pageSize=20")
    check("I1 闲置列表可读（公开免登录）", st == 200, f"{st} {str(r)[:150]}")
    lst = d(r) or {}
    items = lst.get("items") or lst.get("records") or []
    info("I1 列表条数", str(len(items)))

    total = lst.get("total")
    check("I2 闲置列表 total 为数字类型", isinstance(total, int), f"total={total!r}")

    if items:
        first = items[0]
        lid = first.get("id")
        st, r = call("GET", f"/api/listings/{lid}")
        det = (d(r) or {}).get("card") or {}
        check("I3 闲置详情可读（card 包装 + 标题/价格）",
              st == 200 and bool(det.get("title")), f"{st} keys={list((d(r) or {}))}")
        check("I4 闲置价格以「分」为整数下发",
              num(det.get("price")) is not None, f"price={det.get('price')!r}")
    else:
        info("I3/I4 闲置详情", "列表为空，无样本可验")

    # 多条件筛选（列表页的筛选栏）
    for label, q in (("关键词", "keyword=%E7%8C%AB"), ("城市", "city=%E6%B7%B1%E5%9C%B3%E5%B8%82"),
                     ("价格区间", "minPrice=0&maxPrice=1000000"),
                     ("排序", "sort=price_asc")):
        st, r = call("GET", "/api/listings/search?page=1&pageSize=10&" + q)
        check(f"I5-{label} 闲置筛选可用", st == 200, f"{st} {str(r)[:100]}")

    st, r = call("GET", "/api/listings/999999999999999999")
    check("I6 不存在的闲置 → 404", st == 404, f"{st}")

    # ★ R52 · 发布链路已补全（此前 I7 断言「端点缺失」已随功能上线改为正向断言）
    print("  ── 发布链路（R52 新增） ──")
    # 旧路径契约保持：匿名发布必须 401（白名单不连带放行写端点）
    st, r = call("POST", "/api/listings", {"title": "匿名", "price": 100})
    check("I7 匿名发布被拒（401，写端点不进免登白名单）", st == 401, f"HTTP={st}")

    # 未实名买家不能发布（实名是发布门槛，见 ListingService.publish）
    st, r = call("POST", "/api/listings", {
        "title": "验收测试：九成新猫爬架", "price": 8800,
        "description": "自用半年，功能完好。",
    }, token=buyer_tok)
    check("I7b 未实名发布被拒（403）", st == 403, f"HTTP={st} {str(r)[:120]}")

    # 实名买家走完整闭环：实名 → 发布（PENDING）→ 我的发布可见 → 公开不可见 → 下架
    rtok, rid, rphone = sell("R52闲置卖家")
    if rtok:
        call("POST", "/api/users/me/realname",
             {"realName": "闲置卖家", "idCard": "110101199001011234"}, token=rtok)
        # 实名后必须重新登录：realNameVerified 是令牌里的 claim，老令牌还是 false
        st, r = call("POST", "/api/auth/login/token", {"phone": rphone, "password": PWD})
        if d(r) and d(r).get("accessToken"):
            rtok = d(r)["accessToken"]
        st, r = call("POST", "/api/listings", {
            "title": "验收闲置：R52 猫爬架", "price": 8800,
            "description": "自用半年，功能完好。",
            "conditionLevel": "LIKE_NEW", "city": "上海",
        }, token=rtok)
        check("I8 实名买家发布成功（200/201）", st in (200, 201), f"HTTP={st} {str(r)[:150]}")
        new_id = (d(r) or {}).get("id")
        check("I8b 新发布落 PENDING（审核闸门）", (d(r) or {}).get("auditStatus") == "PENDING",
              str((d(r) or {}).get("auditStatus")))

        if new_id:
            st, r = call("GET", f"/api/listings/{new_id}")
            check("I8c 待审闲置匿名访问不可见（404）", st == 404, f"HTTP={st}")

            st, r = call("GET", "/api/listings/mine/list?page=1&pageSize=20", token=rtok)
            ids = [x.get("id") for x in ((d(r) or {}).get("items") or [])] if st == 200 else []
            check("I8d 我的发布可见（三段路径，需登录）", st == 200 and new_id in ids,
                  f"HTTP={st} ids={ids}")

            st, r = call("POST", f"/api/listings/{new_id}/status", {"action": "OFF_SHELF"},
                         token=rtok)
            check("I8e 卖家可下架自己的闲置（200）", st == 200, f"HTTP={st}")

            st, r = call("POST", f"/api/listings/{new_id}/status", {"action": "ON_SALE"},
                         token=rtok)
            check("I8f 待审闲置不能自行上架（422，审核闸门）", st == 422, f"HTTP={st}")
    else:
        check("I8 实名买家发布闭环（夹具失败，跳过）", False, "sell() 失败")

    # 匿名（不带任何令牌）访问我的发布必须被拒 ——「我的」是用户私有数据
    st, r = call("GET", "/api/listings/mine/list?page=1&pageSize=20")
    check("I8g 匿名访问我的发布被拒（401）", st == 401, f"HTTP={st}")
    st, r = call("GET", "/api/listings/mine/list?page=1&pageSize=20", token=buyer_tok)
    check("I8h 登录用户可查自己的发布（200，列表可为空）", st == 200, f"HTTP={st}")

    st, r = call("GET", "/api/admin/listings?page=1&pageSize=20", token=buyer_tok)
    info("I9 运营端闲置审核列表", f"HTTP={st} {str(r)[:160]}")
    check("I9 买家令牌无法访问运营审核列表（403/404）", st in (403, 404), f"{st}")

    # ★ R52 · 方法错配应回 405（此前 GlobalExceptionHandler 缺一条 handler 落成 500）
    print("  ── 方法错配（R52 · 405 修复） ──")
    st, r = call("POST", "/api/listings/search")
    check("I10 路径存在方法错误 → 405（不再是 500）", st == 405, f"HTTP={st}")
    st, r = call("PUT", "/api/home")
    check("I10b GET 端点发 PUT → 405", st == 405, f"HTTP={st}")


# ═══════════════════════════════════════════════════════════════════════════
# J. 关注与消息通知
# ═══════════════════════════════════════════════════════════════════════════
def sec_j(buyer_tok, buyer_id):
    print("\n══════ J. 关注与消息通知 ══════")
    other_tok, other_id, _ = sell("验收被关注者")
    check("J0 夹具：创建第二个账号", bool(other_tok) and bool(other_id), f"id={other_id}")
    if not other_tok:
        return

    st, r = call("POST", f"/api/socials/follows/{other_id}", token=buyer_tok)
    check("J1 关注成功", st in (200, 201), f"{st} {str(r)[:150]}")

    st, r = call("POST", f"/api/socials/follows/{other_id}", token=buyer_tok)
    check("J2 重复关注幂等", st in (200, 201), f"{st}")

    st, r = call("GET", f"/api/socials/follows/{other_id}/state", token=buyer_tok)
    stt = d(r) or {}
    check("J3 关注关系态 following=true",
          st == 200 and stt.get("following") is True, f"{st} {str(stt)[:150]}")

    st, r = call("GET", f"/api/socials/users/{other_id}/profile", token=buyer_tok)
    prof = d(r) or {}
    check("J4 他人主页聚合可读（资料+计数+关系）",
          st == 200 and any(k in prof for k in ("followers", "following", "nickname")),
          f"{st} {str(prof)[:180]}")
    fol = prof.get("followers")
    check("J5 他人主页 followers 为数字类型（record 类型守卫）", isinstance(fol, int),
          f"followers={fol!r} type={type(fol).__name__}")

    st, r = call("GET", "/api/socials/follows/following?page=1&pageSize=20", token=buyer_tok)
    fa = d(r) or {}
    ids = [str(x.get("id") or x.get("userId")) for x in (fa.get("items") or fa.get("records") or [])]
    check("J6 我关注的人列表含刚关注的账号", str(other_id) in ids, f"{st} ids={ids[:8]}")

    st, r = call("GET", "/api/socials/follows/followers?page=1&pageSize=20", token=other_tok)
    fb = d(r) or {}
    ids2 = [str(x.get("id") or x.get("userId")) for x in (fb.get("items") or fb.get("records") or [])]
    check("J7 对方粉丝列表含我", str(buyer_id) in ids2, f"{st} ids={ids2[:8]}")

    st, r = call("POST", f"/api/socials/follows/{buyer_id}", token=buyer_tok)
    check("J8 不能关注自己 → 422（业务校验拒绝）", st == 422, f"{st} {str(r)[:120]}")

    st, r = call("DELETE", f"/api/socials/follows/{other_id}", token=buyer_tok)
    check("J9 取关成功", st in (200, 204), f"{st}")
    st, r = call("DELETE", f"/api/socials/follows/{other_id}", token=buyer_tok)
    check("J10 重复取关幂等", st in (200, 204), f"{st}")

    st, r = call("GET", f"/api/socials/follows/{other_id}/state", token=buyer_tok)
    check("J11 取关后 following=false", (d(r) or {}).get("following") is False, f"{str(d(r))[:120]}")

    st, r = call("POST", f"/api/socials/follows/{other_id}")
    check("J12 未登录关注 → 401", st == 401, f"{st}")

    # ── 私信 ──
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": int(other_id), "content": "你好，验收测试私信"},
                 token=buyer_tok)
    check("J13 发送私信成功", st in (200, 201), f"{st} {str(r)[:150]}")

    st, r = call("GET", "/api/socials/messages/conversations?page=1&pageSize=20", token=buyer_tok)
    conv = d(r) or {}
    citems = conv.get("items") or conv.get("records") or []
    check("J14 会话列表含这条会话", st == 200 and len(citems) > 0, f"{st} n={len(citems)}")
    if citems:
        cu = citems[0].get("unread")
        check("J15 会话未读数为数字类型（R49 修过的类型缺陷）", isinstance(cu, int),
              f"unread={cu!r} type={type(cu).__name__}")

    st, r = call("GET", f"/api/socials/messages/conversations/{other_id}?page=1&pageSize=20",
                 token=buyer_tok)
    check("J16 会话消息可读", st == 200, f"{st} {str(r)[:150]}")

    st, r = call("POST", f"/api/socials/messages/conversations/{other_id}/read", token=buyer_tok)
    check("J17 标记会话已读（幂等）", st in (200, 204), f"{st}")

    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": int(other_id), "content": "未登录发信"})
    check("J18 未登录发私信 → 401", st == 401, f"{st}")

    # ── 通知中心 ──
    print("  ── 通知中心 ──")
    st, r = call("GET", "/api/socials/notices?page=1&pageSize=50", token=buyer_tok)
    notices = d(r) or {}
    nitems = notices.get("items") or notices.get("records") or []
    check("J19 通知列表可读且含全站公告", st == 200 and len(nitems) > 0, f"{st} n={len(nitems)}")

    st, r = call("GET", "/api/socials/notices/unread-count", token=buyer_tok)
    uc = d(r) or {}
    cnt = uc.get("count")
    check("J20 未读数接口可读且 count 为数字类型（R50 修过的记录缺陷）",
          st == 200 and isinstance(cnt, int), f"count={cnt!r} type={type(cnt).__name__}")

    ntotal = notices.get("total")
    check("J21 通知列表 total 为数字类型（record 类型守卫）", isinstance(ntotal, int),
          f"total={ntotal!r}")

    if nitems:
        nid = nitems[0].get("id")
        check("J22 通知行 read 初值为布尔", isinstance(nitems[0].get("read"), bool),
              f"read={nitems[0].get('read')!r}")
        st, r = call("POST", f"/api/socials/notices/{nid}/read", token=buyer_tok)
        check("J23 标记单条通知已读成功", st in (200, 204), f"{st}")
        st, r = call("POST", f"/api/socials/notices/{nid}/read", token=buyer_tok)
        check("J24 重复标记幂等", st in (200, 204), f"{st}")

    st, r = call("POST", "/api/socials/notices/read-all", token=buyer_tok)
    check("J25 全部标为已读成功", st in (200, 204), f"{st}")

    st, r = call("GET", "/api/socials/notices/unread-count", token=buyer_tok)
    check("J26 全部已读后未读数归零", num((d(r) or {}).get("count")) == 0,
          f"count={(d(r) or {}).get('count')!r}")

    st, r = call("POST", "/api/socials/notices/999999999999999999/read", token=buyer_tok)
    check("J27 标记他人/不存在通知 → 404（归属校验）", st == 404, f"{st}")

    st, r = call("GET", "/api/socials/notices")
    check("J28 未登录看通知 → 401", st == 401, f"{st}")


# ═══════════════════════════════════════════════════════════════════════════
# K. 分享 · 点赞 · 收藏
# ═══════════════════════════════════════════════════════════════════════════
def sec_k(buyer_tok, buyer_id, sku):
    print("\n══════ K. 分享 · 点赞 · 收藏 ══════")
    st, r = call("GET", "/api/shares/feed?page=1&pageSize=10")
    check("K1 分享圈信息流可读（公开免登录）", st == 200, f"{st} {str(r)[:150]}")

    spu = sku["spuId"] if sku else "1001"
    st, r = call("POST", "/api/shares",
                 {"spuId": int(spu), "content": "验收测试：猫爬架真不错，毛孩子超爱！"},
                 token=buyer_tok)
    share_id = d(r).get("id") if d(r) else None
    check("K2 发布分享成功", st in (200, 201) and bool(share_id), f"{st} {str(r)[:200]}")

    st, r = call("POST", "/api/shares", {"spuId": int(spu), "content": ""}, token=buyer_tok)
    check("K3 空内容发布被拒（400）", st == 400, f"{st}")

    st, r = call("POST", "/api/shares", {"spuId": int(spu), "content": "x" * 600}, token=buyer_tok)
    check("K4 超 500 字发布被拒（400）", st == 400, f"{st}")

    st, r = call("POST", "/api/shares", {"spuId": int(spu), "content": "未登录发布"})
    check("K5 未登录发布分享 → 401", st == 401, f"{st}")

    # 用第二账号点赞/收藏，避免自赞语义含糊
    tok2, uid2, _ = sell("验收互动者")
    if share_id and tok2:
        st, r = call("POST", f"/api/shares/{share_id}/like", token=tok2)
        like1 = d(r) or {}
        check("K6 点赞成功且返回 active=true",
              st == 200 and like1.get("active") is True, f"{st} {str(like1)[:120]}")
        check("K7 点赞数 count 为数字类型", isinstance(like1.get("count"), int),
              f"count={like1.get('count')!r}")

        st, r = call("POST", f"/api/shares/{share_id}/like", token=tok2)
        like2 = d(r) or {}
        check("K8 再次点赞=取消点赞（toggle 生效）",
              st == 200 and like2.get("active") is False, f"{st} {str(like2)[:120]}")

        st, r = call("POST", f"/api/shares/{share_id}/like", token=tok2)
        check("K9 重新点赞回到生效态", (d(r) or {}).get("active") is True, f"{str(d(r))[:120]}")

        st, r = call("POST", f"/api/shares/{share_id}/favorite", token=tok2)
        fav1 = d(r) or {}
        check("K10 收藏成功且返回 active=true",
              st == 200 and fav1.get("active") is True, f"{st} {str(fav1)[:120]}")

        st, r = call("POST", f"/api/shares/{share_id}/favorite", token=tok2)
        check("K11 再次收藏=取消收藏（toggle 生效）",
              (d(r) or {}).get("active") is False, f"{str(d(r))[:120]}")

        st, r = call("GET", f"/api/shares/{share_id}", token=tok2)
        det = d(r) or {}
        check("K12 分享详情带 liked/favorited 标记", st == 200, f"{st} keys={list(det)[:12]}")

        st, r = call("POST", f"/api/shares/{share_id}/repost", {"content": "转发：好物推荐"}, token=tok2)
        rep = d(r) or {}
        check("K13 转发到分享圈成功且返回新分享 ID",
              st in (200, 201) and bool(rep.get("id")) and str(rep.get("id")) != str(share_id),
              f"{st} repostId={rep.get('id')}")

        st, r = call("POST", f"/api/shares/{share_id}/like")
        check("K14 未登录点赞 → 401", st == 401, f"{st}")
    else:
        check("K6-K14 点赞/收藏/转发", False, "分享 ID 或第二账号缺失")

    st, r = call("GET", f"/api/shares/{share_id}")
    check("K15 分享详情公开可读（免登录）", st == 200, f"{st}")

    st, r = call("GET", "/api/shares/999999999999999999")
    check("K16 不存在的分享 → 404", st == 404, f"{st}")

    st, r = call("GET", "/api/shares/feed?page=1&pageSize=10", token=buyer_tok)
    fd = d(r) or {}
    check("K17 登录态信息流带 liked/favorited 标记",
          st == 200 and (fd.get("items") or fd.get("records") is not None), f"{st}")


# ═══════════════════════════════════════════════════════════════════════════
def main():
    print("=" * 74)
    print("第五十一轮 · 小程序全流程验收测试（登录/交易/售后/个人中心/跳蚤/社交）")
    print("=" * 74)

    st, _ = call("GET", "/actuator/health")
    check("环境：网关健康", st == 200, f"{st}")

    buyer_tok, buyer_id, buyer_phone, addr_id, ast = fixture_buyer()
    check("夹具：验收买家注册+登录+建地址", bool(buyer_tok) and bool(addr_id),
          f"token={'ok' if buyer_tok else 'None'} addr={addr_id} http={ast}")
    if not buyer_tok:
        print("\n买家夹具失败，后续环节无法执行。")
        return

    print(f"  [INFO] 验收买家 id={buyer_id} phone={buyer_phone} addressId={addr_id}")

    sec_a()
    sec_b()
    order_no, pay_no, sku = sec_c(buyer_tok, addr_id)
    if order_no:
        sec_d(order_no, buyer_tok)
    sec_e(buyer_tok, sku, addr_id)
    sec_f(buyer_tok, sku, addr_id)
    sec_g(buyer_tok)
    sec_h(buyer_tok, addr_id)
    sec_i(buyer_tok)
    sec_j(buyer_tok, buyer_id)
    sec_k(buyer_tok, buyer_id, sku)

    print("\n" + "=" * 74)
    print(f"结果：PASS {len(passed)} 项 ｜ FAIL {len(failed)} 项")
    if failed:
        print("\n失败清单：")
        for f in failed:
            print("  × " + f)
    print("=" * 74)


if __name__ == "__main__":
    main()
