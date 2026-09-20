"""生成第 13 片：两组近重复节点的合并判定材料（两侧节点的材料清单 + 边界）。

用说明：两组节点语义高度重叠（错绑改绑时按「标题同名者」落位暴露出来），
合并与否需要读两侧材料内容判断，故单独成片交子代理裁定。
"""
import sys
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

GROUPS = [
    ("MATH", "倒序相加法", "逆序相加法求数列的前n项和"),
    ("MATH", "三角形垂心的向量特征", "平面向量与三角形的垂心"),
]

pack = pack_io.load_json(pack_io.pack_path())
node = {}
for s in pack["subjects"]:
    for t in s["topics"]:
        for kp in (t.get("knowledgePoints") or []):
            node[(s["subject"], kp["slug"])] = (kp, t["name"])
mats: dict[str, list[dict]] = {}
for sp in pack_io.sidecar_paths():
    for m in pack_io.load_json(sp)["materials"]:
        for b in m.get("bindings") or []:
            mats.setdefault(b["knowledgeNodeId"].split(":")[-1], []).append(m)
            break

lines = [
    "# 第 13 片：两组近重复节点——判「该不该合并、保留哪一个」",
    "#",
    "# 背景：错绑改绑时按「标题同名者」落位，暴露了这两组节点语义高度重叠。",
    "# 交付：Write 到 tools/kb_coverage/rebind_fixes/groups/notes.csv",
    "#   表头 group,merged_slug,survivor_slug,verdict,evidence",
    "#   verdict：MERGE（合并，保留 survivor）/ KEEP_BOTH（不该合并，说明两者区别）",
    "#   字段内不要 ASCII 逗号（用「；」）",
    "#",
]
for gi, (subject, a, b) in enumerate(GROUPS, 1):
    lines.append("=" * 78)
    lines.append(f"## 组 {gi}（{subject}）：{a}  ⟷  {b}")
    for slug in (a, b):
        kp, topic = node[(subject, slug)]
        ms = mats.get(slug, [])
        lines.append(f"--- 节点「{slug}」（topic={topic}，材料 {len(ms)} 条）")
        lines.append(f"    边界：{(kp.get('boundary') or '')[:300]}")
        for m in ms[:12]:
            lines.append(f"    · {m['title'][:60]}")
            lines.append(f"        {(m.get('contentMarkdown') or '')[:200].replace(chr(10), ' ')}")
    lines.append("")
Path("tools/kb_coverage/rebind_slices/slice_13_groups.txt").write_text(
    "\n".join(lines) + "\n", encoding="utf-8")
print("已写 slice_13_groups.txt")
