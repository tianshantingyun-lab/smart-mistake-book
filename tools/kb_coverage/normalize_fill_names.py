# -*- coding: utf-8 -*-
"""把逐页产物统一成**唯一规范名**：`p####.jsonl` / `p####.counts.json`。

## 它消灭的失败

代理写文件时可能不按四位零填充（实测 2026-09-25：一个批代理写出 `p211.jsonl`，而同页已有
规范名 `p0211.jsonl`）。后果是**同一页两份产物**：机械门按文件名各判一次 → 同一页出现两行结果 →
按页派工的重做/定点修代理**重名** → 整轮失败（`Subagent name ... is used twice`）。

规范：一页只留一份，名字必须是 `p` + 四位零填充 + 扩展名。保留哪一份按**内容质量**定，
不按文件名：
1. 过闸的那份优先（两两比）；
2. 都过或都不过 → 正文更长的那份；
3. 完全并列 → 保留规范名那份。
`.jsonl` 与 `.counts.json` 分别判（一份可能只坏了一半）。

用法：
    PYTHONPATH=tools python -m kb_coverage.normalize_fill_names --dir <fill 目录>
    （加 --write 才真改名/删除；默认只报告）
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
from kb_coverage import grade_pilot as gp  # noqa: E402

PAGE_RE = re.compile(r"^p(\d+)\.(jsonl|counts\.json)$")
CANON = "p{page:04d}.{suffix}"


def groups(root: Path, suffix: str) -> dict[tuple[str, int], list[Path]]:
    """按 (学科, 页号) 归组——**含只有一份但名字不规范**的（独苗也要改名，否则下游按
    规范名取文件时找不到它）。"""
    out: dict[tuple[str, int], list[Path]] = {}
    for f in sorted(root.glob(f"*/p*{suffix}")):
        m = PAGE_RE.match(f.name)
        if not m:
            continue
        out.setdefault((f.parent.name, int(m.group(1))), []).append(f)
    return out


def score_jsonl(path: Path) -> tuple[int, int]:
    """(是否过文本面判据, 字数)。用 grade_pilot 的读法，坏行不计。"""
    text, _n = gp.load_page(path)
    return (1 if text and not gp.gate.field_text_defects(text) else 0, len(text))


def score_counts(path: Path) -> tuple[int, int]:
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return (0, 0)
    n = doc.get("items_min")
    return (1 if isinstance(n, int) and n > 0 else 0, int(n) if isinstance(n, int) else 0)


def pick(files: list[Path], scorer) -> Path:
    scored = [(scorer(f), f.name == CANON.format(page=int(PAGE_RE.match(f.name).group(1)),
                                                    suffix=PAGE_RE.match(f.name).group(2)), f)
              for f in files]
    # 先按内容质量，再按名字是否规范
    return max(scored, key=lambda t: (t[0], t[1]))[2]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dir", type=Path, default=REPO / "knowledge-production/2027-53-fill")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    if not args.dir.exists():
        print(f"没有这个目录：{args.dir}")
        return 2

    fixed = skipped = 0
    for suffix, scorer in (("jsonl", score_jsonl), ("counts.json", score_counts)):
        for (subject, page), files in sorted(groups(args.dir, suffix).items()):
            canon = args.dir / subject / CANON.format(page=page, suffix=suffix)
            if len(files) == 1 and files[0] == canon:
                skipped += 1            # 已经是唯一规范名：不碰它（避免无谓重写）
                continue
            winner = pick(files, scorer)
            losers = [f for f in files if f != winner]
            print(f"{subject} p{page}.{suffix}：{len(files)} 份 → 保留 {winner.name}"
                  f"（{'规范名' if winner == canon else '非规范名，将改名为 ' + canon.name}）"
                  f"，删除 {[f.name for f in losers]}")
            if args.write:
                data = winner.read_bytes()
                for f in losers:
                    f.unlink()
                canon.write_bytes(data)
                if winner != canon and winner.exists():
                    winner.unlink()
                fixed += 1
    print(f"\n重复页组：{'已规范 ' + str(fixed) + ' 组' if args.write else '（未写盘；加 --write 生效）'}"
          f"；本来就规范未触碰 {skipped} 组")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
