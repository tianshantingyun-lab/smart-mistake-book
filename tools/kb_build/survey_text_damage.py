# -*- coding: utf-8 -*-
"""材料文本损坏普查：把"哪些字段坏了、坏成什么样、凭什么这么说"列成一张表。

## 五类损坏（每一类都有机械证据，不靠感觉）

| 类 | 证据 | 现象 |
|---|---|---|
| A | `\\` + 数字 | `\\Rightarrow\\1=a`：非法转义残迹（回指残迹） |
| B | 同一 5–7 位数字在字段内出现 ≥2 次，或紧贴 `\\frac`/`=` | `$$` 被 shell 展开成 PID（`310243n = \\frac{a-xb}{2}310243`） |
| C | 字面 `/usr/bin/bash` | `$0` 被 shell 展开成脚本名 |
| D | `$` 计数为奇数 | 数学分隔符被吃掉（`$0.1\\ \\mathrm{g}$` → `/usr/bin/bash.1\\ \\mathrm{g}$`） |
| E | 数学片段与源头切块对不上（规范化后找不到） | 分隔符被吃 / 内容被吃，肉眼难查 |

E 只在有源头切块时能判，且**只报不改**（材料是改写，公式通常逐字沿用，但个别改写属正常）。
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import gate, pack_io  # noqa: E402

FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")
BACKSLASH = chr(92)
BASH_LITERAL = re.compile(r"/usr/bin/bash|/bin/bash")
LONG_NUMBER = re.compile(r"(?<![\d.])\d{5,7}(?![\d.])")
MATH_SPAN = re.compile(r"\$[^$]{1,400}\$")
CHUNK_STORE = Path("tools/kb_coverage/tables/extracted_chunks.jsonl")


def norm_formula(text: str) -> str:
    return re.sub(r"\s+", "", text)


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


def pid_like(text: str) -> list[str]:
    """疑似 PID 的数字：`$$` 被 shell 展开后的残留（判据在 gate 里，这里只做展示）。"""
    return [text[:0]] if "pid_repeat" in gate.field_text_defects(text) else []


def survey() -> dict:
    chunks = load_chunk_texts()
    rows = []
    for sp in pack_io.sidecar_paths():
        doc = pack_io.load_json(sp)
        for m in doc["materials"]:
            node = ""
            for b in m.get("bindings") or []:
                node = b.get("knowledgeNodeId", "").split(":")[-1]
                break
            found = re.search(r"（块 ([^）]+)）", m.get("sourceLocator") or "")
            chunk_id = found.group(1) if found else ""
            chunk = chunks.get(chunk_id, "")
            for f in FIELDS:
                v = m.get(f) or ""
                if not v:
                    continue
                classes = {}
                inv = gate.find_invalid_escapes(v)
                if inv:
                    classes["A"] = len(inv)
                pids = pid_like(v)
                if pids:
                    classes["B"] = len(pids)
                if BASH_LITERAL.search(v):
                    classes["C"] = len(BASH_LITERAL.findall(v))
                if v.count("$") % 2:
                    classes["D"] = v.count("$")
                if chunk:
                    missing = []
                    for span in MATH_SPAN.findall(v):
                        core = norm_formula(span.strip("$"))
                        if len(core) >= 6 and core not in norm_formula(chunk):
                            missing.append(core[:60])
                    # 只在字段本身已有 A–D 证据时才把 E 当线索，避免把正常改写全报出来
                    if missing and (set(classes) & {"A", "B", "C", "D"}):
                        classes["E"] = len(missing)
                if classes:
                    rows.append({"volume": sp.name, "subject": m["subject"],
                                 "slug": m["slug"], "node": node, "field": f,
                                 "classes": classes, "chunk_id": chunk_id,
                                 "text": v})
    return {"rows": rows}


def main() -> int:
    data = survey()
    rows = data["rows"]
    total_by_class: Counter = Counter()
    mats: set[str] = set()
    for r in rows:
        for k, v in r["classes"].items():
            total_by_class[k] += v
        mats.add(r["slug"])
    print(f"受损字段 {len(rows)}，涉及材料 {len(mats)}")
    print("按类计数：", dict(sorted(total_by_class.items())))
    print("按学科：", dict(Counter(r["subject"] for r in rows)))
    print("按卷：", dict(Counter(r["volume"][-7:] for r in rows)))
    print("按字段：", dict(Counter(r["field"] for r in rows)))
    print()
    for cls in sorted(total_by_class):
        print(f"== 类 {cls} 样例 ==")
        n = 0
        for r in rows:
            if cls not in r["classes"]:
                continue
            print(f"   [{r['subject']}] {r['slug']}.{r['field']} ×{r['classes'][cls]}")
            if cls in ("B", "C"):
                print("      ", r["text"][:260].replace("\n", "\\n"))
                n += 1
                if n >= 3:
                    break
        if cls in ("A", "D", "E"):
            for r in rows[:3]:
                if cls in r["classes"]:
                    print("      ", r["text"][:200].replace("\n", "\\n"))
                    break
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
