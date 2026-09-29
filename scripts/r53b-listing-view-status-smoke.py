#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R53 热修二冒烟：闲置浏览量 + 我的闲置状态互斥。

用户报障：
  ② 闲置显示「0 人看过」，看过之后仍是 0（统计不生效）；
  ③ 同一件闲置同时出现在「审核中」和「在售」两个标签下（一件商品应只属于一个状态）。

根因：
  ② 后端只有 view_count 字段与出参，**全仓没有任何自增写入**（种子数据里的值永远不变）；
     前端三处（列表/我的闲置/详情）都直接读它。
  ③ 服务端 `status` 与 `auditStatus` 是正交两维，新发布条目天然是「ON_SALE + PENDING」；
     前端「在售」标签只传了 status=ON_SALE（没带 auditStatus=APPROVED），
     于是把还在审核的条目一起捞了出来。

本探针覆盖：
  V. 浏览量 —— 详情每次访问 +1 且本次响应即最新值；列表与我的闲置同步；
     未过审条目（详情 404）不计数；owner 详情（编辑回填）不计数。
  S. 状态互斥 —— 5 个标签（审核中/在售/审核失败/已下架/已售出）构成不重不漏的划分：
     每件闲置恰好命中 1 个；并固定反例「只传 status=ON_SALE 会同时命中两条」。

前置：14 容器健康；product 服务已重建。
用法：python scripts/r53b-listing-view-status-smoke.py
"""
import json
import random
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
PWD = "duoduo12345"
OPERATOR_PHONE = "13688774031"

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


def publish(tok, title):
    st, r = call("POST", "/api/listings", {
        "title": title, "description": "R53b 探针条目", "price": 4300,
        "conditionLevel": "GOOD", "city": "上海", "species": "cat",
    }, token=tok)
    return (d(r) or {}).get("id") if st in (200, 201) else None


def audit(op_tok, lid, action, reason=None):
    body = {"action": action}
    if reason:
        body["reason"] = reason
    return call("POST", f"/api/admin/listings/{lid}/audit", body, token=op_tok)


def mine_map(tok, params=""):
    """我的闲置 → {id: item}"""
    st, r = call("GET", f"/api/listings/mine/list?page=1&pageSize=50{params}", token=tok)
    return {str(it.get("id")): it for it in items_of(r)}


def ids_of(tok, params=""):
    return set(mine_map(tok, params).keys())


# ═══════════════════════════════════════════════════════════════════════════
# V. 浏览量
# ═══════════════════════════════════════════════════════════════════════════
def sec_v(op_tok, seller_tok):
    print("\n══════ V. 闲置浏览量累加与实时展示 ══════")

    lid = publish(seller_tok, "R53b 浏览量探针")
    if not lid:
        check("V0 发布探针条目", False, "发布失败")
        return None
    check("V0 发布成功（初始 viewCount=0）", mine_map(seller_tok).get(str(lid), {}).get("viewCount") == 0,
          f"{mine_map(seller_tok).get(str(lid))}")

    # 未过审（PENDING）：详情 404，且不得计数
    for _ in range(2):
        st, _r = call("GET", f"/api/listings/{lid}")
    check("V1 未过审详情 404（可见性规则不变）", st == 404, f"{st}")
    check("V1b 404 的访问不计数（仍为 0）",
          mine_map(seller_tok).get(str(lid), {}).get("viewCount") == 0,
          f"viewCount={mine_map(seller_tok).get(str(lid), {}).get('viewCount')}")

    # owner 详情（编辑回填）不计数
    call("GET", f"/api/listings/mine/{lid}", token=seller_tok)
    call("GET", f"/api/listings/mine/{lid}", token=seller_tok)
    check("V1c owner 详情（卖家自己看）不计数",
          mine_map(seller_tok).get(str(lid), {}).get("viewCount") == 0,
          f"viewCount={mine_map(seller_tok).get(str(lid), {}).get('viewCount')}")

    st, r = audit(op_tok, lid, "APPROVE")
    check("V2 运营审核通过", st in (200, 201), f"{st} {str(r)[:140]}")

    # 详情每次访问 +1，且本次响应就是最新值
    st, r = call("GET", f"/api/listings/{lid}")
    c1 = (d(r) or {}).get("card", {}).get("viewCount")
    check("V3 首次查看详情 +1（响应即最新值 1）", st == 200 and c1 == 1, f"{st} viewCount={c1}")

    st, r = call("GET", f"/api/listings/{lid}")
    c2 = (d(r) or {}).get("card", {}).get("viewCount")
    check("V4 第二次查看累加到 2", st == 200 and c2 == 2, f"{st} viewCount={c2}")

    # 列表侧同步（我的闲置 / 公开列表）
    mv = mine_map(seller_tok).get(str(lid), {}).get("viewCount")
    check("V5 我的闲置列表同步为 2", mv == 2, f"viewCount={mv}")

    st, r = call("GET", "/api/listings/search?page=1&pageSize=50")
    pub = next((it for it in items_of(r) if str(it.get("id")) == str(lid)), None)
    check("V6 公开列表同步为 2（与详情一致）",
          pub is not None and pub.get("viewCount") == 2,
          f"found={pub is not None} viewCount={(pub or {}).get('viewCount')}")

    # 再访问一次 → 3，证明是「每次累加」而不是一次性
    call("GET", f"/api/listings/{lid}")
    mv2 = mine_map(seller_tok).get(str(lid), {}).get("viewCount")
    check("V7 第三次查看累加到 3（每次访问都计）", mv2 == 3, f"viewCount={mv2}")
    return lid


# ═══════════════════════════════════════════════════════════════════════════
# S. 状态互斥
# ═══════════════════════════════════════════════════════════════════════════
# 与前端 FILTER_PARAMS 一一对应（改了前端必须同步改这里，探针即契约）
TABS = {
    "审核中": "&auditStatus=PENDING",
    "在售": "&status=ON_SALE&auditStatus=APPROVED",
    "审核失败": "&auditStatus=REJECTED",
    "已下架": "&status=OFF_SHELF&auditStatus=APPROVED",
    "已售出": "&status=SOLD&auditStatus=APPROVED",
}


def sec_s(op_tok, seller_tok):
    print("\n══════ S. 我的闲置状态互斥（一物一态）══════")

    # 造 4 条覆盖四种组合
    l_pending = publish(seller_tok, "R53b 状态-待审")          # ON_SALE + PENDING
    l_onsale = publish(seller_tok, "R53b 状态-在售")
    audit(op_tok, l_onsale, "APPROVE")                          # ON_SALE + APPROVED
    l_off = publish(seller_tok, "R53b 状态-已下架")
    audit(op_tok, l_off, "APPROVE")
    call("POST", f"/api/listings/{l_off}/status", {"action": "OFF_SHELF"}, token=seller_tok)
    l_rej = publish(seller_tok, "R53b 状态-驳回")
    audit(op_tok, l_rej, "REJECT", "R53b 探针驳回")

    if not all([l_pending, l_onsale, l_off, l_rej]):
        check("S0 四条探针数据齐备", False, f"{[l_pending, l_onsale, l_off, l_rej]}")
        return

    hits = {}
    for label, params in TABS.items():
        hits[label] = ids_of(seller_tok, params)

    cases = [
        ("审核中", l_pending),
        ("在售", l_onsale),
        ("已下架", l_off),
        ("审核失败", l_rej),
    ]
    for label, lid in cases:
        others = [t for t in TABS if t != label]
        check(f"S1 「{label}」命中对应条目",
              str(lid) in hits[label], f"hits={sorted(hits[label])}")
        check(f"S1b 「{label}」不含其它三条",
              all(str(lid) not in hits[o] for o in others),
              f"其它命中={ {o: str(lid) in hits[o] for o in others} }")

    # 一物一态：四条探针条目各自在 5 个标签里恰好命中 1 次
    for label, lid in cases:
        n = sum(1 for t in TABS if str(lid) in hits[t])
        check(f"S2 「{label}」条目在 5 个标签中恰好命中 1 个（互斥）", n == 1,
              f"命中 {n} 个：{[t for t in TABS if str(lid) in hits[t]]}")

    # 「已售出」标签语义（本轮无真实成交，验证它不误捞在售条目即可）
    check("S3 「已售出」不含在售条目", str(l_onsale) not in hits["已售出"], f"{sorted(hits['已售出'])}")

    # 反例固定：只传 status=ON_SALE（不带审核维度）会同时命中「待审」与「在售」
    loose = ids_of(seller_tok, "&status=ON_SALE")
    both = str(l_pending) in loose and str(l_onsale) in loose
    check("S4 反例固定：只传 status=ON_SALE 会同时命中待审条目（故前端必须带 auditStatus=APPROVED）",
          both, f"待审在={str(l_pending) in loose} 在售在={str(l_onsale) in loose}")


def main():
    print("=" * 74)
    print("R53 热修二冒烟：闲置浏览量 + 我的闲置状态互斥")
    print("=" * 74)

    st, r = call("POST", "/api/auth/login/token", {"phone": OPERATOR_PHONE, "password": PWD})
    op_tok = d(r).get("accessToken") if d(r) else None
    check("运营账号可用（审核链前提）", bool(op_tok), f"{st}")

    seller_tok = signup("R53b卖", real_name="王五")
    check("卖家账号可用（实名后可发布）", bool(seller_tok), "")

    if seller_tok:
        sec_v(op_tok, seller_tok)
        sec_s(op_tok, seller_tok)

    print("\n" + "=" * 74)
    print(f"结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print("  - " + f)
    print("=" * 74)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
