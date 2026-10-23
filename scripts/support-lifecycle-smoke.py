#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
客服会话生命周期 · 端到端冒烟（2026-10-23）

守七件事（对应本轮需求 3/4/5）：
  1. 关闭权只在买家：客服调关闭接口一律 403；
  2. 关闭后客服不能回复（422），买家仍可发送并**自动重开**；
  3. 会话状态只对客服可见：买家侧接口 `status` 恒为 null；
  4. 商家筛选：UNREAD（有待回）/ ANSWERED（已处理＝无待回）/ CLOSED（已关闭）三者互不串味；
  5. 24 小时静默 → 自动结束（调内部 sweep 触发，不 sleep 等 cron）；
  6. 关闭满 30 天且无往来 → 会话与消息物理删除；
  7. 清理只删 uploads/ 下的对象：腾讯云 COS 上的图片被删（URL 404），静态贴纸不受影响。

前置：容器已起（网关 8080 / 社交域直连 8087 / MySQL 容器 duoduo-mysql）。
用法：NO_PROXY="*" python scripts/support-lifecycle-smoke.py

★ 为什么用临时账号：清理用例会**物理删除**整个会话与消息，
  绝不能拿真实测试账号的历史会话做靶子。
"""
import base64
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
SOCIAL = "http://localhost:8087"  # 内部接口直连（不经网关）
CS = "13900000002"                # 官方客服账号（SELLER 角色）
PWD = "duoduo12345"
MYSQL = ["docker", "exec", "duoduo-mysql", "mysql", "-uroot", "-pduoduo_root",
         "-N", "-B", "--default-character-set=utf8mb4", "-e"]

passed = 0
failed = 0

# 1x1 透明 PNG：体积最小，但必须是**真图**（假 PNG 会被 COS 判 InvalidImageFormat）
PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def call(method, path, body=None, token=None, base=BASE, raw_headers=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (raw_headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  [通过] " + name)
    else:
        failed += 1
        print("  [失败] " + name + ("  -> " + str(detail) if detail else ""))


def login(phone):
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    return (r.get("data") or {}).get("accessToken") if st == 200 else None


def mysql(sql):
    out = subprocess.run(MYSQL + [sql], capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        raise RuntimeError("mysql 执行失败：" + (out.stderr or ""))
    return out.stdout.strip()


def upload_png(token):
    boundary = "----duoduo" + uuid.uuid4().hex
    head = (
        "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"lifecycle.png\"\r\n"
        "Content-Type: image/png\r\n\r\n" % boundary
    ).encode("utf-8")
    tail = ("\r\n--%s--\r\n" % boundary).encode("utf-8")
    body = head + PNG_1PX + tail
    req = urllib.request.Request(BASE + "/api/uploads", data=body, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    return (payload.get("data") or {}).get("url")


def url_status(url):
    """返回 HTTP 状态码；连不上返回 0"""
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def _b64url(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def internal_sign(method="POST", caller="duoduo-social"):
    """
    与 InternalCallAuth 同口径签发：
      secret(caller) = b64url(HMAC-SHA256(root, "dd1|key|" + caller))
      sig = b64url(HMAC-SHA256(secret, "dd1\\ncaller\\nts\\nnonce\\nMETHOD"))
    """
    root = os.environ.get("INTERNAL_TOKEN", "duoduo-local-dev-internal-token")
    ts = str(int(time.time()))
    nonce = uuid.uuid4().hex
    derived = _b64url(hmac.new(root.encode(), ("dd1|key|" + caller.lower()).encode(),
                               hashlib.sha256).digest())
    sig = _b64url(hmac.new(derived.encode(), ("\n".join(["dd1", caller, ts, nonce, method])).encode(),
                           hashlib.sha256).digest())
    return "dd1.%s.%s.%s.%s" % (caller, ts, nonce, sig)


def sweep(close=1, cleanup=1):
    header = {"X-Internal-Token": internal_sign("POST")}
    return call("POST", "/api/socials/internal/support-sweep?close=%d&cleanup=%d" % (close, cleanup),
                body=None, base=SOCIAL, raw_headers=header)


def main():
    global passed, failed
    print("== 0. 准备临时买家（清理用例会物理删除整个会话，不能拿真实账号当靶子） ==")
    phone = "197" + str(int(time.time()))[-8:]
    st, r = call("POST", "/api/auth/register",
                 {"phone": phone, "password": PWD, "nickname": "生命周期冒烟"})
    check("注册临时买家", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))
    buyer = login(phone)
    seller = login(CS)
    check("买家/客服均可登录", bool(buyer) and bool(seller))
    if not buyer or not seller:
        return failed

    print("\n== 1. 视频/图片消息与对象存在性 ==")
    img = upload_png(buyer)
    check("上传测试图片", bool(img), img)
    check("图片上传后可访问（200）", url_status(img) == 200, url_status(img))

    st, r = call("POST", "/api/support/messages",
                 {"content": "", "type": "IMAGE", "mediaUrl": img, "width": 1, "height": 1},
                 token=buyer)
    check("买家发送图片消息", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    # 贴纸：静态资源，清理时必须被前缀守卫跳过
    sticker = "http://localhost:8088/stickers/ok.svg"
    st, r = call("POST", "/api/support/messages",
                 {"content": "", "type": "EMOJI", "mediaUrl": sticker}, token=buyer)
    check("买家发送贴纸消息", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    st, r = call("GET", "/api/support/session", token=buyer)
    session = r.get("data") or {}
    sid = session.get("id")
    check("取得会话 id", bool(sid), session)
    if not sid:
        return failed

    print("\n== 2. 关闭权限：只有买家能关 ==")
    st, r = call("POST", "/api/seller/support/sessions/%s/status?value=CLOSED" % sid, token=seller)
    check("客服关闭会话被拒（403）", st == 403 and r.get("code") == "FORBIDDEN", (st, r.get("code")))

    st, r = call("GET", "/api/seller/support/sessions?filter=CLOSED&page=1&pageSize=50", token=seller)
    ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("客服拒绝后会话仍不是已关闭", sid not in ids, ids[:3])

    st, r = call("POST", "/api/support/close?sessionId=%s" % sid, token=buyer)
    check("买家结束会话成功", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    row = mysql("SELECT status, IFNULL(DATE_FORMAT(closed_at,'%%Y-%%m-%%d %%H:%%i:%%s'),'NULL') "
                "FROM duoduo_social.t_support_session WHERE id=%s;" % sid)
    parts = row.split("\t") if row else []
    check("库里状态=CLOSED", bool(parts) and parts[0] == "CLOSED", row)
    check("closed_at 已写入", len(parts) > 1 and parts[1] != "NULL", row)

    st, r = call("GET", "/api/support/messages?page=1&pageSize=10", token=buyer)
    check("买家侧不回会话状态（status=null）", (r.get("data") or {}).get("status") is None,
          (r.get("data") or {}).get("status"))

    st, r = call("POST", "/api/seller/support/sessions/%s/messages" % sid,
                 {"content": "关闭后客服回复"}, token=seller)
    check("关闭后客服回复被拒（422）", st == 422 and r.get("code") == "BUSINESS_RULE", (st, r.get("code")))

    print("\n== 3. 买家再发消息 → 自动重开（且清空关闭时间） ==")
    st, r = call("POST", "/api/support/messages", {"content": "重开验证 " + phone[-4:], "sellerId": 0},
                 token=buyer)
    check("关闭后买家仍可发送", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))
    row = mysql("SELECT status, IFNULL(closed_at,'NULL') FROM duoduo_social.t_support_session "
                "WHERE id=%s;" % sid)
    check("会话自动重开（status=OPEN）", row.startswith("OPEN"), row)
    check("重开后 closed_at 被清空", row.endswith("\tNULL") or row.endswith("NULL"), row)

    print("\n== 4. 商家筛选：未读 / 已处理 / 已关闭 ==")
    st, r = call("GET", "/api/seller/support/sessions?filter=UNREAD&page=1&pageSize=50", token=seller)
    unread_ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("「未读」含该会话", sid in unread_ids, unread_ids[:3])

    call("POST", "/api/seller/support/sessions/%s/read" % sid, token=seller)
    st, r = call("GET", "/api/seller/support/sessions?filter=ANSWERED&page=1&pageSize=50", token=seller)
    answered_ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("「已处理」含该会话", sid in answered_ids, answered_ids[:3])

    st, r = call("GET", "/api/seller/support/sessions?filter=UNREAD&page=1&pageSize=50", token=seller)
    unread_ids = [x["id"] for x in (r.get("data") or {}).get("items", [])]
    check("读完后「未读」不再含该会话", sid not in unread_ids, unread_ids[:3])

    print("\n== 5. 24 小时静默 → 自动结束 ==")
    mysql("UPDATE duoduo_social.t_support_session SET last_message_at = "
          "DATE_SUB(UTC_TIMESTAMP(), INTERVAL 25 HOUR) WHERE id=%s;" % sid)
    st, r = sweep(close=1, cleanup=0)
    check("内部 sweep 可调用", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))
    row = mysql("SELECT status, IFNULL(closed_at,'NULL') FROM duoduo_social.t_support_session "
                "WHERE id=%s;" % sid)
    check("静默 25h 后被自动结束", row.startswith("CLOSED"), row)
    check("自动结束时补写 closed_at", not row.endswith("NULL"), row)

    # 买家完全无感：接口不返回状态，且发送即重开
    st, r = call("GET", "/api/support/messages?page=1&pageSize=10", token=buyer)
    check("自动结束后买家侧仍看不到状态", (r.get("data") or {}).get("status") is None,
          (r.get("data") or {}).get("status"))
    st, r = call("POST", "/api/support/messages", {"content": "自动结束后再问", "sellerId": 0}, token=buyer)
    check("自动结束后买家仍可发送", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    print("\n== 6. 关闭满 30 天 → 物理删除会话/消息 + 删 COS 对象 ==")
    st, r = call("POST", "/api/support/close?sessionId=%s" % sid, token=buyer)
    check("再次结束会话", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))
    mysql("UPDATE duoduo_social.t_support_session SET closed_at = "
          "DATE_SUB(UTC_TIMESTAMP(), INTERVAL 31 DAY), last_message_at = "
          "DATE_SUB(UTC_TIMESTAMP(), INTERVAL 31 DAY) WHERE id=%s;" % sid)

    before_msgs = mysql("SELECT COUNT(*) FROM duoduo_social.t_support_message WHERE session_id=%s;" % sid)
    check("清理前消息存在", int(before_msgs or 0) >= 2, before_msgs)

    st, r = sweep(close=0, cleanup=1)
    data = r.get("data") or {}
    check("清理接口返回删除数", st == 200 and int(data.get("deletedSessions") or 0) >= 1, (st, data))

    left = mysql("SELECT COUNT(*) FROM duoduo_social.t_support_session WHERE id=%s;" % sid)
    check("会话行已物理删除", int(left or -1) == 0, left)
    left_msgs = mysql("SELECT COUNT(*) FROM duoduo_social.t_support_message WHERE session_id=%s;" % sid)
    check("会话消息已全部删除", int(left_msgs or -1) == 0, left_msgs)

    check("COS 图片对象已删除（URL 404）", url_status(img) == 404, url_status(img))
    check("静态贴纸未被误删（仍 200）", url_status(sticker) == 200, url_status(sticker))

    print("\n结果：%d 通过 / %d 失败" % (passed, failed))
    return failed


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
