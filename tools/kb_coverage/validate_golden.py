# -*- coding: utf-8 -*-
"""R3 金标集：机械验证语义代理产出的 build/golden/*.out.jsonl（不用模型）。

输入：
    build/golden/slice-NN.json      —— make_golden_slices.py 的切片（契约基准）
    build/golden/slice-NN.out.jsonl —— 同 stem 的作答：每行一个 JSON
        {"query": "...", "expectedSlug": "...", "subject": "...", "chapter": "<切片 chapter>"}
    科目/包基准：成品知识包（单一事实源，D11，默认 moe-2025-four-subjects-v1.json）

逐行规则（全部机械）：
    R1  每行是 JSON 对象，且**恰好** 4 个字段（query/expectedSlug/subject/chapter，全为非空字符串）
    R2  query：去首尾空白后 4-60 字，含至少一个 CJK 汉字，不含换行
    R3  subject/chapter 与切片文件一致
    R4  expectedSlug 在该切片节点清单内（从而存在该 subject 的包内；
        不在清单但包内有 → 报"跨章"；包内没有 → 报"不存在"）
    R5  同一 (query, expectedSlug) 全局不重复（跨所有切片）
    R6  每个切片有效行 ≥ target×0.8（target=9 → ≥8）
    R7  有效行总量 ≥ 80
    R8  每个 slice-NN.json 都有同 stem 的 .out.jsonl；孤立的 .out.jsonl 无主 → 失败

全过 → 合并写出规范化 JSON（按 subject/chapter/query/expectedSlug 排序，
    sort_keys + indent=1 + 不转义非 ASCII，字节稳定）：
        tools/kb_coverage/tables/golden_queries_v1.json
    及其 .sha256 文件（对规范化 JSON 文件字节的 sha256 十六进制，一行），exit 0；
    否则逐条列出**全部**失败，exit 1。

用法（仓库根）：
    python tools/kb_coverage/validate_golden.py
        [--golden-dir build/golden] [--pack 路径]
        [--out tools/kb_coverage/tables/golden_queries_v1.json] [--sha256-out 路径]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

REPO: Path = Path(__file__).resolve().parents[2]
DEFAULT_GOLDEN_DIR: Path = REPO / "build" / "golden"
DEFAULT_PACK: Path = (
    REPO / "core" / "data" / "src" / "main" / "resources" / "knowledge"
    / "moe-2025-four-subjects-v1.json"
)
DEFAULT_OUT: Path = REPO / "tools" / "kb_coverage" / "tables" / "golden_queries_v1.json"

MIN_QUERY_LEN = 4
MAX_QUERY_LEN = 60
MIN_TOTAL = 80
_SLICE_RE = re.compile(r"^slice-\d{2}\.json$")
_OUT_RE = re.compile(r"^slice-\d{2}\.out\.jsonl$")
_CJK_RE = re.compile(r"[\u4e00-\u9fff]")
_REQUIRED_KEYS = {"query", "expectedSlug", "subject", "chapter"}
# 规范化落盘与 make_golden_slices 同一排版约定，保证字节稳定。
_JSON_KW = dict(ensure_ascii=False, indent=1, sort_keys=True)


def _dump(payload) -> str:
    return json.dumps(payload, **_JSON_KW) + "\n"


def load_pack_slugs(pack_path: Path) -> dict[str, set[str]]:
    """subject -> 该科全部知识点 slug（R4 的"包内存在"基准）。"""
    pack = json.loads(pack_path.read_text(encoding="utf-8"))
    slugs: dict[str, set[str]] = {}
    for entry in pack["subjects"]:
        bag: set[str] = set()
        for topic in entry["topics"]:
            for kp in topic.get("knowledgePoints") or []:
                bag.add(kp["slug"])
        slugs[entry["subject"]] = bag
    return slugs


def discover_slices(golden_dir: Path) -> tuple[list[tuple[Path, dict]], list[Path], list[str]]:
    """返回 (有序切片[(路径, 文档)], 孤立 out 文件, 环境失败信息)。"""
    failures: list[str] = []
    if not golden_dir.is_dir():
        return [], [], [f"切片目录不存在：{golden_dir}"]
    slice_files = [p for p in sorted(golden_dir.iterdir())
                   if _SLICE_RE.match(p.name)]
    # 注意：Path.stem 只剥最后一层后缀（slice-01.out.jsonl → slice-01.out），
    # 配对必须剥完整 ".out.jsonl"。
    out_files = {p.name[: -len(".out.jsonl")]: p
                 for p in sorted(golden_dir.iterdir()) if _OUT_RE.match(p.name)}
    slices: list[tuple[Path, dict]] = []
    for path in slice_files:
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as failure:
            failures.append(f"{path.name}: 切片文件不可解析：{failure}")
            continue
        slices.append((path, doc))
        out_files.pop(path.stem, None)
    orphans = [p for _, p in sorted(out_files.items())]
    return slices, orphans, failures


def check_query(raw: str) -> str | None:
    """R2：返回失败原因；None = 通过。"""
    q = raw.strip()
    if not (MIN_QUERY_LEN <= len(q) <= MAX_QUERY_LEN):
        return (f"query 长度 {len(q)} 不在 {MIN_QUERY_LEN}-{MAX_QUERY_LEN} 字区间")
    if not _CJK_RE.search(q):
        return "query 不含汉字，不是自然中文问句"
    if "\n" in q or "\r" in q:
        return "query 含换行"
    return None


def validate(golden_dir: Path, pack_path: Path) -> tuple[list[dict], list[str]]:
    """返回 (通过全部行级规则且计入去重前的有效条目, 全部失败信息)。"""
    failures: list[str] = []
    slices, orphans, env_failures = discover_slices(golden_dir)
    failures.extend(env_failures)
    for orphan in orphans:
        failures.append(
            f"{orphan.name}: 孤立作答文件，"
            f"没有对应的 {orphan.name[: -len('.out.jsonl')]}.json 切片")
    if env_failures:
        return [], failures

    pack_slugs = load_pack_slugs(pack_path)
    accepted: list[dict] = []
    seen_pairs: set[tuple[str, str]] = set()
    per_slice_count: list[tuple[str, int, int]] = []  # (文件名, 有效行, target)

    for slice_path, doc in slices:
        name = doc.get("subject", "?")
        stem = slice_path.stem
        out_path = golden_dir / f"{stem}.out.jsonl"
        target = doc.get("target", 0)
        slice_subject = doc.get("subject")
        slice_chapter = doc.get("chapter")
        slice_nodes = {n.get("slug") for n in doc.get("nodes") or []}
        if not out_path.is_file():
            failures.append(f"{out_path.name}: 缺失（切片 {stem} 没有作答文件，计 0 行）")
            per_slice_count.append((stem, 0, target))
            continue
        count = 0
        for line_no, line in enumerate(
                out_path.read_text(encoding="utf-8").splitlines(), 1):
            where = f"{out_path.name}:{line_no}"
            if not line.strip():
                failures.append(f"{where}: 空行")
                continue
            try:
                row = json.loads(line)
            except ValueError as failure:
                failures.append(f"{where}: 非法 JSON（{failure}）")
                continue
            if not isinstance(row, dict):
                failures.append(f"{where}: 不是 JSON 对象")
                continue
            extra = set(row) - _REQUIRED_KEYS
            missing = _REQUIRED_KEYS - set(row)
            if extra or missing:
                failures.append(
                    f"{where}: 字段不是恰好 {sorted(_REQUIRED_KEYS)}"
                    f"（缺 {sorted(missing)}，多 {sorted(extra)}）")
                continue
            values = {k: row[k] for k in _REQUIRED_KEYS}
            if any(not isinstance(v, str) or not v.strip() for v in values.values()):
                failures.append(f"{where}: 存在空或非字符串字段 {values}")
                continue
            query = values["query"].strip()
            reason = check_query(query)
            if reason:
                failures.append(f"{where}: {reason}（{values['query']!r}）")
                continue
            if values["subject"] != slice_subject:
                failures.append(
                    f"{where}: subject={values['subject']!r} 与切片 {slice_subject!r} 不符")
                continue
            if values["chapter"] != slice_chapter:
                failures.append(
                    f"{where}: chapter={values['chapter']!r} 与切片 {slice_chapter!r} 不符")
                continue
            slug = values["expectedSlug"]
            if slug not in slice_nodes:
                if slug in pack_slugs.get(slice_subject, set()):
                    failures.append(
                        f"{where}: expectedSlug={slug!r} 不在本切片（{slice_chapter}）"
                        f"节点清单——跨章作答")
                else:
                    failures.append(
                        f"{where}: expectedSlug={slug!r} 在 {slice_subject} 包内不存在")
                continue
            pair = (query, slug)
            if pair in seen_pairs:
                failures.append(
                    f"{where}: (query,expectedSlug) 重复：{query!r} → {slug!r}")
                continue
            seen_pairs.add(pair)
            count += 1
            accepted.append(
                {"subject": slice_subject, "chapter": slice_chapter,
                 "query": query, "expectedSlug": slug})
        per_slice_count.append((stem, count, target))

    for stem, count, target in per_slice_count:
        floor = target * 0.8
        if count < floor:
            failures.append(
                f"{stem}: 有效行 {count} < target×0.8={floor:g}（target={target}）")
    if len(accepted) < MIN_TOTAL:
        failures.append(f"总量 {len(accepted)} < {MIN_TOTAL}")
    return accepted, failures


def write_frozen(accepted: list[dict], out_path: Path, sha_path: Path) -> str:
    """合并、排序、规范化落盘 + sha256。返回摘要。"""
    entries = sorted(
        accepted,
        key=lambda e: (e["subject"], e["chapter"], e["query"], e["expectedSlug"]),
    )
    text = _dump(entries)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(text.encode("utf-8"))
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    sha_path.write_text(digest + "\n", encoding="utf-8")
    return digest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden-dir", type=Path, default=DEFAULT_GOLDEN_DIR)
    parser.add_argument("--pack", type=Path, default=DEFAULT_PACK)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--sha256-out", type=Path, default=None,
                        help="默认 <out> + '.sha256'")
    args = parser.parse_args(argv)
    sha_path = args.sha256_out or (args.out.parent / (args.out.name + ".sha256"))

    accepted, failures = validate(args.golden_dir, args.pack)
    if failures:
        print(f"金标集验证 FAIL：{len(failures)} 处失败")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    digest = write_frozen(accepted, args.out, sha_path)
    by_slice: dict[str, int] = {}
    for entry in accepted:
        by_slice[entry["chapter"]] = by_slice.get(entry["chapter"], 0) + 1
    print(f"金标集验证 PASS：{len(accepted)} 条，{len(by_slice)} 个章")
    for chapter, count in sorted(by_slice.items()):
        print(f"  {chapter}: {count}")
    print(f"已写 {args.out}（sha256={digest}）+ {sha_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
