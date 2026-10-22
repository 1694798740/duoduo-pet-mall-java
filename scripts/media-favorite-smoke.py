#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
B/C 批冒烟：媒体消息 + 收藏/足迹/已购（第七十九轮）

覆盖：
  1. 图片上传（multipart）→ 返回 URL；
  2. mp4 上传 + Range 读取（206 / Content-Range）—— 视频播放的前提；
  3. 客服发送图片消息，会话里能读回 mediaUrl；
  4. 收藏切换 + 收藏列表；
  5. 浏览商品 → 足迹出现；
  6. 已购商品接口可用（无订单时为空，不是 500）。

用法：python scripts/media-favorite-smoke.py
"""
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://localhost:8080"
BUYER = "13600001111"
PWD = "duoduo12345"

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


def login(phone):
    st, r = call("POST", "/api/auth/login/token", {"phone": phone, "password": PWD})
    return (r.get("data") or {}).get("accessToken")


def upload(token, filename, content_type, payload):
    boundary = "----duoduo" + uuid.uuid4().hex
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    body = head + payload + f"\r\n--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(BASE + "/api/uploads", data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8") or "{}")


def main():
    token = login(BUYER)
    check("买家登录", bool(token))
    if not token:
        return failed

    # 1) 图片上传（1x1 PNG）
    png = bytes.fromhex(
        "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
        "890000000d4944415478da63fcffff3f0300050001a5f645400000000049454e44ae426082"
    )
    st, r = upload(token, "probe.png", "image/png", png)
    image_url = (r.get("data") or {}).get("url")
    check("图片上传成功", st == 200 and bool(image_url), r)

    # 2) 视频上传 + Range
    st, r = upload(token, "probe.mp4", "video/mp4", b"\x00\x00\x00\x18ftypmp42" + b"0" * 2048)
    video_url = (r.get("data") or {}).get("url")
    check("mp4 上传成功", st == 200 and bool(video_url), r)
    if video_url:
        req = urllib.request.Request(video_url)
        req.add_header("Range", "bytes=0-9")
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                check("视频 Range 返回 206", resp.status == 206, resp.status)
                check("Content-Range 正确",
                      (resp.headers.get("Content-Range") or "").startswith("bytes 0-9/"),
                      resp.headers.get("Content-Range"))
        except urllib.error.HTTPError as e:
            check("视频 Range 返回 206", False, e.code)

    # 3) 客服发送图片消息
    if image_url:
        st, r = call("POST", "/api/support/messages",
                     {"type": "IMAGE", "content": "", "mediaUrl": image_url}, token)
        check("发送图片消息", st == 200 and r.get("code") == "SUCCESS", r)
        st, r = call("GET", "/api/support/messages?page=1&pageSize=10", token=token)
        items = (r.get("data") or {}).get("items") or []
        check("会话里能读回图片地址",
              any(i.get("mediaUrl") == image_url for i in items),
              [i.get("bizType") for i in items][-3:])

    # 3b) 订单卡：本人可发、他人 403（服务端回交易域取事实）
    st, r = call("POST", "/api/support/messages",
                 {"type": "ORDER", "content": "", "orderNo": "TEST20261021001"}, token)
    order_msg = r.get("data") or {}
    check("订单卡发送成功", st == 200 and order_msg.get("bizType") == "ORDER", r)
    check("订单卡带订单快照",
          bool(order_msg.get("metaJson")) and "orderNo" in (order_msg.get("metaJson") or ""),
          order_msg.get("metaJson"))
    other = login("13600002222")
    st, r = call("POST", "/api/support/messages",
                 {"type": "ORDER", "content": "", "orderNo": "TEST20261021001"}, other)
    check("他人订单卡被拒（403）", st == 403, (st, r.get("code")))

    # 4) 收藏 + 列表
    st, r = call("POST", "/api/favorites/1001", token=token)
    first = (r.get("data") or {}).get("favorited")
    check("收藏切换成功", st == 200 and isinstance(first, bool), r)
    st, r = call("GET", "/api/favorites/status?spuIds=1001", token=token)
    check("收藏状态可读", st == 200 and isinstance(r.get("data"), list), r)
    st, r = call("GET", "/api/favorites/products?page=1&pageSize=10", token=token)
    check("收藏列表可读", st == 200 and (r.get("data") or {}).get("items") is not None, r)

    # 5) 足迹：访问商品详情后应出现在足迹里
    call("GET", "/api/products/1002", token=token)
    st, r = call("GET", "/api/history/products?page=1&pageSize=10", token=token)
    ids = [str(i.get("id")) for i in (r.get("data") or {}).get("items", [])]
    check("足迹记录了刚浏览的商品", "1002" in ids, ids[:5])

    # 6) 已购商品
    st, r = call("GET", "/api/orders/purchased-products?page=1&pageSize=5", token=token)
    check("已购商品接口可用", st == 200 and r.get("code") == "SUCCESS", (st, r.get("code")))

    # 收尾：取消收藏，避免污染人工验收
    call("POST", "/api/favorites/1001", token=token)

    print("\n结果：%d 通过 / %d 失败" % (passed, failed))
    return failed


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
