# -*- coding: utf-8 -*-
"""把"文本损坏"的材料切成作业片，交给子代理逐条修好（写回"修好的整字段"）。

## 为什么要修，而不是只登记

材料文本是**讲题时模型直接读到的输入**：`$\\Rightarrow\\1=a` 读进去，公式语义就断了；
`/usr/bin/bash.1\\ \\mathrm{g}$` 读进去是仪器精度 0.1 g。它们不是显示问题，是内容问题。

## 损坏分四类（都由 `kb_build.gate.field_text_defects` 机械判定）

- A `invalid_escape`：`\\1` 这类非法转义（回指残迹）
- B `dollar_unbalanced`：`$` 不成对（数学分隔符被吃掉）
- C `shell_expanded_script_name`：字面 `/usr/bin/bash`（`$0` 被 shell 展开）
- D `pid_repeat`：同一 5–7 位数重复出现（`$$` 被 shell 展开成 PID）

## 代理怎么交作业（关键：不让模型抄原文）

每个代理在 `tools/kb_coverage/text_fixes/slice_NN/` 下写：

- `<slug>.<field>.txt`：**修好的整字段原文**（纯文本，不做任何转义）
- `notes.csv`：`subject,slug,field,status,evidence`
  - `status` ∈ `FIXED`（已修）/ `WIDER_FIX`（需要动到被吃掉的正文，超出机械许可范围）
    / `UNCERTAIN`（证据不足，不敢改）

只写"修好的结果"、**不重述原文**，是因为这一批损坏本身就源于模型抄写文本
（`\\1`、`$0` 展开），把"抄"从链路里去掉，错就没处生。落盘一律用 Write 工具，
**禁止** heredoc / echo / sed —— 那些会把 `$` 与 `\\` 再吃一遍。

用法：
    PYTHONPATH=tools python tools/kb_coverage/make_text_fix_slices.py [--count 30]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import gate, pack_io  # noqa: E402

FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")
OUT_DIR = Path("tools/kb_coverage/text_fix_slices")
FIX_ROOT = Path("tools/kb_coverage/text_fixes")
CHUNK_STORE = Path("tools/kb_coverage/tables/extracted_chunks.jsonl")
CHUNK_EXCERPT = 2600
OTHER_EXCERPT = 1400
BACKSLASH = chr(92)
PID_RE = re.compile(r"(?<![\d.])(\d{5,7})(?![\d.])")
BASH_RE = re.compile(r"/usr/bin/bash|/bin/bash")


def annotate(value: str) -> str:
    """`\\1` 原位标成 `\\1[i]`，i 是该字段内出现序号（0 起）。"""
    out, index, i = [], 0, 0
    while i < len(value):
        if value.startswith(BACKSLASH + "1", i):
            out.append(f"{BACKSLASH}1[{index}]")
            index += 1
            i += 2
            continue
        out.append(value[i])
        i += 1
    return "".join(out)


def spelling(value: str) -> list[str]:
    """把该字段里"看得见的坏点"列成清单，供代理一一对照。"""
    out = []
    for pos, frag in gate.find_invalid_escapes(value):
        out.append(f"非法转义：{frag!r}（位置 {pos}）")
    for m in BASH_RE.finditer(value):
        out.append(f"shell 展开的脚本名：{m.group(0)!r}（位置 {m.start()}，原文应为 `$0` 展开的数字）")
    counts: dict[str, int] = {}
    for m in PID_RE.finditer(value):
        counts[m.group(1)] = counts.get(m.group(1), 0) + 1
    for number, n in counts.items():
        if n >= 2:
            out.append(f"疑似 PID：{number} 出现 {n} 次（原文应为 `$$`，即行间公式定界符）")
    if value.count("$") % 2:
        out.append(f"`$` 不成对：共 {value.count('$')} 个（奇数，说明有分隔符被吃掉）")
    return out


def load_chunk_texts() -> dict[str, str]:
    out: dict[str, str] = {}
    if not CHUNK_STORE.exists():
        return out
    with CHUNK_STORE.open(encoding="utf-8") as fh:
        for line in fh:
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if rec.get("chunk_id"):
                out[rec["chunk_id"]] = rec.get("text") or ""
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--count", type=int, default=30)
    args = parser.parse_args(argv)

    chunks = load_chunk_texts()
    items = []
    for sp in pack_io.sidecar_paths():
        doc = pack_io.load_json(sp)
        for m in doc["materials"]:
            bad = {f: gate.field_text_defects(m.get(f) or "") for f in FIELDS}
            bad = {f: d for f, d in bad.items() if d}
            if not bad:
                continue
            node = ""
            for b in m.get("bindings") or []:
                node = b.get("knowledgeNodeId", "").split(":")[-1]
                break
            found = re.search(r"（块 ([^）]+)）", m.get("sourceLocator") or "")
            weight = sum(len(gate.find_invalid_escapes(m.get(f) or "")) +
                         len(m.get(f) or "") // 400 for f in bad)
            items.append({"subject": m["subject"], "slug": m["slug"], "node": node,
                          "volume": sp.name, "locator": m.get("sourceLocator") or "",
                          "chunk_id": found.group(1) if found else "",
                          "bad": bad, "material": m, "weight": max(1, weight)})

    items.sort(key=lambda x: -x["weight"])
    bins: list[list[dict]] = [[] for _ in range(args.count)]
    loads = [0] * args.count
    for item in items:
        k = loads.index(min(loads))
        bins[k].append(item)
        loads[k] += item["weight"]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    manifest = []
    for n, bucket in enumerate(bins, 1):
        bucket.sort(key=lambda x: (x["subject"], x["slug"]))
        lines = [
            f"# 作业片 {n:02d}：{len(bucket)} 条材料待修（文本损坏）",
            "#",
            "# 你要做的：把每条材料里**标出来的坏点**修好，然后按下面的格式交作业。",
            "# 交作业格式（目录 tools/kb_coverage/text_fixes/slice_%02d/）：" % n,
            "#   1) <slug>.<field>.txt —— 修好的整字段原文（纯文本，不转义、不加解释）",
            "#      只对「待修字段」写文件；其余字段不要动、不要交。",
            "#   2) notes.csv —— 表头 subject,slug,field,status,evidence",
            "#      status：FIXED / WIDER_FIX / UNCERTAIN",
            "#      evidence：凭什么这么修（例如「与同材料 summary 的 $T=a$ 一致」、",
            "#                「源头切块原文写的是 pH」）",
            "#",
            "# 硬规矩：",
            "#   · 只修坏点，其余一字不改（不许改写、不许润色、不许重排）",
            "#   · 数学分隔符 $ 必须成对；反斜杠后不得出现数字",
            "#   · 落盘只用 Write 工具；**禁止** heredoc/echo/sed/printf 写文件（会把 $ 和 \\ 再吃一遍）",
            "#   · 修不动就写 WIDER_FIX 或 UNCERTAIN，并在 evidence 里说明，不要猜",
            "",
        ]
        for item in bucket:
            m = item["material"]
            lines.append("=" * 78)
            lines.append(f"## {item['subject']} | {item['slug']}")
            lines.append(f"卷: {item['volume']}｜绑定节点: {item['node'] or '<无>'}")
            lines.append(f"定位: {item['locator']}")
            lines.append("")
            for field in FIELDS:
                text = m.get(field) or ""
                if field in item["bad"]:
                    lines.append(f"-- 待修字段 {field}（缺陷：{'/'.join(item['bad'][field])}）--")
                    lines.append(annotate(text))
                    bad_points = spelling(text)
                    if bad_points:
                        lines.append("   坏点清单：")
                        for b in bad_points:
                            lines.append(f"     · {b}")
                elif text.strip():
                    if len(text) > OTHER_EXCERPT:
                        text = text[:OTHER_EXCERPT] + " …（截断）"
                    lines.append(f"   [{field}] {text}")
            chunk = chunks.get(item["chunk_id"])
            if chunk:
                text = chunk if len(chunk) <= CHUNK_EXCERPT else chunk[:CHUNK_EXCERPT] + " …（截断）"
                lines.append("")
                lines.append(f"-- 源头切块 {item['chunk_id']}（材料是它的改写，可作取证）--")
                lines.append(text)
            lines.append("")
        (OUT_DIR / f"slice_{n:02d}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        manifest.append({"slice": n, "file": str(OUT_DIR / f"slice_{n:02d}.txt"),
                         "out_dir": str(FIX_ROOT / f"slice_{n:02d}"),
                         "materials": len(bucket),
                         "fields": sum(len(b["bad"]) for b in bucket),
                         "slugs": [b["slug"] for b in bucket]})

    (OUT_DIR / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print("材料 %d 条、受损字段 %d 个 → %d 片（%s）"
          % (len(items), sum(len(i["bad"]) for i in items), args.count, OUT_DIR))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
