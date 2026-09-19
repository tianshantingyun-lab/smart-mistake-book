# -*- coding: utf-8 -*-
"""主题数组的父级先序排列。

## 它消灭的失败

加载器把 `topics` 当**平铺数组**逐个变成节点（`toNodes` = 对每个 topic 产出它的节点和它的
知识点），而 `knowledge_node.parent_knowledge_node_id` 是**自引用外键、逐行检查**——
子级排在父级前面就会 `FOREIGN KEY constraint failed`，整批安装崩掉。

实测 2026-09-19：成品里有 4 个 topic 的父级排在它后面（`MATH·综合` 在 `MATH` 之前等），
于是当前包的**全新安装直接失败**（旧导入路径同样会踩）。而"数组顺序"既不是包契约的
一部分、原本也没被任何门钉住，却决定安装能否成功。

## 边界

- **只重排位置**：不增删 topic、不改任何字段、不动 `knowledgePoints`。
- **稳定**：父级先序的前提下保留原有相对顺序；同一份输入必得同一份输出（可重放）。
- **不可排序的不静默处理**：父链**成环**的 topic 排在最后并原样保留，由调用方用 [unorderable]
  报出来——排序工具不该悄悄吞掉"这条本来就坏"的事实。父级**不在集合里**的照常放行（顺序改
  不动它，卡住它只会让整个数组都排不出来），它由门指标 `topic_parent_after_child` 单独计。
"""

from __future__ import annotations


def _slugs(topics: list[dict]) -> list[str]:
    return [topic["slug"] for topic in topics]


def unorderable(topics: list[dict]) -> list[str]:
    """哪些 topic 无法排进父级先序（父级不在集合里，或父链成环）。返回 slug 列表。"""
    by_slug = {topic["slug"]: topic for topic in topics}
    emitted: set[str] = set()
    remaining = set(by_slug)
    progressed = True
    while remaining and progressed:
        progressed = False
        for slug in list(remaining):
            parent = by_slug[slug].get("parentSlug")
            if parent is None or parent in emitted or parent not in by_slug:
                emitted.add(slug)
                remaining.discard(slug)
                progressed = True
    return [slug for slug in _slugs(topics) if slug in remaining]


def parent_first(topics: list[dict]) -> list[dict]:
    """把 topics 排成父级先序（稳定）。不可排序的原样排在最后。"""
    by_slug = {topic["slug"]: topic for topic in topics}
    emitted: set[str] = set()
    ordered: list[dict] = []
    pending = list(topics)
    progressed = True
    while pending and progressed:
        progressed = False
        still_pending = []
        for topic in pending:
            parent = topic.get("parentSlug")
            # 父级不在集合里的也放行：那不是顺序问题（顺序改不动它），
            # 把它卡在这里只会让整个数组都排不出来。它由 unorderable() 单独报出。
            if parent is None or parent in emitted or parent not in by_slug:
                ordered.append(topic)
                emitted.add(topic["slug"])
                progressed = True
            else:
                still_pending.append(topic)
        pending = still_pending
    # 剩下的只可能是父链成环；原样接在后面，不静默丢弃
    ordered.extend(pending)
    return ordered
