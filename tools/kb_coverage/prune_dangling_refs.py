# -*- coding: utf-8 -*-
"""清掉状态机里指向"已不存在材料"的 output_ref（悬空引用）。

## 它消灭的失败

`extraction_state.csv` 的 `output_ref` 记录"这个文件产出了哪些材料"，而材料可能因为
**去重**（同块两判产生的同指纹材料）或**合并/删除**而消失——于是账本里留下指向不存在 slug 的
引用，`extraction_state --verify` 会一直报"output_ref 悬空"，而它本身没有清理入口。

本模块按 sidecar 实况裁剪 output_ref：只保留真实存在的 slug，其余删除并报告。
幂等；清完 `--verify` 应回到"账本完整"。

用法：
    PYTHONPATH=tools python -m kb_coverage.prune_dangling_refs [--write]
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from kb_build import pack_io

TABLE = Path(pack_io.REPO) / "tools" / "kb_coverage" / "tables" / "extraction_state.csv"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    exists: set[str] = set()
    for sp in pack_io.sidecar_paths():
        if sp.exists():
            exists.update(m["slug"] for m in pack_io.load_json(sp)["materials"])
    rows = list(csv.DictReader(TABLE.open(encoding="utf-8-sig")))
    cols = list(rows[0].keys())
    fixed = dropped = 0
    for r in rows:
        ref = (r.get("output_ref") or "").strip()
        if not ref:
            continue
        parts = [x for x in ref.split(",") if x]
        keep = [x for x in parts if x in exists]
        if len(keep) != len(parts):
            r["output_ref"] = ",".join(keep)
            fixed += 1
            dropped += len(parts) - len(keep)
    print(f"需修的行 {fixed}，摘掉悬空 slug {dropped}")
    if args.write and fixed:
        with TABLE.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            w.writeheader()
            w.writerows(rows)
        print(f"→ 已写回 {TABLE}")
    elif fixed:
        print("（未写盘；加 --write 生效）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
