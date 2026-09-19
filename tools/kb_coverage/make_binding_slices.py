# -*- coding: utf-8 -*-
"""把"无绑定材料"切成绑定作业片：每条材料配一份候选节点清单，交子代理裁定归属。

## 背景与判据

892 条材料的 `bindings` 是空的（都来自 `registry-*` 批次：材料本身是改写好的知识条目，
但入库时没有绑定任何知识点）。没有绑定 = 讲题检索不到它，所以它是真缺陷，不是外观问题。

判定必须由**语义**做（项目的既定纪律：归位/绑定用模型读内容裁定，不用词面匹配），
因此每片给代理：材料全文（5 个字段）+ **候选节点**（本学科全部节点的 slug 与名字，
外加"名字/别名在标题或摘要里出现过"的机械预筛，标出便于优先看）。

代理回填 `subject,material_slug,node_slug,confidence,evidence`：
`confidence ∈ high/medium/low`；确实没有合适节点的写 `NONE`（宁缺勿错——错误绑定比空绑定更糟：
它会让检索把不相干材料喂给模型，还会污染别名表）。

用法：
    PYTHONPATH=tools python tools/kb_coverage/make_binding_slices.py [--count 30]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

OUT_DIR = Path("tools/kb_coverage/binding_slices")
NODE_DIR = OUT_DIR / "nodes"
FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")


def node_index() -> tuple[dict[str, list[tuple[str, str]]], dict[str, list[str]]]:
    """返回 (subject -> [(slug, name)]) 与 (node slug -> 别名列表)。"""
    pack = pack_io.load_json(pack_io.pack_path())
    by_subject: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for s in pack["subjects"]:
        for t in s["topics"]:
            for kp in (t.get("knowledgePoints") or []):
                by_subject[s["subject"]].append((kp["slug"], kp["name"]))
    aliases: dict[str, list[str]] = defaultdict(list)
    alias_map = Path("tools/kb_build/tables/alias_map.csv")
    if alias_map.exists():
        import csv
        with alias_map.open(encoding="utf-8", newline="") as fh:
            for row in csv.DictReader(fh):
                node = (row.get("slug") or "").strip()
                for a in (row.get("aliases") or "").split("|"):
                    if a.strip():
                        aliases[node].append(a.strip())
    return by_subject, aliases


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=30)
    args = parser.parse_args(argv)

    by_subject, aliases = node_index()
    unbound = []
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            if not (m.get("bindings") or []):
                unbound.append(m)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    NODE_DIR.mkdir(parents=True, exist_ok=True)
    for subject, nodes in by_subject.items():
        lines = [f"# {subject} 节点表（slug | 名字）：{len(nodes)} 个"]
        for slug, name in sorted(nodes, key=lambda x: x[1]):
            lines.append(f"{slug} | {name}")
        (NODE_DIR / f"nodes_{subject}.txt").write_text("\n".join(lines) + "\n",
                                                       encoding="utf-8")

    # 机械预筛：节点名或别名出现在材料文本里
    def prescreen(m: dict) -> list[str]:
        blob = " ".join((m.get(k) or "") for k in FIELDS)
        out = []
        for slug, name in by_subject.get(m["subject"], []):
            if len(name) >= 3 and name in blob:
                out.append(f"{slug}（名字出现）")
                continue
            for a in aliases.get(slug, []):
                if len(a) >= 4 and a in blob:
                    out.append(f"{slug}（别名「{a}」出现）")
                    break
        return out[:10]

    unbound.sort(key=lambda m: (m["subject"], m["slug"]))
    bins: list[list[dict]] = [[] for _ in range(args.count)]
    for i, m in enumerate(unbound):
        bins[i % args.count].append(m)

    manifest = []
    for n, bucket in enumerate(bins, 1):
        lines = [
            f"# 绑定作业片 {n:02d}：{len(bucket)} 条无绑定材料",
            "#",
            "# 任务：为每条材料选定它应该绑定的知识点节点（就是讲题时该材料服务的那个点）。",
            "# 交付（写到 tools/kb_coverage/binding_fixes/slice_%02d/notes.csv）：" % n,
            "#   subject,material_slug,node_slug,confidence,evidence",
            "#   （node_slug 用下面的节点表里的 slug；确实没有合适的写 NONE；",
            "#     evidence 一句话说明依据；字段内不要有 ASCII 逗号）",
            "#",
            "# 硬规矩：",
            "#   · 宁缺勿错：不确定就 confidence=low 或写 NONE —— 错绑会让检索喂错材料",
            "#   · 只能从本学科节点表里选（见 tools/kb_coverage/binding_slices/nodes/nodes_<学科>.txt）",
            "#   · 一条材料只绑一个节点（就是它最主要服务的那个点）",
            "",
        ]
        for m in bucket:
            lines.append("=" * 78)
            lines.append(f"## {m['subject']} | {m['slug']}")
            lines.append(f"来源: {m.get('sourceLocator') or '<无>'}（{m.get('sourceId')}）")
            lines.append(f"类型: {m.get('type')}")
            for f in ("title", "summaryMarkdown", "applicabilityMarkdown",
                      "contentMarkdown", "boundaryMarkdown"):
                value = (m.get(f) or "").strip()
                if not value:
                    continue
                if len(value) > 1200:
                    value = value[:1200] + " …（截断）"
                lines.append(f"[{f}] {value}")
            cand = prescreen(m)
            lines.append("候选（机械预筛，未必对）：" + ("；".join(cand) if cand else "无"))
            lines.append("")
        (OUT_DIR / f"slice_{n:02d}.txt").write_text("\n".join(lines) + "\n",
                                                     encoding="utf-8")
        manifest.append({"slice": n, "materials": len(bucket),
                         "file": str(OUT_DIR / f"slice_{n:02d}.txt"),
                         "out_dir": str(Path("tools/kb_coverage/binding_fixes") / f"slice_{n:02d}"),
                         "slugs": [m["slug"] for m in bucket]})

    (OUT_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print("无绑定材料 %d 条 → %d 片（%s）" % (len(unbound), args.count, OUT_DIR))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
