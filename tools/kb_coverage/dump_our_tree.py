"""为本轮"教材目录对齐核对"生成四科参照文件（本仓库侧的册/章/主题/知识点树）。

只读本仓库，不触网；输出到临时目录供子代理比对。
用法：python tools/kb_coverage/_dump_our_tree.py <输出目录>
"""
import sys
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
out_dir.mkdir(parents=True, exist_ok=True)

pack = pack_io.load_json(pack_io.pack_path())
for s in pack["subjects"]:
    subject = s["subject"]
    lines = [f"# {subject} 主题树（本仓库口径：册 → 章 → 主题 → 点）", ""]
    for t in s["topics"]:
        depth = t["slug"].count("·")
        indent = "  " * depth
        name = t["name"]
        n_points = len(t.get("knowledgePoints") or [])
        lines.append(f"{indent}{name}" + (f"（{n_points} 点）" if n_points else ""))
    (out_dir / f"ours_{subject}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{subject}: {len(s['topics'])} 个主题 → {out_dir / f'ours_{subject}.txt'}")
