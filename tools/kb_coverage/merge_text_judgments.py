# -*- coding: utf-8 -*-
"""合并两批文本判定产物 → 一张可入库的判定表（含三处修复）。

## 它消灭的失败

1. **`chunk_rel` 被写成占位符/截断路径**（实测：1 个代理写 `@REL@`、另一个把路径截到目录名）。
   这类行直接入库会被 materialize 判"块不存在"而整批拒绝。修复依据是 chunk_id 的哈希前缀——
   它是 `sha256(rel_path)[:10]`，可唯一反查回真实文件（chunks 表就是权威映射）。
2. **同一个块被两个代理各判一次**（切片重切后，旧片产物覆盖的块也落在新片里）→ 会产生重复材料。
   修复：按 `(chunk_rel, chunk_id)` 分组，同组内按出现顺序重排 `midx`，组间不再产生重复行。
3. **节点写法不逐字**（大小写、误用候选表第 2 列的名称）→ 能唯一命中的改成规范 slug，
   改不动的记为 `NEW:` 待上层建点，不静默丢弃。

路径纪律：所有输入输出都从 `pack_io.REPO` 出发，且**打开前逐条做包含性校验**
（`resolve()` 后必须仍在仓库根之内，拒绝 `..` 与绝对路径）——glob 结果同样过这道门，
不因为"是我自己扫出来的"就免检。

用法：
    PYTHONPATH=tools python -m kb_coverage.merge_text_judgments
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

REPO = Path(pack_io.REPO).resolve()
AGENT_INPUT = REPO / "build" / "agent-input"
OUT_CSV = AGENT_INPUT / "merged_text_batch.csv"
OUT_NEW = AGENT_INPUT / "text_new_nodes.json"
CHUNKS = REPO / "tools" / "kb_coverage" / "tables" / "extracted_chunks.jsonl"
HDR = ["chunk_rel", "chunk_id", "action", "node_slug", "type", "title", "summary",
       "applicability", "content", "boundary", "note", "midx"]
TYPES = {"CONCEPT_EXPLANATION", "METHOD_MODEL", "WORKED_EXAMPLE", "COMPLETE_SOLUTION",
         "DERIVATION", "MISCONCEPTION_GUIDE", "REPRESENTATION_GUIDE"}


def within_repo(path: Path) -> Path:
    """把路径规范化到仓库内；越界（`..`、绝对路径、符号链接逃逸）直接报错。"""
    resolved = path.resolve()
    if resolved != REPO and REPO not in resolved.parents:
        raise ValueError(f"路径越出仓库根：{path}")
    return resolved


def load_chunk_registry() -> tuple[set[tuple[str, str]], dict[str, str]]:
    """返回 (已知 (rel, chunk) 集合, 哈希前缀 → rel_path)。"""
    known: set[tuple[str, str]] = set()
    by_hash: dict[str, str] = {}
    with within_repo(CHUNKS).open(encoding="utf-8") as f:
        for line in f:
            if not line.strip():
                continue
            d = json.loads(line)
            known.add((d["rel_path"], d["chunk_id"]))
            by_hash.setdefault(d["chunk_id"].split("-", 1)[0], d["rel_path"])
    return known, by_hash


def collect_files(only: str = "") -> list[Path]:
    """文本判定产物：`.agent_t_*`（化学/生物第一批）、`.agent_t2_*`（其第二批）、
    `.agent_mp_*`（数学/物理第一批）、`.agent_mp2_*`（数学/物理第二批），都在仓库根。
    `only` 非空时只收名字里含该片段的前缀（避免把上一批已入库的产物再合并一遍）。"""
    prefixes = [".agent_t_*.csv", ".agent_t2_*.csv", ".agent_mp_*.csv", ".agent_mp2_*.csv"]
    if only:
        prefixes = [p for p in prefixes if only in p]
    found: list[Path] = []
    for prefix in prefixes:
        found += sorted(REPO.glob(prefix))
    return [within_repo(p) for p in found if p.is_file()]


def subject_of(rel: str) -> str | None:
    for key, subj in (("化学", "CHEMISTRY"), ("生物", "BIOLOGY"), ("物理", "PHYSICS"), ("数学", "MATH")):
        if key in rel:
            return subj
    return None


def merge(only: str = "") -> dict:
    known, by_hash = load_chunk_registry()
    pack = pack_io.load_json(pack_io.pack_path())
    slug_by = {s["subject"]: {kp["slug"] for t in s["topics"] for kp in (t.get("knowledgePoints") or [])}
               for s in pack["subjects"]}
    lower_by = {k: {x.lower(): x for x in v} for k, v in slug_by.items()}
    name_by = {s["subject"]: {kp["name"]: kp["slug"] for t in s["topics"]
                              for kp in (t.get("knowledgePoints") or [])} for s in pack["subjects"]}

    stats = Counter()
    problems: list[str] = []
    repairs: Counter[str] = Counter()
    rows: list[dict] = []
    files = collect_files(only)
    for f in files:
        with f.open(encoding="utf-8") as fh:
            data = list(csv.reader(fh))
        if not data or data[0] != HDR:
            problems.append(f"{f.name}: 表头不符")
            continue
        for j, r in enumerate(data[1:], 2):
            if len(r) != 12:
                problems.append(f"{f.name}:{j} 列数 {len(r)}")
                continue
            rec = dict(zip(HDR, r))
            rel, cid = rec["chunk_rel"], rec["chunk_id"]
            if (rel, cid) not in known:
                guess = by_hash.get(cid.split("-", 1)[0])
                if guess and (guess, cid) in known:
                    rec["chunk_rel"] = guess
                    repairs["chunk_rel 按哈希回填"] += 1
                else:
                    problems.append(f"{f.name}:{j} 块不存在 {rel[:40]} / {cid}")
                    continue
            if rec["action"] != "MATERIAL":
                problems.append(f"{f.name}:{j} action={rec['action']}")
                continue
            if rec["type"] not in TYPES:
                problems.append(f"{f.name}:{j} type={rec['type']}")
                continue
            subj = subject_of(rec["chunk_rel"])
            ns = rec["node_slug"]
            if not ns.startswith("NEW:"):
                if subj is None:
                    problems.append(f"{f.name}:{j} 学科未识别")
                    continue
                if ns not in slug_by[subj]:
                    if ns.lower() in lower_by[subj]:
                        rec["node_slug"] = lower_by[subj][ns.lower()]
                        repairs["slug 大小写"] += 1
                    elif ns in name_by[subj]:
                        rec["node_slug"] = name_by[subj][ns]
                        repairs["slug 用名称"] += 1
                    else:
                        rec["node_slug"] = f"NEW:{ns}"
                        repairs["未知节点转 NEW"] += 1
            rows.append(rec)
            stats["rows_in"] += 1

    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in rows:
        grouped[(r["chunk_rel"], r["chunk_id"])].append(r)
    merged: list[dict] = []
    suffix = ["", "b", "c", "d", "e", "f", "g", "h"]
    for key, group in grouped.items():
        stats["chunks"] += 1
        if len(group) > len(suffix):
            problems.append(f"同一块判定行数过多 {key} × {len(group)}")
        for n, rec in enumerate(group):
            rec["midx"] = suffix[n] if n < len(suffix) else f"x{n}"
            merged.append(rec)
    stats["rows_after_dedupe"] = len(merged)

    new_names = Counter(r["node_slug"][4:] for r in merged if r["node_slug"].startswith("NEW:"))
    target = within_repo(OUT_CSV)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=HDR)
        w.writeheader()
        w.writerows(merged)
    within_repo(OUT_NEW).write_text(
        json.dumps({"new": dict(new_names)}, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"files": len(files), "stats": stats, "problems": problems, "repairs": repairs,
            "new": new_names}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--only", default="", help="只收文件名含该片段的前缀，例如 mp")
    args = ap.parse_args(argv)
    res = merge(args.only)
    print(f"输入文件 {res['files']} 个；输入行 {res['stats']['rows_in']}；块 {res['stats']['chunks']}；"
          f"合并行 {res['stats']['rows_after_dedupe']}")
    print("修复：", dict(res["repairs"]))
    print(f"问题 {len(res['problems'])}")
    for p in res["problems"][:10]:
        print("   !", p)
    print(f"NEW 节点 {len(res['new'])} 个（引用 {sum(res['new'].values())} 行）")
    for n, c in res["new"].most_common(12):
        print(f"   {n[:44]} ×{c}")
    print(f"→ {OUT_CSV}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
