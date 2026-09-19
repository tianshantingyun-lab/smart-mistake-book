# -*- coding: utf-8 -*-
"""修材料文本里的两类上游残迹：LaTeX 命令损坏、控制字符。

## 它消灭的失败

成品 sidecar 里 **194 条材料**的 LaTeX 命令是坏的（`\\cos\\alpha` 被写成 `\\coslpha`、
`\\theta` 被写成 `<TAB>heta`），另有 **80 条**正文里留着控制字符（61 条 TAB + 23 条孤立 CR）。
这些字段是**模型直接读到的文本**，坏掉就是让模型读到 `\\coslpha`。

修复逻辑本身早就写好、也早就在跑——但只在 `build.py` 的**内存**里跑（`_repair_material_text`），
而 `build.py` 已停用，成品由一批逐项工具直接改写。于是"算得出该修什么"和"真的改到成品"
之间断了：`report_latex` 实测 194 条**全部可修**，盘上却一条都没修。本工具补上这一段。

## 做法：与 build.py 同一套修复、同一顺序

`strip_control_junk(repair_latex_commands(text, commands))`。**顺序不能反**：形态 2 的损坏
（`<TAB>heta` ← `\\theta`、`<CR>ight` ← `\\right`）拿控制符本身当证据，先 strip 就把证据毁了。

## 无损校验（全过才写回）

1. **材料集合逐条不变**（slug 多重集、顺序不变）。
2. **只有那 5 个文本字段会变**：其余键逐条做规范化指纹比对，任何一个不同即拒绝写回。
3. **只改真坏的材料**：未受损的字段一个字节都不动（diff 最小、可复核）。
4. **修后门判据归零**：`gate._latex_damaged` 与控制字符集在改动材料上都必须为 0。
5. **幂等**：同一份输入跑两遍，第二遍 0 改动。

## 用法

    PYTHONPATH=tools python -m kb_build.fix_material_text            # 报告
    PYTHONPATH=tools python -m kb_build.fix_material_text --write    # 写回 sidecar
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from kb_build import gate, pack_io, textfix

FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")


def _other_field_fingerprint(doc: dict) -> list[tuple]:
    """除 [FIELDS] 外的全部内容指纹——用来证明"只改了那 5 个字段"。

    逐材料记录 slug 与其余键的规范化取值；列表顺序也在内（材料顺序变了同样算改动）。
    """
    out = []
    for material in doc["materials"]:
        rest = {k: v for k, v in material.items() if k not in FIELDS}
        out.append((material.get("slug"),
                    json.dumps(rest, sort_keys=True, ensure_ascii=False)))
    return out


def _is_junk(text: str) -> bool:
    return any(ch in text for ch in textfix._CONTROL_JUNK)


def repair_document(doc: dict) -> dict:
    """就地把一个 sidecar 的受损材料文本修好，返回统计。"""
    commands = gate._REAL_LATEX_COMMANDS
    stats = {"damaged": 0, "junk": 0, "repaired": 0, "bad_fields": 0,
             "still_damaged": [], "still_junk": []}
    for material in doc["materials"]:
        damaged = [f for f in FIELDS if gate._latex_damaged(material.get(f) or "")]
        junk = [f for f in FIELDS if _is_junk(material.get(f) or "")]
        if damaged:
            stats["damaged"] += 1
        if junk:
            stats["junk"] += 1
        touched = False
        for field in damaged + [f for f in junk if f not in damaged]:
            original = material.get(field) or ""
            fixed = textfix.strip_control_junk(
                textfix.repair_latex_commands(original, commands)
            )
            if fixed != original:
                material[field] = fixed
                touched = True
        if touched:
            stats["repaired"] += 1
        # 修完必须干净：门判据与控制字符集都要归零，否则如实报出来
        for field in FIELDS:
            value = material.get(field) or ""
            if gate._latex_damaged(value):
                stats["bad_fields"] += 1
                stats["still_damaged"].append(f"{material.get('slug')}.{field}")
            if _is_junk(value):
                stats["still_junk"].append(f"{material.get('slug')}.{field}")
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="修材料文本的 LaTeX 与控制字符残迹")
    parser.add_argument("--write", action="store_true", help="写回 sidecar（默认只报告）")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    paths = pack_io.sidecar_paths()
    docs = [pack_io.load_json(p) for p in paths]
    before = [_other_field_fingerprint(d) for d in docs]

    merged = {"damaged": 0, "junk": 0, "repaired": 0, "bad_fields": 0,
              "still_damaged": [], "still_junk": []}
    for doc in docs:
        stats = repair_document(doc)
        for key in ("damaged", "junk", "repaired", "bad_fields"):
            merged[key] += stats[key]
        for key in ("still_damaged", "still_junk"):
            merged[key].extend(stats[key])

    print(f"受损材料（LaTeX）{merged['damaged']}；含控制字符 {merged['junk']}；"
          f"实际改写 {merged['repaired']} 条")
    print(f"修后仍受损字段 {merged['bad_fields']}；仍含控制字符字段 {len(merged['still_junk'])}")
    for line in merged["still_damaged"][:8]:
        print(f"   ! 仍受损 {line}")
    for line in merged["still_junk"][:8]:
        print(f"   ! 仍含控制字符 {line}")

    # 无损一：其余字段逐条不变
    after = [_other_field_fingerprint(d) for d in docs]
    changed_elsewhere = [i for i, (a, b) in enumerate(zip(before, after)) if a != b]
    if changed_elsewhere:
        print(f"\n无损校验失败：第 {changed_elsewhere[:5]} 个 sidecar 的非文本字段被改动")
        return 1
    # 无损二：幂等（把修好的结果按写盘-读回的路径再过一遍，必须 0 改动）
    second = sum(
        repair_document(json.loads(pack_io.serialize(doc)))["repaired"]
        for doc in docs
    )
    if second:
        print(f"\n无损校验失败：重放仍要再改 {second} 条（修复不幂等）")
        return 1
    print("无损校验通过：材料集合与其余字段逐条不变；修复幂等（重放 0 改动）")

    if args.write:
        for doc, path in zip(docs, paths):
            pack_io.dump_json(doc, path)
        print(f"\n→ 已写回 {len(docs)} 个 sidecar")
    else:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
