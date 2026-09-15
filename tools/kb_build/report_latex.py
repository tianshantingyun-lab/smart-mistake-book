# -*- coding: utf-8 -*-
"""报告 LaTeX 修复的覆盖情况：修好了多少、还剩哪些需人工确认。

用法：PYTHONPATH=tools python -m kb_build.report_latex
"""

from __future__ import annotations

import collections
import re

from kb_build import gate, pack_io, textfix

BS = chr(92)
FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")


def main() -> int:
    token = re.compile(BS + BS + r"([A-Za-z]+)")
    damaged = repaired = 0
    remaining_names: collections.Counter[str] = collections.Counter()
    remaining_samples: list[str] = []

    for _path, material in pack_io.load_materials():
        for field in FIELDS:
            text = material.get(field) or ""
            if not gate._latex_damaged(text):
                continue
            damaged += 1
            fixed = textfix.repair_latex_commands(text, gate._REAL_LATEX_COMMANDS)
            if not gate._latex_damaged(fixed):
                repaired += 1
                break
            for name in token.findall(fixed):
                if name in gate._REAL_LATEX_COMMANDS:
                    continue
                if any(name.endswith(tail) for tail in gate._LATEX_TAILS):
                    remaining_names[name] += 1
            if len(remaining_samples) < 8:
                remaining_samples.append(f"[{material.get('subject')}] {fixed[:110]}")
            break

    print(f"受损材料 {damaged}；修复成功 {repaired}；仍受损 {damaged - repaired}")
    print()
    print("仍未覆盖的损坏命令名：")
    if not remaining_names:
        print("   （无 —— 剩余项不是命令名吞并形态，需另查）")
    for name, count in remaining_names.most_common(40):
        print(f"   {BS}{name:<22} {count:>4}")
    print()
    print("仍受损的样例：")
    for line in remaining_samples:
        print(f"   {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
