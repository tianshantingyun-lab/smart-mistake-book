# -*- coding: utf-8 -*-
"""把"错绑嫌疑"切成作业片，交子代理逐条裁定（改绑 / 保留 / 都不合适）。

## 判据与它的局限

嫌疑来自 `binding_suspects.csv`：**材料标题与另一个节点的名字相同**（例如材料《等角定理》
挂在「空间向量的有关定理」下，而包里确有「等角定理」这个节点）。这是**启发式**：
同名通常意味着错绑，但也可能是"材料确实服务现节点、只是名字撞了"。所以必须逐条读内容判，
不能自动改——错绑会让检索把不相干材料喂给模型，改错则把好绑定拆掉。

## 片里给什么

每条给出：材料标题/摘要/正文（截断）/边界、**现挂节点**（名字+边界原文）、
**候选节点**（标题同名者，名字+边界原文）。两侧边界是关键证据——它写明该节点收什么。

## 回填格式

`subject,material_slug,current_node_slug,verdict,node_slug,evidence`
- `verdict` ∈ `REBIND`（改绑到候选/其它更合适的节点）/ `KEEP`（现挂正确，判据误报）
  / `NONE`（两个都不合适，需人工）
- `node_slug`：REBIND 时必填（同科已存在节点；缺省即候选节点）
- `evidence`：一句话依据，引用材料内容与节点边界，不要 ASCII 逗号

用法：
    PYTHONPATH=tools python tools/kb_coverage/make_rebind_slices.py [--count 12]
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

SUSPECTS = Path("tools/kb_build/tables/binding_suspects.csv")
OUT_DIR = Path("tools/kb_coverage/rebind_slices")
EXCERPT = 900


def load_nodes() -> dict[tuple[str, str], dict]:
    pack = pack_io.load_json(pack_io.pack_path())
    out = {}
    for s in pack["subjects"]:
        for t in s["topics"]:
            for kp in (t.get("knowledgePoints") or []):
                out[(s["subject"], kp["slug"])] = kp
    return out


def load_materials() -> dict[str, dict]:
    out = {}
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            out[m["slug"]] = m
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=12)
    args = parser.parse_args(argv)

    nodes = load_nodes()
    materials = load_materials()
    with SUSPECTS.open(encoding="utf-8", newline="") as fh:
        rows = [r for r in csv.DictReader(fh) if r["subject"] in
                ("PHYSICS", "CHEMISTRY", "BIOLOGY")]
    rows.sort(key=lambda r: (r["subject"], r["material"]))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    bins: list[list[dict]] = [[] for _ in range(args.count)]
    for i, r in enumerate(rows):
        bins[i % args.count].append(r)

    manifest = []
    for n, bucket in enumerate(bins, 1):
        lines = [
            f"# 错绑裁定片 {n:02d}：{len(bucket)} 条",
            "#",
            "# 每条嫌疑的形状：材料标题与**另一个节点**同名。逐条读内容与两侧节点边界后判：",
            "#   材料到底服务哪个点？现挂对不对？",
            "#",
            "# 交付：Write 到 tools/kb_coverage/rebind_fixes/slice_%02d/notes.csv" % n,
            "#   表头 subject,material_slug,current_node_slug,verdict,node_slug,evidence",
            "#   verdict：REBIND（改绑）/ KEEP（现挂正确，判据误报）/ NONE（都不合适）",
            "#   字段内不要有 ASCII 逗号（用「；」）",
            "#",
            "# 硬规矩：",
            "#   · 宁缺勿错：改错会把好绑定拆掉、还会污染别名表；拿不准写 NONE",
            "#   · REBIND 的 node_slug 必须是同科已存在节点（默认就是候选节点）",
            "#   · 判断依据是「材料讲了什么」对上「节点边界声明收什么」，不是词面",
            "",
        ]
        for r in bucket:
            m = materials.get(r["material"], {})
            cur = nodes.get((r["subject"], r["current_node_slug"]), {})
            cand = nodes.get((r["subject"], r["title_matches_node"]), {})
            lines.append("=" * 78)
            lines.append(f"## {r['subject']} | {r['material']}")
            lines.append(f"材料标题：{r['material_title']}")
            for f in ("summaryMarkdown", "contentMarkdown", "boundaryMarkdown"):
                v = (m.get(f) or "").strip()
                if not v:
                    continue
                if len(v) > EXCERPT:
                    v = v[:EXCERPT] + " …（截断）"
                lines.append(f"  [{f}] {v}")
            lines.append(f"现挂节点：{r['current_node_name']}（slug={r['current_node_slug']}）")
            lines.append(f"  现挂节点边界：{(cur.get('boundary') or '')[:260]}")
            lines.append(f"候选节点：{r['title_matches_node']}"
                         f"（名字={cand.get('name', '?')}）")
            lines.append(f"  候选节点边界：{(cand.get('boundary') or '')[:260]}")
            lines.append("")
        (OUT_DIR / f"slice_{n:02d}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        manifest.append({"slice": n, "materials": len(bucket),
                         "file": str(OUT_DIR / f"slice_{n:02d}.txt"),
                         "out_dir": str(Path("tools/kb_coverage/rebind_fixes") / f"slice_{n:02d}"),
                         "rows": [r["material"] for r in bucket]})

    (OUT_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print("物化生嫌疑 %d 条 → %d 片（%s）" % (len(rows), args.count, OUT_DIR))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
