# -*- coding: utf-8 -*-
"""审计并剔除"例题派生"的教学材料——库里只留知识点。

判据（客观信号，可复核）：
  1. 标题是题号/考试来源：`（2025·…）`、`【典例`、`例N`、`第N题`
  2. 正文含成套选项：同一段里出现 ≥3 个 `A.` `B.` `C.` `D.`（或全角／中文顿号变体）
  3. 正文含答案与判定语：`答案`、`故选`、`正确选项`、`解析：` 后紧跟求解过程
  4. 正文是纯题干：以需求动词收尾（求…、正确的是…）且无结论式表述

输出：
  - 审计报告（按判据计数 + 样例）
  - `tables/material_actions.csv`（slug,action,reason）供生成器剔除

用法：PYTHONPATH=tools python -m kb_build.audit_material_examples [--write]
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import Counter

from kb_build import pack_io, tables

# 题号 / 考试来源
_EXAM_TITLE = re.compile(r"^\s*[（(]\s*\d{4}\s*[·・]|【典例|【变式|^例\s*\d|^第\s*\d+\s*题|真题|模拟题")
# 成套选项：同一字段里出现 A./B./C. 且总数>=3
_OPTION = re.compile(r"(?:^|[\s；;。，,])([ABCD])[.．、]")
ANSWER = re.compile(r"答案|故选|正确选项|【答案】|解析：\s*[A-D]\b")
# 纯题干收尾（无结论）
_STEM_TAIL = re.compile(r"(求[^。]{0,12}$|正确的是\s*[（(]?\s*[）)]?\s*$|为\s*[（(]\s*[）)]\s*$)")


def _option_count(text: str) -> int:
    return len({m.group(1) for m in _OPTION.finditer(text)})


def classify(material: dict) -> tuple[bool, str]:
    """返回 (是否例题派生, 依据)。注意用布尔——字符串 "no" 在 Python 里是真值。"""
    title = material.get("title") or ""
    body = " ".join(material.get(k) or "" for k in
                    ("contentMarkdown", "summaryMarkdown", "applicabilityMarkdown"))
    if _EXAM_TITLE.search(title):
        return True, f"标题是题号/来源：{title[:24]}"
    if _option_count(body) >= 3:
        return True, "正文含成套选项 A/B/C/D"
    if ANSWER.search(body):
        return True, "正文含答案/故选等判定语"
    if _STEM_TAIL.search(body) and len(body) < 160:
        return True, "正文是短题干且无结论"
    return False, ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    materials = [m for _p, m in pack_io.load_materials()]
    flagged: list[dict] = []
    reasons = Counter()
    for material in materials:
        is_example, why = classify(material)
        if is_example:
            flagged.append({"slug": material["slug"], "subject": material.get("subject", ""),
                            "title": material.get("title", ""), "reason": why})
            reasons[why.split("：")[0]] += 1

    print(f"教学材料 {len(materials)} 条；判为例题派生 {len(flagged)} 条")
    print()
    print("按判据：")
    for why, count in reasons.most_common():
        print(f"   {count:>5}  {why}")
    print()
    print("样例（前 20）：")
    for row in flagged[:20]:
        print(f"   [{row['subject'][:4]}] {row['title'][:40]}  ← {row['reason'][:34]}")

    if args.write:
        path = tables.TABLES_DIR / "material_actions.csv"
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=["slug", "action", "reason"])
            writer.writeheader()
            for row in flagged:
                writer.writerow({"slug": row["slug"], "action": "delete", "reason": row["reason"]})
        print()
        print(f"已写出 {path}（生成器据此剔除；未列出的材料保持不动）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
