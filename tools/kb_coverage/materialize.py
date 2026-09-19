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
        "contentFingerprint": hashlib.sha256(f"{top_dir}|{subject}".encode("utf-8")).hexdigest().upper(),
        # 源导入时间取过去时刻：契约要求 material.reviewedAt >= source.importedAt，
        # 若同刻抓取，写入顺序会让 source 比 material 晚几毫秒 → 整批导入被拒（KD-15）。
        "importedAtEpochMillis": _now_ms() - 60_000,
        "contentUsePolicy": "REVIEWED_SYNTHESIS_ONLY",
        "licenseExpression": None,
        "licenseUri": None,
        "attributionText": "桌面教辅资料直抽改写（REVIEWED_SYNTHESIS），仅内部教学参考。",
    }


def _existing_source_entries() -> dict[str, dict]:
    """把各卷里已有的 source 条目按 sourceId 汇总，取 importedAt 最早的那份。

    为什么必须复用而不是重新生成：同一个 sourceId 会随不同批次落进不同卷，各卷带的
    importedAt 是各自写入时刻。Kotlin 侧 distinctBy 只认**先出现**的那份，于是后写的
    那份（时间更晚）可能成为生效值，让先前批次里 reviewedAt 更早的材料"早于来源导入"
    —— 正是 KD-15 的同类失败。复用最早时间戳可让任意写入顺序都不触发契约。
    """
    out: dict[str, dict] = {}
    for sp in pack_io.sidecar_paths():
        try:
            doc = pack_io.load_json(sp)
        except Exception:  # noqa: BLE001 - 单卷读不出不该打断整批
            continue
        for s in doc.get("sources") or []:
            sid = s.get("sourceId")
            if not sid:
                continue
            prev = out.get(sid)
            if prev is None or (s.get("importedAtEpochMillis") or 0) < (prev.get("importedAtEpochMillis") or 0):
                out[sid] = s
    return out


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


def _sidecar_state() -> dict:
    """一次读齐各卷，并算好「每卷字符数 + 每科材料数」；之后靠增量维护。

    为什么必须增量：写入是按行循环的，而"选目标卷"原先每行都要把全部卷的**所有材料**
    重新加载与计数（实测 16,623 行 × 20,456 条 ≈ 每秒几十行的爬行速度，一轮跑不完）。
    改成一次装载 + 追加时增量记账，选卷退化为 O(卷数)。
    """
    state: dict = {"paths": [], "docs": {}, "counts": {}, "sizes": {}}
    for p in pack_io.sidecar_paths():
        state["paths"].append(p)
        if not p.exists():           # 已登记进索引、还没落盘的空卷
            state["docs"][p] = {"schemaVersion": 2, "packId": "moe-2025-four-subjects-v1",
                                "sources": [], "materials": []}
            state["counts"][p] = {}
            state["sizes"][p] = 0
            continue
        doc = pack_io.load_json(p)
        state["docs"][p] = doc
        c: dict[str, int] = {}
        for m in doc["materials"]:
            c[m["subject"]] = c.get(m["subject"], 0) + 1
        state["counts"][p] = c
        state["sizes"][p] = len(pack_io.serialize(doc))
    return state


def _pick_sidecar(state: dict, subject: str) -> Path:
    """选目标卷：**在未超限的卷里**取同科材料最少的；全都超限才开新卷并登记索引。

    两个曾经踩过的坑：
    1. 静默丢数据：滚动只返回新路径、不登记索引 → 每行都滚到同一个"未登记的新卷名"并各自
       新建空 doc（4,606 条只活下来 1 条）。
    2. **索引污染**：只在"并列最小"里挑第一个，会把已超 2.5M 的老卷选回来 → 每行都触发滚动，
       一轮登出 2,971 个从未落盘的空卷名。现在先过滤掉超限卷，只有全超限才真的开新卷。
    """
    usable = [p for p in state["paths"] if state["sizes"][p] + 5000 <= ROLL_AT_CHARS]
    if usable:
        return min(usable, key=lambda p: state["counts"][p].get(subject, 0))
    new = pack_io.next_sidecar_path()
    pack_io.write_sidecar_index([*state["paths"], new])
    state["paths"].append(new)
    state["docs"][new] = {"schemaVersion": 2, "packId": "moe-2025-four-subjects-v1",
                          "sources": [], "materials": []}
    state["counts"][new] = {}
    state["sizes"][new] = 0
    return new


def _note_appended(state: dict, target: Path, material: dict, subject: str) -> None:
    """增量记账：追加一条材料后更新该卷的字符数与同科计数（选卷依据）。"""
    state["sizes"][target] += len(pack_io.serialize(material))
    counts = state["counts"][target]
    counts[subject] = counts.get(subject, 0) + 1


def write(judgments: list[dict]) -> dict:
    """落盘：材料进 sidecar（源条目随行）、状态机推进。幂等靠状态机。"""
    pl = plan(judgments)
    if pl["errors"]:
        raise ValueError("判定表有错，拒绝写盘：\n  " + "\n  ".join(pl["errors"][:10]))
    chunks = pl["chunks"]
    states = pl["states"]
    refs: dict[str, str] = {rel: st.get("output_ref", "") for rel, st in states.items()}
    state = _sidecar_state()
    # 已入库 slug 与它们的绑定目标：直接读 state 里已加载的卷，避免再读一遍磁盘
    # （也顺带容忍"索引里有、文件还没落盘"的空卷）。
    existing_slugs: set[str] = set()
    existing_targets: dict[str, str] = {}
    for doc in state["docs"].values():
        for m in doc["materials"]:
            existing_slugs.add(m["slug"])
            for b in m.get("bindings") or []:
                existing_targets.setdefault(m["slug"], b["knowledgeNodeId"].split(":")[-1])
    known_sources = _existing_source_entries()
    changed_sidecars: dict[Path, dict] = {}
    sources_added: dict[str, list[str]] = {}
    touched: set[str] = set()
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
            prev = existing_targets.get(slug)
            if prev != r["node_slug"]:
                raise ValueError(
                    f"slug 撞车：{slug} 已绑 {prev}，本行目标 {r['node_slug']}"
                    "（同块被两行复用时必须在判定表里给后一行填 midx 后缀 b/c/d）")
            continue  # 幂等：材料已实际入库（以 sidecar 实况为准，状态漂移也能自愈）
        top_dir = r["chunk_rel"].split("/", 1)[0] if "/" in r["chunk_rel"] else r["chunk_rel"]
        sid = _source_id(top_dir, subject)
        target = _pick_sidecar(state, subject)
        doc = state["docs"][target]
        changed_sidecars[target] = doc
        if not any(s["sourceId"] == sid for s in doc["sources"]):
            entry = known_sources.get(sid) or _source_entry(top_dir, subject)
            doc["sources"].append(dict(entry))
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
        _note_appended(state, target, doc["materials"][-1], subject)
        existing_slugs.add(slug)
        ref = refs.get(r["chunk_rel"], "")
        if slug not in ref:
            refs[r["chunk_rel"]] = (ref + "," if ref else "") + slug
            touched.add(r["chunk_rel"])
        done += 1
    # 先把材料落盘，再一次性推进状态机 —— 反过来的话，落盘失败就会留下"状态说已入库、
    # 磁盘上没有"的悬空（verify 的 output_ref 门会抓，但那时已经晚了）。
    for p, doc in changed_sidecars.items():
        pack_io.dump_json(doc, p)
    if touched:
        # 一次写完：es.mark 会重写整张状态表（10k+ 行），逐行调用等于每行重写一次 CSV
        # —— 实测这会把一轮 16,623 行的写入拖到几十分钟，并在 Windows 上把文件写坏。
        es.mark({rel: ("EXTRACTED", refs[rel]) for rel in sorted(touched)}, "materialize")
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
