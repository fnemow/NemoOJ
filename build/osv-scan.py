#!/usr/bin/env python3
"""
依赖安全扫描脚本 —— 基于 OSV (https://osv.dev) API。

背景：OWASP dependency-check 需要同步体积很大的 NVD 数据库，在国内网络下经常超时；
OSV 提供开放批量查询接口，聚合了 GitHub Security Advisories、NVD、GHSA 等来源，
无需 API Key，适合作为本地/CI 的快速扫描手段。

用法：
    python build/osv-scan.py <deps.txt> [<deps2.txt> ...]

输入：`mvn dependency:list -DincludeScope=runtime -DoutputFile=deps.txt` 的输出。
输出：标准输出打印漏洞摘要；同时写出 osv-report.json（原始响应）。

注意：本脚本只报告事实，不对"是否可修复"做假设。
"""
import json
import re
import sys
import urllib.request
import urllib.error
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
OSV_VULN_URL = "https://api.osv.dev/v1/vulns/"
BATCH_SIZE = 200

# querybatch 只返回 id/modified，细节需按 id 单独获取
VULN_DETAIL_WORKERS = 8

# mvn dependency:list 的行格式: groupId:artifactId:type:version:scope
DEP_LINE = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):(\w+):([\w.\-]+):([\w\-.]+)\s*$")

SEVERITY_ORDER = {"CRITICAL": 0, "HIGH": 1, "MODERATE": 2, "MEDIUM": 2, "LOW": 3, "": 4}


def parse_deps(paths):
    """解析依赖清单，返回去重后的 (group, artifact, version) 列表。"""
    deps = OrderedDict()
    for path in paths:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = DEP_LINE.match(line)
                if not m:
                    continue
                group, artifact, _type, version, _scope = m.groups()
                # 跳过本项目的内部模块（内部模块随本仓库一起发布，不通过 OSV 跟踪）
                if group.startswith("com.nemo"):
                    continue
                deps[(group, artifact, version)] = True
    return list(deps.keys())


def query_osv(deps):
    """批量查询 OSV，返回 {包名: [漏洞]} 的原始响应列表。"""
    results = []
    for start in range(0, len(deps), BATCH_SIZE):
        chunk = deps[start:start + BATCH_SIZE]
        payload = {
            "queries": [
                {"package": {"name": f"{g}:{a}", "ecosystem": "Maven"}, "version": v}
                for (g, a, v) in chunk
            ]
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            OSV_BATCH_URL, data=data,
            headers={"Content-Type": "application/json"},
            method="POST")
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        for dep, item in zip(chunk, body.get("results", [])):
            for vuln in item.get("vulns", []) or []:
                results.append({"dep": dep, "vuln": vuln})
        print(f"  已扫描 {min(start + BATCH_SIZE, len(deps))}/{len(deps)} 个依赖",
              file=sys.stderr)
    return results


def fetch_detail(vuln_id):
    """按 id 获取漏洞详情；失败时返回 None，由调用方降级处理。"""
    try:
        req = urllib.request.Request(OSV_VULN_URL + vuln_id)
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as exc:  # 网络抖动不应中断整个扫描
        print(f"  获取 {vuln_id} 详情失败: {exc}", file=sys.stderr)
        return None


def cvss_score_of(vuln):
    """
    从 OSV 详情中提取 CVSS 分数。
    OSV 的 severity[].score 为 CVSS 向量字符串（如 CVSS:3.1/AV:N/...），
    其中不含数值分数，需要用 database_specific.cvss.score 或向量本身。
    """
    db = vuln.get("database_specific", {}) or {}
    cvss = db.get("cvss") or {}
    score = cvss.get("score")
    if isinstance(score, (int, float)):
        return float(score)
    for sev in vuln.get("severity", []) or []:
        score = sev.get("score")
        if isinstance(score, (int, float)):
            return float(score)
    return None


def severity_of(vuln):
    """优先使用数据源标注的等级，其次由 CVSS 分数换算，缺失时返回空串（不臆测）。"""
    for affected in vuln.get("affected", []) or []:
        sev = (affected.get("ecosystem_specific") or {}).get("severity")
        if sev:
            return str(sev).upper()
    db = vuln.get("database_specific", {}) or {}
    if db.get("severity"):
        return str(db["severity"]).upper()

    score = cvss_score_of(vuln)
    if score is None:
        return ""
    if score >= 9.0:
        return "CRITICAL"
    if score >= 7.0:
        return "HIGH"
    if score >= 4.0:
        return "MEDIUM"
    if score > 0:
        return "LOW"
    return "NONE"


def version_key(version):
    """版本号排序键，保证 9.0.9 < 9.0.10 这类数字顺序正确。"""
    nums = [int(n) for n in re.findall(r"\d+", version or "")]
    return nums or [0]


def release_line(version):
    """取版本号的主次版本前缀，如 9.0.68 -> '9.0'，4.1.84.Final -> '4.1'。"""
    nums = re.findall(r"\d+", version or "")
    return ".".join(nums[:2]) if len(nums) >= 2 else version


def relevant_fixes(current_version, fixes):
    """
    只保留与当前依赖同一发布线的修复版本。
    OSV 的 affected.ranges 会同时列出 8.5.x / 10.x / 11.x 等其它主线，
    对判断"本项目升到哪一版"没有意义，直接展示会造成误导。
    """
    line = release_line(current_version)
    return [f for f in fixes if release_line(f) == line]


def fixed_versions(vuln):
    """收集该漏洞在各 affected 段声明的修复版本。"""
    fixed = set()
    for affected in vuln.get("affected", []) or []:
        for rng in affected.get("ranges", []) or []:
            for ev in rng.get("events", []) or []:
                if "fixed" in ev:
                    fixed.add(ev["fixed"])
    return sorted(fixed)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    deps = parse_deps(sys.argv[1:])
    print(f"解析到 {len(deps)} 个去重依赖（不含 com.nemo 内部模块）", file=sys.stderr)

    findings = query_osv(deps)

    # 补充详情（严重度、修复版本），按 id 去重后并发获取
    unique_ids = sorted({f["vuln"].get("id") for f in findings if f["vuln"].get("id")})
    print(f"获取 {len(unique_ids)} 条漏洞详情…", file=sys.stderr)
    details = {}
    with ThreadPoolExecutor(max_workers=VULN_DETAIL_WORKERS) as pool:
        for vid, detail in zip(unique_ids, pool.map(fetch_detail, unique_ids)):
            if detail:
                details[vid] = detail

    report = []
    for f in findings:
        vid = f["vuln"].get("id")
        detail = details.get(vid) or f["vuln"]
        g, a, v = f["dep"]
        report.append({
            "dependency": f"{g}:{a}",
            "version": v,
            "id": vid,
            "aliases": detail.get("aliases", []),
            "severity": severity_of(detail),
            "cvss": cvss_score_of(detail),
            "summary": (detail.get("summary") or "").strip(),
            "fixedVersions": fixed_versions(detail),
        })

    with open("osv-report.json", "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)

    if not findings:
        print("\n未发现 OSV 收录的漏洞。")
        return 0

    # 按依赖聚合
    by_dep = OrderedDict()
    for r in report:
        by_dep.setdefault((r["dependency"], r["version"]), []).append(r)

    rows = []
    for (name, ver), items in by_dep.items():
        worst = min((i["severity"] or "" for i in items),
                    key=lambda s: SEVERITY_ORDER.get(s, 4))
        ids = ", ".join(sorted({i["id"] for i in items}))
        fixes = set()
        for i in items:
            fixes.update(i["fixedVersions"])
        same_line = relevant_fixes(ver, fixes)
        rows.append((SEVERITY_ORDER.get(worst, 4), name, ver, len(items),
                     worst or "未标注", ids,
                     ", ".join(sorted(same_line, key=version_key)) or "无同线修复版"))

    rows.sort(key=lambda r: (r[0], r[1]))

    print(f"\n发现 {len(report)} 条漏洞记录，涉及 {len(by_dep)} 个依赖\n")
    print(f"{'依赖':<58} {'当前版本':<17} {'严重度':<9} {'数量':<5} {'修复版本'}")
    print("-" * 130)
    for _ord, name, ver, cnt, worst, ids, fixes in rows:
        print(f"{name:<58} {ver:<17} {worst:<9} {cnt:<5} {fixes}")

    # 严重度分布
    dist = OrderedDict()
    for r in report:
        key = r["severity"] or "未标注"
        dist[key] = dist.get(key, 0) + 1
    print("\n严重度分布: " + ", ".join(f"{k}={v}" for k, v in dist.items()))

    print("\n详细清单（按依赖，仅列 HIGH/CRITICAL/MEDIUM）:")
    for _ord, name, ver, cnt, worst, ids, fixes in rows:
        if worst in ("HIGH", "CRITICAL", "MEDIUM"):
            print(f"{name}:{ver}\n    {ids}")
    print("\n完整结果已写入 osv-report.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
