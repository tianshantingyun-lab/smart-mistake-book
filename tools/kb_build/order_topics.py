# -*- coding: utf-8 -*-
"""把各科的 topic 数组排成父级先序，并证明这次重排无损。

## 它消灭的失败

见 [kb_build.topic_order] 的模块说明：子级排在父级前面会让加载器撞
`knowledge_node.parent_knowledge_node_id` 的外键，**整批安装失败**。

## 为什么是独立工具而不是改生成器

成品不是单一生成器的产物：`build.py` 停用中，而 `relocate_points.py` 是**绕过 build.py
的追加式写入者**（`ensure_synthesis_chain` 无条件把新建的「综合」链 append 到数组尾）。
本工具先把成品修正到正确顺序，再把同一函数接进那个写入者防复发。

## 无损的定义（三条都要满足才写回）

1. **topic 集合逐条不变**（slug 多重集相同）
2. **每条 topic 的 parentSlug 一字不动**
3. **每个 topic 的知识点数不变**

排列只改位置。若任一条不满足，拒绝写回。

  用法： PYTHONPATH=tools python -m kb_build.order_topics            # 报告
        PYTHONPATH=tools python -m kb_build.order_topics --write    # 写回
"""

from __future__ import annotations

import argparse
from pathlib import Path

from kb_build import pack_io, topic_order


def _snapshot(pack: dict) -> dict:
    """写回前抓一份可比对的快照：slug 集合、父子关系、点数。"""
    return {
        subject["subject"]: {
            topic["slug"]: (
                topic.get("parentSlug"),
                len(topic.get("knowledgePoints") or []),
            )
            for topic in subject["topics"]
        }
        for subject in pack["subjects"]
    }


def _offenders(topics: list[dict]) -> list[str]:
    position = {topic["slug"]: index for index, topic in enumerate(topics)}
    out = []
    for index, topic in enumerate(topics):
        parent = topic.get("parentSlug")
        if parent is not None and parent in position and position[parent] > index:
            out.append(f"子 {topic['slug'][:34]}（第{index}）排在父 {parent[:34]}（第{position[parent]}）之前")
    return out


def reorder(pack: dict) -> int:
    """就地排好每一科的 topics，返回被改动的 topic 数。幂等。"""
    changed = 0
    for subject in pack["subjects"]:
        before = [topic["slug"] for topic in subject["topics"]]
        subject["topics"] = topic_order.parent_first(subject["topics"])
        after = [topic["slug"] for topic in subject["topics"]]
        changed += sum(1 for a, b in zip(before, after) if a != b)
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="把 topic 数组排成父级先序")
    parser.add_argument("--write", action="store_true", help="写回成品包（默认只报告）")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)

    offenders_before = sum(len(_offenders(s["topics"])) for s in pack["subjects"])
    before = _snapshot(pack)
    changed = reorder(pack)
    after = _snapshot(pack)
    offenders_after = sum(len(_offenders(s["topics"])) for s in pack["subjects"])

    print(f"父级排在子级之后的 topic：{offenders_before} → {offenders_after}")
    # 注意口径：一个 topic 前移会让它之后的所有 topic 顺移，所以这个数**不是**"被搬动的个数"，
    # 而是"下标变了的个数"。搬动多少条看 offenders 的差值更准。
    print(f"下标发生变化的 topic：{changed} 个（含被顺移的）")

    # 无损三查
    problems = []
    if set(before) != set(after):
        problems.append(f"科目集合变了：{set(before) ^ set(after)}")
    for subject, topics in before.items():
        if set(topics) != set(after[subject]):
            problems.append(f"[{subject}] topic 集合变了")
            continue
        for slug, (parent, points) in topics.items():
            new_parent, new_points = after[subject][slug]
            if parent != new_parent:
                problems.append(f"[{subject}] {slug[:34]} 的 parentSlug 被改了")
            if points != new_points:
                problems.append(f"[{subject}] {slug[:34]} 的知识点数被改了")

    unorderable = [
        f"[{s['subject']}] {slug[:40]}"
        for s in pack["subjects"]
        for slug in topic_order.unorderable(s["topics"])
    ]
    if unorderable:
        # 不是排序能解决的问题（父级不存在或成环），单列出来不混进"已修好"
        print(f"\n仍无法排序 {len(unorderable)} 个（父级缺失或成环，需改内容）：")
        for line in unorderable[:8]:
            print(f"  {line}")

    if problems:
        print(f"\n无损校验失败 {len(problems)} 条（前 5）：")
        for line in problems[:5]:
            print(f"  {line}")
        return 1
    print("无损校验通过：topic 集合、父子关系、知识点数逐条不变")

    if args.write:
        pack_io.dump_json(pack, path)
        print(f"\n→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
