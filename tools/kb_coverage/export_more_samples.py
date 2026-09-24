"""导出第二批抽查样本（更多、更有代表性）到桌面：源页图 + 转写文字 + 特征统计。

挑选是**确定性**的（不随机）：按"页面特征"各挑一条，覆盖
  封面 / 思维导图·知识图谱 / 表格 / 手写批注 / 例题含答案 / 【图】密集 / 超长页，
并按学科尽量铺开。方便人工抽查不同形态的忠实度。
"""
import json
import pathlib
import re
import shutil
import sys

SRC = pathlib.Path("knowledge-production/2027-53-transcripts-staged")
PAGES = pathlib.Path("build/2027-53-pages")
OUT = pathlib.Path.home() / "Desktop" / "53转写抽查-2"


def load():
    for f in sorted(SRC.glob("**/range_*.jsonl")):
        subj = f.parts[2]     # knowledge-production / 2027-53-transcripts-staged / <SUBJECT> / <stem> / file
        stem = f.parent.name
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
                yield subj, stem, o


def feats(t: str) -> dict:
    return {
        "figs": t.count("【图"),
        "formulas": len(re.findall(r"\$[^$]{1,80}\$", t)),
        "has_table": bool(re.search(r"\|.*\|", t)) or "表格" in t or "对照表" in t,
        "has_map": any(k in t for k in ("思维导图", "知识图谱", "树状图", "思维导图（")),
        "has_hand": any(k in t for k in ("手写", "红笔", "铅笔")),
        "has_problem": any(k in t for k in ("解析", "答案", "故选", "例题", "【例")),
        "chars": len(t),
    }


PICKERS = [
    ("封面", lambda f, t: "封面" in (t.get("heading") or "") or f["chars"] < 700),
    ("思维导图/知识图谱", lambda f, t: f["has_map"]),
    ("表格页", lambda f, t: f["has_table"]),
    ("含手写批注", lambda f, t: f["has_hand"]),
    ("例题含答案解析", lambda f, t: f["has_problem"]),
    ("【图】密集（≥3）", lambda f, t: f["figs"] >= 3),
    ("超长页（≥3200字）", lambda f, t: f["chars"] >= 3200),
]


def main() -> int:
    recs = list(load())
    print(f"可用页记录 {len(recs)}")
    by_subj: dict[str, list] = {}
    for subj, stem, o in recs:
        by_subj.setdefault(subj, []).append((stem, o, feats(o["text"])))

    subjects = sorted(by_subj, key=lambda s: -len(by_subj[s]))
    picks = []
    taken = set()
    for li, (label, pred) in enumerate(PICKERS):
        got = 0
        # 每个形态跨学科各取，最多 2 条；学科顺序按形态下标轮转，避免全落在数学
        order = subjects[li % len(subjects):] + subjects[:li % len(subjects)]
        for subj in order:
            if got >= 2:
                break
            for st, o, f in by_subj[subj]:
                if pred(f, o) and (subj, o["page"]) not in taken:
                    picks.append((label, subj, st, o, f))
                    taken.add((subj, o["page"]))
                    got += 1
                    break
    picks = picks[:12]

    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    md = ["# 2027版《53知识清单》转写抽查（第二批）", "",
          "每条都配了**源页图**（同目录 jpg）。请打开图与文字逐字对照；",
          "重点看：公式是否对、图形描述是否说了图上真有的东西、题目/答案是否也全量转了。", ""]
    rows = []
    for i, (label, subj, stem, o, f) in enumerate(picks, 1):
        src = PAGES / subj / stem / f"p{o['page']:04d}.jpg"
        name = f"{i:02d}_{subj}_p{o['page']:04d}_{label.replace('/', '-')}.jpg"
        ok = False
        if src.exists():
            shutil.copy2(src, OUT / name)
            ok = True
        md += [f"## {i}. {subj} 第 {o['page']} 页｜{label}", "",
               f"源页图：`{name}`" + ("" if ok else "（**缺页图**）"),
               f"模型判读标题：{o.get('heading', '')}",
               f"特征：{o['text'].count('【图')} 处【图】｜{f['formulas']} 个公式｜"
               f"{f['chars']} 字｜{'含表格' if f['has_table'] else ''}"
               f"{'含手写批注' if f['has_hand'] else ''}"
               f"{'含题目/答案' if f['has_problem'] else ''}",
               "", "转写文字：", "", "```", o["text"].strip(), "```", ""]
        rows.append((i, subj, o["page"], label, f["chars"], f["figs"], f["formulas"],
                     f["has_problem"], f["has_hand"], f["has_table"], o.get("heading", "")))
        print(f"{i:2d}. {subj:<10} p{o['page']:<4} {label:<16} {f['chars']:>5}字 "
              f"图{f['figs']} 公式{f['formulas']} 图={ok}")
    (OUT / "转写对照.md").write_text("\n".join(md), encoding="utf-8")

    tsv = ["序\t学科\t页\t形态\t字数\t图描述\t公式\t含题目\t手写\t表格\t标题"]
    for r in rows:
        tsv.append("\t".join(str(x).replace("\t", " ") for x in r))
    (OUT / "清单.tsv").write_text("\n".join(tsv) + "\n", encoding="utf-8")
    print("已写:", OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
