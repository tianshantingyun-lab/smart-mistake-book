# -*- coding: utf-8 -*-
"""通用撤回：把指定 slug 的条目从生成器与 materials.jsonl 里一并删掉。

用法： python withdraw_slugs.py <slug> [<slug> ...]
理由要写在调用方的报告里——这个脚本只做精确切除，不做判断。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

JSONL = Path(r"D:\smart mistake book\knowledge-production\wusan\materials.jsonl")
GEN_DIR = Path(r"D:\smart mistake book\tools\kb_build")
# 入口标记：每条材料都以 `    _material(\n        "<slug>"` 开头，以下一条 `_material(` 或
# 文件末的 `]` 结束。用「到下一条 _material( 之前」的切法，避免依赖每条的具体字段。
START = '    _material(\n        "'


def trim_generator(slug: str) -> None:
    for gen in sorted(GEN_DIR.glob("wusan_*.py")):
        text = gen.read_text(encoding="utf-8")
        marker = START + slug + '"'
        if marker not in text:
            continue
        start = text.index(marker)
        nxt = text.find(START, start + len(marker))
        end = nxt if nxt != -1 else text.index("]\n\n\ndef main", start) + 2
        gen.write_text(text[:start] + text[end:], encoding="utf-8")
        print(f"生成器 {gen.name}：已删 {end - start} 字符（{slug}）")
        return
    print(f"生成器：未找到 {slug}")


def trim_jsonl(slugs: set[str]) -> None:
    lines = [ln for ln in JSONL.read_text(encoding="utf-8").splitlines() if ln.strip()]
    kept = [ln for ln in lines if json.loads(ln)["slug"] not in slugs]
    JSONL.write_text("\n".join(kept) + "\n", encoding="utf-8")
    print(f"materials.jsonl：{len(lines)} -> {len(kept)} 条")


if __name__ == "__main__":
    targets = sys.argv[1:]
    if not targets:
        raise SystemExit("用法：python withdraw_slugs.py <slug> [...]")
    for s in targets:
        trim_generator(s)
    trim_jsonl(set(targets))
