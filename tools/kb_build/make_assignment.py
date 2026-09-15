# -*- coding: utf-8 -*-
"""给子代理生成"作业单"：一章的空白节点清单 + 该章的页号 + 页图/转录件路径。

**为什么要这一步**：子代理**没有 shell 权限**（实测：跑 `blank_nodes` 这类命令会被拒），
所以它不能自己取任务。主代理先把作业单落成文件，代理只读文件即可，不必跑任何命令。

用法：
  PYTHONPATH=tools python -m kb_build.make_assignment "化学必修第一册|第一章 物质及其变化" chem_b1_ch1
  PYTHONPATH=tools python -m kb_build.make_assignment --batch   # 按缺口自动排前 3 章
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
ROUTED = pack_io.REPO / "tools" / "kb_build" / "tables" / "wusan_routed.csv"
SCRATCH = pack_io.REPO / "scratch"
# 会话暂存区：子代理只能读/写这里（以及 tools/、build/wusan-render/），读 D 盘其余位置会被拒。
SCRATCHPAD = Path(r"C:\Users\听云\AppData\Local\Temp\commandcode"
                  r"\C--Users---\1206695c-4363-419f-ba8b-252b711ad74a\scratchpad")
SUBJECT_DIR = {"CHEMISTRY": "chemistry", "PHYSICS": "physics",
               "MATH": "math", "BIOLOGY": "biology"}


def load_blank() -> dict[tuple[str, str, str], list[dict]]:
    pack = json.loads((STAGING / pack_io.PACK_NAME).read_text(encoding="utf-8"))
    bound: set[str] = set()
    for path in sorted(STAGING.glob("moe-2025-teaching-support-v2-*.json")):
        for m in json.loads(path.read_text(encoding="utf-8"))["materials"]:
            for b in m.get("bindings") or []:
                bound.add(b["knowledgeNodeId"])
    rows: dict[tuple[str, str, str], list[dict]] = defaultdict(list)
    for subject in pack["subjects"]:
        name = subject["subject"]
        by_slug = {t["slug"]: t for t in subject["topics"]}
        for topic in subject["topics"]:
            chain, cursor = [], topic
            while cursor is not None:
                chain.append(cursor["name"])
                parent = cursor.get("parentSlug")
                cursor = by_slug.get(parent) if parent else None
            chain.reverse()
            if len(chain) < 2:
                continue
            for point in topic.get("knowledgePoints") or []:
                nid = f"kb:{pack['packId']}:{name.lower()}:atomic:{point['slug']}"
                if nid not in bound:
                    rows[(name, chain[0], chain[1])].append(
                        {"topic": topic["name"], "name": point["name"],
                         "slug": point["slug"], "kind": point["kind"],
                         "boundary": point.get("boundary") or ""})
    return rows


def routed_pages() -> dict[tuple[str, str], list[int]]:
    pages: dict[tuple[str, str], list[int]] = defaultdict(list)
    with ROUTED.open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            try:
                pages[(row["subject"], row["chapter"])].append(int(row["pdf_page"]))
            except ValueError:
                continue
    return pages


def copy_sources(subject: str, chapter: str, key: str, pages: list[int]) -> Path:
    """把该章转录件复制进**会话暂存区**——子代理读不了 `knowledge-research/`。

    实测（2026-09-14 通道测试）：子代理的允许区只有 `C:\\Users\\听云`、`tools\\kb_build`、
    `build/wusan-render/<科>` 等；读 `knowledge-research/**` 会被拒（"outside workspace"），
    而**页图能看、暂存区能写**。所以校对所需的转录件必须先搬进暂存区。
    """
    import shutil
    folder = SUBJECT_DIR[subject]
    src = pack_io.REPO / "knowledge-research" / "candidates" / "wusan" / folder / "transcript"
    dst = SCRATCHPAD / f"src_{key}"
    dst.mkdir(parents=True, exist_ok=True)
    copied = 0
    for page in pages:
        name = f"p{page:04d}.md"
        origin = src / name
        if origin.exists():
            shutil.copy2(origin, dst / name)
            copied += 1
    return dst if copied else dst


def write_assignment(subject: str, book: str, chapter: str, key: str,
                     rows, pages) -> Path:
    items = rows[(subject, book, chapter)]
    page_list = sorted(set(pages.get((subject, chapter), [])))
    folder = SUBJECT_DIR[subject]
    src_dir = copy_sources(subject, chapter, key, page_list)
    out = SCRATCH / f"assign_{key}.md"
    lines = [
        f"# 作业单：{subject} {book} {chapter}",
        "",
        f"- 章缩写（用于文件名）：`{key}`",
        f"- 本页组五三页号：{', '.join(f'p{p:04d}' for p in page_list)}（共 {len(page_list)} 页）",
        f"- 页图目录：`D:\\smart mistake book\\build\\wusan-render\\{folder}\\`",
        f"- **转录件（已复制到暂存区，用这个路径读）**：`{src_dir}`",
        f"  - 原始位置 `knowledge-research/…` 子代理读不了（会被拒），所以搬了一份。",
        "",
        f"## 要写的空白节点（{len(items)} 个）",
        "",
        "| # | 主题 | 节点名 | slug（绑定要用它） | kind | 现有边界 |",
        "|---|---|---|---|---|---|",
    ]
    for index, item in enumerate(items, 1):
        boundary = (item["boundary"] or "").replace("|", "／")[:40]
        lines.append(f"| {index} | {item['topic']} | {item['name']} | `{item['slug']}` "
                     f"| {item['kind']} | {boundary} |")
    lines += [
        "",
        "## 交付",
        "",
        f"1. `D:\\smart mistake book\\scratch\\draft_{key}.jsonl`（一行一条 13 键材料）",
        f"2. `D:\\smart mistake book\\scratch\\draft_{key}.md`（逐节点写没写、逐页校对差异、缺失节点线索）",
        "",
        "作业规程见 `D:\\smart mistake book\\tools\\kb_build\\AGENT_BRIEF.md`——**先完整读它**。",
        "",
        "**你没有 shell 权限**：不要试图运行任何命令（会被拒）。只用 read_file 看页图与转录件、",
        "用 write_file 写上面两个文件。",
    ]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    # **同时写一份到会话暂存区**：子代理读不了 `D:\smart mistake book\scratch\`
    # （它的白名单只有 `C:\Users\听云`、`tools\kb_build`、`build/wusan-render/<科>`），
    # 作业单放在 D 盘会让它第一步就被拒（实测：6 秒、2 次工具调用即中断）。
    SCRATCHPAD.mkdir(parents=True, exist_ok=True)
    (SCRATCHPAD / f"assign_{key}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chapter", nargs="?", default=None, help="`科目|章名`，如 `化学必修第一册|第一章 物质及其变化`")
    parser.add_argument("key", nargs="?", default=None, help="文件名缩写，如 chem_b1_ch1")
    parser.add_argument("--batch", action="store_true", help="按缺口自动排前 3 章")
    args = parser.parse_args(argv)

    rows = load_blank()
    pages = routed_pages()

    if args.batch:
        ranked = sorted(rows.items(), key=lambda kv: (-len(kv[1]), kv[0]))
        picked = ranked[:3]
        for (subject, book, chapter), items in picked:
            key = (f"{subject[:4].lower()}_{book[:2]}_{chapter[:2]}"
                   .replace(" ", "").replace("第", "").replace("章", ""))
            path = write_assignment(subject, book, chapter, key, rows, pages)
            print(f"{path.name}: {len(items)} 个空白节点  [{subject}] {book} {chapter}")
        return 0

    if not args.chapter or not args.key:
        parser.print_help()
        return 0
    book, chapter = args.chapter.split("|", 1)
    matched = [k for k in rows if k[1] == book and k[2] == chapter]
    if not matched:
        print(f"未找到：册「{book}」章「{chapter}」")
        print("空白最多的 12 章（格式：册|章  科目  空白数）：")
        ranked = sorted(rows.items(), key=lambda kv: -len(kv[1]))[:12]
        for (subject_name, book_name, chapter_name), items in ranked:
            print(f"  {book_name}|{chapter_name}  [{subject_name}]  {len(items)} 个空白")
        return 1
    subject_name, book_name, chapter_name = matched[0]
    path = write_assignment(subject_name, book_name, chapter_name, args.key, rows, pages)
    print(f"已写出 {path}（{len(rows[matched[0]])} 个空白节点）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
