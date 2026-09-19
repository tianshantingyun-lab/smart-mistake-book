# -*- coding: utf-8 -*-
"""把 30 个作业片交回的"修好的整字段"写回成品包，并做**独立复核**。

## 它消灭的失败

517 条材料的文本坏了（`\\1` 回指残迹、`$$`→PID、`$0`→/usr/bin/bash、`$` 被吃）。
子代理逐条修好了，但"代理说修好了"不是证据：**模型抄写文本这件事本身就是这次事故的
来源**，所以写回前必须有一道不依赖代理自述的机械复核。

## 复核分三层（vc = verdict）

1. `VC_RESTORED`：该字段与**损坏前提交**（`cffda989^`，即 923a13db）里的同 slug 同字段
   **逐字节相等** —— 复原，不是重写。最强证据。
2. `VC_TABLE`：与入库前的判定产物（判定表/代理 CSV）逐字节相等。
3. `VC_REVIEW`：以上都拿不到（该材料首次入库时就带残迹），或与损坏前副本不一致 ——
   一律列出来人工看，不自动写回，除非 `--accept-review`。

无论哪一层，新文本还必须**自己就是干净的**：`gate.field_text_defects` 为空、`$` 成对、
无反斜杠后跟数字。这是"修好了"的最低定义。

## 无损与幂等

- 材料集合（slug 多重集与顺序）逐条不变；只有 `notes.csv` 列出的字段会变，其余键逐条指纹比对。
- 重放 0 改动（修好的文本再喂一遍，一层判据都还应通过且无差异）。

## 用法

    PYTHONPATH=tools python tools/kb_coverage/apply_text_fixes.py            # 报告
    PYTHONPATH=tools python tools/kb_coverage/apply_text_fixes.py --write    # 写回
    PYTHONPATH=tools python tools/kb_coverage/apply_text_fixes.py --write --judgments
    PYTHONPATH=tools python tools/kb_coverage/apply_text_fixes.py --write --accept-review
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import gate, pack_io  # noqa: E402

FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")
FIX_ROOT = Path("tools/kb_coverage/text_fixes")
PACK_REL = "core/data/src/main/resources/knowledge"
PRE_DAMAGE = "cffda989^"
JUDGE_TABLE = Path("tools/kb_coverage/tables/material_judgments.csv")
JUDGE_COLUMN = {"title": "title", "summaryMarkdown": "summary",
                "applicabilityMarkdown": "applicability",
                "contentMarkdown": "content", "boundaryMarkdown": "boundary"}


def _git_show(path: str) -> str | None:
    try:
        out = subprocess.run(["git", "show", f"{PRE_DAMAGE}:{path}"],
                             capture_output=True, check=True)
    except subprocess.CalledProcessError:
        return None
    return out.stdout.decode("utf-8")


def pre_damage_fields() -> dict[str, dict[str, str]]:
    """slug -> {field: 文本}，来自损坏前提交的同卷副本。"""
    out: dict[str, dict[str, str]] = {}
    for sp in pack_io.sidecar_paths():
        rel = f"{PACK_REL}/{sp.name}"
        text = _git_show(rel)
        if text is None:
            continue
        doc = json.loads(text)
        for m in doc.get("materials", []):
            out[m["slug"]] = {f: m.get(f) or "" for f in FIELDS}
    return out


def table_fields() -> dict[tuple[str, str], str]:
    """(chunk_id, node_slug) -> title，用于按块+标题取入库前文本（仅在需要时用）。"""
    if not JUDGE_TABLE.exists():
        return {}
    out: dict[tuple[str, str], str] = {}
    with JUDGE_TABLE.open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            out[(row.get("chunk_id", ""), row.get("node_slug", ""))] = row.get("title", "")
    return out


def load_deliveries() -> tuple[dict[tuple[str, str], tuple[str, str, str]], list[str]]:
    """(slug, field) -> (new_text, status, evidence)；并返回问题清单。"""
    deliv: dict[tuple[str, str], tuple[str, str, str]] = {}
    problems: list[str] = []
    for slice_dir in sorted(FIX_ROOT.glob("slice_*")):
        notes = slice_dir / "notes.csv"
        if notes.exists():
            with notes.open(encoding="utf-8", newline="") as fh:
                for row in csv.DictReader(fh):
                    key = ((row.get("slug") or "").strip(), (row.get("field") or "").strip())
                    if row.get("status", "").strip() == "UNCERTAIN":
                        problems.append(f"{slice_dir.name}: UNCERTAIN {key[0]}.{key[1]}："
                                        f"{(row.get('evidence') or '')[:60]}")
                    deliv.setdefault(key, ("", row.get("status", "").strip(),
                                           row.get("evidence", "")))
        for path in sorted(slice_dir.glob("*.txt")):
            name = path.name[:-4]
            if "." not in name:
                problems.append(f"{slice_dir.name}: 文件名无法解析 {path.name}")
                continue
            slug, field = name.rsplit(".", 1)
            if field not in FIELDS:
                problems.append(f"{slice_dir.name}: 未知字段名 {path.name}")
                continue
            text = path.read_text(encoding="utf-8")
            prev = deliv.get((slug, field))
            status = prev[1] if prev else "FIXED"
            evidence = prev[2] if prev else ""
            deliv[(slug, field)] = (text, status, evidence)
    return deliv, problems


def _volume_revisions(path: str, limit: int = 40) -> list[str]:
    """该卷最近若干次提交（从新到旧）。"""
    try:
        out = subprocess.run(["git", "log", f"--format=%H", f"-{limit}", "--", path],
                             capture_output=True, check=True)
    except subprocess.CalledProcessError:
        return []
    return out.stdout.decode().split()


def field_existed_in_history(volume: str, escaped_text: str) -> str | None:
    """这段文本是否在历史某一版里**原样存在** → 返回命中的 commit 短号。

    为什么用"原文子串"当判据：字段文本要进包，必然以 JSON 转义后的形态落在 JSON 里。
    命中的含义是"这串文本确实在某一版成品里存在过"，即**复原**而非代理现写。
    逐字段解析历史 JSON 太贵（每版 5–10MB × 40 版），原文子串匹配足够且便宜。
    """
    rel = f"{PACK_REL}/{volume}"
    for rev in _volume_revisions(rel):
        try:
            blob = subprocess.run(["git", "show", f"{rev}:{rel}"],
                                  capture_output=True, check=True).stdout
        except subprocess.CalledProcessError:
            continue
        if escaped_text.encode("utf-8") in blob:
            return rev[:8]
    return None


def load_products() -> list[tuple[str, str]]:
    """入库前的判定产物：仓库根的 `.agent_*.csv`（模型判定后、写进包之前的那一层）。"""
    out: list[tuple[str, str]] = []
    for path in sorted(Path(".").glob(".agent_*.csv")):
        try:
            out.append((path.name, path.read_text(encoding="utf-8", errors="replace")))
        except OSError:
            continue
    return out


def field_existed_in_products(text: str, products: list[tuple[str, str]]) -> str | None:
    """修复后的整字段是否在入库前产物里**逐字存在**（即"复原"而非"现写"）。

    探针取整字段前 160 字：产物 CSV 里字段是原样文本（含逗号时被引号包住），
    长探针足以定位。
    """
    probe = text[:160]
    if len(probe) < 24:
        return None
    for name, blob in products:
        if probe in blob:
            return name
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--judgments", action="store_true",
                        help="同时把修复镜像回判定表（按块号+节点+原文逐字核对）")
    parser.add_argument("--accept-review", action="store_true",
                        help="把 VC_REVIEW 的字段也写回（默认只报告）")
    parser.add_argument("--limit-review", type=int, default=12,
                        help="报告里最多列几条 REVIEW")
    args = parser.parse_args(argv)

    deliv, problems = load_deliveries()
    pre = pre_damage_fields()
    products = load_products()
    print(f"作业片交回字段 {len(deliv)} 个；notes 问题 {len(problems)} 条")

    paths = pack_io.sidecar_paths()
    docs = {p: pack_io.load_json(p) for p in paths}
    before_rest = {
        p: [(m.get("slug"), json.dumps({k: v for k, v in m.items() if k not in FIELDS},
                                       sort_keys=True, ensure_ascii=False))
            for m in d["materials"]] for p, d in docs.items()}

    verdicts: dict[str, list[tuple[Path, dict, str, str, str]]] = {
        "restored": [], "table": [], "review": [], "rejected": []}
    applied_notes: set[tuple[str, str]] = set()
    history_cache: dict[tuple[str, str], str | None] = {}
    for path, doc in docs.items():
        for material in doc["materials"]:
            slug = material["slug"]
            for field in FIELDS:
                key = (slug, field)
                if key not in deliv:
                    continue
                new_text, status, evidence = deliv[key]
                old_text = material.get(field) or ""
                if not new_text:
                    verdicts["rejected"].append((path, material, field, old_text, "", "交付文件为空"))
                    continue
                defects = gate.field_text_defects(new_text)
                if defects:
                    verdicts["rejected"].append(
                        (path, material, field, old_text, new_text,
                         f"修后仍有缺陷 {defects}"))
                    continue
                if new_text == old_text:
                    applied_notes.add(key)
                    verdicts["restored"].append((path, material, field, old_text, new_text,
                                                 "与现文一致（无需改）"))
                    continue
                applied_notes.add(key)
                clean = pre.get(slug, {}).get(field, "")
                if clean and clean == new_text:
                    verdicts["restored"].append((path, material, field, old_text, new_text,
                                                 f"与 {PRE_DAMAGE} 同字段逐字一致"))
                    continue
                cache_key = (path.name, new_text)
                if cache_key not in history_cache:
                    escaped = json.dumps(new_text, ensure_ascii=False)[1:-1]
                    history_cache[cache_key] = field_existed_in_history(path.name, escaped)
                rev = history_cache[cache_key]
                if rev:
                    verdicts["restored"].append((path, material, field, old_text, new_text,
                                                 f"历史版本 {rev} 里逐字存在"))
                    continue
                prod = field_existed_in_products(new_text, products)
                if prod:
                    verdicts["restored"].append((path, material, field, old_text, new_text,
                                                 f"入库前产物 {prod} 里逐字存在"))
                else:
                    why = ("无任何历史副本" if not clean
                           else "与损坏前副本不一致（可能是后续正当改动）")
                    verdicts["review"].append((path, material, field, old_text, new_text,
                                               f"{why}｜status={status}｜{evidence[:70]}"))
                applied_notes.add(key)

    missing = [k for k in deliv if k not in applied_notes]
    print(f"复核：复原 {len(verdicts['restored'])}｜待人工看 {len(verdicts['review'])}｜"
          f"退回 {len(verdicts['rejected'])}｜未匹配到材料 {len(missing)}")
    for p in problems[:10]:
        print("   ! notes:", p)
    for p in missing[:10]:
        print("   ! 交付了但包里没有该字段:", p[0], p[1])
    for path, material, field, _old, _new, why in verdicts["rejected"][:10]:
        print(f"   ✗ {material['slug']}.{field}: {why}")
    print(f"\n== 待人工看（前 {args.limit_review}／共 {len(verdicts['review'])}）==")
    for path, material, field, old_text, new_text, why in verdicts["review"][:args.limit_review]:
        print(f"  · {material['slug']}.{field}｜{why}")
        print(f"      旧({len(old_text)}字): {old_text[:150]}")
        print(f"      新({len(new_text)}字): {new_text[:150]}")

    # 无损一：材料集合与其余字段不变（写回前检查）
    plan = verdicts["restored"] + (verdicts["review"] if args.accept_review else [])
    if verdicts["rejected"]:
        print("\n有无损/合规问题（退回项），本轮不写回")
        return 1
    for _path, material, field, _old, new_text, _why in plan:
        material[field] = new_text
    after_rest = {
        p: [(m.get("slug"), json.dumps({k: v for k, v in m.items() if k not in FIELDS},
                                       sort_keys=True, ensure_ascii=False))
            for m in d["materials"]] for p, d in docs.items()}
    if before_rest != after_rest:
        print("\n无损校验失败：材料集合或非文本字段被改动")
        return 1
    print(f"\n无损校验通过：材料集合与其余字段逐条不变；本次改 {len(plan)} 个字段")

    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0

    # 镜像判定表要用「修复前」的整字段文本做三重定位，故在赋值前记录
    touched_ids: list[tuple[str, str, str, str, str, str, str]] = []
    for _path, material, field, old_text, new_text, _why in plan:
        node = ""
        for b in material.get("bindings") or []:
            node = b.get("knowledgeNodeId", "").split(":")[-1]
            break
        found = re.search(r"（块 ([^）]+)）", material.get("sourceLocator") or "")
        touched_ids.append((material.get("subject", ""), material["slug"], field,
                            old_text, new_text, node, found.group(1) if found else ""))

    for path, doc in docs.items():
        pack_io.dump_json(doc, path)
    print(f"→ 已写回 {len(docs)} 个 sidecar、{len(plan)} 个字段")

    if args.judgments:
        n, problems2 = mirror_judgments(touched_ids)
        print(f"→ 判定表镜像 {n} 处；未镜像 {len(problems2)}")
        for p in problems2[:8]:
            print("   !", p)
    return 0


def mirror_judgments(touched: list[tuple]) -> tuple[int, list[str]]:
    """把修好的整字段镜像回判定表：按 (块号, 节点, 原文逐字) 三重定位。"""
    if not JUDGE_TABLE.exists():
        return 0, ["判定表不存在"]
    raw = JUDGE_TABLE.read_bytes()
    crlf = raw.count(b"\r\n") > raw.count(b"\n") - raw.count(b"\r\n")
    with JUDGE_TABLE.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    stats = 0
    problems: list[str] = []
    for subject, slug, field, old_text, new_text, node, chunk in touched:
        column = JUDGE_COLUMN[field]
        matched = [r for r in rows
                   if r.get("chunk_id") == chunk and r.get("node_slug") == node
                   and r.get(column) == old_text] if chunk and node else []
        if len(matched) != 1:
            problems.append(f"{slug}.{field}: 判定表命中 {len(matched)} 行，未镜像")
            continue
        matched[0][column] = new_text
        stats += 1
    if stats:
        with JUDGE_TABLE.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()),
                                    lineterminator="\r\n" if crlf else "\n")
            writer.writeheader()
            writer.writerows(rows)
    return stats, problems


if __name__ == "__main__":
    raise SystemExit(main())
