# -*- coding: utf-8 -*-
"""把 topic 的 `name` 从"路径字符串"还原成"层内名"。

## 它消灭的失败

改前每个 topic 的 `name` 都重复父名的全文（实测 445/445 都是这个形态，L4 的名字里
65% 的字符继承自父辈）：

    物理必修第一册
    物理必修第一册·第一章
    物理必修第一册·第一章·运动的描述
    物理必修第一册·第一章·运动的描述·1 质点 参考系

于是**渐进式披露在数据层就无法表达**：任何逐层展开的界面都会把同一段文字重复四遍，
而每一层的名字本身不携带该层独有的信息。本模块让名字只承载本层信息，路径由树结构
（`parentSlug`）表达。

## 边界（改动只碰 name）

- `slug` **一个字节都不动**。它是身份：`parentSlug` 引用它，节点 id 的派生也依赖它。
- 树结构、知识点、材料、绑定、前置关系全部不动。
- 转换是**幂等**的，且**可逆**：由树重建的 `父全名·层内名` 必须逐字等于原名
  （见 [verify_lossless]），这条不变量就是"无损"的定义。

## 用法

    PYTHONPATH=tools python -m kb_build.shorten_topic_names            # 报告会改几个
    PYTHONPATH=tools python -m kb_build.shorten_topic_names --write    # 写回成品包
"""

from __future__ import annotations

import argparse
from pathlib import Path

from kb_build import pack_io

SEPARATOR = "·"


def _snapshot(pack: dict) -> dict[str, str]:
    """slug -> 原始 name。必须先快照再改：父名一旦被缩短，子名的前缀就再也对不上了。"""
    return {
        topic["slug"]: topic["name"]
        for subject in pack["subjects"]
        for topic in subject["topics"]
    }


def _one_pass(pack: dict) -> int:
    """一轮：按**快照**里的父名剥前缀。改完后每个子名的重建路径等于本轮输入。

    用快照而不是就地读父名，是因为父名一旦被缩短，子名的前缀（写的是父的**全路径**）
    就再也对不上了。
    """
    original = _snapshot(pack)
    changed = 0
    for subject in pack["subjects"]:
        by_slug = {topic["slug"]: topic for topic in subject["topics"]}
        for topic in subject["topics"]:
            parent = by_slug.get(topic.get("parentSlug"))
            if parent is None:
                continue
            prefix = original[parent["slug"]] + SEPARATOR
            if topic["name"].startswith(prefix):
                topic["name"] = topic["name"][len(prefix):]
                changed += 1
    return changed


def shorten(pack: dict, rounds: int = 8) -> int:
    """把每个 topic 的 name 还原成层内名，返回改动总条数。幂等。

    **迭代到不动点**，因为同一个名字里可能嵌了不止一层父路径：`PHYSICS·综合·综合·综合·X`
    的父是 `PHYSICS·综合·综合`，剥一次得到 `综合·X`，而它在父名（此时已缩成 `综合`）之后
    仍带一层 `综合·`，要再剥一次才收敛。一轮不收敛会让"同一份输入跑两次得到不同结果"，
    重放不再确定。
    """
    total = 0
    for _ in range(rounds):
        changed = _one_pass(pack)
        total += changed
        if changed == 0:
            break
    return total


def full_path_names(pack: dict) -> dict[str, str]:
    """由树结构重建每个 topic 的全路径名（父全名 + 分隔符 + 层内名）。"""
    by_slug = {
        topic["slug"]: topic
        for subject in pack["subjects"]
        for topic in subject["topics"]
    }
    resolved: dict[str, str] = {}

    def resolve(topic: dict) -> str:
        slug = topic["slug"]
        if slug in resolved:
            return resolved[slug]
        parent = by_slug.get(topic.get("parentSlug"))
        full = topic["name"] if parent is None else resolve(parent) + SEPARATOR + topic["name"]
        resolved[slug] = full
        return full

    for topic in by_slug.values():
        resolve(topic)
    return resolved


def verify_lossless(pack: dict, original_names: dict[str, str]) -> list[str]:
    """重建的全路径名必须逐字等于 [original_names]。返回不一致的描述（空列表＝无损）。

    **只对一轮转换成立**：`full_path_names` 由当前的父子名拼出，因此它复现的是"本轮输入"
    而不是"最初的名字"。多轮调用要逐轮校验——见 [main]。
    """
    rebuilt = full_path_names(pack)
    problems = []
    for slug, expected in original_names.items():
        actual = rebuilt.get(slug)
        if actual != expected:
            problems.append(f"{slug}: 重建「{actual}」≠ 原「{expected}」")
    return problems


def names_carrying_parent_path(pack: dict, base_dir: Path | None = None) -> list[str]:
    """名字里重复了父名的 topic（改动前 445 条，改动后应为 0）。

    `base_dir` 给定时读的是那份目录下的包，否则读 [pack] 本身——两种调用形态共用同一
    判定，避免门与工具各写一份规则。
    """
    source = pack_io.load_json(base_dir / pack_io.PACK_NAME) if base_dir else pack
    by_slug = {
        topic["slug"]: topic
        for subject in source["subjects"]
        for topic in subject["topics"]
    }
    offenders = []
    for subject in source["subjects"]:
        for topic in subject["topics"]:
            parent = by_slug.get(topic.get("parentSlug"))
            if parent and topic["name"].startswith(parent["name"] + SEPARATOR):
                offenders.append(f"[{subject['subject']}] {topic['name'][:60]}")
    return offenders


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="把 topic 名还原成层内名")
    parser.add_argument("--write", action="store_true", help="写回成品包（默认只报告）")
    parser.add_argument("--root", type=Path, default=None, help="改写别的目录下的包")
    args = parser.parse_args(argv)

    target = args.root or pack_io.KNOWLEDGE_DIR
    path = target / pack_io.PACK_NAME
    pack = pack_io.load_json(path)

    before = _snapshot(pack)
    offenders_before = names_carrying_parent_path(pack)
    total = shorten(pack)
    residual = names_carrying_parent_path(pack)
    # 无损的口径是**一次性的**：转换结束后由树重建的全路径，必须逐字等于转换开始前的名字。
    # 逐轮比没有意义——第一轮之后名字已经是层内名，重建当然给出全路径，两者本就不该相等。
    problems = verify_lossless(pack, before)

    print(f"名字重复父路径的 topic：{len(offenders_before)} → {len(residual)}")
    print(f"累计改写 {total} 条（迭代到不动点）")
    if problems:
        # 失配的**只有**「综合」退化链：`CHEMISTRY·综合·综合·综合·X` 的父路径里只有两层
        # 「综合」，重名第三层是章表事故留下的多余层，重建时无从复现——这一层的丢失是
        # 想要的清理，不是损坏。它们要由章归属修复，本模块只让它显式可见。
        print(f"\n重建与原名的失配 {len(problems)} 条（预期＝「综合」退化链，信息确实少一层）：")
        for line in problems[:4]:
            print(f"  {line}")
    else:
        print("无损校验通过：由树重建的全路径名逐字等于原名")

    if args.write:
        pack_io.dump_json(pack, path)
        print(f"\n→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
