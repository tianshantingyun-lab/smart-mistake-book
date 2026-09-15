# -*- coding: utf-8 -*-
"""从现行成品生成 node_actions.csv 提案（AI 生成 + 人工定稿的第一步）。

只产出提案，不直接改包。每行都带 reason 与 evidence，便于逐行审校：
- delete  抽取事故（题干/答案标记），改写等于凭空造知识点
- merge   同一概念多个节点（含 ★ 变体），目标是保留的那一个
- rename  名称不可用但有可机械推导的正确名
- review  名称不可用且需要读上下文才能定名，留空交人工

用法：PYTHONPATH=tools python -m kb_build.propose            # 打印统计
      PYTHONPATH=tools python -m kb_build.propose --write    # 写入 tables/
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import defaultdict

from kb_build import gate, pack_io, tables

REASONS = ("delete", "merge", "rename", "review")


def _canon(name: str) -> str:
    """去 ★ 与结尾标点/冒号后的规范名，用于识别同一概念。

    结尾冒号必须一起去掉：`基本不等式：` 与 `基本不等式` 是同一概念的两份抽取，
    不去掉就会在改名后才撞车，把本可直接合并的组留成待审。
    """
    return re.sub(r"[★☆]+", "", name).rstrip("．。，、：: ").strip()


def _bound_counts() -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for _path, material in pack_io.load_materials():
        for binding in material.get("bindings") or []:
            counts[binding["knowledgeNodeId"]] += 1
    return counts


def build_proposals() -> list[dict[str, str]]:
    pack = pack_io.load_json(pack_io.pack_path())
    pack_id = pack["packId"]
    bound = _bound_counts()

    def node_id(subject: str, slug: str) -> str:
        return f"kb:{pack_id}:{subject.lower()}:atomic:{slug}"

    points = [(s, p) for s, _t, p in pack_io.iter_points(pack)]

    # 1) 同一概念分组（去 ★ 后同名），决定 merge 的保留者
    groups: dict[tuple[str, str], list[tuple[str, dict]]] = defaultdict(list)
    for subject, point in points:
        groups[(subject, _canon(point["name"]))].append((subject, point))

    merge_into: dict[tuple[str, str], str] = {}
    for key, members in groups.items():
        if len(members) < 2:
            continue
        # 整组都是抽取残渣时全部删除：若先合并再删保留者，会留下指向已删节点的悬空目标
        canon_name = key[1]
        if all(gate.is_extraction_residue(p["name"]) for _s, p in members):
            continue
        # 通用属性词（性质/定义/分类/模型解读…）不能当保留者：把多个概念并进
        # 一个叫"性质"的节点比留着更糟。整组转人工，由章节表阶段定名。
        if canon_name in _GENERIC:
            continue

        def rank(item: tuple[str, dict]) -> tuple:
            subject, point = item
            return (
                1 if "★" in point["name"] else 0,          # 无星号优先
                -bound.get(node_id(subject, point["slug"]), 0),  # 绑定材料多者优先
                len(point["name"]),                          # 名称短者优先
                point["slug"],                               # 稳定兜底
            )

        ordered = sorted(members, key=rank)
        keep_slug = ordered[0][1]["slug"]
        for subject, point in ordered[1:]:
            merge_into[(subject, point["slug"])] = keep_slug

    # 2) 逐节点给动作
    rows: list[dict[str, str]] = []
    for subject, point in points:
        slug, name = point["slug"], point["name"]
        verdict = gate._is_bad_name(name)

        if (subject, slug) in merge_into:
            rows.append({
                "subject": subject, "slug": slug, "action": "merge",
                "new_name": "", "new_slug": merge_into[(subject, slug)],
                "reason": f"与 {merge_into[(subject, slug)]} 是同一概念（去星号后同名）",
            })
            continue

        if verdict == "题干/答案标记" or verdict == "高考题干残句":
            rows.append({
                "subject": subject, "slug": slug, "action": "delete",
                "new_name": "", "new_slug": "",
                "reason": f"抽取事故：{verdict}，非知识点",
            })
            continue

        cleaned = _propose_rename(name)
        if verdict is None and cleaned is None:
            continue        # 名称本来可用且无需改写
        if cleaned:
            rows.append({
                "subject": subject, "slug": slug, "action": "rename",
                "new_name": cleaned, "new_slug": "",
                "reason": f"{verdict} -> 机械推导" if verdict else "名称只含难度星号/结尾标点，清理即可",
            })
        else:
            rows.append({
                "subject": subject, "slug": slug, "action": "review",
                "new_name": "", "new_slug": "",
                "reason": f"名称不可用（{verdict}），需读上下文定名",
            })

    # 改名后与既有名称撞车的，机械上无法判断是"同一概念的重复"还是"不同概念同名"
    # （`向心力：` 该并入 向心力，而 `电容：通交流隔直流` 与 电容 是两回事），
    # 一律降级为 review，不用 merge 掩盖判断。
    return _downgrade_rename_collisions(rows, points)


def _downgrade_rename_collisions(rows: list[dict[str, str]],
                                  points: list[tuple[str, dict]]) -> list[dict[str, str]]:
    dropped = {(r["subject"], r["slug"]) for r in rows if r["action"] in ("delete", "merge")}
    renamed = {(r["subject"], r["slug"]): r["new_name"] for r in rows
               if r["action"] == "rename" and r["new_name"]}

    by_canon: dict[tuple[str, str], list[tuple[tuple[str, str], str]]] = defaultdict(list)
    for subject, point in points:
        key = (subject, point["slug"])
        if key in dropped:
            continue
        final_name = renamed.get(key, point["name"])
        by_canon[(subject, _canon(final_name))].append((key, point["name"]))

    # 同名组里只要有一方要改名，整组都得人工确认：机械上无法区分"同一概念的重复"
    # （向心力： 该并入 向心力）与"不同概念同名"（电容：通交流隔直流 与 电容 是两回事）。
    collided: dict[tuple[str, str], str] = {}
    for members in by_canon.values():
        if len(members) < 2:
            continue
        for key, _name in members:
            collided[key] = "、".join(n[:20] for _k, n in members if _k != key)

    for row in rows:
        key = (row["subject"], row["slug"])
        if row["action"] == "rename" and key in collided:
            row["action"] = "review"
            row["new_name"] = ""
            row["reason"] = f"改名后与「{collided[key]}」同名，需确认是否同一概念（同概念则 merge）"
    return rows


_GENERIC = gate._GENERIC_NOUNS | {
    # 由 2026-09-12 数据普查得到：这些是教辅的结构标签/属性名词，不是知识点名。
    # 它们必须读上下文才能定名（如 `表达式：ΔU＝Q＋W` 该并入哪个知识点），
    # 机械截断会同时丢掉唯一标识信息并制造假重复。
    "实验", "易错辨析", "表达式", "应用", "已知", "模型解读", "大小", "情景分析",
    "仪器构造", "数据处理", "深度思考", "已知反应", "按要求填空", "方向", "定义与目的",
    "基本原则", "分析步骤", "本质", "核心特点", "特殊情形", "天上", "地下", "计算",
    "图象", "图表", "归纳", "总结", "对比", "联系", "区别", "判断", "推导", "证明",
}
# 句子被截断后常见的悬挂连接词尾巴。刻意不含"的/是/为"——它们常是词的一部分
# （"目的"曾被削成"目"），宁可不trim也不要造出错名。
_DANGLING_TAIL = re.compile(r"(形如|如下|例如|其中)$")


def _propose_rename(name: str) -> str | None:
    """能机械推导出可靠名称时返回新名；需要上下文时返回 None。

    规则：去 ★ -> 取第一个冒号前的部分 -> 去掉悬挂连接词尾巴。
    通用名词（定义/意义/性质…）一律不猜，留给人工——它们必须读上下文才能定名。
    """
    stripped = re.sub(r"[★☆]+", "", name).strip()
    if not stripped:
        return None

    # 先去星号看看够不够：`实验：使用高倍显微镜观察几种细胞★★★` 去掉星号就是好名字，
    # 若先去截冒号会截成"实验"（撞通用词表）而白丢一个可用名。
    if stripped != name.strip() and gate._is_bad_name(stripped) is None:
        return stripped

    head = re.split(r"[：:]", stripped, maxsplit=1)[0].strip()
    head = head.rstrip("．。，、；; ")
    # 去掉指向图表的悬空括注：`受力分析（图示如下）` -> `受力分析`
    head = re.sub(r"[（(][^）)]*(图示如下|如下图|如图|如下表|见下图|见下表)[^）)]*[）)]", "", head).strip()

    while True:
        trimmed = _DANGLING_TAIL.sub("", head).rstrip("．。，、；; ")
        if trimmed == head or len(trimmed) < 2:
            break
        head = trimmed

    if len(head) < 2:
        return None
    if head in _GENERIC:
        return None
    if gate._is_bad_name(head) is not None:
        return None
    if head == name.strip():
        return None
    return head


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="写入 tables/node_actions.csv")
    args = parser.parse_args(argv)

    rows = build_proposals()
    by_reason = defaultdict(int)
    for row in rows:
        by_reason[row["action"]] += 1
    total_points = sum(1 for _ in pack_io.iter_points(pack_io.load_json(pack_io.pack_path())))

    print(f"知识点总数 {total_points}；提案 {len(rows)} 行")
    for action in ("delete", "merge", "rename", "review"):
        print(f"   {action:<8} {by_reason[action]:>5}")
    print(f"   保持不动 {total_points - len(rows):>5}")
    print()
    print("review 类样例（需人工定名）：")
    shown = 0
    for row in rows:
        if row["action"] == "review" and shown < 12:
            print(f"   [{row['subject']}] {row['slug'][:52]}  ({row['reason']})")
            shown += 1

    if args.write:
        path = tables.TABLES_DIR / tables.NODE_ACTIONS
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(
                fh, fieldnames=["subject", "slug", "action", "new_name", "new_slug", "reason"]
            )
            writer.writeheader()
            writer.writerows(rows)
        print()
        print(f"已写入 {path}")

    _report_rename_collisions(rows)
    return 0


def _report_rename_collisions(rows: list[dict[str, str]]) -> None:
    """改名后可能与既有名称撞车；这类必须改成 merge，不能留两个同名节点。"""
    pack = pack_io.load_json(pack_io.pack_path())
    final: dict[tuple[str, str], list[str]] = defaultdict(list)
    renamed = {(r["subject"], r["slug"]): r["new_name"] for r in rows
               if r["action"] == "rename" and r["new_name"]}
    dropped = {(r["subject"], r["slug"]) for r in rows if r["action"] in ("delete", "merge")}
    for subject, point in ((s, p) for s, _t, p in pack_io.iter_points(pack)):
        key = (subject, point["slug"])
        if key in dropped:
            continue
        final[(subject, _canon(renamed.get(key, point["name"])))].append(point["name"])
    collisions = {k: v for k, v in final.items() if len(v) > 1}
    print()
    if collisions:
        print(f"警告：改名后仍有 {len(collisions)} 组同名，需改成 merge：")
        for (subject, canon), names in list(collisions.items())[:10]:
            print(f"   [{subject}] {canon[:40]}  <- {names}")
    else:
        print("改名后无新同名，可交给章节表与别名表继续定稿")


if __name__ == "__main__":
    raise SystemExit(main())
