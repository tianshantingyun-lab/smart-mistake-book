# -*- coding: utf-8 -*-
"""把逐条人工裁决 `build/kb-staging/binding_verdicts.csv` 落到 `tables/material_bindings.csv`。

**为什么需要这一步**：`build_bindings_table.py` 的两条自动判据都建立在"字面证据"上
（整名包含、2 字组合重合）。实测证明了字面够不着的那一批里**两种情形各占一半**：
「排尿不仅受到脊髓的控制」→「神经系统的分级调节」是对的，只是换了说法；
「人船模型」→「动量守恒定律」是错的，而库里明明有同名节点。
**相似度分不出这两者**，只能逐条读材料正文来判。589 条已经判完，这里把它落表。

裁决三种取值：
- `keep`：现绑就是对的，不动。
- `bind`：改指到第三列给的**节点名**（名字要能在**暂存包**里解析成 slug——
  `build.py` 会删/并节点，绑到生成后不存在的节点会让整包解码失败）。
- `unbind`：现绑是错的、又找不到正确归属 → 写空目标，`build.py` 把绑定清掉。
  讲题时把一条不相关的材料当依据，比检索不到更糟，而且用户无从发现。

用法： PYTHONPATH=tools python -m kb_build.apply_binding_verdicts [--write]
"""

from __future__ import annotations

import argparse
import collections
import csv
from pathlib import Path

from kb_build import pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
VERDICTS = STAGING / "binding_verdicts.csv"
OUT = pack_io.REPO / "tools" / "kb_build" / "tables" / "material_bindings.csv"
FIELDS = ["material_slug", "point_slug", "subject", "reason"]

REASON_BIND = "逐条读正文判定：原绑与材料所讲不是一回事，改指本节点"
REASON_UNBIND = "逐条读正文判定：原绑与材料所讲不是一回事，且无正确归属，改为不导入"


def _targets() -> dict[tuple[str, str], str]:
    staged = pack_io.load_json(STAGING / pack_io.PACK_NAME)
    out: dict[tuple[str, str], str] = {}
    for subject in staged["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                out[(subject["subject"], point["name"])] = point["slug"]
    return out


def _material_subjects() -> dict[str, str]:
    out: dict[str, str] = {}
    for path in pack_io.sidecar_paths():
        for material in pack_io.load_json(path)["materials"]:
            out[material["slug"]] = material["subject"]
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="写回 material_bindings.csv")
    args = parser.parse_args(argv)

    target = _targets()
    subjects = _material_subjects()

    with VERDICTS.open(encoding="utf-8", newline="") as fh:
        verdict_rows = list(csv.DictReader(fh))
    dup = [s for s, n in collections.Counter(
        r["material_slug"] for r in verdict_rows).items() if n > 1]
    if dup:
        raise SystemExit(f"裁决表有重复材料行：{dup}")

    existing = list(csv.DictReader(OUT.open(encoding="utf-8", newline="")))
    done = {r["material_slug"] for r in existing}
    clash = sorted(done & {r["material_slug"] for r in verdict_rows})
    if clash:
        raise SystemExit(f"自动判据与人工裁决撞车（同名材料两边都改）：{clash[:10]}")
    # 报未解析的名字时要连标题和原绑节点一起给，判"该改指谁"靠的是材料讲什么
    with (STAGING / "binding_review.csv").open(encoding="utf-8", newline="") as fh:
        review = {r["material_slug"]: r for r in csv.DictReader(fh)}

    rows = list(existing)
    bound = unbound = kept = 0
    missing: list[dict] = []
    for row in verdict_rows:
        slug, verdict, name = row["material_slug"], row["verdict"], row["target"].strip()
        # 科目只从材料本身取：裁决表给的第三列是**名字**，跨科目重名（如「物质循环」）会歧义
        subject = subjects.get(slug)
        if not subject:
            raise SystemExit(f"裁决表里的材料 {slug!r} 不在任何侧车里")
        if verdict == "keep":
            kept += 1
            continue
        if verdict == "unbind":
            rows.append({"material_slug": slug, "point_slug": "", "subject": subject,
                         "reason": REASON_UNBIND})
            unbound += 1
            continue
        if verdict != "bind":
            raise SystemExit(f"{slug}: 未知裁决 {verdict!r}")
        point = target.get((subject, name))
        if not point:
            # 一次把**所有**对不上的名字报全：裁决表是逐条读出来的，名字写成库里没有的
            # 写法是转录误差，逐条报会来回跑 589 次
            missing.append({"material_slug": slug, "subject": subject, "verdict_name": name,
                            "title": review.get(slug, {}).get("title", ""),
                            "current_node_name": review.get(slug, {}).get("current_node_name", "")})
            continue
        rows.append({"material_slug": slug, "point_slug": point, "subject": subject,
                     "reason": REASON_BIND})
        bound += 1

    if missing:
        path = STAGING / "binding_verdicts_unresolved.csv"
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(missing[0].keys()))
            writer.writeheader()
            writer.writerows(missing)
        print(f"目标名对不上库里节点 {len(missing)} 条 → {path}")
        for item in missing:
            print(f"  [{item['subject']}] {item['material_slug']} → {item['verdict_name']!r}")
        return 1

    print(f"裁决 {len(verdict_rows)} 条：keep {kept} / bind {bound} / unbind {unbound}")
    print(f"material_bindings.csv：原有 {len(existing)} 行 → 现 {len(rows)} 行")
    if not args.write:
        print("（未写回；加 --write）")
        return 0
    with OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row[k] for k in FIELDS})
    print(f"已写入 {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
