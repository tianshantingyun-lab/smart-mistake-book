# -*- coding: utf-8 -*-
"""生成器预检：在 `build --write` **之前**把全部违规一次列出来。

为什么需要它：`build.py` 的校验是**逐条遇到就停**（`NewContentError`），所以我这几轮
反复出现"改一条、再撞下一条"的循环——4 次里有 3 次是同一个 `contentMarkdown 必须 1–4 行`。
预检把同一套判据（直接调用 `new_content._validate_material`，不另写一份）跑遍所有
生成器与 `materials.jsonl`，一次报全。

用法：
  PYTHONPATH=tools python -m kb_build.check_materials          # 只报违规
  PYTHONPATH=tools python -m kb_build.check_materials --stats  # 连条数与分布一起报
"""

from __future__ import annotations

import argparse
import importlib
import json
import sys
from pathlib import Path

from kb_build import new_content, pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
GEN_DIR = Path(__file__).resolve().parent
JSONL = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"


def known_slugs() -> set[tuple[str, str]]:
    """(科目, slug) 集合 = 暂存包节点 ∪ new_points.csv 里新增的节点。

    只用于**预检**；权威校验在 `Builder._post_build_slugs()`（生成后仍存在的节点集）。
    预检取的是超集，所以它不会漏报"绑定悬空"。
    """
    pack = json.loads((STAGING / pack_io.PACK_NAME).read_text(encoding="utf-8"))
    slugs = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    points, _placements, _boundaries = new_content.load_new_points()
    slugs |= set(points)
    return slugs


def from_generators() -> list[tuple[str, dict]]:
    out: list[tuple[str, dict]] = []
    for path in sorted(GEN_DIR.glob("wusan_*.py")):
        module = importlib.import_module(f"kb_build.{path.stem}")
        for material in getattr(module, "MATERIALS", []):
            out.append((path.name, material))
    return out


def from_jsonl() -> list[tuple[str, dict]]:
    if not JSONL.exists():
        return []
    out = []
    for index, line in enumerate(JSONL.read_text(encoding="utf-8").splitlines()):
        if line.strip():
            out.append((f"materials.jsonl[{index}]", json.loads(line)))
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stats", action="store_true")
    args = parser.parse_args(argv)

    known = known_slugs()
    violations: list[str] = []

    # 两个来源**各有自己的 seen**：生成器与 materials.jsonl 里出现同一份材料是设计如此
    # （生成器写出去的就是它），共用一个 seen 会把 117 条正常条目误报成"slug 重复"。
    gen_items = from_generators()
    jsonl_items = from_jsonl()
    seen_gen: set[str] = set()
    seen_jsonl: set[str] = set()
    for tag, material in gen_items:
        try:
            new_content._validate_material(material, 0, known, seen_gen)
        except new_content.NewContentError as exc:
            violations.append(f"{tag} {material.get('slug', '?')}: {exc}")
        except Exception as exc:  # 结构性问题（缺键、类型不对）也一并报出来
            violations.append(f"{tag} {material.get('slug', '?')}: {type(exc).__name__}: {exc}")
    for tag, material in jsonl_items:
        try:
            new_content._validate_material(material, 0, known, seen_jsonl)
        except new_content.NewContentError as exc:
            violations.append(f"{tag} {material.get('slug', '?')}: {exc}")
        except Exception as exc:
            violations.append(f"{tag} {material.get('slug', '?')}: {type(exc).__name__}: {exc}")

    # 第三件事：两边同 slug 的条目必须**逐字段一致**，否则"重跑生成器"与"已写出的表"分叉了。
    gen_by_slug = {m["slug"]: m for _t, m in gen_items}
    for tag, material in jsonl_items:
        twin = gen_by_slug.get(material["slug"])
        if twin is not None and twin != material:
            violations.append(f"{tag}: 与生成器里的同名条目内容不一致（{material['slug']}）")

    if args.stats:
        from collections import Counter
        items = gen_items + jsonl_items
        subjects = Counter(m.get("subject") for _t, m in items)
        types = Counter(m.get("type") for _t, m in items)
        lines = Counter(len((m.get("contentMarkdown") or "").split("\n")) for _t, m in items)
        print(f"生成器 {len(gen_items)} 条 + jsonl {len(jsonl_items)} 条")
        print(f"  科目 {dict(subjects)}")
        print(f"  类型 {dict(types)}")
        print(f"  正文行数分布 {dict(sorted(lines.items()))}（>4 行即违规）")
        print(f"  单条正文字数：min={min(len(m.get('contentMarkdown') or '') for _t, m in items)} "
              f"max={max(len(m.get('contentMarkdown') or '') for _t, m in items)}")

    if violations:
        print(f"违规 {len(violations)} 条：")
        for line in violations:
            print("  " + line)
        return 1
    print(f"预检通过：生成器 {len(gen_items)} 条 + jsonl {len(jsonl_items)} 条，全部符合契约")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
