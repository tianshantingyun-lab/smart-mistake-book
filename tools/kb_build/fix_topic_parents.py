# -*- coding: utf-8 -*-
"""补齐主题父链：slug 含 '·' 却没有 parentSlug（或被写成 None）的主题，按 slug 前缀补上父主题。

## 它消灭的失败

`relocate_points` 首次建"数学综合链"与"化学实验分析"主题时漏写 parentSlug，
于是这 4 个主题在树里成了**根节点**（册层）。后果不是崩，而是静默的：
- 门禁的层级判断按 parentSlug 走，深度算成 0，`chapter_layer_has_points` 就看不见它们；
- App 侧 `parentKnowledgeNodeId` 为空，渐进式披露里它们会和"必修第一册"并列显示。

规则：slug = a·b·c，若 (a·b) 已是主题则 parentSlug = a·b；只有一段的根主题 parentSlug=None。
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from kb_build import pack_io  # noqa: E402


def repair(pack: dict) -> list[tuple[str, str, str | None, str | None]]:
    """只补缺失/修悬空，**绝不删已有且有效的父链**。

    曾经踩过的坑：第一批实现按"父必须等于 slug 前缀"来判，于是把 `化学实验基础`、
    `物质的分离与提纯`、`铝及其化合物`、`用高倍显微镜观察细胞` 这类**跨分支挂接**
    （slug 不含前缀、父级另有其人）的主题的父级清成了 None —— 它们本来就是有意挂在
    实验/章主题下的。规则改成：已有且指向真实主题 → 原样保留。
    """
    fixed = []
    for subject in pack["subjects"]:
        slugs = {t["slug"] for t in subject["topics"]}
        for topic in subject["topics"]:
            have = topic.get("parentSlug")
            if have and have in slugs:
                continue
            segments = [s for s in topic["slug"].split("·") if s]
            want = "·".join(segments[:-1]) if len(segments) > 1 and "·".join(segments[:-1]) in slugs else None
            if want != have:
                topic["parentSlug"] = want
                fixed.append((subject["subject"], topic["slug"], have, want))
    return fixed


def main() -> int:
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    fixed = repair(pack)
    print(f"需要修父链的主题 {len(fixed)} 个：")
    for subj, slug, have, want in fixed:
        print(f"  [{subj}] {slug}：{have!r} → {want!r}")
    if "--write" in sys.argv and fixed:
        pack_io.dump_json(pack, path)
        print(f"→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
