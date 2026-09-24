"""把几条转写样例（源页图 + 转写文字）放到桌面，供人工逐字核对。

挑选规则（确定性，不随机）：每个有数据的学科各挑
  ① 第一条含【图：…】的页（验证"图形文字描述"）
  ② 第一条含答案/解析的页（验证"题目也全量转写"）
  ③ 第一条纯知识页（验证正文忠实度）
最多 6 条，去重。
"""
import json
import pathlib
import shutil
import sys

DESKTOP = pathlib.Path.home() / "Desktop" / "53转写抽查"
PAGES = pathlib.Path("build/2027-53-pages")
TRANS = pathlib.Path("build/2027-53-transcripts")


def load_records():
    for f in sorted(TRANS.glob("**/range_*.jsonl")):
        for raw in f.read_text(encoding="utf-8", errors="replace").splitlines():
            s = raw.strip()
            if not s:
                continue
            if s.endswith(","):
                s = s[:-1].rstrip()
            try:
                o = json.loads(s)
            except json.JSONDecodeError:
                continue
            if (o.get("text") or "").strip():
                yield f, o


def main() -> int:
    recs = list(load_records())
    print(f"可用页记录: {len(recs)}")
    by_subject: dict[str, list] = {}
    for f, o in recs:
        by_subject.setdefault(f.parts[2], []).append((f, o))

    probes = []
    for subj, items in sorted(by_subject.items()):
        pick_fig = next((x for x in items if "【图" in x[1]["text"] and len(x[1]["text"]) > 300), None)
        pick_prob = next((x for x in items if any(k in x[1]["text"] for k in ("解析", "答案", "故选"))), None)
        pick_plain = next((x for x in items if "【图" not in x[1]["text"] and len(x[1]["text"]) > 300), None)
        for tag, x in (("含图形描述", pick_fig), ("含题目解析", pick_prob), ("纯知识页", pick_plain)):
            seen = {(p[3]["page"], p[1]) for p in probes}
            if x and (x[1]["page"], subj) not in seen:
                probes.append((tag, subj, x[0], x[1]))
    probes = probes[:6]

    if DESKTOP.exists():
        shutil.rmtree(DESKTOP)
    DESKTOP.mkdir(parents=True)
    md = ["# 2027版《53知识清单》转写抽查（供你逐字核对）", "",
          "每条：源页图（同目录 jpg）+ 模型读图转写的文字。**请打开图片与文字对照**。", ""]
    for i, (tag, subj, f, o) in enumerate(probes, 1):
        stem = f.parent.name
        img_src = PAGES / subj / stem / f"p{o['page']:04d}.jpg"
        img_name = f"样例{i}_{subj}_p{o['page']:04d}.jpg"
        copied = False
        if img_src.exists():
            shutil.copy2(img_src, DESKTOP / img_name)
            copied = True
        md += [f"## 样例 {i}｜{subj}｜第 {o['page']} 页｜{tag}", ""]
        md.append(f"源页图：`{img_name}`" + ("" if copied else "（**未找到页图**）"))
        md += ["", f"页面标题（模型判读）：{o.get('heading', '')}", "", "转写文字：", "", "```",
               o["text"].strip(), "```", ""]
        print(f"样例{i}: {subj} p{o['page']} [{tag}] 图={copied} 字数={len(o['text'])}")
    (DESKTOP / "转写对照.md").write_text("\n".join(md), encoding="utf-8")
    print("已写:", DESKTOP)
    return 0


if __name__ == "__main__":
    sys.exit(main())
