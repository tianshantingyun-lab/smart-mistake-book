# -*- coding: utf-8 -*-
"""内容绑定裁定汇总器（Stage-4 · WP2 汇总）：把 8 个切片裁定并成一张审计表。

## 它消灭的失败

WP2 的裁定散在 8 个 `slice-NN.verdicts.csv` 里（409 行），下游（施工改绑 / 阶段报告 /
复核代理）既读不全也复算不了：手抄会漏行、错序，还会把**靶子样本**和**随机抽样样本**
混成一锅（两者的口径与可推断范围完全不同）。本工具把 8 个切片**逐行原样**并成一张表
（只做去重与排序，不改一个字），并给出 `--check`：用切片文件重建表再与落盘表逐行比对，
任何漂移变红。**唯一写者**——本文件是这张表在仓库里的唯一生成器。

## 纪律（写死在代码里，不在运行期由外部输入决定）

- **只合并、不裁定**：本工具不产生任何 `verdict`，也不改任何行的 `verdict` /
  `suggested_node_slug` / `evidence`。裁定全部来自 WP2 逐条读内容的语义判定
  （规范 `docs/kb-content-conformance-spec.md` §4.2 五条）。
- **表头逐字** = `audit_content_bindings.COLUMNS`（与切片文件同列同序），不增列不减列。
- **样本分列**靠 `slice` 列：`slice-06` = 随机分层抽样（`kind="sample"`），其余 =
  靶子样本（`kind="nodes"/"anchors"`）。`kind` 取自 `audit_content_bindings.SLICE_PLAN`
  （唯一事实源，不在本文件另写一份切片清单）。
- **引文核验只查出处**：`verbatim_gap_rows()` 判的是「引文是不是该材料正文的逐字片段」，
  即证据是否可自证；它**不**用来判绑定对错（登记册 I-05：字符串度量在归属轴上是无效指标）。
- 金标集冻结：本工具只读包与切片，不读、不改、不重排金标文件。

## 用法（仓库根下）

```
PYTHONPATH=tools python -m kb_build.merge_content_audit_verdicts            # 打印统计（不写）
PYTHONPATH=tools python -m kb_build.merge_content_audit_verdicts --write    # 落表
PYTHONPATH=tools python -m kb_build.merge_content_audit_verdicts --check    # 复算并比对落盘表
```

产物：`tools/kb_build/tables/content_audit_2026-09-25.csv`（八列，409 行）。
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter
from pathlib import Path

from kb_build import pack_io, tables
from kb_build.audit_content_bindings import COLUMNS, SLICE_DIRNAME, SLICE_PLAN, TABLE_NAME

VERDICTS = ("KEEP", "REBIND", "NONE")
# 材料引文的定界（各切片带的记号：切片约定「」= 材料原句、『』= 节点侧，
# 「“…”」是 slice-08 的写法）。三种分开扫，因为要查的是**出处**不是体例；
# 且必须容忍嵌套——`「“平移圆”模型：…」` 里的 “ ” 是引用内容，不是定界符
# （首版用单条 `[「『“]…[」』”]` 把这种引文切碎，误报成「引文对不上」）。
QUOTE_PATTERNS = (re.compile(r"「([^」]{4,})」"),
                  re.compile(r"『([^』]{4,})』"),
                  re.compile(r"“([^”]{4,})”"))
ELLIPSIS = re.compile(r"…|\.\.\.")


def table_path() -> Path:
    return tables.TABLES_DIR / TABLE_NAME


def slice_dir() -> Path:
    return pack_io.work_dir().parent / SLICE_DIRNAME


def slice_files(directory: Path | None = None) -> list[Path]:
    directory = directory or slice_dir()
    return sorted(directory.glob("slice-[0-9][0-9].verdicts.csv"))


def slice_kind(slice_id: str) -> str:
    """切片的样本种类（`sample` = 随机分层抽样；`nodes`/`anchors` = 靶子样本）。"""
    for spec in SLICE_PLAN:
        if spec["slice"] == slice_id:
            return spec["kind"]
    return "unknown"


def read_slices(directory: Path | None = None) -> tuple[list[dict], list[Path]]:
    """逐行读切片裁定（原样，不动一个字符）；表头必须与 COLUMNS 逐字相同。"""
    rows: list[dict] = []
    files = slice_files(directory)
    for path in files:
        with path.open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            if list(reader.fieldnames or ()) != list(COLUMNS):
                raise ValueError("%s 表头不是 COLUMNS 逐字：%s" % (path, reader.fieldnames))
            for row in reader:
                rows.append({column: row.get(column, "") for column in COLUMNS})
    return rows, files


def validate(rows: list[dict]) -> None:
    for row in rows:
        verdict = row["verdict"].strip()
        if verdict not in VERDICTS:
            raise ValueError("非法 verdict %r（%s / %s）" % (verdict, row["material_slug"], row["slice"]))
        if not row["material_slug"].strip() or not row["current_node_slug"].strip():
            raise ValueError("缺材料或现绑节点：%s" % row)
        if not row["subject"].strip() or not row["slug"].strip():
            raise ValueError("缺 subject / slug：%s" % row)
        if not row["evidence"].strip():
            raise ValueError("空证据：%s / %s" % (row["slice"], row["material_slug"]))
        suggested = row["suggested_node_slug"].strip()
        if verdict == "REBIND" and not suggested:
            raise ValueError("REBIND 没有 suggested_node_slug：%s" % row["material_slug"])
        if verdict != "REBIND" and suggested:
            raise ValueError("非 REBIND 却有 suggested_node_slug：%s / %s"
                             % (row["material_slug"], verdict))


def merge(rows: list[dict]) -> list[dict]:
    """去重（八列全字段相同才算同一行）+ 排序（subject / slug，再按材料与切片定序）。

    同一条 (材料, 现绑节点) 若在**两个样本**里各判一次（靶子片 + 抽样片），两行都保留：
    证据与口径不同，删掉任一行都会让那个样本没法逐行复算。
    """
    seen: set[tuple[str, ...]] = set()
    unique: list[dict] = []
    for row in rows:
        key = tuple(row[column] for column in COLUMNS)
        if key in seen:
            continue
        seen.add(key)
        unique.append(row)
    return sorted(unique, key=lambda r: (r["subject"], r["slug"], r["material_slug"],
                                         r["current_node_slug"], r["slice"]))


def counts(rows: list[dict]) -> Counter:
    return Counter(row["verdict"].strip() for row in rows)


def write_table(rows: list[dict], path: Path | None = None) -> Path:
    path = path or table_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(COLUMNS), lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in COLUMNS})
    return path


def _normalise(text: str) -> str:
    return re.sub(r"\s+", "", text)


def _quotes(evidence: str) -> list[str]:
    return [quote for pattern in QUOTE_PATTERNS for quote in pattern.findall(evidence)]


def verbatim_gap_rows(rows: list[dict]) -> list[tuple[str, str]]:
    """REBIND 里引文对不上材料正文的行（只查出处，不判绑定对错）。

    口径：把证据里的引文按省略号切开，只要**有一段**是该材料正文（含 summary）的
    逐字连续片段就算可自证；一段都对不上才登记。空白差异（换行/空格）不算差异，
    省略号（`…`）是允许的省写，故先切段再比。
    """
    from kb_build.audit_content_bindings import load_facts

    facts = load_facts()
    gaps: list[tuple[str, str]] = []
    for row in rows:
        if row["verdict"].strip() != "REBIND":
            continue
        material = facts.material(row["material_slug"])
        if material is None:
            gaps.append((row["slice"], row["material_slug"]))
            continue
        text = _normalise((material.get("contentMarkdown") or "")
                          + (material.get("summaryMarkdown") or ""))
        segments = [segment for quote in _quotes(row["evidence"])
                    for segment in ELLIPSIS.split(quote) if len(segment) >= 6]
        if not any(_normalise(segment) in text for segment in segments):
            gaps.append((row["slice"], row["material_slug"]))
    return gaps


def report(rows: list[dict]) -> None:
    tally = counts(rows)
    by_kind = Counter()
    by_slice = Counter()
    for row in rows:
        kind = slice_kind(row["slice"])
        by_kind["random-sample" if kind == "sample" else "target"] += 1
        by_slice[row["slice"]] += 1
    print("总行数 %d ｜ REBIND %d ｜ KEEP %d ｜ NONE %d"
          % (len(rows), tally["REBIND"], tally["KEEP"], tally["NONE"]))
    print("样本：靶子 %d 行 ｜ 随机分层 %d 行（按 slice 列区分）"
          % (by_kind["target"], by_kind["random-sample"]))
    for slice_id in sorted(by_slice):
        print("   %s %-8s %3d 行" % (slice_id, slice_kind(slice_id), by_slice[slice_id]))
    gaps = verbatim_gap_rows(rows)
    print("REBIND 引文对不上材料正文的行：%d / %d" % (len(gaps), tally["REBIND"]))
    for slice_id, material in gaps:
        print("   %s / %s" % (slice_id, material))


def check(rows: list[dict]) -> int:
    """复算：切片 → 表；与落盘表逐行比对。返回进程退出码。"""
    path = table_path()
    if not path.exists():
        print("落盘表不在位：%s" % path, file=sys.stderr)
        return 1
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        shipped = [{column: row.get(column, "") for column in COLUMNS} for row in reader]
        header = list(reader.fieldnames or ())
    if header != list(COLUMNS):
        print("落盘表表头不是 COLUMNS 逐字：%s" % header, file=sys.stderr)
        return 1
    if shipped == rows:
        print("复算一致：%d 行（表 == 8 个切片去重排序后的并）" % len(rows))
        return 0
    print("复算不一致：落盘 %d 行 vs 复算 %d 行" % (len(shipped), len(rows)), file=sys.stderr)
    for index, (left, right) in enumerate(zip(shipped, rows)):
        if left != right:
            print("  第 %d 行起分叉：\n    落盘 %s\n    复算 %s" % (index, left, right), file=sys.stderr)
            break
    return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="内容绑定裁定汇总（Stage-4 WP2）")
    parser.add_argument("--write", action="store_true", help="把合并结果落到 tables/")
    parser.add_argument("--check", action="store_true", help="复算并与落盘表比对")
    parser.add_argument("--slice-dir", type=Path, default=None, help="换切片目录（测试用）")
    args = parser.parse_args(argv)

    rows, files = read_slices(args.slice_dir)
    if not files:
        print("没找到切片文件：%s" % (args.slice_dir or slice_dir()), file=sys.stderr)
        return 1
    validate(rows)
    merged = merge(rows)
    if args.write:
        print("→ 审计表 %s（%d 行）" % (write_table(merged), len(merged)))
    report(merged)
    if args.check:
        return check(merged)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
