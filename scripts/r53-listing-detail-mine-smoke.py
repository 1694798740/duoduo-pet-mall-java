#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R53 冒烟：详情页卖家卡/LISTING 问一问 + 我的闲置状态体系（审核维度 + 重新提审）。

覆盖：
  M. LISTING 问一问 —— 服务端按闲置归属定人（不信前端）→ 咨询会话豁免非互关限制
     → 会话键带闲置段（lo:hi:l{listingId}）互不串线 → TEXT 后续落回同一会话
     → 自己问自己被拒 → 幽灵闲置 404
  A. 审核维度过滤 —— mine/list?auditStatus=（PENDING/APPROVED/REJECTED 白名单）
     → 与 status 维度正交 → 非法值 400（400 口径显式化，前端不会误传）
  R. 驳回→重提闭环 —— 运营驳回（status 同步落 OFF_SHELF）→ 卖家可见原因
     → 不再混入「在售」→ owner 详情回填 → resubmit（内容更新+回 PENDING+回 ON_SALE）
     → 运营通过 → 公开详情可见 + card.sellerId 下发

前置：14 容器健康；本轮 product/social 两服务已重建。
用法：python scripts/r53-listing-detail-mine-smoke.py
"""
import json
import random
import time
import urllib.parse
import urllib.request
import urllib.error

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
OPERATOR_PHONE = "13688774031"  # 历史运营账号（role=OPERATOR）

passed, failed = [], []


def check(name, ok, got=""):
    (passed if ok else failed).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else f"   实际={got}"))


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
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300]}
    except Exception as e:
        return None, {"error": str(e)}


def d(r):
    return (r or {}).get("data") if isinstance(r, dict) else None


def phone_new():
    return "139" + "".join(random.choice("0123456789") for _ in range(8))


def signup(prefix, real_name=None):
    """注册 + 登录，可选实名（实名后才能发布闲置）。返回 (token, userId, phone)"""
    p = phone_new()
    call("POST", "/api/auth/register", {"phone": p, "password": PWD, "nickname": prefix})
    st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
    tok = d(r).get("accessToken") if d(r) else None
    uid = d(r).get("user", {}).get("id") if d(r) else None
    if real_name:
        st, rn = call("POST", "/api/users/me/realname",
                      {"realName": real_name, "idCard": "110101199001011234"}, token=tok)
        if st not in (200, 201):
            print(f"    [WARN] 实名提交失败 {st} {str(rn)[:150]}")
        # 实名后必须重新登录：realNameVerified 是写进令牌的 claim，
        # 老令牌里仍是 false（这是设计如此，不是缺陷）
        st, r = call("POST", "/api/auth/login/token", {"phone": p, "password": PWD})
        tok = d(r).get("accessToken") if d(r) else None
    return tok, uid, p


def page_items(r):
    """分页响应取列表：本域分页字段名是 items（不是 records）"""
    data = d(r)
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("items") or data.get("records") or []
    return []


def publish_listing(tok, title, price=8800):
    st, r = call("POST", "/api/listings", {
        "title": title, "description": "R53 探针自测条目", "price": price,
        "conditionLevel": "GOOD", "city": "上海", "species": "cat",
    }, token=tok)
    return st, r, (d(r) or {}).get("id") if st in (200, 201) else None


# ═══════════════════════════════════════════════════════════════════════════
# A. 审核维度过滤
# ═══════════════════════════════════════════════════════════════════════════
def sec_a():
    print("\n══════ A. 审核维度过滤（mine/list auditStatus） ══════")

    seller_tok, seller_id, _ = signup("R53卖A", real_name="张三")
    listing_id = None
    if seller_tok:
        st, r, listing_id = publish_listing(seller_tok, "R53-A 审核过滤探针")
        ok = st in (200, 201) and (d(r) or {}).get("auditStatus") == "PENDING"
        check("A0 发布落 PENDING（回归）", ok, f"{st} {str(r)[:150]}")

    # —— A1 auditStatus=PENDING 可见新发布条目 ——
    if listing_id:
        st, r = call("GET", "/api/listings/mine/list?auditStatus=PENDING", token=seller_tok)
        ids = [x.get("id") for x in page_items(r)] if st == 200 else []
        check("A1 auditStatus=PENDING 含新发布条目", listing_id in ids, f"{st} ids={ids[:3]}")

    # —— A2 status 与 auditStatus 正交组合 ——
    if listing_id:
        # 发布即 ON_SALE：PENDING 条目也在 status=ON_SALE 名下（历史行为保持）
        st, r = call("GET", "/api/listings/mine/list?status=ON_SALE&auditStatus=PENDING", token=seller_tok)
        ids = [x.get("id") for x in page_items(r)] if st == 200 else []
        check("A2 status=ON_SALE + auditStatus=PENDING 组合过滤", listing_id in ids, f"{st} ids={ids[:3]}")

    # —— A3 非法 auditStatus 显式 400（文案可读）——
    st, r = call("GET", "/api/listings/mine/list?auditStatus=BOGUS", token=seller_tok)
    ok = st == 400 and "auditStatus" in json.dumps(r, ensure_ascii=False)
    check("A3 非法 auditStatus 被拒（400 + 文案含字段名）", ok, f"{st} {str(r)[:150]}")

    # —— A4 status 非法值仍 400（回归）——
    st, r = call("GET", "/api/listings/mine/list?status=PENDING", token=seller_tok)
    check("A4 status=PENDING 仍被拒（400，审核维度走 auditStatus）", st == 400, f"{st}")

    return seller_tok, seller_id, listing_id


# ═══════════════════════════════════════════════════════════════════════════
# M. LISTING 问一问
# ═══════════════════════════════════════════════════════════════════════════
def sec_m(seller_tok, seller_id, listing_id):
    print("\n══════ M. LISTING 问一问（闲置咨询会话） ══════")

    buyer_tok, buyer_id, _ = signup("R53买M")

    # —— M1 匿名发 LISTING 被拒 ——
    st, r = call("POST", "/api/socials/messages",
                 {"receiverId": seller_id, "bizType": "LISTING", "bizId": listing_id})
    check("M1 匿名发闲置咨询被拒（401）", st == 401, f"{st}")

    # —— M2 服务端定人：receiverId 传错人也不影响，收件人=闲置归属卖家 ——
    if listing_id and buyer_tok and seller_id:
        st, r = call("POST", "/api/socials/messages", {
            "receiverId": 1,  # 前端占位传值（会被忽略）
            "bizType": "LISTING", "bizId": listing_id, "content": "R53 还在吗？",
        }, token=buyer_tok)
        view = d(r) or {}
        ok = st in (200, 201) and str(view.get("receiverId")) == str(seller_id)
        check("M2 收件人=闲置归属卖家（服务端定人，忽略前端占位）", ok,
              f"{st} receiverId={view.get('receiverId')} sellerId={seller_id}")

    # —— M3 会话读取：listingId 进对会话，consulting=true ——
    if buyer_tok and seller_id and listing_id:
        st, r = call("GET", f"/api/socials/messages/conversations/{seller_id}?listingId={listing_id}",
                     token=buyer_tok)
        page = d(r) or {}
        msgs = page.get("items") or []
        has_listing_msg = any(m.get("bizType") == "LISTING" for m in msgs)
        check("M3 会话读取带闲置上下文（200 + consulting + listingId）",
              st == 200 and page.get("consulting") is True
              and str(page.get("listingId")) == str(listing_id) and has_listing_msg,
              f"{st} consulting={page.get('consulting')} listingId={page.get('listingId')} msgs={len(msgs)}")

    # —— M4 咨询会话豁免非互关一条限制：第二条 TEXT 也能发 ——
    if buyer_tok and seller_id and listing_id:
        st, r = call("POST", "/api/socials/messages", {
            "receiverId": seller_id, "bizType": "TEXT",
            "listingId": listing_id, "content": "R53 第二条（豁免验证）",
        }, token=buyer_tok)
        check("M4 咨询会话内非互关可继续发 TEXT（豁免一条限制）", st in (200, 201),
              f"{st} {str(r)[:150]}")

    # —— M5 会话键隔离：闲置会话 ≠ 主会话 ——
    if buyer_tok and seller_id:
        st, r = call("GET", f"/api/socials/messages/conversations/{seller_id}", token=buyer_tok)
        page = d(r) or {}
        # 主会话没发过消息：items 应为空（不会串到闲置会话的内容）
        check("M5 主会话与闲置咨询会话互不串线",
              st == 200 and not (page.get("items") or [])
              and page.get("listingId") is None,
              f"{st} items={len(page.get('items') or [])} listingId={page.get('listingId')}")

    # —— M6 自己问自己被拒 ——
    if seller_tok and listing_id:
        st, r = call("POST", "/api/socials/messages", {
            "receiverId": seller_id, "bizType": "LISTING", "bizId": listing_id,
        }, token=seller_tok)
        # 业务规则违例走 422 BUSINESS_RULE（400 是参数校验，422 是规则拒绝）
        ok = st == 422 and "自己" in json.dumps(r, ensure_ascii=False)
        check("M6 自己咨询自己的闲置被拒（422 业务规则）", ok, f"{st} {str(r)[:150]}")

    # —— M7 幽灵闲置 404 ——
    if buyer_tok:
        st, r = call("POST", "/api/socials/messages", {
            "receiverId": seller_id, "bizType": "LISTING", "bizId": 900000000000,
        }, token=buyer_tok)
        check("M7 幽灵闲置咨询被拒（404）", st == 404, f"{st} {str(r)[:120]}")

    # —— M8 productId 与 listingId 互斥 ——
    if buyer_tok and seller_id and listing_id:
        st, r = call("POST", "/api/socials/messages", {
            "receiverId": seller_id, "bizType": "TEXT",
            "productId": 3001, "listingId": listing_id, "content": "冲突",
        }, token=buyer_tok)
        check("M8 productId/listingId 同时携带被拒（400）", st == 400, f"{st}")

    return buyer_tok


# ═══════════════════════════════════════════════════════════════════════════
# R. 驳回 → 重提闭环
# ═══════════════════════════════════════════════════════════════════════════
def sec_r(seller_tok, seller_id, listing_id):
    print("\n══════ R. 驳回 → 修改 → 重新提审闭环 ══════")

    # 运营登录（历史 OPERATOR 账号；登录失败则整段跳过并明说）
    op_tok = None
    st, r = call("POST", "/api/auth/login/token", {"phone": OPERATOR_PHONE, "password": PWD})
    if st == 200 and d(r):
        op_tok = d(r).get("accessToken")
    if not op_tok:
        print("    [WARN] 运营账号登录失败，R 段跳过（不影响 M/A 段结论）")
        return
    check("R0 运营账号登录成功", True)

    buyer_tok, _, _ = signup("R53买R")

    # —— R1 发布第二件用于闭环 ——
    st, r, lid = publish_listing(seller_tok, "R53-R 闭环探针", price=6600)
    check("R1 发布第二条（闭环主体）", st in (200, 201) and lid, f"{st} {str(r)[:150]}")
    if not lid:
        return

    # —— R2 卖家视角详情（owner）可读自己未过审条目 ——
    st, r = call("GET", f"/api/listings/mine/{lid}", token=seller_tok)
    view = d(r) or {}
    check("R2 owner 详情可读未过审条目（200 + 全集字段）",
          st == 200 and view.get("title") == "R53-R 闭环探针"
          and "description" in view and "images" in view,
          f"{st} {str(r)[:180]}")

    # —— R3 owner 详情归属越权：买家读卖家的 → 403 ——
    if buyer_tok:
        st, r = call("GET", f"/api/listings/mine/{lid}", token=buyer_tok)
        check("R3 owner 详情非本人被拒（403）", st == 403, f"{st} {str(r)[:120]}")

    # —— R4 未过审条目公开详情 404（回归：可见性规则未被新端点破坏）——
    st, r = call("GET", f"/api/listings/{lid}")
    check("R4 未过审公开详情仍 404", st == 404, f"{st}")

    # —— R5 运营驳回（必填原因）——
    reason = "R53 驳回探针：图片含联系方式"
    st, r = call("POST", f"/api/admin/listings/{lid}/audit",
                 {"action": "REJECT", "reason": reason}, token=op_tok)
    view = d(r) or {}
    ok = st in (200, 201) and view.get("auditStatus") == "REJECTED" and view.get("auditReason") == reason
    check("R5 运营驳回成功（auditStatus=REJECTED + 原因落库）", ok, f"{st} {str(r)[:180]}")

    # —— R6 驳回后 status 同步落 OFF_SHELF（新语义：不混入「在售」）——
    st, r = call("GET", "/api/listings/mine/list?status=ON_SALE", token=seller_tok)
    ids_on_sale = [x.get("id") for x in page_items(r)] if st == 200 else []
    st2, r2 = call("GET", "/api/listings/mine/list?auditStatus=REJECTED", token=seller_tok)
    rejected = next((x for x in page_items(r2) if str(x.get("id")) == str(lid)), None) if st2 == 200 else None
    check("R6 驳回条目不再出现在「在售」筛选",
          st == 200 and str(lid) not in [str(i) for i in ids_on_sale],
          f"{st} on_sale={ids_on_sale[:3]}")
    check("R6b 驳回条目出现在「审核失败」筛选（status=OFF_SHELF + 原因）",
          rejected is not None and rejected.get("status") == "OFF_SHELF"
          and rejected.get("auditReason") == reason,
          f"item={str(rejected)[:180]}")

    # —— R7 PENDING 状态不能重提 ——
    st, r, lid2 = publish_listing(seller_tok, "R53-R2 重提挡板", price=3300)
    if lid2:
        st2, r2 = call("POST", f"/api/listings/{lid2}/resubmit",
                       {"title": "R53-R2 试图重提", "price": 3300}, token=seller_tok)
        # 业务规则违例走 422 BUSINESS_RULE（400 是参数校验，422 是规则拒绝）
        ok = st2 == 422 and "审核" in json.dumps(r2, ensure_ascii=False)
        check("R7 PENDING 条目重提被拒（422 业务规则）", ok, f"{st2} {str(r2)[:150]}")

    # —— R8 重提：内容更新 + 回 PENDING + 回 ON_SALE ——
    new_title = "R53-R 闭环探针（已修改）"
    st, r = call("POST", f"/api/listings/{lid}/resubmit", {
        "title": new_title, "description": "R53 修改后的描述",
        "price": 5500, "conditionLevel": "LIKE_NEW", "city": "上海",
        "species": "cat", "images": [],
    }, token=seller_tok)
    view = d(r) or {}
    ok = (st in (200, 201) and view.get("auditStatus") == "PENDING"
          and view.get("status") == "ON_SALE" and view.get("title") == new_title)
    check("R8 重提成功（内容更新 + auditStatus=PENDING + status=ON_SALE）", ok,
          f"{st} {str(r)[:200]}")

    # —— R9 重提后驳回原因清空、出现在 PENDING 筛选 ——
    st, r = call("GET", "/api/listings/mine/list?auditStatus=PENDING", token=seller_tok)
    pend = next((x for x in page_items(r) if str(x.get("id")) == str(lid)), None) if st == 200 else None
    check("R9 重提后条目回到「审核中」筛选", pend is not None, f"{st} {str(r)[:150]}")

    # —— R10 运营通过 → 公开详情可见 + card.sellerId 下发（卖家卡数据源）——
    st, r = call("POST", f"/api/admin/listings/{lid}/audit", {"action": "APPROVE"}, token=op_tok)
    check("R10 运营通过成功", st in (200, 201) and (d(r) or {}).get("auditStatus") == "APPROVED",
          f"{st} {str(r)[:150]}")

    st, r = call("GET", f"/api/listings/{lid}")
    card = (d(r) or {}).get("card") or {}
    check("R11 过审后公开详情可见（200 + card.sellerId）",
          st == 200 and str(card.get("sellerId")) == str(seller_id),
          f"{st} sellerId={card.get('sellerId')}")

    # —— R12 已过审条目重提被拒 ——
    st, r = call("POST", f"/api/listings/{lid}/resubmit",
                 {"title": "再改一次", "price": 5500}, token=seller_tok)
    ok = st == 422 and "无需重新提交" in json.dumps(r, ensure_ascii=False)
    check("R12 APPROVED 条目重提被拒（422 业务规则）", ok, f"{st} {str(r)[:150]}")

    # —— R13 非运营不能审核（回归）——
    if seller_tok:
        st, r = call("POST", f"/api/admin/listings/{lid}/audit", {"action": "APPROVE"}, token=seller_tok)
        check("R13 非运营不能审核（401/403）", st in (401, 403), f"{st}")


def main():
    print("=== R53 冒烟：详情页卖家卡 / LISTING 问一问 / 我的闲置状态体系 ===")
    t0 = time.time()
    seller_tok, seller_id, listing_id = sec_a()
    if seller_tok and listing_id:
        sec_m(seller_tok, seller_id, listing_id)
        sec_r(seller_tok, seller_id, listing_id)
    else:
        print("    [WARN] A 段未取得发布条目，M/R 段跳过")
    print(f"\n=== 结果：{len(passed)} PASS / {len(failed)} FAIL（{time.time()-t0:.1f}s） ===")
    if failed:
        print("失败项：")
        for f in failed:
            print("  - " + f)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
