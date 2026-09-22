# -*- coding: utf-8 -*-
"""把节点 `定位：` 串与章表不一致的全部条目按章表对齐。

## 背景与裁定

门指标 `chapter_locator_mismatch` 起初报 1676。拆开后（见
`report_chapter_locator_mismatch.py`）= 413 条"同章两种写法" + 1263 条"归属分歧"。
用户裁定（2026-09-19）：**章表权威**。于是本工具从"只对齐写法类"扩展为
**对齐全部有章表声明的不一致条目**。

1263 条归属分歧看着吓人，实测是 **116 个来源单元**（同一个单元的所有节点共享同一个
章表声明）——决策单位是单元，不是节点。抽查最大的 12 个单元（约 350 条），章表全部
对、节点的定位串全部错（节点写的是册名/章节俗称/别的章）。

## 对齐后的形态

`定位：{册}·{章号 章名}[·{节…}]`——节级定位（第三段起）原样保留。
`gate._place_of` 对两种写法（`册 章` 空格式 / `册·章` 点式）解析结果相同，所以这是
安全的收敛。

## 为什么不动主题（topic）的 sourceLocator

主题的 sourceLocator 是**章表做单元匹配用的键**（无《》时整串就是单元名），改了它
章表 join 就断。主题定位串与节点不一致是展示瑕疵，且 199 条"三方不一致"里
有些正是主题结构本身要重划的——留到主题结构那一轮处理，登记为观察。

## 无损（全过才写回）

1. 只改第一个 `。` 之前那一段；`。` 之后逐字节不变（`（见知识清单/教材）` 占位也在此列）。
2. 每个点的其它字段逐字节不变；点数与 slug 集合不变。
3. 写回前报告**全部单元 → 章表声明的映射**（116 行）——这是本次改动的判定依据，
   留档可复核；章表错了就是在这里看出来，而不是改完 1306 个节点后才发现。
4. 幂等：对齐后的条目不再落在不一致集合里，重跑 0 改动。

## 用法

    PYTHONPATH=tools python -m kb_build.align_chapter_locators            # 报告（含单元映射）
    PYTHONPATH=tools python -m kb_build.align_chapter_locators --write    # 写回 staging 包
"""

from __future__ import annotations

import argparse
import re
from collections import defaultdict
from pathlib import Path

from kb_build import pack_io, report_chapter_locator_mismatch as R

LOCATOR = re.compile(r"^定位：(?P<body>[^。]*)")


def _segments(body: str) -> list[str]:
    return [seg.strip() for seg in body.split("·") if seg.strip()]


def _kept_tail(segments: list[str]) -> list[str]:
    """第三段起是节级定位，原样保留。"""
    return segments[2:] if len(segments) >= 3 else []


def plan(pack: dict, rows: list[dict], boundaries: dict[tuple[str, str], str]) -> dict:
    """算出每条要对齐成什么。不改任何东西。

    覆盖**全部**有章表声明的不一致条目（写法类与归属类）——裁定是"章表权威"，
    不再按"章名是否被包含"挑着改：归属类里章表声明就是该单元人工定稿的教材章，
    节点定位串写的是抽取时留下的俗称/册名/别的章。
    """
    out = {"changes": [], "skipped": []}
    for row in rows:
        key = (row["subject"], row["slug"])
        boundary = boundaries[key]
        match = LOCATOR.match(boundary)
        if not match:
            out["skipped"].append((key, "边界没有 `定位：` 前缀"))
            continue
        segments = _segments(match.group("body"))
        tail = "".join(f"·{seg}" for seg in _kept_tail(segments))
        new_body = f"{row['declared_book']}·{row['declared_chapter']}{tail}"
        new_boundary = boundary[:match.start("body")] + new_body + boundary[match.end("body"):]
        if new_boundary == boundary:
            continue
        out["changes"].append({"key": key, "old": boundary, "new": new_boundary,
                               "kept_tail": _kept_tail(segments),
                               "unit": row.get("unit", ""), "kind": row["kind"]})
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
    parser.add_argument("--write", action="store_true", help="写回 staging 包（默认只报告）")
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

    print(f"不一致 {len(rows)} 条：将对齐 {len(changes)}，跳过 {len(planned['skipped'])}，"
          f"按单元分组 {len({(c['unit'], c['new']) for c in changes})} 个映射")
    for key, why in planned["skipped"][:6]:
        print(f"   ! 跳过 {key[1][:40]}：{why}")
    # 单元级映射是本次改动的**判定依据**：同一来源单元的所有节点被改成同一个声明。
    # 章表错了只能在这里看出来（改完 1300 多个节点再发现就晚了）。
    by_unit: dict[tuple, list[dict]] = defaultdict(list)
    for change in changes:
        by_unit[(change["unit"], change["new"].split("。")[0])].append(change)
    print(f"\n单元 → 章表声明（{len(by_unit)} 组，按条数降序）：")
    for (unit, target), group in sorted(by_unit.items(), key=lambda kv: -len(kv[1])):
        sample = group[0]
        print(f"   {len(group):4d}  {unit[:44]:46s} ⇒ {target[3:]}")
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
    print(f"→ 已写回 {path}（内容戳由晋升时刷新）")

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
