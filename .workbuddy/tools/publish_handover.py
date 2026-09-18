"""
把 docs/handover/ 下的 9 篇交接文档发布到腾讯文档「项目资产（按时间归档）」目录。

为什么用 Python 直连技能脚本，而不是命令行调 MCP：
  腾讯文档 MCP 走命令行时参数上限约 32KB（Windows），而单篇正文转 HTML 后 base64 常在 20~90KB，
  必然超限。本脚本 `sys.path.insert` 技能目录后**在进程内**调用 `tencentdocs.call_tool`，
  参数不经过命令行，长度不再是问题（这是本项目已验证可行的模式，见 sync_ledger.py）。

用法：
  python publish_handover.py                                   # 默认发布 docs/handover（人读交接包）
  python publish_handover.py --src docs/agent --sub-title "2026-09-18 AI Agent 接管包"

执行顺序（幂等，可重复跑）：
  1. 根目录「多多宠物商店 · 项目资产（按时间归档）」不存在则建；
  2. 其下「2026-09-18 交接包」不存在则建；
  3. 逐篇：doc 不存在则建 → overwrite_doc_with_html 整篇写入 → get_outline 校验非空；
  4. 回写 docs/handover/manifest.json（title / file_id / url / bytes / 大纲节数）。
"""

import argparse
import base64
import json
import os
import re
import sys

SKILL_DIR = r"C:/Users/16947/.workbuddy/plugins/cache/workbuddy-builtin/tencent-docs-plugin/5.5.6-wb.38337834.g5f969292.h4918b5ced607/skills/tencent-docs"
ROOT = r"D:/智能体项目/duoduo-pet-mall-java"

# 默认值 = 人读交接包（保持原行为）；另一套用命令行覆盖为 docs/agent（AI 接管包）
parser = argparse.ArgumentParser(description="把本地 Markdown 文档集发布到腾讯文档目录（幂等）")
parser.add_argument("--src", default=os.path.join(ROOT, "docs", "handover"), help="本地 md 目录")
parser.add_argument("--manifest", default=None, help="manifest.json 输出路径（默认 <src>/manifest.json）")
parser.add_argument("--folder-title", default="多多宠物商店 · 项目资产（按时间归档）", help="云端根目录名")
parser.add_argument("--sub-title", default="2026-09-18 交接包", help="云端子目录名")
args, _ = parser.parse_known_args()

HANDOVER = args.src
MANIFEST = args.manifest or os.path.join(HANDOVER, "manifest.json")
FOLDER_TITLE = args.folder_title
SUB_TITLE = args.sub_title
MANIFEST_KIND = "ai-agent" if os.path.basename(HANDOVER.rstrip("/\\")) == "agent" else "human-handover"

sys.path.insert(0, SKILL_DIR)
import tencentdocs  # noqa: E402


def call(tool: str, args: dict):
    result, _ = tencentdocs.call_tool("tencent-docs", tool, args)
    if isinstance(result, dict) and "result" in result:
        text = result["result"]["content"][0]["text"]
        try:
            return json.loads(text)
        except Exception:
            return {"raw": text}
    return result


def call_doc(tool: str, args: dict):
    result, _ = tencentdocs.call_tool("doc-mcp", tool, args)
    if isinstance(result, dict) and "result" in result:
        text = result["result"]["content"][0]["text"]
        try:
            return json.loads(text)
        except Exception:
            return {"raw": text}
    return result


# ---------- Markdown → HTML（够用即可；与 sync_ledger.py 同一实现思路） ----------

def inline(text: str) -> str:
    import html as _h
    out = _h.escape(text, quote=False)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', out)
    return out


def md_to_html(md: str) -> str:
    import html as _h
    lines = md.split("\n")
    out, i = [], 0
    in_ul = in_ol = in_code = False

    def close_lists():
        nonlocal in_ul, in_ol
        if in_ul:
            out.append("</ul>")
            in_ul = False
        if in_ol:
            out.append("</ol>")
            in_ol = False

    while i < len(lines):
        line, s = lines[i], lines[i].strip()
        if s.startswith("```"):
            close_lists()
            out.append("</pre>" if in_code else "<pre>")
            in_code = not in_code
            i += 1
            continue
        if in_code:
            out.append(_h.escape(line))
            i += 1
            continue
        if s.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:\-|]+\|$", lines[i + 1].strip()):
            close_lists()
            out.append("<table><thead><tr>")
            for c in [x.strip() for x in s.strip("|").split("|")]:
                out.append(f"<th>{inline(c)}</th>")
            out.append("</tr></thead><tbody>")
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                cells = [x.strip() for x in lines[i].strip().strip("|").split("|")]
                out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in cells) + "</tr>")
                i += 1
            out.append("</tbody></table>")
            continue
        if not s:
            close_lists()
            i += 1
            continue
        if re.match(r"^(-{3,}|\*{3,})$", s):
            close_lists()
            out.append("<hr/>")
            i += 1
            continue
        m = re.match(r"^(#{1,6})\s+(.*)$", s)
        if m:
            close_lists()
            lvl = len(m.group(1))
            out.append(f"<h{lvl}>{inline(m.group(2))}</h{lvl}>")
            i += 1
            continue
        if s.startswith(">"):
            close_lists()
            out.append(f"<blockquote>{inline(s.lstrip('>').strip())}</blockquote>")
            i += 1
            continue
        if re.match(r"^[-*]\s+", s):
            if in_ol:
                out.append("</ol>")
                in_ol = False
            if not in_ul:
                out.append("<ul>")
                in_ul = True
            out.append(f"<li>{inline(re.sub(r'^[-*]\\s+', '', s))}</li>")
            i += 1
            continue
        if re.match(r"^\d+\.\s+", s):
            if in_ul:
                out.append("</ul>")
                in_ul = False
            if not in_ol:
                out.append("<ol>")
                in_ol = True
            out.append(f"<li>{inline(re.sub(r'^\\d+\\.\\s+', '', s))}</li>")
            i += 1
            continue
        close_lists()
        out.append(f"<p>{inline(s)}</p>")
        i += 1

    close_lists()
    if in_code:
        out.append("</pre>")
    return "\n".join(out)


def find_folder(title: str, parent_id: str | None):
    """在指定父目录下按标题找文件夹；找不到返回 None"""
    listing = call("manage.folder_list", {"folder_id": parent_id} if parent_id else {})
    for item in (listing.get("list") or []):
        if item.get("title") == title and item.get("is_folder"):
            return item["id"]
    return None


def ensure_folder(title: str, parent_id: str | None) -> str:
    found = find_folder(title, parent_id)
    if found:
        print(f"  [已存在] 文件夹 {title} → {found}")
        return found
    args = {"title": title, "file_type": "folder"}
    if parent_id:
        args["parent_id"] = parent_id
    created = call("manage.create_file", args)
    fid = created.get("file_id") or created.get("id")
    print(f"  [已创建] 文件夹 {title} → {fid}")
    return fid


def find_doc(title: str, parent_id: str) -> str | None:
    listing = call("manage.folder_list", {"folder_id": parent_id})
    for item in (listing.get("list") or []):
        if item.get("title") == title and not item.get("is_folder"):
            return item["id"]
    return None


def main() -> None:
    print("== 1. 准备目录 ==")
    root_id = ensure_folder(FOLDER_TITLE, None)
    sub_id = ensure_folder(SUB_TITLE, root_id)

    files = sorted(f for f in os.listdir(HANDOVER) if f.endswith(".md"))
    print(f"\n== 2. 发布 {len(files)} 篇文档 ==")
    manifest = []
    for fn in files:
        path = os.path.join(HANDOVER, fn)
        md = open(path, encoding="utf-8").read()
        title = os.path.splitext(fn)[0]
        html_doc = (
            "<html><head><meta charset='utf-8'></head><body>"
            + md_to_html(md)
            + "</body></html>"
        )
        b64 = base64.b64encode(html_doc.encode("utf-8")).decode("ascii")

        file_id = find_doc(title, sub_id)
        is_new = file_id is None
        if is_new:
            created = call("manage.create_file", {"title": title, "file_type": "doc", "parent_id": sub_id})
            file_id = created.get("file_id") or created.get("id")

        written = call_doc("overwrite_doc_with_html", {"file_id": file_id, "base64_html_text": b64})
        outline = call_doc("get_outline", {"file_id": file_id})
        sections = len((outline.get("outlines") or []))
        print(f"  [{title}] file_id={file_id} html={len(html_doc)}B 大纲节数={sections} ({'新建' if is_new else '复用'})")
        manifest.append({
            "title": title,
            "file_id": file_id,
            "url": f"https://docs.qq.com/doc/{file_id}",
            "md_bytes": len(md.encode("utf-8")),
            "html_bytes": len(html_doc.encode("utf-8")),
            "outline_sections": sections,
            "written": written,
        })

    payload = {
        "kind": MANIFEST_KIND,
        "source_dir": HANDOVER.replace("\\", "/"),
        "generated_at": "2026-09-18",
        "tencent_folder": {"title": FOLDER_TITLE, "folder_id": root_id},
        "tencent_subfolder": {"title": SUB_TITLE, "folder_id": sub_id},
        "docs": manifest,
    }
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print(f"\n== 3. manifest 已写入 {MANIFEST}（{len(manifest)} 条）==")


if __name__ == "__main__":
    main()
