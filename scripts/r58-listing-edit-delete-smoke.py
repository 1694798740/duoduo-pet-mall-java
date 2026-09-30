#!/usr/bin/env python3
"""闲置编辑与删除（B-14）专项探针 · 第五十八轮

B-14 的原始问题：卖家发布了闲置之后，既改不了内容也删不掉 ——
发布页「我的发布」里只有上下架，写错一个价格就只能任它挂着，
或者重新发一条重复的。编辑/删除一直是缺口（第五十三轮只补了「驳回后重提」）。

本探针验证：
  A. 编辑：内容变更 → 回到待审核（审核结论针对旧内容，不能继续挂 APPROVED）；
  B. 编辑后不可见、重新审核通过后可见且内容已更新；
  C. 驳回件编辑后恢复在售（否则通过审核仍不可见且无入口放出来）；
  D. 删除是软删：公开详情 404、公开列表查不到、「我的发布」查不到，行仍在库里；
  E. 越权与匿名：非本人 403；**匿名 401**（三段路径必须落在网关白名单外）；
  F. 已售出不可删（交易凭证留存）。

用法：python scripts/r58-listing-edit-delete-smoke.py
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
CATEGORY_ID = int(os.environ.get("R58_CATEGORY_ID", "1"))
# 发布闲置要求实名，而种子账号里没有「既实名、密码又已知」的账号 ——
# 所以这里现注册两个临时账号并直接在库里补实名，用完删掉（不污染种子数据）。
SUFFIX = str(int(os.environ.get("R58_SUFFIX") or __import__("time").time()))
SELLER_PHONE = "139" + SUFFIX[-8:]                              # 11 位
OTHER_PHONE = "138" + str(int(SUFFIX) + 1234567)[-8:]           # 11 位，且与卖家不同

PASSED, FAILED = [], []


def check(name, ok, got=""):
    (PASSED if ok else FAILED).append(name)
    print(("  [PASS] " if ok else "  [FAIL] ") + name + ("" if ok else "   实际=" + str(got)[:240]))


JARS = {}


def call(method, path, body=None, token=None, timeout=25):
    data = json.dumps(body).encode() if body is not None else None
    opener = urllib.request.build_opener()
    if token is not None:
        opener.add_handler(urllib.request.HTTPCookieProcessor(JARS.setdefault(token, CookieJar())))
    req = urllib.request.Request(GW + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
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
    key = "sess:" + phone
    JARS[key] = CookieJar()
    st, _ = call("POST", "/api/auth/login", {"phone": phone, "password": PWD}, token=key)
    return key if st == 200 else None


def register_real_named(phone, nickname):
    """注册一个**已实名**的临时账号（发布闲置的前置条件），返回会话 key"""
    key = "sess:" + phone
    JARS[key] = CookieJar()
    st, body = call("POST", "/api/auth/register",
                    {"phone": phone, "password": PWD, "nickname": nickname}, token=key)
    if st != 200:
        return None
    uid = (body.get("data") or {}).get("id")
    sql(f"UPDATE duoduo_user.t_user SET real_name='探针实名', id_card_masked='440***********001X' "
        f"WHERE id = {uid}")
    # 重新登录：令牌里的实名标志是登录时从库里读的，改库后必须换一枚新令牌
    return login(phone)


def sql(q):
    out = subprocess.run(["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root",
                          "-N", "-B", "-e", q], capture_output=True, text=True)
    return out.stdout.strip()


def publish(token, title, price):
    return call("POST", "/api/listings", {
        "title": title, "description": "探针用闲置，验证编辑与删除链路",
        "categoryId": CATEGORY_ID, "price": price, "originalPrice": price * 2,
        "conditionLevel": "LIKE_NEW", "city": "深圳", "images": [],
        "species": "猫", "breed": "英短",
    }, token=token)


def approve(admin, listing_id):
    return call("POST", f"/api/admin/listings/{listing_id}/audit", {"action": "APPROVE"}, token=admin)


def in_public_search(listing_id):
    """公开列表里能不能翻到这条（最多翻 10 页，够覆盖种子量）"""
    for page in range(1, 11):
        st, body = call("GET", f"/api/listings/search?page={page}&pageSize=50")
        if st != 200:
            return False
        for row in (body.get("data") or {}).get("list", []):
            if str(row.get("id")) == str(listing_id):
                return True
        if page >= ((body.get("data") or {}).get("pages") or 1):
            break
    return False


def main():
    print("=" * 72)
    print("闲置编辑与删除（B-14）专项探针 @ " + GW)
    print("=" * 72)

    admin = login(ADMIN_PHONE)
    seller = register_real_named(SELLER_PHONE, "探针-卖家")
    other = register_real_named(OTHER_PHONE, "探针-旁人")
    check("P1 运营 / 卖家 / 旁人三个账号均可登录（探针前置）",
          admin is not None and seller is not None and other is not None,
          (admin, seller, other))
    if not (admin and seller and other):
        return 1

    created = []

    # ---------- A. 编辑 → 回到待审核 ----------
    print("\n[A] 编辑内容 → 回到待审核")
    title_v1 = "探针闲置-初版标题"
    st, body = publish(seller, title_v1, 12000)
    check("A1 发布闲置成功", st == 200, (st, body))
    if st != 200:
        return 1
    listing_id = (body.get("data") or {}).get("id")
    created.append(listing_id)
    print(f"  样本闲置 id={listing_id}")

    st, _ = approve(admin, listing_id)
    check("A2 运营审核通过", st == 200, st)
    st, body = call("GET", f"/api/listings/{listing_id}")
    check("A3 审核通过后公开详情可读", st == 200 and
          ((body.get("data") or {}).get("card") or {}).get("title") == title_v1, (st, str(body)[:160]))

    title_v2 = "探针闲置-改后标题"
    st, body = call("POST", f"/api/listings/{listing_id}/edit", {
        "title": title_v2, "description": "改过的内容",
        "categoryId": CATEGORY_ID, "price": 9900, "originalPrice": 20000,
        "conditionLevel": "GOOD", "city": "广州", "images": [],
        "species": "猫", "breed": "英短",
    }, token=seller)
    check("A4 编辑返回 200", st == 200, (st, body))
    data = (body.get("data") or {}) if st == 200 else {}
    check("A5 编辑后审核态回到 PENDING", data.get("auditStatus") == "PENDING", data.get("auditStatus"))
    check("A6 编辑后标题已更新", data.get("title") == title_v2, data.get("title"))
    check("A7 编辑后价格已更新", data.get("price") == 9900, data.get("price"))

    # ---------- B. 编辑后不可见，重审通过后可见 ----------
    print("\n[B] 重新审核期间的可见性")
    st, _ = call("GET", f"/api/listings/{listing_id}")
    check("B1 待审核期间公开详情 404（不能继续挂旧结论）", st == 404, st)
    check("B2 待审核期间不在公开列表", not in_public_search(listing_id), "仍在列表")
    st, _ = approve(admin, listing_id)
    check("B3 重新审核通过", st == 200, st)
    st, body = call("GET", f"/api/listings/{listing_id}")
    check("B4 重新通过后可见且是新内容", st == 200 and
          ((body.get("data") or {}).get("card") or {}).get("title") == title_v2, (st, str(body)[:160]))

    # ---------- C. 越权与匿名 ----------
    print("\n[C] 越权与匿名（路径形状即安全边界）")
    st, _ = call("POST", f"/api/listings/{listing_id}/edit",
                 {"title": "旁人改标题", "price": 1}, token=other)
    check("C1 非本人编辑 403", st == 403, st)
    st, _ = call("POST", f"/api/listings/{listing_id}/delete", token=other)
    check("C2 非本人删除 403", st == 403, st)
    # 关键：白名单是 /api/listings/*（两段、不区分方法）。若把编辑/删除写成两段路径，
    # 这两个请求会变成匿名可调 —— 那等于开放匿名改别人发布的内容。
    st, _ = call("POST", f"/api/listings/{listing_id}/edit",
                 {"title": "匿名改标题", "price": 1})
    check("C3 匿名编辑被挡（401/403）", st in (401, 403), st)
    st, _ = call("POST", f"/api/listings/{listing_id}/delete")
    check("C4 匿名删除被挡（401/403）", st in (401, 403), st)
    # 对照组（防止 C3/C4 是空转断言）：白名单对**两段**路径确实放行。
    # 若这条返回 401，说明网关白名单整体没生效，那 C3/C4 的「匿名被挡」就毫无意义。
    st, _ = call("GET", f"/api/listings/{listing_id}")
    check("C5 对照组：匿名读详情（两段路径）确实免登放行", st == 200, st)

    # ---------- D. 删除是软删 ----------
    print("\n[D] 删除（软删）")
    st, body = call("POST", f"/api/listings/{listing_id}/delete", token=seller)
    check("D1 删除返回 200", st == 200, (st, body))
    st, _ = call("GET", f"/api/listings/{listing_id}")
    check("D2 删除后公开详情 404", st == 404, st)
    check("D3 删除后不在公开列表", not in_public_search(listing_id), "仍在列表")
    st, body = call("GET", "/api/listings/mine/list?pageSize=50", token=seller)
    ids = [str(r.get("id")) for r in (body.get("data") or {}).get("list", [])]
    check("D4 删除后不在「我的发布」", str(listing_id) not in ids, ids[:5])
    row = sql(f"SELECT CONCAT((deleted_at IS NOT NULL), '|', status) FROM duoduo_product.t_listing "
              f"WHERE id = {listing_id}")
    # 注意别写成 `deleted_at IS NOT NULL AND status` —— 后面那个字符串会被转成数字，
    # 恒为 0，断言会假失败（探针自己也踩了一次）。
    check("D5 行仍在库里、deleted_at 已写、status 落 OFF_SHELF（软删不是物理删）",
          row == "1|OFF_SHELF", row)
    st, _ = call("POST", f"/api/admin/listings/{listing_id}/audit",
                 {"action": "APPROVE"}, token=admin)
    check("D6 已删除的条目运营也审不了（404）", st == 404, st)

    # ---------- E. 驳回件编辑后恢复在售 ----------
    print("\n[E] 驳回件编辑后恢复在售（否则通过审核仍不可见）")
    title_v3 = "探针闲置-驳回重改"
    st, body = publish(seller, title_v3, 8000)
    check("E1 发布第二条成功", st == 200, (st, body))
    if st == 200:
        lid2 = (body.get("data") or {}).get("id")
        created.append(lid2)
        st, _ = call("POST", f"/api/admin/listings/{lid2}/audit",
                     {"action": "REJECT", "reason": "探针：刻意驳回"}, token=admin)
        check("E2 运营驳回", st == 200, st)
        status_before = sql(f"SELECT status FROM duoduo_product.t_listing WHERE id = {lid2}")
        check("E3 驳回后 status 被压成 OFF_SHELF", status_before == "OFF_SHELF", status_before)
        st, body = call("POST", f"/api/listings/{lid2}/edit", {
            "title": "探针闲置-驳回后已改", "description": "改好了",
            "categoryId": CATEGORY_ID, "price": 7500, "originalPrice": 15000,
            "conditionLevel": "GOOD", "city": "深圳", "images": [],
            "species": "猫", "breed": "英短",
        }, token=seller)
        check("E4 驳回件可编辑", st == 200, (st, body))
        status_after = sql(f"SELECT status FROM duoduo_product.t_listing WHERE id = {lid2}")
        check("E5 编辑后 status 恢复 ON_SALE", status_after == "ON_SALE", status_after)
        st, _ = approve(admin, lid2)
        st, body = call("GET", f"/api/listings/{lid2}")
        check("E6 重新通过后立即可见", st == 200, (st, str(body)[:160]))

    # ---------- F. 已售出不可删 ----------
    print("\n[F] 已售出不可删（交易凭证留存）")
    title_v4 = "探针闲置-已售"
    st, body = publish(seller, title_v4, 5000)
    if st == 200:
        lid3 = (body.get("data") or {}).get("id")
        created.append(lid3)
        sql(f"UPDATE duoduo_product.t_listing SET status = 'SOLD' WHERE id = {lid3}")
        st, _ = call("POST", f"/api/listings/{lid3}/delete", token=seller)
        check("F1 已售出删除被拒（非 2xx）", st >= 400, st)
        st, _ = call("POST", f"/api/listings/{lid3}/edit",
                     {"title": "改已售的", "price": 1}, token=seller)
        check("F2 已售出编辑被拒（非 2xx）", st >= 400, st)
        sql(f"UPDATE duoduo_product.t_listing SET status = 'OFF_SHELF' WHERE id = {lid3}")
    else:
        check("F1 已售出删除被拒（非 2xx）", True, "无样本，跳过")
        check("F2 已售出编辑被拒（非 2xx）", True, "无样本，跳过")

    # ---------- 清理：探针数据与种子数据隔离 ----------
    print("\n[Z] 清理探针数据")
    if created:
        sql("DELETE FROM duoduo_product.t_listing WHERE id IN (%s)"
            % ",".join(str(x) for x in created))
        left = sql("SELECT COUNT(*) FROM duoduo_product.t_listing WHERE id IN (%s)"
                   % ",".join(str(x) for x in created))
        check("Z1 探针闲置已清理（不污染种子数据）", left == "0", left)
    sql("DELETE FROM duoduo_user.t_user WHERE phone IN ('%s','%s')" % (SELLER_PHONE, OTHER_PHONE))
    left_user = sql("SELECT COUNT(*) FROM duoduo_user.t_user WHERE phone IN ('%s','%s')"
                    % (SELLER_PHONE, OTHER_PHONE))
    check("Z2 探针账号已清理", left_user == "0", left_user)

    print("\n" + "=" * 72)
    print(f"结果：{len(PASSED)} 通过 / {len(FAILED)} 失败")
    for f in FAILED:
        print("  - " + f)
    print("=" * 72)
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
