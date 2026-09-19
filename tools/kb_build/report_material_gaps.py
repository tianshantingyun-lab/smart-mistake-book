# -*- coding: utf-8 -*-
"""缺料登记册：哪些知识点没有材料、哪些只有一条（薄弱），一次算清、落表可追踪。

## 它消灭的失败

"还缺哪些节点的材料"过去只能靠跑门禁时看 `unbound_points` 一个数字（它只报零材料），
而"只有 1 条材料"的薄弱节点（实测 814 个）从来不可见 —— 补料没有依据，下一轮也无从对照。
本模块把两级缺口一次算清并落成**可复算的表**（每次入库后重跑即可对比增减）：

- `zero`：零材料节点（必须补；块池里没有干净来源时如实标注"待补源"）
- `thin`：只有 1 条材料的节点（薄弱，用于后续加厚）

表里带 `subject / slug / name / topic / material_count / tier / note`，`note` 记录补料线索
（例如"块池只有真题碎片"）。

用法：
    PYTHONPATH=tools python -m kb_build.report_material_gaps            # 只打印统计
    PYTHONPATH=tools python -m kb_build.report_material_gaps --write    # 落表 + 打印
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

OUT = Path(pack_io.REPO) / "knowledge-production" / "node-material-gaps-2026-09.csv"
FIELDS = ("subject", "slug", "name", "topic", "material_count", "tier", "note")

# 零材料节点的补料线索（人工维护：说明为什么缺、去哪里找）
KNOWN_GAPS = {
    ("MATH", "由线-面关系误解向量关系"):
        "块池里该主题只有真题碎片，无干净知识块；待从《空间向量》讲义/教材正文补一条材料",
    ("CHEMISTRY", "自然资源的开发利用"):
        "化学必修第二册第八章，块池无对应知识块（只有真题）；待从该章讲义或教材正文补一条材料",
}


def counts() -> dict[tuple[str, str], int]:
    out: dict[tuple[str, str], int] = defaultdict(int)
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            for b in m.get("bindings") or []:
                parts = b["knowledgeNodeId"].split(":")
                out[(parts[-3].upper(), parts[-1])] += 1
    return out


def rows() -> list[dict]:
    pack = pack_io.load_json(pack_io.pack_path())
    cnt = counts()
    out: list[dict] = []
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                n = cnt.get((subject["subject"], point["slug"]), 0)
                if n > 1:
                    continue
                tier = "zero" if n == 0 else "thin"
                out.append({
                    "subject": subject["subject"],
                    "slug": point["slug"],
                    "name": point["name"],
                    "topic": topic["slug"],
                    "material_count": n,
                    "tier": tier,
                    "note": KNOWN_GAPS.get((subject["subject"], point["slug"]), ""),
                })
    out.sort(key=lambda r: (r["tier"] != "zero", r["subject"], r["slug"]))
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    data = rows()
    by_tier = Counter(r["tier"] for r in data)
    by_subject = Counter((r["tier"], r["subject"]) for r in data)
    print(f"缺口共 {len(data)} 个：零材料 {by_tier['zero']}、仅 1 条材料 {by_tier['thin']}")
    for (tier, subject), n in sorted(by_subject.items()):
        print(f"   {tier:<5} {subject:<10} {n}")
    print("零材料清单：")
    for r in [r for r in data if r["tier"] == "zero"]:
        print(f"   [{r['subject']}] {r['name']}（{r['topic'][:32]}）— {r['note'][:60] or '待判定'}")
    if args.write:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        with OUT.open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=FIELDS)
            w.writeheader()
            w.writerows(data)
        print(f"→ 已写 {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
