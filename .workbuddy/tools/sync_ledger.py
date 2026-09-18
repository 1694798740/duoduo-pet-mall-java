"""
把本地 docs/PLAN.md 覆盖同步到腾讯文档《开发计划与执行台账》。

为什么要这个脚本，而不是直接命令行调：
  腾讯文档 MCP 走 stdio/命令行时参数长度有上限（Windows 约 32KB），
  本文件正文约 24KB，base64 后 ~33KB 直接超限（Argument list too long）。
  因此改为「进程内直接调用技能脚本的 call_tool」——参数不经过命令行，长度不再是问题。

实现说明：
  * 只做「够用」的 Markdown → HTML 转换（标题 / 表格 / 列表 / 引用 / 分隔线 / 加粗 / 行内代码 / 链接 / 代码块）；
    这是一份工程看板，不需要完整的 CommonMark 兼容，过度追求反而引入新 bug。
  * 关键点是**表格**：看板里大半内容是表格，转换错了整篇可读性就崩了。
  * 覆盖写入用 overwrite_doc_with_html（整篇替换），旧版本仍可在腾讯文档历史里查看。
"""

import base64
import html
import re
import sys

SKILL_DIR = r"C:/Users/16947/.workbuddy/plugins/cache/workbuddy-builtin/tencent-docs-plugin/5.5.6-wb.38337834.g5f969292.h4918b5ced607/skills/tencent-docs"
PLAN = r"D:/智能体项目/duoduo-pet-mall-java/docs/PLAN.md"
FILE_ID = "LwirGvLSSAVG"

sys.path.insert(0, SKILL_DIR)
import tencentdocs  # noqa: E402


def inline(text: str) -> str:
    """行内标记：先转义，再处理 **加粗** / `代码` / [链接](url)。"""
    out = html.escape(text, quote=False)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', out)
    return out


def md_to_html(md: str) -> str:
    lines = md.split("\n")
    out = []
    i = 0
    in_ul = in_ol = in_code = False
    in_table = False

    def close_lists():
        nonlocal in_ul, in_ol
        if in_ul:
            out.append("</ul>")
            in_ul = False
        if in_ol:
            out.append("</ol>")
            in_ol = False

    def close_table():
        nonlocal in_table
        if in_table:
            out.append("</tbody></table>")
            in_table = False

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # 代码块
        if stripped.startswith("```"):
            close_lists()
            close_table()
            if in_code:
                out.append("</pre>")
                in_code = False
            else:
                out.append("<pre>")
                in_code = True
            i += 1
            continue
        if in_code:
            out.append(html.escape(line))
            i += 1
            continue

        # 表格：当前行是 | ... | 且下一行是分隔行 |---|
        if stripped.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:\-|]+\|$", lines[i + 1].strip()):
            close_lists()
            out.append("<table><thead><tr>")
            for cell in [c.strip() for c in stripped.strip("|").split("|")]:
                out.append(f"<th>{inline(cell)}</th>")
            out.append("</tr></thead><tbody>")
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in cells) + "</tr>")
                i += 1
            out.append("</tbody></table>")
            continue

        if not stripped:
            close_lists()
            i += 1
            continue

        if re.match(r"^-{3,}$", stripped) or re.match(r"^\*{3,}$", stripped):
            close_lists()
            out.append("<hr/>")
            i += 1
            continue

        m = re.match(r"^(#{1,6})\s+(.*)$", stripped)
        if m:
            close_lists()
            level = len(m.group(1))
            out.append(f"<h{level}>{inline(m.group(2))}</h{level}>")
            i += 1
            continue

        if stripped.startswith(">"):
            close_lists()
            out.append(f"<blockquote>{inline(stripped.lstrip('>').strip())}</blockquote>")
            i += 1
            continue

        if re.match(r"^[-*]\s+", stripped):
            if in_ol:
                out.append("</ol>")
                in_ol = False
            if not in_ul:
                out.append("<ul>")
                in_ul = True
            out.append(f"<li>{inline(re.sub(r'^[-*]\\s+', '', stripped))}</li>")
            i += 1
            continue

        if re.match(r"^\d+\.\s+", stripped):
            if in_ul:
                out.append("</ul>")
                in_ul = False
            if not in_ol:
                out.append("<ol>")
                in_ol = True
            out.append(f"<li>{inline(re.sub(r'^\\d+\\.\\s+', '', stripped))}</li>")
            i += 1
            continue

        close_lists()
        out.append(f"<p>{inline(stripped)}</p>")
        i += 1

    close_lists()
    close_table()
    if in_code:
        out.append("</pre>")
    return "\n".join(out)


def main() -> None:
    md = open(PLAN, encoding="utf-8").read()
    body = md_to_html(md)
    doc = (
        "<html><head><meta charset='utf-8'><title>多多宠物商店 · 开发计划与执行台账</title></head>"
        f"<body>{body}</body></html>"
    )
    b64 = base64.b64encode(doc.encode("utf-8")).decode("ascii")
    print(f"markdown {len(md)} 字符 → html {len(doc)} 字符 → base64 {len(b64)} 字符")
    result = tencentdocs.call_tool(
        "doc-mcp",
        "overwrite_doc_with_html",
        {"file_id": FILE_ID, "base64_html_text": b64},
    )
    print(result)


if __name__ == "__main__":
    main()
