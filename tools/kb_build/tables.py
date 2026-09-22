# -*- coding: utf-8 -*-
"""权威表：知识库内容的人工定稿层。

表位于 tools/kb_build/tables/，全部为 CSV（UTF-8，首行表头）。
表不存在 = 该项未定稿，生成器与门禁都按"未声明"处理（因此修前必然不通过）。

字段约定：
  chapter_map.csv       subject,slug,volume,chapter,theme
                        章节归属的唯一权威；未列出的节点继承基线归属
  alias_map.csv         subject,slug,alias        一行一条别名；未列出的节点别名清空
  boundary_map.csv      subject,slug,boundary     一行一条边界；未列出的节点边界清空
  prereq_map.csv        subject,slug,prerequisite 一行一条前置；未列出的节点无前置
  material_bindings.csv material_slug,point_slug  一行一条绑定；未列出则该材料不绑定

硬约束（生成器与门禁共同执行）：
- 别名只允许同专题来源，禁止跨专题（由 builder 校验）
- 边界必须是"适用范围/前提/易错"的结构化表述，不得是原文摘录（由内容门抽检）
- 前置必须成 DAG（无环），且不得跨科
"""

from __future__ import annotations

import csv
from pathlib import Path
from typing import Any, Iterable

TABLES_DIR: Path = Path(__file__).resolve().parent / "tables"

CHAPTER_MAP = "chapter_map.csv"
# 来源定位单元 -> 教材（册, 章），章节归属的主表（101 行，人工核对）
CHAPTER_BY_SOURCE = "chapter_by_source.csv"
ALIAS_MAP = "alias_map.csv"
BOUNDARY_MAP = "boundary_map.csv"
PREREQ_MAP = "prereq_map.csv"
MATERIAL_BINDINGS = "material_bindings.csv"


def _read(name: str) -> list[dict[str, str]]:
    path = TABLES_DIR / name
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as fh:
        return [row for row in csv.DictReader(fh)]


def _require_columns(name: str, rows: list[dict[str, str]], required: Iterable[str]) -> None:
    if not rows:
        return
    present = set(rows[0].keys())
    missing = set(required) - present
    if missing:
        raise ValueError(f"{name} 缺少列：{sorted(missing)}")


def load_chapter_by_source() -> dict[str, dict[str, str]]:
    """来源定位单元 -> {book, chapter, decision}。"""
    rows = _read(CHAPTER_BY_SOURCE)
    _require_columns(CHAPTER_BY_SOURCE, rows,
                     ("source_unit", "book", "chapter", "decision"))
    return {
        r["source_unit"].strip(): {
            "book": (r.get("book") or "").strip(),
            "chapter": (r.get("chapter") or "").strip(),
            "decision": (r.get("decision") or "").strip(),
        }
        for r in rows
    }


def load_chapter_map() -> dict[tuple[str, str], tuple[str, str, str]]:
    """节点级章节覆盖。**键必须是节点的 slug，不是 name**。

    这里刻意不静默：若某行的 slug 在知识包中不存在，说明写的是 name 或拼错了，
    必须报错——否则该行会被悄悄忽略，章节纠正看起来"生效了"其实没生效
    （2026-09-13 实际踩过一次）。
    """
    rows = _read(CHAPTER_MAP)
    _require_columns(CHAPTER_MAP, rows, ("subject", "slug", "volume", "chapter", "theme"))
    out = {
        (r["subject"].strip(), r["slug"].strip()): (
            r["volume"].strip(), r["chapter"].strip(), r["theme"].strip(),
        )
        for r in rows
    }
    validate_chapter_map_slugs(out)
    return out


def validate_chapter_map_slugs(mapping: dict[tuple[str, str], tuple[str, str, str]]) -> None:
    """校验 chapter_map 的 slug 都真实存在；不存在即报错（防 name/slug 混用）。"""
    if not mapping:
        return
    from kb_build import pack_io
    pack = pack_io.load_json(pack_io.pack_path())
    known = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    unknown = [key for key in mapping if key not in known]
    if unknown:
        raise ValueError(
            f"{CHAPTER_MAP}: {len(unknown)} 行的 slug 在知识包中不存在（可能写成了 name）：{unknown[:5]}"
        )


def load_alias_map() -> dict[tuple[str, str], list[str]]:
    rows = _read(ALIAS_MAP)
    _require_columns(ALIAS_MAP, rows, ("subject", "slug", "alias"))
    out: dict[tuple[str, str], list[str]] = {}
    for r in rows:
        out.setdefault((r["subject"].strip(), r["slug"].strip()), []).append(r["alias"].strip())
    return out


def load_boundary_map() -> dict[tuple[str, str], str]:
    rows = _read(BOUNDARY_MAP)
    _require_columns(BOUNDARY_MAP, rows, ("subject", "slug", "boundary"))
    return {(r["subject"].strip(), r["slug"].strip()): r["boundary"].strip() for r in rows}


def load_prereq_rows() -> list[tuple[str, str, str]]:
    """原始行 [(subject, slug, prerequisite)]，保持顺序便于审校。"""
    rows = _read(PREREQ_MAP)
    _require_columns(PREREQ_MAP, rows, ("subject", "slug", "prerequisite"))
    return [
        (r["subject"].strip(), r["slug"].strip(), r["prerequisite"].strip())
        for r in rows
    ]


def load_prereq_map() -> dict[str, dict[str, Any]]:
    """slug -> {"prerequisites": [slug...], "subject": str}。"""
    out: dict[str, dict[str, Any]] = {}
    for subject, slug, prereq in load_prereq_rows():
        entry = out.setdefault(slug, {"prerequisites": [], "subject": subject})
        if entry["subject"] != subject:
            raise ValueError(f"{PREREQ_MAP}: slug {slug!r} 出现在多个科目")
        entry["prerequisites"].append(prereq)
    return out


def is_declared_prereq(mapping: dict[str, dict[str, Any]], point_slug: str, prereq_slug: str) -> bool:
    entry = mapping.get(point_slug)
    return bool(entry) and prereq_slug in entry["prerequisites"]


def load_material_bindings() -> dict[str, str]:
    """材料 slug -> 知识点 slug。"""
    rows = _read(MATERIAL_BINDINGS)
    _require_columns(MATERIAL_BINDINGS, rows, ("material_slug", "point_slug"))
    out: dict[str, str] = {}
    for r in rows:
        slug = r["material_slug"].strip()
        if slug in out:
            raise ValueError(f"{MATERIAL_BINDINGS}: 材料 {slug!r} 有多条绑定（合同只允许恰好 1 条 PRIMARY）")
        out[slug] = r["point_slug"].strip()
    return out


def table_status() -> dict[str, int]:
    """各表当前行数，用于报告审校进度。"""
    names = (CHAPTER_MAP, ALIAS_MAP, BOUNDARY_MAP, PREREQ_MAP, MATERIAL_BINDINGS)
    return {name: len(_read(name)) for name in names}
