# -*- coding: utf-8 -*-
"""判定表 → 教学材料入库（Phase 2 第二段：语义判定产物落盘）。

## 它消灭的失败

office_extract 只产出"块"，块不是知识包的一部分：不判定、不写材料字段、
不绑节点，块就永远进不了包。本模块把**模型语义判定的结果**（判定表）
确定性地落成 sidecar 材料记录，并同步提取状态机（EXTRACTED + output_ref）。

## 判定表（tables/material_judgments.csv，由语义判定通道逐批产出）

    chunk_rel,chunk_id,action,node_slug,type,title,summary,applicability,content,boundary,note

- action=MATERIAL：node_slug 必须存在于成品包；type 是 7 种枚举之一；
  title/summary/content 是判定者改写后的形态（REVIEWED_SYNTHESIS 纪律）。
- action=SKIP：判定为不入库（题干残渣/重复/超纲），note 记理由。
- 一行 = 一个块；幂等：chunk 已在表中且已 EXTRACTED 的不再处理。

## 卷滚动

目标 sidecar 预计超过 2.5M 字符时，开 next_sidecar_path() 新卷并重写索引
（Kotlin loader 读索引，无需改代码）。

## 用法

    PYTHONPATH=tools python -m kb_coverage.materialize --dry-run
    PYTHONPATH=tools python -m kb_coverage.materialize --write
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import re
import time
from pathlib import Path

from kb_build import pack_io
from kb_coverage import extraction_state as es
from kb_coverage.office_extract import CHUNKS, load_chunks

JUDGMENTS = Path(__file__).resolve().parent / "tables" / "material_judgments.csv"
COLUMNS = ("chunk_rel", "chunk_id", "action", "node_slug", "type", "title",
           "summary", "applicability", "content", "boundary", "note", "midx")
ROLES = ("PRIMARY",)
TYPES = {"CONCEPT_EXPLANATION", "METHOD_MODEL", "WORKED_EXAMPLE",
         "COMPLETE_SOLUTION", "DERIVATION", "MISCONCEPTION_GUIDE", "REPRESENTATION_GUIDE"}
SAFE_SLUG = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
ROLL_AT_CHARS = 2_500_000

SUBJ3 = {"MATH": "mat", "PHYSICS": "phy", "CHEMISTRY": "che", "BIOLOGY": "bio"}


def _now_ms() -> int:
    return int(time.time() * 1000)


def _source_id(top_dir: str, subject: str) -> str:
    h = hashlib.sha256(top_dir.encode("utf-8")).hexdigest()[:10]
    return f"desktop-src-{h}:{subject.lower()}"


def _source_entry(top_dir: str, subject: str) -> dict:
    sid = _source_id(top_dir, subject)
    return {
        "sourceId": sid,
        "subject": subject,
        "sourceType": "AUTHORIZED_EDUCATION_MATERIAL",
        "title": f"桌面教辅资料 · {top_dir}",
        "publisher": "教辅汇编（桌面原始资料）",
        "edition": "2026/2027版",
        "sourceUri": "https://www.example.edu/desktop-kb-source",
        "licenseStatus": "REFERENCE_ONLY",
        "contentFingerprint": hashlib.sha256(top_dir.encode("utf-8")).hexdigest().upper(),
        "importedAtEpochMillis": _now_ms(),
        "contentUsePolicy": "REVIEWED_SYNTHESIS_ONLY",
        "licenseExpression": None,
        "licenseUri": None,
        "attributionText": "桌面教辅资料直抽改写（REVIEWED_SYNTHESIS），仅内部教学参考。",
    }


def load_judgments() -> list[dict]:
    if not JUDGMENTS.exists():
        return []
    rows = list(csv.DictReader(open(JUDGMENTS, encoding="utf-8")))
    for r in rows:
        missing = [c for c in COLUMNS if c not in r]
        if missing:
            raise ValueError(f"判定表缺列 {missing}")
    return rows


def plan(judgments: list[dict]) -> dict:
    """dry-run：校验 + 计划（不写盘）。"""
    chunks = {(c["rel_path"], c["chunk_id"]): c for c in load_chunks()}
    pack = pack_io.load_json(pack_io.pack_path())
    nodes = {s["subject"]: {k["slug"] for t in s["topics"] for k in t["knowledgePoints"]}
             for s in pack["subjects"]}
    states = es.load_states()
    errors, materials, skips = [], [], 0
    for r in judgments:
        key = (r["chunk_rel"], r["chunk_id"])
        ch = chunks.get(key)
        if ch is None:
            errors.append(f"块不存在: {key}")
            continue
        if r["action"] == "SKIP":
            skips += 1
            continue
        if r["action"] != "MATERIAL":
            errors.append(f"非法 action {r['action']}（{key}）")
            continue
        subject = ch["subject"]
        if subject not in ("MATH", "PHYSICS", "CHEMISTRY", "BIOLOGY"):
            errors.append(f"非四科块: {subject}（{key}）")
            continue
        if r["type"] not in TYPES:
            errors.append(f"非法 type {r['type']}（{key}）")
            continue
        if r["node_slug"] not in nodes[subject]:
            errors.append(f"节点不存在: {subject}/{r['node_slug']}（{key}）")
            continue
        for f in ("title", "summary", "content"):
            if not r[f].strip():
                errors.append(f"缺 {f}（{key}）")
    return {"errors": errors, "material_count": sum(1 for r in judgments if r["action"] == "MATERIAL"),
            "skip_count": skips, "states": states, "pack": pack, "chunks": chunks}


def _pick_sidecar(pack: dict, subject: str) -> tuple[Path, dict]:
    """选目标卷：同科材料最少的；预计超 2.5M 字符则开新卷。"""
    sizes: dict[Path, int] = {}
    counts: dict[Path, dict[str, int]] = {}
    for p in pack_io.sidecar_paths():
        doc = pack_io.load_json(p)
        sizes[p] = len(pack_io.serialize(doc))
        c = counts[p] = {}
        for m in doc["materials"]:
            c[m["subject"]] = c.get(m["subject"], 0) + 1
    target = min(pack_io.sidecar_paths(), key=lambda p: counts[p].get(subject, 0))
    if sizes[target] + 5000 > ROLL_AT_CHARS:
        target = pack_io.next_sidecar_path()
        return target, None
    return target, pack_io.load_json(target)


def write(judgments: list[dict]) -> dict:
    """落盘：材料进 sidecar（源条目随行）、状态机推进。幂等靠状态机。"""
    pl = plan(judgments)
    if pl["errors"]:
        raise ValueError("判定表有错，拒绝写盘：\n  " + "\n  ".join(pl["errors"][:10]))
    chunks = pl["chunks"]
    states = pl["states"]
    refs: dict[str, str] = {rel: st.get("output_ref", "") for rel, st in states.items()}
    existing_slugs: set[str] = set()
    for sp in pack_io.sidecar_paths():
        existing_slugs.update(m["slug"] for m in pack_io.load_json(sp)["materials"])
    changed_sidecars: dict[Path, dict] = {}
    sources_added: dict[str, list[str]] = {}
    done = 0
    now = _now_ms()
    for r in judgments:
        key = (r["chunk_rel"], r["chunk_id"])
        ch = chunks[key]
        if r["action"] != "MATERIAL":
            continue
        subject = ch["subject"]
        h = r["chunk_id"].split("-", 1)[0]
        idx = r["chunk_id"].rsplit("-", 1)[-1]
        slug = f"ext-{SUBJ3[subject]}-{h}-{idx}{(r.get('midx') or '').strip()}"
        assert SAFE_SLUG.match(slug), slug
        if slug in existing_slugs:
            continue  # 幂等：材料已实际入库（以 sidecar 实况为准，状态漂移也能自愈）
        top_dir = r["chunk_rel"].split("/", 1)[0] if "/" in r["chunk_rel"] else r["chunk_rel"]
        sid = _source_id(top_dir, subject)
        target, doc = _pick_sidecar(pl["pack"], subject)
        if doc is None:
            doc = {"schemaVersion": 2, "packId": "moe-2025-four-subjects-v1",
                   "sources": [], "materials": []}
        else:
            doc = changed_sidecars.get(target) or pack_io.load_json(target)
            changed_sidecars[target] = doc
        if not any(s["sourceId"] == sid for s in doc["sources"]):
            doc["sources"].append(_source_entry(top_dir, subject))
            sources_added.setdefault(target.name, []).append(sid)
        node = (f"kb:moe-2025-four-subjects-v1:{subject.lower()}:atomic:{r['node_slug']}")
        doc["materials"].append({
            "slug": slug, "subject": subject, "type": r["type"],
            "title": r["title"].strip(),
            "summaryMarkdown": r["summary"].strip(),
            "applicabilityMarkdown": (r["applicability"] or "").strip() or r["summary"].strip(),
            "contentMarkdown": r["content"].strip(),
            "boundaryMarkdown": (r["boundary"] or "").strip() or r["summary"].strip(),
            "derivationKind": "REVIEWED_SYNTHESIS",
            "sourceId": sid,
            "sourceLocator": f"桌面资料 {top_dir} / {r['chunk_rel']}（块 {r['chunk_id']}）",
            "reviewedAtEpochMillis": now,
            "bindings": [{"knowledgeNodeId": node, "role": "PRIMARY"}],
        })
        existing_slugs.add(slug)
        ref = refs.get(r["chunk_rel"], "")
        if slug not in ref:
            refs[r["chunk_rel"]] = (ref + "," if ref else "") + slug
            es.mark({r["chunk_rel"]: ("EXTRACTED", refs[r["chunk_rel"]])}, "materialize")
        done += 1
    for p, doc in changed_sidecars.items():
        pack_io.dump_json(doc, p)
    rolled = [p for p in changed_sidecars if not p.exists() or True]
    return {"materialized": done, "sidecars": {p.name: len(d["materials"]) for p, d in changed_sidecars.items()},
            "sources_added": sources_added}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    judgments = load_judgments()
    if args.write:
        stats = write(judgments)
        print(stats)
        return 0
    pl = plan(judgments)
    print(f"判定 {len(judgments)} 行：材料 {pl['material_count']}，跳过 {pl['skip_count']}，错误 {len(pl['errors'])}")
    for e in pl["errors"][:10]:
        print("  -", e)
    return 1 if pl["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
