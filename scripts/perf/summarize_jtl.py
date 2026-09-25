# -*- coding: utf-8 -*-
"""A12 压测结果汇总：读取 JTL CSV，输出吞吐/延迟分位数/错误率（总体+分环节）。

用法: python summarize_jtl.py <jtl1> [jtl2 ...]
JTL 列: timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,
        success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
"""
import csv
import sys
import io


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * p / 100.0
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def summarize(path):
    with io.open(path, encoding="utf-8", errors="replace", newline="") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        print(f"\n=== {path} : 空文件 ===")
        return

    t0 = min(int(r["timeStamp"]) for r in rows)
    t1 = max(int(r["timeStamp"]) + int(r["elapsed"]) for r in rows)
    dur = max((t1 - t0) / 1000.0, 0.001)
    total = len(rows)
    errs = sum(1 for r in rows if r["success"].strip() != "true")

    print(f"\n=== {path.split('/')[-1] if '/' in path else path.split(chr(92))[-1]} ===")
    print(f"样本总数 {total}  时长 {dur:.0f}s  吞吐 {total/dur:.1f}/s  "
          f"错误 {errs} ({errs/total*100:.2f}%)")

    lat = sorted(int(r["elapsed"]) for r in rows)
    print(f"延迟 Avg {sum(lat)/len(lat):.0f}ms  "
          f"P50 {percentile(lat,50):.0f}  P90 {percentile(lat,90):.0f}  "
          f"P95 {percentile(lat,95):.0f}  P99 {percentile(lat,99):.0f}  "
          f"Max {lat[-1]}ms")

    by_label = {}
    for r in rows:
        lab = r["label"]
        d = by_label.setdefault(lab, {"lat": [], "err": 0, "n": 0, "codes": {}})
        d["lat"].append(int(r["elapsed"]))
        d["n"] += 1
        if r["success"].strip() != "true":
            d["err"] += 1
        code = r["responseCode"] or "EMPTY"
        d["codes"][code] = d["codes"].get(code, 0) + 1

    print(f"{'环节':<28}{'样本':>8}{'Avg':>7}{'P95':>7}{'P99':>7}{'错误率':>9}  响应码")
    for lab, d in by_label.items():
        la = sorted(d["lat"])
        codes = ",".join(f"{c}x{n}" for c, n in sorted(d["codes"].items()))
        print(f"{lab:<28}{d['n']:>8}{sum(la)/len(la):>7.0f}{percentile(la,95):>7.0f}"
              f"{percentile(la,99):>7.0f}{d['err']/d['n']*100:>8.2f}%  {codes}")

    err_rows = [r for r in rows if r["success"].strip() != "true"][:3]
    for r in err_rows:
        print("错误样例:", r["label"], "|", r["responseCode"][:80], "|",
              (r["failureMessage"] or "")[:80])


if __name__ == "__main__":
    for p in sys.argv[1:]:
        summarize(p)
