# -*- coding: utf-8 -*-
"""重建节点别名：只保留**有据可依**的别名，并顺手把污染清掉。

## 它消灭的失败

别名曾经是"字符串机械匹配"的产物：`函数的概念` 挂着 `导数的概念与导函数`、
`排列与组合的概念及公式`、`绝对值型函数的处理方式`。这不是脏数据那么简单——别名是
**检索打分**与**模型选点**的输入：
- `KnowledgeContextRetriever`：别名重合给权重 7、精确短语给 70 分加成；
- `RoomMistakeOrganizationRepository`：用 `aliases + canonicalName` 匹配学生文字；
- `OpenAiProblemOrganizationProtocol`：把别名清单发给模型让它挑节点。
别名指向别的概念 = 直接把检索和模型往错误节点上引。

## 判据（三条，全部可复核）

1. **主名**永远保留；
2. 别名必须是**该节点自己绑定材料的标题**（题目标题就是这份材料在讲的东西；
   绑定是对的⇒别名跟着对），且形状合格（≤24 字、无句末标点、不是残片、过坏名检测）；
3. **全局互斥**：同一学科内，一个名字只能归一个节点——被两个以上节点同时claim的候选
   一律丢弃（否则"按别名匹配"本身就歧义）。

门禁 `ghost_aliases` 的口径（"既不等于主名、也不来自当前绑定材料的标题"）与本判据同源，
所以重建后该指标必然为 0；`alias_collision`（新增）则守住第 3 条的长期不变量。

## 用法

    PYTHONPATH=tools python -m kb_build.rebuild_aliases            # 试算（不写）
    PYTHONPATH=tools python -m kb_build.rebuild_aliases --write    # 写表并回写成品包
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import gate, pack_io

TABLES = Path(__file__).resolve().parent / "tables"
ALIAS_MAP = "alias_map.csv"
DROPPED = "alias_dropped.csv"
NAME_MAX = 24
# 句末标点与**逗号**：名字里不会出现逗号——出现就说明是"从句/句子片段"
# （实测拦下 `奇偶性性质法求参：拆出含参部分，由不含参` 这类截断）。
# 冒号保留：教材节名就是 `实验：探究平抛运动的特点` 这种写法，拒掉会丢好别名。
SENTENCE_PUNCT = "。！？；;，,"
BAD_PREFIX = ("例", "典例", "如", "解", "答案", "解析", "已知", "下列", "判断", "计算", "求")
BAD_SUFFIX = ("的", "和", "与", "或", "及", "供", "为", "是")
BRACKETS = {"（": "）", "(": ")", "【": "】", "[": "]", "《": "》", "“": "”"}


def _balanced(text: str) -> bool:
    stack = []
    for ch in text:
        if ch in BRACKETS:
            stack.append(BRACKETS[ch])
        elif ch in BRACKETS.values():
            if not stack or stack.pop() != ch:
                return False
    return not stack


def title_usable(title: str) -> str | None:
    """材料标题能不能当别名；不能则返回原因。"""
    t = (title or "").strip()
    if not t:
        return "空标题"
    if len(t) > NAME_MAX:
        return "超长（>24 字）"
    if any(ch in t for ch in SENTENCE_PUNCT):
        return "含逗号/句末标点（是句子不是名字）"
    if t.startswith(BAD_PREFIX):
        return "以题面/指令开头（是题干不是名字）"
    if t.endswith(BAD_SUFFIX):
        return "以虚词结尾（截断残片）"
    if not _balanced(t):
        return "括号不配对（截断残片）"
    if "$" in t or "\\" in t:
        return "含 LaTeX 定界符（是公式片段不是名字）"
    verdict = gate._is_bad_name(t)
    if verdict:
        return f"坏名检测：{verdict}"
    return None


def bound_titles() -> dict[tuple[str, str], set[str]]:
    titles: dict[tuple[str, str], set[str]] = defaultdict(set)
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            for b in m.get("bindings") or []:
                parts = b["knowledgeNodeId"].split(":")
                titles[(parts[-3].upper(), parts[-1])].add(m.get("title") or "")
    return titles


def rebuild(pack: dict, titles: dict[tuple[str, str], set[str]] | None = None) -> dict:
    titles = bound_titles() if titles is None else titles
    # 候选取集：每个节点的候选（主名另算，不参与互斥）
    candidates: dict[tuple[str, str], set[str]] = {}
    dropped: list[dict] = []
    names_by_subject: dict[str, set[str]] = defaultdict(set)
    node_by_key: dict[tuple[str, str], dict] = {}
    for subject in pack["subjects"]:
        for t in subject["topics"]:
            for p in t.get("knowledgePoints") or []:
                key = (subject["subject"], p["slug"])
                node_by_key[key] = p
                names_by_subject[subject["subject"]].add(p["name"])
    for key, point in node_by_key.items():
        subject, _slug = key
        keep: set[str] = set()
        for title in titles.get(key, set()):
            reason = title_usable(title)
            if reason:
                dropped.append({"subject": subject, "slug": point["slug"], "candidate": title,
                                "reason": reason})
                continue
            keep.add(title.strip())
        # 与其它节点主名撞车 → 歧义，丢弃
        for alias in sorted(keep & (names_by_subject[subject] - {point["name"]})):
            keep.discard(alias)
            dropped.append({"subject": subject, "slug": point["slug"], "candidate": alias,
                            "reason": "与同学科另一节点主名相同（歧义）"})
        # 与自己的主名相同 → 冗余（App 侧 aliases 是集合，重复值直接被契约拒）
        keep.discard(point["name"])
        candidates[key] = keep
    # 全局互斥：被多个节点 claim 的候选全部丢弃
    claim = Counter()
    for keep in candidates.values():
        for alias in keep:
            claim[alias] += 1
    for key, keep in candidates.items():
        for alias in [a for a in keep if claim[a] > 1]:
            keep.discard(alias)
            dropped.append({"subject": key[0], "slug": node_by_key[key]["slug"],
                            "candidate": alias, "reason": f"被 {claim[alias]} 个节点同时 claim（全局互斥）"})
    return {"candidates": candidates, "dropped": dropped, "node_by_key": node_by_key}


def apply_to_pack(pack: dict, plan: dict) -> dict:
    stats = Counter()
    for key, point in plan["node_by_key"].items():
        extra = sorted(a for a in plan["candidates"].get(key, set()) if a != point["name"])
        new_aliases = [point["name"]] + extra
        assert len(set(new_aliases)) == len(new_aliases), new_aliases
        old = point.get("aliases") or []
        if old != new_aliases:
            stats["nodes_changed"] += 1
        stats["old_total"] += len(old)
        stats["new_total"] += len(new_aliases)
        point["aliases"] = new_aliases
    return stats


def write_tables(plan: dict) -> None:
    TABLES.mkdir(parents=True, exist_ok=True)
    with (TABLES / ALIAS_MAP).open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["subject", "slug", "alias"])
        for (subject, slug), keep in sorted(plan["candidates"].items()):
            for alias in sorted(keep):
                w.writerow([subject, slug, alias])
    with (TABLES / DROPPED).open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["subject", "slug", "candidate", "reason"])
        w.writeheader()
        w.writerows(sorted(plan["dropped"], key=lambda r: (r["subject"], r["slug"], r["candidate"])))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    plan = rebuild(pack)
    candidates = plan["candidates"]
    with_alias = sum(1 for v in candidates.values() if v)
    print(f"节点 {len(candidates)}：有别名 {with_alias}，别名总数 {sum(len(v) for v in candidates.values())}")
    print(f"丢弃候选 {len(plan['dropped'])}（按原因）:")
    for reason, count in Counter(d["reason"].split("：")[0] for d in plan["dropped"]).most_common(8):
        print(f"   {reason:<26} {count}")
    stats = apply_to_pack(pack, plan)
    print(f"别名：{stats['old_total']} → {stats['new_total']}；变更节点 {stats['nodes_changed']}")
    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0
    write_tables(plan)
    pack_io.dump_json(pack, path)
    print(f"→ 已写回 {path}；表：{ALIAS_MAP} / {DROPPED}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
