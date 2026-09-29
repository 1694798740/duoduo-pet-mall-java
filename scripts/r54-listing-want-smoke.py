#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R54 冒烟：闲置「想要人数」（按咨询去重）与「浏览次数」（打开/刷新累加）。

用户口径（第五十四轮）：
  * 「多少次浏览」= 页面**每被打开或刷新一次**即累计一次，同一用户多次查看可累加；
  * 「多少人想要」= 仅按**咨询行为**统计 —— 买家首次向卖家发送咨询消息计 1 人，
    同一买家后续再发不重复计数（每位买家只记一次）。

两个口径必须分开，不能互相顶替：浏览是「被打开的次数」，想要是「有意向的人」。
拿浏览当想要，卖家会把「路过看一眼」也当成购买意向，据此让价或备货都会失真。

覆盖：
  W. 想要人数 —— 首次咨询 +1 / 同一买家重复咨询不重复计 / 同会话后续闲聊不计 /
     不同买家各计一次 / 详情·列表·我的闲置三处一致 / 内部端点的幂等与边界。
  V. 浏览次数回归 —— 连续访问累加（同一用户可累加）、与想要互不干扰。

前置：14 容器健康；product / social 镜像已重建；已执行 docs/sql/incremental/18-r54-listing-want.sql。
用法：python scripts/r54-listing-want-smoke.py
"""
import json
import os
import random
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
PRODUCT_DIRECT = "http://127.0.0.1:8082"  # 内部端点直连：验证它不在网关暴露，且过滤器生效
INTERNAL_TOKEN = os.environ.get("INTERNAL_TOKEN", "duoduo-local-dev-internal-token")
PWD = "duoduo12345"
OPERATOR_PHONE = "13688774031"

passed, failed = [], []


def check(name, ok, got=""):
    (passed if ok else failed).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else "   实际=" + str(got)))


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
        raw = e.read().decode("utf-8")
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


def signup(prefix, real_name=None):
    p = phone_new()
    call("POST", "/api/auth/register", {"phone": p, "password": PWD, "nickname": prefix})
    st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
    tok = d(r).get("accessToken") if d(r) else None
    if real_name:
        call("POST", "/api/users/me/realname",
             {"realName": real_name, "idCard": "110101199001011234"}, token=tok)
        st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
        tok = d(r).get("accessToken") if d(r) else None
    return tok


def login_op():
    st, r = call("POST", "/api/auth/login/token", {"phone": OPERATOR_PHONE, "password": PWD})
    return d(r).get("accessToken") if d(r) else None


def publish(tok, title):
    st, r = call("POST", "/api/listings", {
        "title": title, "description": "R54 探针条目", "price": 5200,
        "conditionLevel": "GOOD", "city": "上海", "species": "cat",
    }, token=tok)
    return (d(r) or {}).get("id") if st in (200, 201) else None


def audit(op_tok, lid, action, reason=None):
    body = {"action": action}
    if reason:
        body["reason"] = reason
    return call("POST", f"/api/admin/listings/{lid}/audit", body, token=op_tok)


def detail_card(lid):
    """公开详情卡（注意：每调用一次都会给浏览次数 +1，断言浏览数时须计入本调用）"""
    st, r = call("GET", f"/api/listings/{lid}")
    return st, (d(r) or {}).get("card") or {}


def mine_item(tok, lid):
    st, r = call("GET", "/api/listings/mine/list?page=1&pageSize=50", token=tok)
    return next((it for it in items_of(r) if str(it.get("id")) == str(lid)), None)


def public_item(lid):
    st, r = call("GET", "/api/listings/search?page=1&pageSize=50")
    return next((it for it in items_of(r) if str(it.get("id")) == str(lid)), None)


def ask_listing(buyer_tok, seller_id, lid, content="这件还在吗？"):
    """闲置咨询（bizType=LISTING）：收件人由服务端按闲置归属定，这里传 sellerId 仅作占位"""
    return call("POST", "/api/socials/messages", {
        "receiverId": str(seller_id), "bizType": "LISTING", "bizId": str(lid), "content": content,
    }, token=buyer_tok)


def text_in_listing_conv(buyer_tok, seller_id, lid, content="再聊两句"):
    """同会话内后续闲聊（bizType=TEXT 显式带 listingId）：不应再计想要"""
    return call("POST", "/api/socials/messages", {
        "receiverId": str(seller_id), "bizType": "TEXT", "content": content, "listingId": str(lid),
    }, token=buyer_tok)


def want_direct(lid, buyer_id, with_token=True):
    """内部端点直连（不经网关）：验证幂等返回值与凭据保护"""
    headers = {"X-Internal-Token": INTERNAL_TOKEN} if with_token else {}
    return call("POST", f"/api/listings/internal/{lid}/want?buyerId={buyer_id}",
                base=PRODUCT_DIRECT, headers=headers)


# ═══════════════════════════════════════════════════════════════════════════
# W. 想要人数：按咨询行为去重
# ═══════════════════════════════════════════════════════════════════════════
def sec_w(op_tok, seller_tok):
    print("\n══════ W. 「想要人数」按咨询行为去重统计 ══════")

    lid = publish(seller_tok, "R54 想要人数探针")
    if not lid:
        check("W0 发布探针条目", False, "发布失败")
        return None
    check("W0 发布成功", True)

    st, r = audit(op_tok, lid, "APPROVE")
    check("W0b 运营审核通过", st in (200, 201), f"{st} {str(r)[:120]}")

    st, card = detail_card(lid)
    seller_id = card.get("sellerId")
    check("W1 初始想要人数为 0", st == 200 and card.get("wantCount") == 0,
          f"{st} wantCount={card.get('wantCount')}")

    a = signup("r54买家甲")
    b = signup("r54买家乙")
    check("W1b 两个买家账号就绪", bool(a) and bool(b))

    # 首次咨询 → 计 1 人
    st, r = ask_listing(a, seller_id, lid)
    check("W2 买家A 首次咨询成功（LISTING）", st in (200, 201), f"{st} {str(r)[:140]}")
    st, card = detail_card(lid)
    check("W2b 想要人数变为 1", card.get("wantCount") == 1, f"wantCount={card.get('wantCount')}")

    # 同一买家再点一次「我想要」→ 不得重复计数
    st, r = ask_listing(a, seller_id, lid, "我再问一次")
    check("W3 买家A 重复咨询仍成功", st in (200, 201), f"{st}")
    st, card = detail_card(lid)
    check("W3b ★同一买家重复咨询不重复计数（仍为 1）", card.get("wantCount") == 1,
          f"wantCount={card.get('wantCount')}")

    # 同会话内后续闲聊（TEXT）→ 也不算新的想要
    st, r = text_in_listing_conv(a, seller_id, lid)
    check("W4 同会话后续闲聊成功（TEXT）", st in (200, 201), f"{st} {str(r)[:140]}")
    st, card = detail_card(lid)
    check("W4b ★同会话后续消息不计入想要（仍为 1）", card.get("wantCount") == 1,
          f"wantCount={card.get('wantCount')}")

    # 另一位买家首次咨询 → 各计一次
    st, r = ask_listing(b, seller_id, lid, "我也想要")
    check("W5 买家B 首次咨询成功", st in (200, 201), f"{st} {str(r)[:140]}")
    st, card = detail_card(lid)
    check("W5b 不同买家各计一次（变为 2）", card.get("wantCount") == 2,
          f"wantCount={card.get('wantCount')}")

    # 三处视图口径一致
    want_detail = card.get("wantCount")
    mv = mine_item(seller_tok, lid) or {}
    pv = public_item(lid) or {}
    check("W6 我的闲置 wantCount 一致", mv.get("wantCount") == want_detail,
          f"mine={mv.get('wantCount')} detail={want_detail}")
    check("W6b 公开列表 wantCount 一致", pv.get("wantCount") == want_detail,
          f"public={pv.get('wantCount')} detail={want_detail}")

    # 内部端点：凭据保护 + 幂等返回值
    st, r = want_direct(lid, 999999999, with_token=False)
    check("W7 内部端点无凭据被拒（403）", st == 403, f"{st} {str(r)[:120]}")

    buyer_c = random.randint(10 ** 17, 9 * 10 ** 17)
    st1, r1 = want_direct(lid, buyer_c)
    st2, r2 = want_direct(lid, buyer_c)
    check("W8 内部端点首次上报 counted=true", st1 == 200 and (d(r1) or {}).get("counted") is True,
          f"{st1} {str(r1)[:140]}")
    check("W9 ★内部端点重复上报 counted=false（幂等）",
          st2 == 200 and (d(r2) or {}).get("counted") is False, f"{st2} {str(r2)[:140]}")
    st, card = detail_card(lid)
    check("W9b 重复上报后想要人数仍为 3（1+1+1）", card.get("wantCount") == 3,
          f"wantCount={card.get('wantCount')}")

    # 边界：不存在的闲置
    st, r = want_direct(1, buyer_c)
    check("W10 不存在的闲置上报 → 404", st == 404, f"{st} {str(r)[:140]}")

    # 返回末次卡片（调用方可对比两个计数）
    return card


# ═══════════════════════════════════════════════════════════════════════════
# V. 浏览次数：同一用户可累加（回归 + 与想要互不干扰）
# ═══════════════════════════════════════════════════════════════════════════
def sec_v(op_tok, seller_tok):
    print("\n══════ V. 浏览次数：打开/刷新累加（同一用户可累加） ══════")

    lid = publish(seller_tok, "R54 浏览次数探针")
    if not lid:
        check("V0 发布探针条目", False, "发布失败")
        return
    audit(op_tok, lid, "APPROVE")

    a = signup("r54浏览买家")
    seen = []
    seller_id = None
    for i in range(3):
        st, card = detail_card(lid)
        if seller_id is None and st == 200:
            seller_id = card.get("sellerId")
        seen.append(card.get("viewCount") if st == 200 else None)
    check("V1 连续三次打开详情 → 1/2/3（同一用户可累加）", seen == [1, 2, 3], f"实际={seen}")

    mv = mine_item(seller_tok, lid) or {}
    check("V2 我的闲置浏览次数同步为 3", mv.get("viewCount") == 3, f"viewCount={mv.get('viewCount')}")

    # 咨询行为走的是另一条链路：不碰浏览次数，只碰想要人数
    ask_listing(a, seller_id, lid)
    st, card = detail_card(lid)
    check("V3 咨询不增加浏览次数（浏览只按打开次数累加 → 4）",
          card.get("viewCount") == 4, f"viewCount={card.get('viewCount')}")
    check("V3b 同一次访问里想要人数为 1（两个口径互不干扰）", card.get("wantCount") == 1,
          f"wantCount={card.get('wantCount')}")


def main():
    print("=== R54 闲置「想要人数」+「浏览次数」冒烟 ===")
    op_tok = login_op()
    st, r = call("GET", "/api/listings/search?page=1&pageSize=1")
    check("前置：网关可达", st == 200, f"{st}")
    check("前置：运营账号登录", bool(op_tok))

    seller_tok = signup("r54卖家", real_name="卖家小六")
    check("前置：卖家账号就绪（已实名）", bool(seller_tok))

    if not op_tok or not seller_tok:
        print("\n前置失败，终止")
        return

    sec_w(op_tok, seller_tok)
    sec_v(op_tok, seller_tok)

    print("\n" + "═" * 74)
    print(f"结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print("  - " + f)
    print("═" * 74)
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
