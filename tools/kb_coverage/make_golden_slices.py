# -*- coding: utf-8 -*-
"""R3 金标集：确定性切出 10 个"章"切片（四科覆盖），交语义代理出题。

它消灭的失败：金标集若靠随手挑章，逐章≥0.80 的门（D12）就没有可复核的章集；
切片必须**确定性**——同一成品包重跑逐字节相同——否则冻结前的"一次性"无从谈起。

输入（单一事实源，D11）：成品知识包
    core/data/src/main/resources/knowledge/moe-2025-four-subjects-v1.json
输出（只写 build/，不碰成品 JSON）：
    build/golden/slice-01.json .. slice-10.json
    build/golden/manifest.json（10 片清单：file/subject/chapter/chapterName/nodeCount/target）

选章规则（纯机械，无模型、无时间戳）：
1. 排除合成"综合"节点：slug 以 ``<SUBJECT>·综合`` 开头的 topic 不是教材章
   （跨册综合/综合复习），逐章门不适用；真实章（如"化石燃料的综合利用"）不受影响。
2. 每科按（知识点数降序，包内文件顺序升序）取前 3 章为候选，共 12 个。
3. 候选再按同一序取前 10 → 自然四科覆盖：MATH 3 / PHYSICS 3 / CHEMISTRY 3 / BIOLOGY 1。
4. 切片编号按（学科包内顺序、科内章排名）排列。
每片 target=9（10 片 → 90 条，落 D12 的 80-100 区间）。

下游契约（语义代理，非本脚本职责）：
    读 build/golden/slice-NN.json 的节点清单，挑本章节点写自然中文问句，
    逐行写 build/golden/slice-NN.out.jsonl：
        {"query": "...", "expectedSlug": "...", "subject": "...", "chapter": "<片内 chapter 字段>"}
    机器验证见 tools/kb_coverage/validate_golden.py。

用法（仓库根）：
    python tools/kb_coverage/make_golden_slices.py [--pack 路径] [--out-dir build/golden]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO: Path = Path(__file__).resolve().parents[2]
DEFAULT_PACK: Path = (
    REPO / "core" / "data" / "src" / "main" / "resources" / "knowledge"
    / "moe-2025-four-subjects-v1.json"
)
DEFAULT_OUT_DIR: Path = REPO / "build" / "golden"

SLICE_COUNT = 10
PER_SUBJECT_CANDIDATES = 3
TARGET = 9
ALIASES_KEEP = 8
BOUNDARY_KEEP = 60
# 规范化落盘：键排序 + 仓库 JSON 排版约定（indent=1，不转义非 ASCII），保证逐字节幂等。
_JSON_KW = dict(ensure_ascii=False, indent=1, sort_keys=True)


def load_pack(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _is_synthetic_generic(subject: str, slug: str) -> bool:
    """合成综合节点 = slug 以 '<SUBJECT>·综合' 开头（跨册综合/综合复习，非教材章）。"""
    return slug.startswith(f"{subject}·综合")


def rank_chapters(subject_entry: dict) -> list[dict]:
    """科内章排名：（知识点数降序，包内文件顺序升序），排除合成综合与空章。"""
    subject = subject_entry["subject"]
    ranked: list[tuple[int, int, dict]] = []
    for index, topic in enumerate(subject_entry["topics"]):
        points = topic.get("knowledgePoints") or []
        if not points or _is_synthetic_generic(subject, topic["slug"]):
            continue
        ranked.append((-len(points), index, topic))
    ranked.sort(key=lambda item: (item[0], item[1]))
    return [topic for _, _, topic in ranked]


def pick_slices(pack: dict) -> list[tuple[str, dict]]:
    """按模块 docstring 的规则选出 10 个 (subject, topic)。"""
    candidates: list[tuple[int, int, str, dict]] = []
    for subject_index, subject_entry in enumerate(pack["subjects"]):
        subject = subject_entry["subject"]
        for rank, topic in enumerate(rank_chapters(subject_entry)):
            if rank >= PER_SUBJECT_CANDIDATES:
                break
            candidates.append(
                (-len(topic["knowledgePoints"]), subject_index, subject, topic)
            )
    candidates.sort(key=lambda item: (item[0], item[1], item[3]["slug"]))
    return [
        (subject, topic)
        for _, _, subject, topic in candidates[:SLICE_COUNT]
    ]


def build_slice(number: int, subject: str, topic: dict) -> dict:
    nodes = []
    for kp in topic["knowledgePoints"]:
        nodes.append(
            {
                "slug": kp["slug"],
                "name": kp["name"],
                "aliases": (kp.get("aliases") or [])[:ALIASES_KEEP],
                "boundary": (kp.get("boundary") or "")[:BOUNDARY_KEEP],
            }
        )
    return {
        "slice": number,
        "subject": subject,
        "chapter": topic["slug"],
        "chapterName": topic["name"],
        "target": TARGET,
        "nodeCount": len(nodes),
        "outFile": f"slice-{number:02d}.out.jsonl",
        "nodes": nodes,
    }


def _dump(payload) -> str:
    return json.dumps(payload, **_JSON_KW) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=DEFAULT_PACK,
                        help="成品知识包 JSON（默认 moe-2025-four-subjects-v1.json）")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR,
                        help="切片输出目录（默认 build/golden）")
    args = parser.parse_args(argv)

    pack = load_pack(args.pack)
    chosen = pick_slices(pack)
    if len(chosen) != SLICE_COUNT:
        print(f"error: 选章得到 {len(chosen)} 个，预期 {SLICE_COUNT}——包结构可能已变",
              file=sys.stderr)
        return 1

    args.out_dir.mkdir(parents=True, exist_ok=True)
    manifest = []
    for number, (subject, topic) in enumerate(chosen, 1):
        slice_doc = build_slice(number, subject, topic)
        if slice_doc["nodeCount"] < TARGET:
            print(f"error: slice-{number:02d}（{subject}/{topic['slug']}）"
                  f"节点仅 {slice_doc['nodeCount']} 个，低于 target={TARGET}",
                  file=sys.stderr)
            return 1
        (args.out_dir / f"slice-{number:02d}.json").write_text(
            _dump(slice_doc), encoding="utf-8")
        manifest.append(
            {
                "file": f"slice-{number:02d}.json",
                "subject": subject,
                "chapter": topic["slug"],
                "chapterName": topic["name"],
                "nodeCount": slice_doc["nodeCount"],
                "target": TARGET,
            }
        )
    (args.out_dir / "manifest.json").write_text(_dump(manifest), encoding="utf-8")

    for row in manifest:
        print(f"slice 文件 {row['file']}  {row['subject']:8s} "
              f"{row['chapter']}  nodeCount={row['nodeCount']}  target={row['target']}")
    print(f"已写 {len(manifest)} 片 → {args.out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
