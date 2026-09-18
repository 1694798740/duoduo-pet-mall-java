"""校验已发布到腾讯文档的 9 篇交接文档：标题层级结构（递归统计）+ 是否存在空文档。"""

import json
import sys

SKILL = r"C:/Users/16947/.workbuddy/plugins/cache/workbuddy-builtin/tencent-docs-plugin/5.5.6-wb.38337834.g5f969292.h4918b5ced607/skills/tencent-docs"
MANIFEST = r"D:/智能体项目/duoduo-pet-mall-java/docs/handover/manifest.json"
sys.path.insert(0, SKILL)
import tencentdocs  # noqa: E402


def call(service, tool, args):
    r, _ = tencentdocs.call_tool(service, tool, args)
    t = r["result"]["content"][0]["text"] if isinstance(r, dict) and "result" in r else str(r)
    try:
        return json.loads(t)
    except Exception:
        return {"raw": t[:300]}


def count(nodes):
    n = 0
    for x in nodes or []:
        n += 1 + count(x.get("children"))
    return n


def titles(nodes, out=None):
    out = out if out is not None else []
    for x in nodes or []:
        out.append(f"{x.get('level', '')}:{x.get('title', '').strip()}")
        titles(x.get("children"), out)
    return out


def main():
    m = json.load(open(MANIFEST, encoding="utf-8"))
    print(f"共 {len(m['docs'])} 篇，逐篇校验（含子标题）：")
    bad = []
    for d in m["docs"]:
        o = call("doc-mcp", "get_outline", {"file_id": d["file_id"]})
        n = count(o.get("outlines"))
        ok = n >= 4
        if not ok:
            bad.append(d["title"])
        print(f"  {'OK ' if ok else 'BAD'} {d['title']}：标题数={n} ｜ 例 {titles(o.get('outlines'))[1:4]}")
    print(">> 校验通过" if not bad else f">> 异常：{bad}")

    o0 = call("doc-mcp", "get_outline", {"file_id": m["docs"][0]["file_id"]})
    print("\n00 篇完整标题树：")
    for x in titles(o0.get("outlines")):
        print("    ", x)


if __name__ == "__main__":
    main()
