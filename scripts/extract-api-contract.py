#!/usr/bin/env python3
"""接口契约抽取器：从各服务 Controller 源码**机械抽取**端点清单，生成 docs/agent/04 的「端点总表」。

为什么要有这个脚本：
  04 篇自称「DTO 字段表由源码机械抽取生成」，但仓库里此前并没有抽取器 ——
  于是文档在 R56、R57、R58 三轮里持续落后（新增端点没人同步），
  而 AI 与新同学恰恰把这份文档当成「接口事实来源」。文档与源码不一致，
  比没有文档更危险：它会让人照着错的契约写代码。

设计取舍：
  · 只抽取**能确定的事实**（HTTP 方法、完整路径、所在文件、是否标注内部/运营），
    不猜参数与返回结构 —— 那部分仍以源码为准；
  · 输出写在 04 篇的 AUTO-GEN 标记之间，**人工撰写的说明不会被覆盖**；
  · 每次跑都重写整段，因此「契约文档随代码同步」可以变成一条可执行的门禁。

用法：python scripts/extract-api-contract.py          # 默认就地更新 04 篇
      python scripts/extract-api-contract.py --check  # 只比对，不落盘（CI 用，落后即退出码 1）
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DOC = os.path.join(ROOT, "docs", "agent", "04-接口契约全集.md")
BEGIN = "<!-- AUTO-GEN:ENDPOINTS:BEGIN -->"
END = "<!-- AUTO-GEN:ENDPOINTS:END -->"

MODULES = [
    ("duoduo-user", "用户域", "8081"),
    ("duoduo-product", "商品域", "8082"),
    ("duoduo-trade", "交易域", "8083"),
    ("duoduo-fulfilment", "履约域", "8084"),
    ("duoduo-marketing", "营销域", "8085"),
    ("duoduo-ai", "AI 导购域", "8086"),
    ("duoduo-social", "社交域", "8087"),
]

METHOD_ANNOTATIONS = {
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
    "RequestMapping": "ANY",
}


def strip_comment(line):
    """去掉行尾注释（抽取正则只关心注解本身）"""
    idx = line.find("//")
    return line[:idx] if idx >= 0 else line


def find_controllers(module_dir):
    out = []
    base = os.path.join(ROOT, module_dir, "src", "main", "java")
    for dirpath, _, files in os.walk(base):
        for f in files:
            if f.endswith("Controller.java"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def annotation_value(src, pos, name):
    """取某个注解括号里的第一个字符串字面量（路径）"""
    # 注意：正则**不能**把 `(` 也吃掉 —— 否则下面判断 `src[i] == "("` 时 i 已经越过了左括号，
    # 会走进「无参数」分支，导致所有路径都被抽成空串（第一版就踩了这个坑）。
    m = re.compile(r"@" + name + r"\s*").search(src, pos)
    if not m:
        return None
    i = m.end()
    if i < len(src) and src[i] == "(":
        depth, j = 1, i + 1
        while j < len(src) and depth > 0:
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
            j += 1
        inner = src[i + 1:j - 1]
    else:
        inner = ""
    strs = re.findall(r"\"([^\"]*)\"", inner)
    return strs[0] if strs else ""


def parse_file(path):
    """返回 [(method, full_path, note)]"""
    src = open(path, encoding="utf-8").read()
    # 类级 @RequestMapping
    cls = re.search(r"@RequestMapping\s*\(", src)
    base = annotation_value(src, cls.start() if cls else 0, "RequestMapping") if cls else ""
    if base is None:
        base = ""
    items = []
    for name, verb in METHOD_ANNOTATIONS.items():
        for m in re.finditer(r"@" + name + r"\b", src):
            line_start = src.rfind("\n", 0, m.start()) + 1
            head = strip_comment(src[line_start:m.end()])
            if head.strip().startswith("//"):
                continue
            val = annotation_value(src, m.start(), name)
            sub = val if val else ""
            full = (base + "/" + sub) if sub else base
            full = re.sub(r"/+", "/", full)
            # 该方法的可见性说明：看其后 400 字符内是否有内部/运营关键词
            tail = src[m.end():m.end() + 600]
            note = ""
            if "internal" in full.lower() or "InternalToken" in tail:
                note = "内部凭据"
            elif "admin" in full.lower() or "requireOperator" in tail or "OperatorGuard" in tail:
                note = "运营"
            items.append((verb, full, note))
    # 去重保序
    seen, uniq = set(), []
    for it in items:
        if it not in seen:
            seen.add(it)
            uniq.append(it)
    return uniq


def build_section():
    lines = [BEGIN, "", "## 2.1 端点总表（**自动生成，勿手改**）", "",
             "> 由 `scripts/extract-api-contract.py` 从各服务 `Controller` 源码抽取；",
             "> 参数与返回结构仍以源码为准，本表只保证**路径与方法**不落后于代码。", ""]
    total = 0
    for module, label, port in MODULES:
        files = find_controllers(module)
        rows = []
        for f in files:
            rel = os.path.relpath(f, ROOT).replace("\\", "/")
            for verb, full, note in parse_file(f):
                rows.append((verb, full, note, rel))
        if not rows:
            continue
        rows.sort(key=lambda r: (r[1], r[0]))
        total += len(rows)
        lines.append(f"### {label} `{module}:{port}`（{len(rows)} 个端点）")
        lines.append("")
        lines.append("| 方法 | 路径 | 说明 | 源码 |")
        lines.append("|---|---|---|---|")
        for verb, full, note, rel in rows:
            lines.append(f"| {verb} | `{full}` | {note} | `{rel}` |")
        lines.append("")
    lines.append(f"合计 {total} 个端点。")
    lines.append("")
    lines.append(END)
    return "\n".join(lines) + "\n"


def main():
    check_only = "--check" in sys.argv
    section = build_section()
    doc = open(DOC, encoding="utf-8").read()
    if BEGIN in doc and END in doc:
        before, rest = doc.split(BEGIN, 1)
        _, after = rest.split(END, 1)
        # 幂等：END 之后的空行每次重建都要**先剥掉再统一补一个**，
        # 否则每跑一次就多一个空行，--check 会永远报「落后」（第一版就踩了这个坑）。
        new_doc = before + section + "\n" + after.lstrip("\n")
    else:
        # 第一次生成：插到「## 2. 网关白名单」之前
        anchor = "## 2. 网关白名单"
        idx = doc.find(anchor)
        if idx < 0:
            print("找不到插入锚点，终止")
            return 1
        new_doc = doc[:idx] + section + "\n" + doc[idx:]
    if check_only:
        if new_doc.strip() != doc.strip():
            print("[FAIL] 接口契约文档落后于源码，请运行：python scripts/extract-api-contract.py")
            return 1
        print("[PASS] 接口契约文档与源码一致")
        return 0
    open(DOC, "w", encoding="utf-8").write(new_doc)
    print(f"已更新 {os.path.relpath(DOC, ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
