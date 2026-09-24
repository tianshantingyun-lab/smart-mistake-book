# -*- coding: utf-8 -*-
"""对比 staging 与成品目录的知识包差异——晋升前确认"只改了该改的"。

## 它消灭的失败

`promote` 会把 staging 整体镜像进成品目录。若 staging 里混着**别人或别的轮次**的改动，
晋升就会把它们一起推给用户，而"我没改别的"只是自述。本工具给出可读的逐字段差异清单：
按 (学科, slug) 逐知识点比 boundary / name / aliases / kind / 前置、按 slug 逐材料比文本字段，
以及主题树的增删与顺序变化。它是晋升前的那一眼。

## 判据

- 只在**两侧都存在**的文件上比较（缺文件即报错退出 2——缺文件时谈"差异"没有意义）；
- 文本字段用规范化 JSON（键排序、去首尾空白）比，避免格式差异被当成内容差异；
- 材料文本字段只报"变了没有 + 字数变化"，不打印正文（20 万字的正文打出来没人看）。

用法：
    PYTHONPATH=tools python -m kb_build.diff_pack_staging [--max 30] [--json]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
STAGING = REPO / "build/kb-staging"
SHIPPED = REPO / "core/data/src/main/resources/knowledge"
PACK = "moe-2025-four-subjects-v1.json"
MAT_FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown", "contentMarkdown",
              "boundaryMarkdown")


def _norm(obj) -> str:
    return json.dumps(obj, sort_keys=True, ensure_ascii=False).strip()


def point_fields(pack: dict) -> dict[tuple[str, str], dict]:
    out = {}
    for subj in pack.get("subjects", []):
        for _t, p in [(None, p) for t in subj.get("topics", []) for p in t.get("knowledgePoints", [])]:
            out[(subj.get("subject"), p.get("slug"))] = p
    return out


def material_fields(docs: list[dict]) -> dict[str, dict]:
    out = {}
    for d in docs:
        for m in d.get("materials", []):
            out[m.get("slug")] = m
    return out


def family(dir_: Path) -> tuple[dict, list[dict]]:
    pack = json.loads((dir_ / PACK).read_text(encoding="utf-8"))
    docs = []
    for f in sorted(dir_.glob("moe-2025-teaching-support-v2-*.json")):
        docs.append(json.loads(f.read_text(encoding="utf-8")))
    return pack, docs


def diff_families(staging: Path, shipped: Path) -> dict:
    for name in (PACK,):
        for base in (staging, shipped):
            if not (base / name).exists():
                raise FileNotFoundError(f"{base / name} 不存在——缺文件时无法比较差异")
    sp, sd = family(staging)
    hp, hd = family(shipped)
    a_pts, b_pts = point_fields(sp), point_fields(hp)
    a_mats, b_mats = material_fields(sd), material_fields(hd)

    changed: list[dict] = []
    for key in sorted(set(a_pts) | set(b_pts), key=lambda k: (k[0] or "", k[1] or "")):
        pa, pb = a_pts.get(key), b_pts.get(key)
        where = f"[{key[0]}] {key[1]}"
        if pa is None:                      # 只在成品里有 → staging 把它删了
            changed.append({"where": where, "field": "point", "what": "删除知识点"})
            continue
        if pb is None:                      # 只在 staging 里有 → 新增
            changed.append({"where": where, "field": "point", "what": "新增知识点"})
            continue
        for field in ("boundary", "name", "aliases", "kind", "sourceLocator", "prerequisiteSlugs"):
            if _norm(pa.get(field)) != _norm(pb.get(field)):
                detail = ""
                if field == "boundary":
                    detail = f"（{len(pb.get(field) or '')} → {len(pa.get(field) or '')} 字）"
                changed.append({"where": where, "field": field, "what": f"字段变了{detail}"})
    for slug in sorted(set(a_mats) | set(b_mats), key=lambda s: s or ""):
        ma, mb = a_mats.get(slug), b_mats.get(slug)
        if ma is None:
            changed.append({"where": slug, "field": "material", "what": "删除材料"})
            continue
        if mb is None:
            changed.append({"where": slug, "field": "material", "what": "新增材料"})
            continue
        for field in MAT_FIELDS:
            if _norm(ma.get(field)) != _norm(mb.get(field)):
                changed.append({"where": slug, "field": f"material.{field}",
                                "what": f"{len(mb.get(field) or '')} → {len(ma.get(field) or '')} 字"})
        if _norm(ma.get("bindings")) != _norm(mb.get("bindings")):
            changed.append({"where": slug, "field": "material.bindings", "what": "绑定变了"})

    topics_a = [(s.get("subject"), t.get("name")) for s in sp.get("subjects", []) for t in s.get("topics", [])]
    topics_b = [(s.get("subject"), t.get("name")) for s in hp.get("subjects", []) for t in s.get("topics", [])]
    topic_delta = {
        "added": [f"{s}/{n}" for s, n in topics_a if (s, n) not in set(topics_b)],
        "removed": [f"{s}/{n}" for s, n in topics_b if (s, n) not in set(topics_a)],
        "order_changed": topics_a != topics_b and sorted(topics_a) == sorted(topics_b),
    }
    counts: dict[str, int] = {}
    for c in changed:
        counts[c["field"]] = counts.get(c["field"], 0) + 1
    return {"staging": str(staging), "shipped": str(shipped), "changed": changed,
            "counts": counts, "topic_delta": topic_delta,
            "points": [len(a_pts), len(b_pts)], "materials": [len(a_mats), len(b_mats)]}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--staging", type=Path, default=STAGING)
    ap.add_argument("--shipped", type=Path, default=SHIPPED)
    ap.add_argument("--max", type=int, default=30)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    try:
        out = diff_families(args.staging, args.shipped)
    except FileNotFoundError as e:
        print(f"✗ {e}")
        return 2
    if args.json:
        print(json.dumps({**out, "changed": out["changed"][:args.max]}, ensure_ascii=False))
        return 0
    print(f"知识点 {out['points'][1]} → {out['points'][0]}；材料 {out['materials'][1]} → {out['materials'][0]}")
    print(f"差异合计 {len(out['changed'])} 处：{out['counts']}")
    td = out["topic_delta"]
    print(f"主题：+{len(td['added'])} −{len(td['removed'])}"
          + ("（顺序变了）" if td["order_changed"] else ""))
    for c in out["changed"][:args.max]:
        print(f"   {c['where']} · {c['field']}：{c['what']}")
    if len(out["changed"]) > args.max:
        print(f"   …（还有 {len(out['changed']) - args.max} 处，用 --max 调大）")
    if not out["changed"]:
        print("两侧逐字段相同。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
