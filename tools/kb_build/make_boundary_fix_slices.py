# -*- coding: utf-8 -*-
"""W7 工单生成器：给"断在公式中途的 boundary"造出可修的工作单与证据文件。

## 它消灭的失败（KD-26 / §W-02）

成品包里有 20 条 `boundary` 断在公式中途（`…（椭圆是 $b^2\tan\\frac{\\` 这种），
`$` 不成对、4 条还有行尾悬空反斜杠；门的 4 个文本判据只扫材料字段，所以这些字段能在
"22/22 全绿"下随包分发。修它需要两样东西，本工具一次给全：

1. **不许乱改的边界**：算出 `safe_prefix`——把尾部那段**没写完**的片段切掉后的前缀，
   修好的正文必须以它逐字开头（照抄不许漂移）；修的人只许**往后补**，不许改前面。
2. **证据**：把该知识点**已绑定的材料**全文抽到一个证据文件里。补的尾巴要能在材料里
   找到（`apply_boundary_fixes` 会逐字核），找不到的只能按"收尾"处理（把没写完的那半句
   去掉，让字段以完整句结束）——两条路都不发明内容。

## 产物

- `tables/boundary_fix_work_order.csv`：一行一个待修知识点（学科/名称/前缀/现有正文/
  材料条数/证据文件路径/建议动作）；
- `tables/boundary_fix_evidence/<序号>_<学科>_<slug>.md`：证据文件（现正文 + 前缀 +
  全部绑定材料正文），交给子代理逐条补；
- `tables/boundary_fix_slices/slice_NN.csv`：按 `--slices` 切好的派工单（子代理各拿一片）。

用法：
    PYTHONPATH=tools python -m kb_build.make_boundary_fix_slices            # 报告
    PYTHONPATH=tools python -m kb_build.make_boundary_fix_slices --write    # 落盘
"""

from __future__ import annotations

import argparse
import csv
import glob
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
from kb_build import gate  # noqa: E402

STAGING = REPO / "build/kb-staging"
PACK = "moe-2025-four-subjects-v1.json"
TABLES = REPO / "tools/kb_build/tables"
WORK_ORDER = TABLES / "boundary_fix_work_order.csv"
EVIDENCE_DIR = TABLES / "boundary_fix_evidence"
SLICE_DIR = TABLES / "boundary_fix_slices"
COLUMNS = ("subject", "slug", "name", "action", "materials", "prefix_len",
           "body_len", "evidence_path", "current_boundary")


def load_pack() -> dict:
    for base in (STAGING, REPO / "core/data/src/main/resources/knowledge"):
        p = base / PACK
        if p.exists():
            return json.loads(p.read_text(encoding="utf-8"))
    raise SystemExit("找不到包文件（staging 与成品目录都没有）")


def load_materials() -> dict[str, list[dict]]:
    """知识点名 -> 绑定到它的材料（按 knowledgeNodeId 的 slug 部分归集）。"""
    out: dict[str, list[dict]] = {}
    for f in sorted(glob.glob(str(REPO / "core/data/src/main/resources/knowledge/"
                                    "moe-2025-teaching-support-v2-*.json"))):
        for m in json.loads(Path(f).read_text(encoding="utf-8")).get("materials", []):
            for b in (m.get("bindings") or []):
                node = (b.get("knowledgeNodeId") or "").split(":atomic:")[-1]
                if node:
                    out.setdefault(node, []).append(m)
    return out


def safe_prefix(boundary: str) -> str:
    """切掉尾部没写完的那段：从最后一个未闭合的 `$` 起全去掉，再收掉悬空反斜杠与空白。

    `$` 个数为偶时说明公式都闭合了，问题只剩行尾悬空反斜杠 → 收掉它。
    """
    text = boundary
    if text.count("$") % 2:
        cut = text.rfind("$")
        text = text[:cut]
    text = text.rstrip()
    while text.endswith("\\"):
        text = text[:-1].rstrip()
    return text


_NO_DOLLAR = ""          # 占位：定界符错位型不看前缀


def classify(boundary: str, materials: list[dict]) -> str:
    """两种坏法要分开修，判据是**文本本身完不完整**（不看 `$`）：

    - 以句末标点收尾 → 正文是完整的，坏的是 `$` 自己错位/多余（实测 CHEMISTRY 热重法：
      `（失=m_起始\\times$ 残留率）` 里的 `$` 落在了公式体中间）→ **只许动 `$`**，
      去掉 `$` 之后逐字不动；
    - 否则 → 断在公式中途，要**补尾**（有材料证据就按材料补，没有就把没写完的
      那半句收掉——两条路都不发明内容）。
    """
    if boundary.rstrip().endswith(("。", "！", "？")):
        return "修定界符（正文完整，$ 错位）"
    return "补全尾（有材料证据）" if materials else "收尾（无材料证据）"


def node_rows(pack: dict, materials: dict[str, list[dict]]) -> list[dict]:
    rows: list[dict] = []
    for subj in pack.get("subjects", []):
        s = subj.get("subject")
        for t in subj.get("topics", []):
            for kp in t.get("knowledgePoints", []):
                body = kp.get("boundary") or ""
                if not body or not gate.field_text_defects(body):
                    continue
                mats = materials.get(kp.get("slug") or "", [])
                action = classify(body, mats)
                delimiter_only = action.startswith("修定界符")
                prefix = _NO_DOLLAR if delimiter_only else safe_prefix(body)
                rows.append({
                    "subject": s, "slug": kp.get("slug") or "", "name": kp.get("name") or "",
                    "action": action,
                    "materials": str(len(mats)),
                    "prefix_len": "—" if delimiter_only else str(len(prefix)),
                    "body_len": str(len(body)),
                    "evidence_path": str(EVIDENCE_DIR / f"{len(rows) + 1:02d}_{s}_{kp.get('slug')}.md"),
                    "current_boundary": body,
                    "_prefix": prefix, "_mats": mats, "_delim": delimiter_only,
                })
    return rows


def write_evidence(row: dict) -> None:
    path = Path(row["evidence_path"])
    path.parent.mkdir(parents=True, exist_ok=True)
    if row["_delim"]:
        head = [
            f"# {row['subject']} · {row['name']}（{row['slug']}）",
            "",
            "## 这一条**正文是完整的**，坏的只是 `$` 自己错位/多余",
            "",
            "```",
            row["current_boundary"],
            "```",
            "",
            f"## 硬约束：只许动 `$`",
            "",
            "- 修好的正文**去掉全部 `$` 之后**必须与上面**逐字相同**（一个字、一个标点都不许变）；",
            "- 修好后整条 `$` 个数必须是偶数，且每一对 `$` 包住的应该是一段完整的式子；",
            "- 依据只能是页面/材料里本来是什么式子——不要改动句子本身。",
            "",
            f"## 该知识点已绑定的材料（{len(row['_mats'])} 条）",
            "",
        ]
    else:
        head = [
            f"# {row['subject']} · {row['name']}（{row['slug']}）",
            "",
            "## 现在包里的 boundary（断在末尾）",
            "",
            "```",
            row["current_boundary"],
            "```",
            "",
            f"## 必须逐字保留的前缀（{len(row['_prefix'])} 字符，修好的正文要以它开头）",
            "",
            "```",
            row["_prefix"],
            "```",
            "",
            f"## 该知识点已绑定的材料（{len(row['_mats'])} 条）",
            "",
        ]
    parts = head
    if not row["_mats"]:
        parts += ["（没有绑定材料：**不许凭印象补公式**，只做「收尾」——把末尾没写完的那半句去掉，",
                  "让字段以完整句结束）", ""]
    for i, m in enumerate(row["_mats"], 1):
        parts += [f"### 材料 {i}：[{m.get('slug')}] {m.get('title') or ''}", ""]
        for key, label in (("summaryMarkdown", "摘要"), ("contentMarkdown", "正文"),
                           ("boundaryMarkdown", "边界"), ("applicabilityMarkdown", "适用")):
            v = (m.get(key) or "").strip()
            if v:
                parts += [f"**{label}**：", "", v, ""]
    path.write_text("\n".join(parts) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--slices", type=int, default=4, help="派工片数（每片交给一个子代理）")
    args = ap.parse_args(argv)

    rows = node_rows(load_pack(), load_materials())
    print(f"待修 boundary：{len(rows)} 条"
          f"（补全 {sum(1 for r in rows if r['_mats'])} / 收尾 {sum(1 for r in rows if not r['_mats'])}）")
    for r in rows:
        print(f"   {r['subject']:<10s} {r['name'][:18]:18s} 材料 {r['materials']:>3s} "
              f"前缀 {r['prefix_len']:>4s} / 现 {r['body_len']:>4s}  {r['action']}")
        print(f"        前缀尾: …{r['_prefix'][-46:]!r}")
    if not args.write:
        print("\n（未写盘；加 --write 生成工作单与证据文件）")
        return 0

    for r in rows:
        write_evidence(r)
    WORK_ORDER.parent.mkdir(parents=True, exist_ok=True)
    with WORK_ORDER.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(COLUMNS), lineterminator="\n",
                           extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)
    SLICE_DIR.mkdir(parents=True, exist_ok=True)
    n = args.slices
    per = (len(rows) + n - 1) // n
    made = []
    for i in range(n):
        chunk = rows[i * per:(i + 1) * per]
        if not chunk:
            continue
        p = SLICE_DIR / f"slice_{i + 1:02d}.csv"
        with p.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(COLUMNS), lineterminator="\n",
                               extrasaction="ignore")
            w.writeheader()
            w.writerows(chunk)
        made.append((p.name, len(chunk)))
    print(f"→ 已写 {WORK_ORDER}（{len(rows)} 行）、证据 {len(rows)} 份、派工片 {made}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
