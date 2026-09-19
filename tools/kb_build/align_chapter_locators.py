# -*- coding: utf-8 -*-
"""把"同章、两种写法"的定位串按章表对齐（只动 `定位：` 那一段）。

## 它消灭的失败

门指标 `chapter_locator_mismatch` 1676 条里，**413 条不是归属分歧而是写法分歧**：
节点写 `物理必修第一册 第一章·运动的描述·1 质点…`，章表声明 `物理必修第一册 / 第一章 运动的描述`
——同一章，只是"章号"落在不同的位置上（节点写在册后面、章表并进章名里），
而门的判据（`gate._place_of`）在有 `·` 时取**第二个** `·` 段当章名，于是两边永远对不上。

## 对齐后的形态（是 `_place_of` 明文承认的两种老写法之一）

`定位：{册}·{章号 章名}[·{节…}]`——把章号并进章名、不再单独成段。

**为什么保留 `·{节}`**：那是抽出来时的节级定位（`·1　质点　参考系和坐标系`），是有用的信息；
丢掉它等于为了对齐而删内容。对齐只改"册/章"那两段。

## 无损（全过才写回）

1. 只改第一个 `。` 之前的那一段；`。` 之后逐字节不变（`（见知识清单/教材）` 这类占位也在此列）。
2. 每个点的其它字段逐字节不变；点数与 slug 集合不变。
3. **不丢信息**：原来的章名（去章号与虚词后）必须被新章名包含；不满足的**跳过并报出**，
   不硬改——那种多半是 `report_chapter_locator_mismatch` 里"归属真不同"的簇，不是本工具的事。
4. 幂等：改完再跑 0 改动（对齐后的点不再落在不一致集合里）。

## 用法

    PYTHONPATH=tools python -m kb_build.align_chapter_locators            # 报告
    PYTHONPATH=tools python -m kb_build.align_chapter_locators --write    # 写回成品包
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

from kb_build import pack_io, report_chapter_locator_mismatch as R, update_manifest

LOCATOR = re.compile(r"^定位：(?P<body>[^。]*)")
MECHANICAL = ("prefix_only", "same_chapter_alias")


def _segments(body: str) -> list[str]:
    return [seg.strip() for seg in body.split("·") if seg.strip()]


def _kept_tail(segments: list[str]) -> list[str]:
    """第三段起是节级定位，原样保留。"""
    return segments[2:] if len(segments) >= 3 else []


def plan(pack: dict, rows: list[dict], boundaries: dict[tuple[str, str], str]) -> dict:
    """算出每条要对齐成什么，并逐条做无损判定。不改任何东西。"""
    out = {"changes": [], "skipped": []}
    for row in rows:
        if row["kind"] not in MECHANICAL:
            continue
        key = (row["subject"], row["slug"])
        boundary = boundaries[key]
        match = LOCATOR.match(boundary)
        if not match:
            out["skipped"].append((key, "边界没有 `定位：` 前缀"))
            continue
        segments = _segments(match.group("body"))
        # 不丢信息：原章名的实义字必须被新章名包含
        if R._norm(row["current_chapter"]) not in R._norm(row["declared_chapter"]):
            out["skipped"].append(
                (key, f"章名改写幅度大：{row['current_chapter']} ⊄ {row['declared_chapter']}"))
            continue
        tail = "".join(f"·{seg}" for seg in _kept_tail(segments))
        new_body = f"{row['declared_book']}·{row['declared_chapter']}{tail}"
        new_boundary = boundary[:match.start("body")] + new_body + boundary[match.end("body"):]
        out["changes"].append({"key": key, "old": boundary, "new": new_boundary,
                               "kept_tail": _kept_tail(segments)})
    return out


def apply(pack: dict, changes: list[dict]) -> int:
    """就地写回。返回改动条数。"""
    applied = 0
    by_key = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}
    for change in changes:
        point = by_key[change["key"]]
        point["boundary"] = change["new"]
        applied += 1
    return applied


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="写回成品包（默认只报告）")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    rows = R.collect(pack)
    boundaries = {(s, p["slug"]): (p.get("boundary") or "") for s, _t, p in pack_io.iter_points(pack)}
    planned = plan(pack, rows, boundaries)
    changes = planned["changes"]

    print(f"不一致 {len(rows)} 条：可机械对齐 {len(changes)}，跳过 {len(planned['skipped'])}，"
          f"其余 {len(rows) - len(changes) - len(planned['skipped'])} 条属归属分歧（另一支工具的事）")
    for key, why in planned["skipped"][:6]:
        print(f"   ! 跳过 {key[1][:40]}：{why}")
    print("\n对齐样例（旧 → 新）：")
    for change in changes[:6]:
        print(f"   {change['old'][:52]:54s}")
        print(f"   {change['new'][:52]:54s}")

    if not args.write:
        print("\n（未写盘；加 --write 生效）")
        return 0
    if not changes:
        return 0

    # 无损基线：点集、slug 集、以及"除 boundary 外"的字段
    before_points = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    before_slugs = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
    others_before = {(s, p["slug"]): {k: v for k, v in p.items() if k != "boundary"}
                     for s, _t, p in pack_io.iter_points(pack)}
    applied = apply(pack, changes)
    if applied != len(changes):
        print(f"无损校验失败：计划 {len(changes)} 条、实际写 {applied} 条")
        return 1
    others_after = {(s, p["slug"]): {k: v for k, v in p.items() if k != "boundary"}
                    for s, _t, p in pack_io.iter_points(pack)}
    after_points = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    after_slugs = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
    if before_points != after_points or before_slugs != after_slugs or others_before != others_after:
        print("无损校验失败：点数/slug 集合/其它字段发生了变化")
        return 1
    # 无损：`。` 之后的内容逐条不变
    for change in changes:
        old_tail = change["old"].split("。", 1)[1] if "。" in change["old"] else ""
        new_tail = change["new"].split("。", 1)[1] if "。" in change["new"] else ""
        if old_tail != new_tail:
            print(f"无损校验失败：{change['key'][1][:40]} 的 `。` 之后内容被改动")
            return 1
    print(f"\n无损校验通过：{applied} 条只改了 `定位：` 那一段，其余字段与 `。` 之后逐字节不变")

    pack_io.dump_json(pack, path)
    update_manifest.main(["--stamp"])
    print(f"→ 已写回 {path}")

    # 幂等：重跑一遍必须 0 改动
    reloaded = pack_io.load_json(path)
    again = plan(reloaded, R.collect(reloaded),
                 {(s, p["slug"]): (p.get("boundary") or "") for s, _t, p in pack_io.iter_points(reloaded)})
    if again["changes"]:
        print(f"无损校验失败：重放仍要再改 {len(again['changes'])} 条")
        return 1
    print("幂等校验通过：重放 0 改动")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
