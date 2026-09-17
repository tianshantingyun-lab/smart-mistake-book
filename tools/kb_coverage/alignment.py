# -*- coding: utf-8 -*-
"""课标内容要求 ↔ KB 原子节点 的对照台账构建器（覆盖口径设计 §3 的第一版：A↔C 两层）。

## 它消灭的失败

"覆盖高考考点"不可复核（考试大纲已废止，设计 §1）。可辩护的口径是
"覆盖 2025 课标内容要求 X/Y 条（UNDECIDED 除外）"——前提是每条都有
带证据的定位映射。本模块把判定结果落盘成可复算的台账：

- 判定表 `tables/curriculum_alignment.csv`（agent 语义判定的产物，逐条）
- 台账 `knowledge-production/kb-coverage-alignment-2026-v2.json`（发布形态）

## 纪律（来自设计 §3.3）

- 覆盖率 = COVERED / (总数 − UNDECIDED)，**同时公布分子、分母、UNDECIDED 绝对数**。
- UNDECIDED 不许默认落进 NOT_COVERED。
- 判定者是用户指定的模型语义判定通道（2026-09-16 决定），reviewer 显式标 AI，
  不冒充人工（I-05：词面匹配已证伪，必须语义判定）。

## 用法

    PYTHONPATH=tools python -m kb_coverage.alignment --check     # 判定表完整性门
    PYTHONPATH=tools python -m kb_coverage.alignment --build     # 构建 v2 台账
"""

from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path

from kb_build import pack_io

LEDGER_2025 = Path(__file__).resolve().parent.parent.parent / "knowledge-production" / \
    "knowledge-coverage-ledger-2025-v1.json"
TABLE = Path(__file__).resolve().parent / "tables" / "curriculum_alignment.csv"
OUTPUT = Path(__file__).resolve().parent.parent.parent / "knowledge-production" / \
    "kb-coverage-alignment-2026-v2.json"
COLUMNS = ("subject", "candidate_slug", "status", "target_node_slug",
           "evidence_phrase", "note")
SUBJECTS = ("MATH", "PHYSICS", "CHEMISTRY", "BIOLOGY")
STATUSES = ("COVERED", "PARTIAL", "NOT_COVERED", "UNDECIDED")
REVIEWER = "Qwen3.8-27B（用户指定语义判定通道，2026-09-16 决定）"


def load_candidates() -> dict[str, list[dict]]:
    """四科课标候选：subject -> [{slug, statement, sourceLocator}]（含 scope summary）。"""
    doc = json.loads(LEDGER_2025.read_text(encoding="utf-8"))
    out: dict[str, list[dict]] = {s: [] for s in SUBJECTS}
    for subj_doc in doc["subjects"]:
        subj = subj_doc["subject"]
        if subj not in out:
            continue
        for mod in subj_doc["modules"]:
            for kp in mod.get("knowledgePoints") or []:
                out[subj].append({"slug": kp["slug"], "statement": kp["name"],
                                  "sourceLocator": kp.get("sourceLocator", ""),
                                  "module": mod.get("name", "")})
            for ss in mod.get("scopeSummaries") or []:
                out[subj].append({"slug": ss["slug"], "statement": ss["name"],
                                  "sourceLocator": ss.get("sourceLocator", ""),
                                  "module": mod.get("name", ""), "scope": True})
    return out


def load_judgments() -> dict[tuple[str, str], dict]:
    if not TABLE.exists():
        return {}
    rows = list(csv.DictReader(open(TABLE, encoding="utf-8")))
    return {(r["subject"].strip(), r["candidate_slug"].strip()): r for r in rows}


def check() -> list[str]:
    """完整性门：每条候选恰有一行判定；状态合法；COVERED/PARTIAL 的 target 必须在包中。"""
    problems: list[str] = []
    cands = load_candidates()
    judg = load_judgments()
    pack = pack_io.load_json(pack_io.pack_path())
    nodes: dict[str, set[str]] = {s: set() for s in SUBJECTS}
    for s, _t, k in pack_io.iter_points(pack):
        if s in nodes:
            nodes[s].add(k["slug"])
    for subj in SUBJECTS:
        want = {c["slug"] for c in cands[subj]}
        got = {k[1] for k in judg if k[0] == subj}
        for slug in sorted(want - got):
            problems.append(f"{subj} 缺判定：{slug}")
        for slug in sorted(got - want):
            problems.append(f"{subj} 多余判定（候选里没有）：{slug}")
        for (s, slug), r in judg.items():
            if s != subj:
                continue
            st = r["status"].strip()
            tgt = r["target_node_slug"].strip()
            if st not in STATUSES:
                problems.append(f"{subj}/{slug} 非法状态 {st}")
            if st in ("COVERED", "PARTIAL") and tgt not in nodes[subj]:
                problems.append(f"{subj}/{slug} target 不在包中：{tgt}")
            if st in ("NOT_COVERED", "UNDECIDED") and tgt:
                problems.append(f"{subj}/{slug} {st} 不应有 target：{tgt}")
    return problems


def build_doc() -> dict:
    """纯函数：判定表 + 候选 + 成品包 → 台账文档（不落盘）。"""
    problems = check()
    if problems:
        raise ValueError(f"台账不完整，拒绝构建：{problems[:10]}")
    cands = load_candidates()
    judg = load_judgments()
    pack = pack_io.load_json(pack_io.pack_path())
    now = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d")
    subjects_out = []
    for subj in SUBJECTS:
        rows = []
        for c in cands[subj]:
            r = judg[(subj, c["slug"])]
            st = r["status"].strip()
            tgt = r["target_node_slug"].strip()
            rows.append({
                "candidateRef": f"candidate:moe:2025:{subj.lower()}:curriculum-text/{c['slug']}",
                "statement": c["statement"],
                "module": c["module"],
                "scopeSummary": bool(c.get("scope")),
                "sourceLocator": c["sourceLocator"],
                "status": st,
                "targetRef": f"kb:{pack['packId']}:{subj.lower()}:atomic:{tgt}" if tgt else None,
                "evidencePhrase": r["evidence_phrase"].strip(),
                "reviewState": "AI_REVIEWED",
                "reviewer": REVIEWER,
                "reviewedAt": now,
                "note": r["note"].strip(),
            })
        cnt = {s: sum(1 for x in rows if x["status"] == s) for s in STATUSES}
        denom = len(rows) - cnt["UNDECIDED"]
        subjects_out.append({
            "subject": subj,
            "statements": rows,
            "counts": {
                "total": len(rows), **cnt,
                "coverageNumerator": cnt["COVERED"],
                "coverageDenominator": denom,
                "coverage": round(cnt["COVERED"] / denom, 4) if denom else None,
            },
        })
    total = {s: sum(x["counts"][s] for x in subjects_out) for s in STATUSES}
    doc = {
        "schemaVersion": 1,
        "alignmentId": "kb-coverage-alignment-2026-v2",
        "baseline": "2025 课标内容要求（source-register-2025-v1；2026-09-18 九科 PDF 指纹全部逐字节核验——数学/语/英/政/史/地 6 份重下并核验，物/化/生 3 份在盘核验）",
        "targetPack": pack["packId"],
        "reviewer": REVIEWER,
        "reviewedAt": now,
        "subjects": subjects_out,
        "total": {
            **total,
            "coverageNumerator": total["COVERED"],
            "coverageDenominator": sum(x["counts"]["coverageDenominator"] for x in subjects_out),
        },
        "publishedRule": "覆盖率 = COVERED / (总数 − UNDECIDED)；分子、分母、UNDECIDED 绝对数同时公布",
    }
    return doc


def build() -> dict:
    doc = build_doc()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
    return doc


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--build", action="store_true")
    args = ap.parse_args(argv)
    if args.check or (not args.build):
        problems = check()
        for subj in SUBJECTS:
            n_cand = len(load_candidates()[subj])
            n_judg = sum(1 for k in load_judgments() if k[0] == subj)
            print(f"{subj:<10} 候选 {n_cand}，已判定 {n_judg}")
        if problems:
            print(f"台账不完整：{len(problems)} 项，前 10：")
            for p in problems[:10]:
                print("  -", p)
            return 1
        print("台账完整：全部候选恰有一行判定，target 均在包中")
    if args.build:
        doc = build()
        print(f"已写 {OUTPUT}")
        for s in doc["subjects"]:
            c = s["counts"]
            print(f"{s['subject']:<10} total={c['total']} COVERED={c['COVERED']} "
                  f"PARTIAL={c['PARTIAL']} NOT_COVERED={c['NOT_COVERED']} "
                  f"UNDECIDED={c['UNDECIDED']} 覆盖={c['coverage']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
