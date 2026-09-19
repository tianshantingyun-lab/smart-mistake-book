# -*- coding: utf-8 -*-
"""修材料文本里的**非法转义**残迹（当前主要是 2,265 处 `\\1`）。

## 它消灭的失败

成品包 473 条材料的文本里带着 `\\1` 这种残迹（`\\Rightarrow\\1=a`、`\\Delta\\1`、
`\\forall\\1_1`）——它们是模型（子代理）生成 LaTeX 时留下的回指残迹，被逐层照抄进
判定表与 sidecar。后果不是"看着难受"：这些字段是**讲题时模型直接读到的文本**，
读到的是一段语法上无意义的串，公式语义就断了；`title` 还会进别名表参与检索。

门原先看不见它：`latex_damage` 只查"真命令丢了反斜杠"（`\\cos\\alpha` → `\\coslpha`），
反方向（反斜杠后面跟了不该跟的字符）没有任何判据。所以本工具与门里的
`invalid_escape` 是**配套**的：门负责不再让它静默出厂，工具负责把存量修掉。

## 三类分别怎么修

1. **`\\1`（语义不明，必须逐处判定）**：由 `tables/invalid_escape_fixes.csv` 提供。
   表的键是 `(subject, material_slug, field, index)`，`index` 是该字段内第几个
   `\\1`（0 起、从左到右）；`replacement` 是要替换进去的字符/表达式（如 `T`、
   `\\mathrm{m}`）；`evidence` 是"凭什么这么修"的取证说明（一般为同材料其它字段里
   同一公式的完整写法）。**不需要**重述原文，避免抄写出错。
2. **行尾孤立反斜杠**：直接去掉。
3. **`\\` + 全角标点**（如 `\\，`）：去掉反斜杠。
4. **`\\'`**：语料里一律以 `f\\'(x)` 出现（无参数的重音命令，渲染不出东西），
   换成 `'`。

## 无损校验（全过才写回）

1. 材料集合（slug 多重集与顺序）逐条不变。
2. 只有那 5 个文本字段会变：其余键逐条指纹比对，任一不同即拒绝写回。
3. **改动只落在被替换的位置**：把每个字段的"去掉全部非法转义后的骨架"做比对，
   骨架必须逐字不变（等价于证明没有顺手改别处）。
4. **表内替换合法**：替换串自身不得含非法转义；同一 `(slug, field)` 的 `index`
   不得重复、不得越界。
5. **幂等**：重放 0 改动。

## 用法

    PYTHONPATH=tools python -m kb_build.fix_invalid_escapes            # 报告
    PYTHONPATH=tools python -m kb_build.fix_invalid_escapes --write    # 写回
    PYTHONPATH=tools python -m kb_build.fix_invalid_escapes --judgments --write
        # 同时把改动镜像回判定表（按块号 + 字段 + 序号定位，上下文必须逐字相符）
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from pathlib import Path

from kb_build import gate, pack_io

FIELDS = ("title", "summaryMarkdown", "applicabilityMarkdown",
          "contentMarkdown", "boundaryMarkdown")
FIX_TABLE = Path(__file__).resolve().parent / "tables" / "invalid_escape_fixes.csv"
JUDGE_TABLE = Path("tools") / "kb_coverage" / "tables" / "material_judgments.csv"
JUDGE_COLUMN = {"title": "title", "summaryMarkdown": "summary",
                "applicabilityMarkdown": "applicability",
                "contentMarkdown": "content", "boundaryMarkdown": "boundary"}
BACKSLASH = chr(92)
DIGIT_PAT = re.compile(re.escape(BACKSLASH) + r"[0-9]")


class FixTableError(Exception):
    """表本身不合法：宁可整体拒绝，也不写半份。"""


def load_fixes(path: Path | None = None) -> list[dict]:
    path = path or FIX_TABLE
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    for i, row in enumerate(rows, 2):
        for key in ("subject", "material_slug", "field", "index",
                    "replacement", "evidence"):
            if not (row.get(key) or "").strip():
                raise FixTableError(f"{path.name}:{i} 缺 {key}")
        if row["field"] not in FIELDS:
            raise FixTableError(f"{path.name}:{i} field 非法：{row['field']}")
        if not row["index"].strip().isdigit():
            raise FixTableError(f"{path.name}:{i} index 必须是数字：{row['index']}")
        if gate.find_invalid_escapes(row["replacement"]):
            raise FixTableError(f"{path.name}:{i} 替换串自身含非法转义：{row['replacement']!r}")
        if not row["replacement"].strip():
            raise FixTableError(f"{path.name}:{i} 替换串为空")
    return rows


def _skeleton(text: str) -> str:
    """把非法转义整段抹掉后的骨架：用来证明"改动只落在这些位置"。"""
    out = []
    i = 0
    holes = {p for p, _frag in gate.find_invalid_escapes(text)}
    while i < len(text):
        if i in holes:
            i += 1
            nxt = text[i] if i < len(text) else ""
            if nxt and not (nxt.isascii() and nxt.isalpha()):
                i += 1                     # 连同被反斜杠带走的那个字符一起抹
            out.append("\x00")             # 占位符：位置数必须一致
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def _apply_digits(text: str, replacements: list[tuple[int, str]]) -> str:
    """按 index（第几个 `\\1`）就位替换。"""
    out = text
    for index, repl in sorted(replacements, key=lambda kv: -kv[0]):
        hits = [p for p, _frag in gate.find_invalid_escapes(out)
                if out[p:p + 2] == BACKSLASH + "1"]
        if index >= len(hits):
            raise FixTableError(f"index {index} 越界（该字段只有 {len(hits)} 处 \\1）")
        pos = hits[index]
        out = out[:pos] + repl + out[pos + 2:]
    return out


def _apply_mechanical(text: str) -> tuple[str, int]:
    """行尾孤立反斜杠 / `\\` + 全角标点 / `\\'`。返回 (新文本, 改动数)。"""
    changed = 0
    out = text
    # 行尾孤立反斜杠
    new = re.sub(re.escape(BACKSLASH) + r"\s*$", "", out)
    if new != out:
        changed += 1
        out = new
    for pos, frag in reversed(gate.find_invalid_escapes(out)):
        nxt = out[pos + 1:pos + 2]
        if nxt == "'":
            out = out[:pos] + "'" + out[pos + 2:]
            changed += 1
        elif nxt and not (nxt.isascii() and nxt.isalpha()):
            out = out[:pos] + out[pos + 1:]
            changed += 1
    return out, changed


def repair_document(doc: dict, fixes: dict[tuple[str, str, str], list[tuple[int, str]]],
                    stats: dict) -> None:
    for material in doc["materials"]:
        slug = material.get("slug") or ""
        for field in FIELDS:
            original = material.get(field) or ""
            if not original or not gate.find_invalid_escapes(original):
                continue
            replacements = fixes.get((material.get("subject", ""), slug, field), [])
            if DIGIT_PAT.search(original) and not replacements:
                # 有 `\1` 却没人裁定怎么写：整字段一律不动。只做机械修复会把 `\1` 变成 `1`，
                # 公式语义照样是断的，却让"还有几处没修"这个信号消失。
                stats["uncovered"].append(f"{slug}.{field}")
                continue
            out = _apply_digits(original, replacements) if replacements else original
            out, mechanical = _apply_mechanical(out)
            if out == original:
                continue
            if _skeleton(out) != _skeleton(original):
                stats["skeleton_changed"].append(f"{slug}.{field}")
                continue
            material[field] = out
            stats["fields"] += 1
            stats["mechanical"] += mechanical
            stats["digit_hits"] += len(replacements)
            stats["touched"].append((material.get("subject", ""), slug, field,
                                     original, out))


def _judgments_rows() -> tuple[list[dict], list[str]]:
    if not JUDGE_TABLE.exists():
        return [], []
    with JUDGE_TABLE.open(encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        return list(reader), list(reader.fieldnames or [])


def mirror_judgments(touched: list[tuple], locators: dict[str, tuple[str, str]],
                     write: bool) -> tuple[int, list[str]]:
    """把修好的字段文本镜像回判定表同一行。

    定位必须三样同时对：块号（material 的定位串里的块 id）、绑定节点、以及**修复前
    的整字段文本逐字相等**。少一样都不写——判定表是入册的上游，改错一行比不改更糟。
    `locators[slug] = (node_slug, chunk_id)` 由调用方从 sidecar 的 sourceLocator/bindings 取。
    """
    rows, _fields = _judgments_rows()
    if not rows:
        return 0, ["判定表不存在，跳过镜像"]
    stats = 0
    problems: list[str] = []
    for subject, slug, field, old_text, new_text in touched:
        column = JUDGE_COLUMN[field]
        node_slug, chunk_id = locators.get(slug, ("", ""))
        if not chunk_id or not node_slug:
            problems.append(f"{slug}.{field}：材料缺块定位/节点绑定，不镜像")
            continue
        matched = [r for r in rows
                   if r.get("chunk_id") == chunk_id
                   and r.get("node_slug") == node_slug
                   and r.get(column) == old_text]
        if len(matched) != 1:
            problems.append(f"{slug}.{field}：判定表命中 {len(matched)} 行（应为 1），不镜像")
            continue
        matched[0][column] = new_text
        stats += 1
    if write and stats:
        # 行尾必须沿用文件原有风格（判定表是 CRLF）：否则一次修复会把 27MB 全量改写行尾，
        # 让 diff 里塞满与本次修复无关的行。
        raw = JUDGE_TABLE.read_bytes()
        terminator = "\r\n" if raw.count(b"\r\n") > raw.count(b"\n") - raw.count(b"\r\n") else "\n"
        with JUDGE_TABLE.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()),
                                    lineterminator=terminator)
            writer.writeheader()
            writer.writerows(rows)
    return stats, problems


def _material_locators() -> dict[str, tuple[str, str]]:
    """slug -> (绑定节点 slug, 定位串里的块 id)。"""
    out: dict[str, tuple[str, str]] = {}
    for path in pack_io.sidecar_paths():
        for m in pack_io.load_json(path)["materials"]:
            node = ""
            for b in m.get("bindings") or []:
                node = b.get("knowledgeNodeId", "").split(":")[-1]
                break
            found = re.search(r"（块 ([^）]+)）", m.get("sourceLocator") or "")
            out[m["slug"]] = (node, found.group(1) if found else "")
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--judgments", action="store_true",
                        help="同时把修复镜像回判定表")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)
    if args.root:
        pack_io.use_directory(args.root)

    try:
        rows = load_fixes()
    except FixTableError as exc:
        print(f"修复表不合法：{exc}")
        return 1

    fixes: dict[tuple[str, str, str], list[tuple[int, str]]] = {}
    for row in rows:
        key = (row["subject"].strip(), row["material_slug"].strip(), row["field"].strip())
        fixes.setdefault(key, []).append((int(row["index"]), row["replacement"]))

    paths = pack_io.sidecar_paths()
    docs = [pack_io.load_json(p) for p in paths]
    before_rest = [
        [(m.get("slug"), json.dumps({k: v for k, v in m.items() if k not in FIELDS},
                                    sort_keys=True, ensure_ascii=False))
         for m in d["materials"]] for d in docs]

    stats: dict = {"fields": 0, "mechanical": 0, "digit_hits": 0,
                   "uncovered": [], "skeleton_changed": [], "touched": []}
    for doc in docs:
        repair_document(doc, fixes, stats)

    remaining = 0
    for doc in docs:
        for m in doc["materials"]:
            for f in FIELDS:
                remaining += len(gate.find_invalid_escapes(m.get(f) or ""))

    print(f"修复字段 {stats['fields']} 个（其中 \\1 替换 {stats['digit_hits']} 处、"
          f"机械修复 {stats['mechanical']} 处）")
    if stats["uncovered"]:
        print(f"未被表覆盖的 \\1 字段 {len(stats['uncovered'])}：{stats['uncovered'][:6]}")
    for bad in stats["skeleton_changed"][:6]:
        print(f"   ! 骨架被改动，拒绝写回：{bad}")
    print(f"修后仍余非法转义 {remaining} 处")

    after_rest = [
        [(m.get("slug"), json.dumps({k: v for k, v in m.items() if k not in FIELDS},
                                    sort_keys=True, ensure_ascii=False))
         for m in d["materials"]] for d in docs]
    if before_rest != after_rest:
        print("无损校验失败：材料集合或非文本字段被改动")
        return 1
    second = {"fields": 0, "mechanical": 0, "digit_hits": 0,
              "uncovered": [], "skeleton_changed": [], "touched": []}
    replayed = [json.loads(pack_io.serialize(d)) for d in docs]
    for doc in replayed:
        repair_document(doc, fixes, second)
    if second["fields"]:
        print(f"无损校验失败：重放仍要改 {second['fields']} 个字段（不幂等）")
        return 1
    print("无损校验通过：材料集合与其余字段逐条不变；改动只落在被替换位置；幂等")

    if stats["skeleton_changed"]:
        print("（存在骨架被改动的字段：本轮不写回）")
        return 1
    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0

    for doc, path in zip(docs, paths):
        pack_io.dump_json(doc, path)
    print(f"→ 已写回 {len(docs)} 个 sidecar")
    if args.judgments:
        n, problems = mirror_judgments(stats["touched"], _material_locators(), write=True)
        print(f"→ 判定表镜像 {n} 处；未镜像 {len(problems)}")
        for p in problems[:6]:
            print("   !", p)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
