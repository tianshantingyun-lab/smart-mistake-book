# -*- coding: utf-8 -*-
"""把知识点从当前主题移到更合适的主题（表驱动、幂等、无损）。

## 它消灭的失败

两轮扫描件视觉转写新建了 328 个方法/概念节点。落点按关键词批量判的：
跨章的方法类统一进了各科「综合」桶（物理 113、生物 29、化学 13），数学没有综合桶，
于是 15 个兜底节点落进了「函数的概念与性质」。语义上偏粗——一个讲「传送带模型」的节点
挂在「综合复习」里，使用者按章浏览时找不到它；讲「晶格投影」的节点挂在综合桶里同理。

`relocate_chapter_points` 只处理"章层→主题层"，覆盖不了这种"主题→另一主题（可跨册）"的移动，
故本模块补上：**同时改写 boundary 的定位串与章表行**，让门禁的归属一致性继续成立
（不改定位串会导致 `chapter_locator_mismatch` 涨）。

## 无损与幂等

- 点的 `slug` 不动（身份；材料绑定/前置/检索特征都引用它），只换 `parentSlug`。
- 每个点守恒：移动前后知识点总数不变。
- 目标主题不存在时报错（不静默新建），**只有**目标属于某科 `·综合·综合·综合` 链且该链缺失时，
  才按其它科的既有形态补齐 `X·综合` → `X·综合·综合` → `X·综合·综合·综合`（名：跨册综合/综合复习/综合复习）。
- 重复执行结果相同：已在目标主题且定位串已对齐的行会被跳过。

## 用法

    PYTHONPATH=tools python -m kb_build.relocate_points            # 报告
    PYTHONPATH=tools python -m kb_build.relocate_points --write    # 写回成品包
"""

from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path

from kb_build import pack_io, tables, topic_order

TABLE = "point_relocation.csv"
COLUMNS = ("subject", "slug", "to_topic_slug", "reason")
NEW_TOPICS = "new_topics.csv"
TABLES = Path(__file__).resolve().parent / "tables"
CHAPTER_MAP = "chapter_map.csv"
LOCATOR = re.compile(r"^定位：[^。]*。")


def load_relocations(path: Path | None = None) -> list[dict]:
    path = path or (TABLES / TABLE)
    if not path.exists():
        return []
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    seen: set[tuple[str, str]] = set()
    out: list[dict] = []
    for r in rows:
        subject, slug, target = (r["subject"].strip(), r["slug"].strip(), r["to_topic_slug"].strip())
        if not subject or not slug or not target:
            raise ValueError(f"{TABLE}: subject/slug/to_topic_slug 不能为空：{r}")
        if not r["reason"].strip():
            raise ValueError(f"{TABLE}: {subject}/{slug} 缺 reason（为什么该挪）")
        if (subject, slug) in seen:
            raise ValueError(f"{TABLE}: ({subject}, {slug}) 重复")
        seen.add((subject, slug))
        out.append({"subject": subject, "slug": slug, "to_topic_slug": target, "reason": r["reason"].strip()})
    return out


def place_of_topic(topic_slug: str) -> str:
    """由目标主题 slug 推出 boundary 定位串里的"册 章"。"""
    segments = [s for s in topic_slug.split("·") if s]
    if len(segments) >= 3 and segments[1] == "综合" and segments[2] == "综合":
        return f"{segments[0]}·综合·综合"
    if len(segments) >= 2:
        return f"{segments[0]} {segments[1]}"
    return segments[0]


def ensure_synthesis_chain(pack: dict, subject: str, target_slug: str) -> int:
    """按 slug 逐级补齐缺失的中间主题（含 '<SUBJ>·综合' 链）。返回新建主题数。

    只补"路径上缺的那几级"，不新建叶子本身：叶子（目标主题）必须已在表中给出，
    否则说明表写错了名字——那种错要报错暴露，不能靠自动建主题掩盖。
    深度规则：册=0、章=1、主题=2；知识点只能挂主题层（gate 的 chapter_layer_has_points
    会把挂到章层的点算作违规），所以目标至少要有 3 段。
    """
    segments = [s for s in target_slug.split("·") if s]
    if len(segments) < 3:
        return 0
    subj_pack = next((s for s in pack["subjects"] if s["subject"] == subject), None)
    if subj_pack is None:
        raise ValueError(f"未知科目 {subject}")
    existing = {t["slug"] for t in subj_pack["topics"]}
    created = 0
    for depth in range(1, len(segments)):      # 只补祖先，不含最后一个叶子段
        slug = "·".join(segments[:depth])
        if slug in existing:
            continue
        if segments[:depth][1:] == ["综合"] * (depth - 1) and segments[0] == subject:
            name = "跨册综合" if depth == 1 else "综合复习"
        else:
            name = segments[depth - 1]
        subj_pack["topics"].append({
            "slug": slug, "name": name,
            "parentSlug": ("·".join(segments[:depth - 1]) if depth > 1 else None),
            "sourceLocator": f"定位：{slug}",
            "knowledgePoints": [],
        })
        existing.add(slug)
        created += 1
    return created


def load_new_topics(path: Path | None = None) -> list[dict]:
    """显式要新建的主题（叶子）表：subject,slug,name,reason。

    为什么单独一张表、而不是让归位自动建叶子：主题是给使用者看的导航层，
    名字写错会静默长出一个垃圾主题。放表里 = 逐条可审、可 diff、可回滚。
    """
    path = path or (TABLES / NEW_TOPICS)
    if not path.exists():
        return []
    rows = tables._read(path)
    tables._require_columns(NEW_TOPICS, rows, ("subject", "slug", "name", "reason"))
    out = []
    for r in rows:
        if not (r["subject"].strip() and r["slug"].strip() and r["name"].strip() and r["reason"].strip()):
            raise ValueError(f"{NEW_TOPICS}: 四列都不能为空：{r}")
        out.append({k: r[k].strip() for k in ("subject", "slug", "name", "reason")})
    return out


def create_topics(pack: dict, rows: list[dict]) -> dict:
    stats = {"created": 0, "skipped": 0, "errors": []}
    for r in rows:
        subj_pack = next((s for s in pack["subjects"] if s["subject"] == r["subject"]), None)
        if subj_pack is None:
            stats["errors"].append(f"未知科目 {r['subject']}")
            continue
        existing = {t["slug"] for t in subj_pack["topics"]}
        if r["slug"] in existing:
            stats["skipped"] += 1
            continue
        stats["created"] += ensure_synthesis_chain(pack, r["subject"], r["slug"])
        segments = [s for s in r["slug"].split("·") if s]
        subj_pack["topics"].append({
            "slug": r["slug"], "name": r["name"],
            "parentSlug": ("·".join(segments[:-1]) if len(segments) > 1 else None),
            "sourceLocator": f"定位：{r['slug']}",
            "knowledgePoints": [],
        })
        stats["created"] += 1
    return stats


def relocate(pack: dict, rows: list[dict]) -> dict:
    stats = {"moved": 0, "skipped": 0, "topics_created": 0, "errors": [], "moves": []}
    # 主题索引：subject -> {slug: topic}
    for subject in pack["subjects"]:
        subj = subject["subject"]
        myrows = [r for r in rows if r["subject"] == subj]
        if not myrows:
            continue
        for r in myrows:
            stats["topics_created"] += ensure_synthesis_chain(pack, subj, r["to_topic_slug"])
        topics = {t["slug"]: t for t in subject["topics"]}
        point_by_slug = {}
        for t in subject["topics"]:
            for kp in t.get("knowledgePoints") or []:
                point_by_slug[kp["slug"]] = t
        for r in myrows:
            current = point_by_slug.get(r["slug"])
            if current is None:
                stats["errors"].append(f"[{subj}] 知识点不存在：{r['slug']}")
                continue
            target = topics.get(r["to_topic_slug"])
            if target is None:
                stats["errors"].append(f"[{subj}] 目标主题不存在：{r['to_topic_slug']}")
                continue
            if current["slug"] == target["slug"]:
                stats["skipped"] += 1
                continue
            point = next(kp for kp in current["knowledgePoints"] if kp["slug"] == r["slug"])
            want_place = place_of_topic(target["slug"])
            boundary = point.get("boundary") or ""
            new_boundary = (LOCATOR.sub(f"定位：{want_place}。", boundary)
                            if LOCATOR.match(boundary) else f"定位：{want_place}。{boundary}")
            current["knowledgePoints"] = [kp for kp in current["knowledgePoints"] if kp["slug"] != r["slug"]]
            point["boundary"] = new_boundary
            target.setdefault("knowledgePoints", []).append(point)
            stats["moved"] += 1
            stats["moves"].append({"subject": subj, "slug": r["slug"],
                                   "from_topic": current["slug"], "to_topic": target["slug"],
                                   "place": want_place, "reason": r["reason"]})
    return stats


def sync_chapter_map(moves: list[dict], path: Path | None = None) -> dict:
    """章表行跟着点走：目标是综合链 → 删行（无节点级覆盖）；否则改 volume/chapter/theme。"""
    path = path or (TABLES / CHAPTER_MAP)
    if not path.exists():
        return {"updated": 0, "removed": 0}
    with path.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        cols = list(reader.fieldnames or [])
        rows = list(reader)
    by_key = {(m["subject"], m["slug"]): m for m in moves}
    updated = removed = 0
    out = []
    for row in rows:
        move = by_key.get((row["subject"], row["slug"]))
        if not move:
            out.append(row)
            continue
        segments = [s for s in move["to_topic"].split("·") if s]
        if segments[-3:] == ["综合", "综合", "综合"]:
            removed += 1
            continue
        row["volume"] = segments[0]
        row["chapter"] = segments[1] if len(segments) > 1 else ""
        row["theme"] = segments[2] if len(segments) > 2 else ""
        updated += 1
        out.append(row)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=cols)
        writer.writeheader()
        writer.writerows(out)
    return {"updated": updated, "removed": removed}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    rows = load_relocations()
    if not rows:
        print(f"{TABLE} 为空，无事可做")
        return 0
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    topic_stats = create_topics(pack, load_new_topics())
    stats = relocate(pack, rows)
    stats["errors"] = topic_stats["errors"] + stats["errors"]
    print(f"新建主题：{topic_stats['created']}（幂等跳过 {topic_stats['skipped']}）")
    print(f"归位表 {len(rows)} 行：移动 {stats['moved']}、跳过（已就位）{stats['skipped']}、"
          f"按需补祖先 {stats['topics_created']}、错误 {len(stats['errors'])}")
    for e in stats["errors"][:10]:
        print("  !", e)
    if stats["errors"]:
        return 1
    by_target: dict[str, int] = {}
    for m in stats["moves"]:
        by_target[m["to_topic"]] = by_target.get(m["to_topic"], 0) + 1
    for target, count in sorted(by_target.items(), key=lambda x: -x[1]):
        print(f"  → {target[:64]:<66} {count}")
    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0
    # 写回前排一道父级先序：本模块是**唯一会 append topic 的写入者**
    # （`ensure_synthesis_chain` 把新建的「综合」链无条件追加到数组尾），而加载器按数组顺序
    # 生成节点、外键逐行检查——子级排在父级前面会让整批安装崩掉。实测这条路径造出过 4 处。
    reordered = 0
    for subject in pack["subjects"]:
        ordered = topic_order.parent_first(subject["topics"])
        if ordered != subject["topics"]:
            subject["topics"] = ordered
            reordered += 1
    if reordered:
        print(f"父级先序：{reordered} 个科目的 topic 数组被重排")

    pack_io.dump_json(pack, path)
    if stats["moves"]:
        print("章表同步：", sync_chapter_map(stats["moves"]))
    print(f"→ 已写回 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
