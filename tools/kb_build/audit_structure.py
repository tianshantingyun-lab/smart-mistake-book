# -*- coding: utf-8 -*-
"""知识库结构审计：空白节点 / 缺失知识点 / 归类错误 / 嵌套结构 / 内容质量。

只读，不改任何文件（报告写进 build/kb-staging/ 的 CSV 供追查）。

五个问题分开报，因为处置方式不同：

- **嵌套结构**：主题树的孤儿、环、深度、空壳，以及节点 `定位：` 里的册/章
  与它在树上的实际位置是否一致。
- **空白节点**：没有任何材料绑定的知识点（App 里检索不到）。再按名字分诊：
  像「行首标签」的（定义/实质/图示/实验用品…）该改名或合并，不该硬塞材料。
- **缺失知识点**：转录件每页末尾都有「本页可形成的知识点」清单，把它与本库节点名
  对表——对不上的就是"五三讲了、库里没有"，按章聚合并给出总数。
- **归类错误**：kind 与名字形态不符、材料 type 与节点 kind 不搭、材料标题与绑定
  节点名一个 2-gram 都不共享（已知的弱匹配问题）。
- **内容质量**：材料正文行数/长度/ASCII 引号/模板边界/重名重标题等形态问题。

  用法： PYTHONPATH=tools python -m kb_build.audit_structure
        PYTHONPATH=tools python -m kb_build.audit_structure --sample 20
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

STAGING = pack_io.REPO / "build" / "kb-staging"
TRANSCRIPT_ROOT = pack_io.REPO / "knowledge-research" / "candidates" / "wusan"
SUBJECT_DIR = {"MATH": "math", "PHYSICS": "physics", "CHEMISTRY": "chemistry",
               "BIOLOGY": "biology"}

# 「行首标签」词表：这些名字当节点名时脱离父主题就不成知识点，学生不会这样问。
LABEL_WORDS = ("定义", "实质", "规律", "特点", "性质", "原理", "用途", "图示", "图例",
               "实验原理", "实验用品", "实验步骤", "数据处理", "误差分析", "简要回答",
               "说明", "应用", "概念", "分类", "比较", "判断", "计算", "方法", "示例",
               "注意事项", "小结", "归纳", "总结", "重点", "难点", "考点", "提示")
# 引用数字的易错类名字：计数无法核实，且多数与其它节点同义。
COUNTED_NAME = re.compile(r"[“\"']?[一二三四五六七八九十两0-9７89]+\s*[“\"']?\s*(?:点|个|条|种|类|步|法|误区|提醒)")
# 抽取残片：题干特征。
FRAGMENT_NAME = re.compile(r"(吗？?$|？$|如图|下列|正确的一项是|求[：:]|已知.*则|；$)")

_PUNCT = re.compile(r"[\s，。、；：（）()［］\[\]【】“”\"'·—\-_/\\|!?？！,.:;]+")
_KEEP = re.compile(r"[\w\u4e00-\u9fff]+")


def norm(text: str) -> str:
    return _PUNCT.sub("", text or "")


def bigrams(text: str) -> set[str]:
    t = norm(text)
    return {t[i:i + 2] for i in range(len(t) - 1)} or {t}


def load_pack():
    pack = json.loads((STAGING / pack_io.PACK_NAME).read_text(encoding="utf-8"))
    materials = []
    for path in sorted(STAGING.glob("moe-2025-teaching-support-v2-*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        for m in doc["materials"]:
            materials.append((path.name, m))
    return pack, materials


def node_rows(pack):
    """(subject, topic chain, point) 一行一个知识点。"""
    rows = []
    for subject in pack["subjects"]:
        name = subject["subject"]
        by_slug = {t["slug"]: t for t in subject["topics"]}
        for topic in subject["topics"]:
            chain, cursor, guard = [], topic, 0
            while cursor is not None and guard < 20:
                chain.append(cursor["name"])
                parent = cursor.get("parentSlug")
                cursor = by_slug.get(parent) if parent else None
                guard += 1
            chain.reverse()
            for point in topic.get("knowledgePoints") or []:
                rows.append((name, topic, chain, point))
    return rows


def check_nesting(pack, sample: int) -> dict:
    out = {}
    for subject in pack["subjects"]:
        name = subject["subject"]
        topics = subject["topics"]
        by_slug = {t["slug"]: t for t in topics}
        slugs = set(by_slug)
        problems = defaultdict(list)
        if len(slugs) != len(topics):
            problems["主题 slug 重复"].append(str(len(topics) - len(slugs)))
        for t in topics:
            parent = t.get("parentSlug")
            if parent and parent not in slugs:
                problems["父级不存在（孤儿）"].append(f"{t['name']} -> {parent}")
            if parent == t["slug"]:
                problems["自环"].append(t["name"])
            depth, cursor, seen = 1, t, {t["slug"]}
            while cursor.get("parentSlug"):
                cursor = by_slug.get(cursor["parentSlug"])
                if cursor is None or cursor["slug"] in seen:
                    break
                seen.add(cursor["slug"])
                depth += 1
            if depth > 4:
                problems["树深 > 4"].append(f"{t['name']} (depth={depth})")
            kids = sum(1 for x in topics if x.get("parentSlug") == t["slug"])
            if not (t.get("knowledgePoints") or []) and not kids:
                problems["空壳主题（无节点无子级）"].append(t["name"])
        # 册/章：树上前两层 vs 节点 boundary 的定位串
        mismatch = []
        for _s, _t, chain, point in node_rows(pack):
            if len(chain) < 2:
                mismatch.append(f"{point['name']}: 树上不足两层 {chain}")
                continue
            boundary = point.get("boundary") or ""
            loc = boundary.split("。")[0]
            if not loc.startswith("定位："):
                mismatch.append(f"{point['name']}: 无定位前缀")
                continue
            body = loc[len("定位："):]
            if chain[0] and chain[0] not in body:
                mismatch.append(f"{point['name']}: 册不符 树={chain[0]} 定位={body[:30]}")
            elif len(chain) > 1 and chain[1] and chain[1] not in body:
                mismatch.append(f"{point['name']}: 章不符 树={chain[1]} 定位={body[:30]}")
        if mismatch:
            problems["定位串与树位置不一致"] = mismatch
        out[name] = {
            "主题数": len(topics),
            "知识点数": sum(len(t.get("knowledgePoints") or []) for t in topics),
            **{k: v for k, v in problems.items()},
        }
    return out


def check_blank(nodes, bound: set[str], sample: int) -> dict:
    blank = []
    for subject, topic, chain, point in nodes:
        nid = point["id"]
        if nid in bound:
            continue
        flags = []
        if any(point["name"] == w or point["name"].endswith(w) and len(point["name"]) <= 4
               for w in LABEL_WORDS):
            flags.append("标签式名字")
        if COUNTER := COUNTED_NAME.search(point["name"]):
            flags.append("名字含引用计数")
        if FRAGMENT_NAME.search(point["name"]) or len(point["name"]) > 20:
            flags.append("疑似残片/整句")
        blank.append((subject, "/".join(chain[:2]), topic["name"], point["name"],
                      point["kind"], "|".join(flags) or "普通知识点"))
    return {"总数": len(blank), "明细": blank}


def parse_transcript_points(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    marker = "本页可形成的知识点"
    idx = text.rfind(marker)
    if idx < 0:
        return []
    out = []
    for line in text[idx:].splitlines()[1:]:
        line = line.strip()
        if line.startswith("- "):
            item = line[2:].strip()
            if item and "（无" not in item and "无；" not in item:
                out.append(item)
        elif line.startswith("#"):
            break
    return out


def check_missing(nodes, sample: int, pages: set[str] | None = None) -> dict:
    """把转录件每页的「本页可形成的知识点」与库内节点名对表。

    传 `pages` 时只查那几页，并逐页打印**每一条**未命中的候选——这是"补完一章回头核对
    有没有漏内容"的口径（`--pages p0113,p0114`）。
    """
    node_names = Counter()
    for subject, _t, _c, point in nodes:
        node_names[point["name"]] += 1
    norm_index = {norm(n): n for n in node_names}
    missing = defaultdict(list)   # (subject, chapter) -> [名字]
    pages_total = pages_missing_all = 0
    per_page: list[tuple[str, int, int, list[str]]] = []
    for subject, folder in SUBJECT_DIR.items():
        d = TRANSCRIPT_ROOT / folder / "transcript"
        if not d.exists():
            continue
        for path in sorted(d.glob("p*.md")):
            if pages and path.stem not in pages:
                continue
            pages_total += 1
            items = parse_transcript_points(path)
            if not items:
                continue
            hits = 0
            unmatched: list[str] = []
            for item in items:
                n = norm(item)
                if n in norm_index:
                    hits += 1
                    continue
                if any(n and (n in k or k in n) and len(n) >= 4 for k in norm_index):
                    hits += 1
                    continue
                unmatched.append(item)
                missing[subject].append((path.stem, item))
            per_page.append((path.stem, len(items), hits, unmatched))
            if hits == 0:
                pages_missing_all += 1
    return {"页数": pages_total, "整页无命中": pages_missing_all, "明细": missing,
            "逐页": per_page}


def check_misclass(nodes, materials, sample: int) -> dict:
    by_id = {p["id"]: (s, t, c, p) for s, t, c, p in nodes}
    kind_shape = []
    for subject, topic, chain, point in nodes:
        name, kind = point["name"], point["kind"]
        if kind == "EXPERIMENT" and not re.search(r"(实验|探究|验证|测量|测定|观察|检验|制取|制备)", name):
            kind_shape.append((subject, chain[1] if len(chain) > 1 else "", name, kind, "名字无实验特征"))
        if kind == "PROCEDURE" and not re.search(r"(法|步骤|操作|流程|计算|判断|比较|分析|书写|配平|推断|求)", name):
            kind_shape.append((subject, chain[1] if len(chain) > 1 else "", name, kind, "名字无过程特征"))
        if kind == "REASONING" and not re.search(r"(关系|比较|判断|推理|分析|讨论|理解|依据|规律)", name):
            kind_shape.append((subject, chain[1] if len(chain) > 1 else "", name, kind, "名字无推理特征"))
    weak = []
    type_mismatch = []
    per_node = Counter()
    for fname, m in materials:
        for b in m.get("bindings") or []:
            nid = b["knowledgeNodeId"]
            per_node[nid] += 1
            info = by_id.get(nid)
            if info is None:
                continue
            subject, topic, chain, point = info
            if m["subject"] != subject:
                type_mismatch.append((m["slug"], "跨科绑定", point["name"]))
            if not (bigrams(m["title"]) & bigrams(point["name"])):
                weak.append((m["subject"], chain[1] if len(chain) > 1 else "", point["name"], m["title"]))
            if m["type"] == "METHOD_MODEL" and point["kind"] == "CONCEPT" and not re.search(r"(法|步骤|方法|计算|判断|书写)", point["name"]):
                type_mismatch.append((m["slug"], "METHOD_MODEL 绑到纯概念节点", point["name"]))
    too_many = [(k, v) for k, v in per_node.items() if v > 4]
    return {"kind 与名字形态不符": kind_shape, "材料与节点弱匹配": weak,
            "type/跨科异常": type_mismatch, "单节点材料 > 4": too_many}


def check_quality(materials, sample: int) -> dict:
    issues = defaultdict(list)
    titles = Counter()
    trio = Counter()
    for fname, m in materials:
        titles[m["title"]] += 1
        for b in m.get("bindings") or []:
            trio[(b["knowledgeNodeId"], m["title"])] += 1
        body = m.get("contentMarkdown") or ""
        if len(body.split("\n")) > 4:
            issues["正文超 4 行"].append(m["slug"])
        if len(body) < 24:
            issues["正文过短（无信息量）"].append(m["slug"])
        if '"' in body or '"' in (m.get("summaryMarkdown") or ""):
            issues["含 ASCII 双引号"].append(m["slug"])
        boundary = m.get("boundaryMarkdown") or ""
        if boundary.startswith(("该结论为一般规律归纳", "一轮复习结论性要点",
                                "适用于教材原型实验与操作类题型")):
            issues["通用模板边界"].append(m["slug"])
        if m["derivationKind"] != "REVIEWED_SYNTHESIS":
            issues["derivationKind 非 REVIEWED_SYNTHESIS"].append(m["slug"])
        if m["type"] not in ("CONCEPT_EXPLANATION", "METHOD_MODEL", "MISCONCEPTION_GUIDE"):
            issues["type 不在允许集"].append(m["slug"])
    dup_title = [(t, n) for t, n in titles.items() if n > 1]
    dup_bind = [(k, n) for k, n in trio.items() if n > 1]
    return {"材料总数": len(materials), "问题": dict(issues),
            "重复标题": dup_title, "同节点同标题重复": dup_bind}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample", type=int, default=12)
    parser.add_argument("--pages", default=None,
                        help="只查这几页（逗号分隔，如 p0113,p0114），用于补完一章后核对漏项")
    args = parser.parse_args(argv)
    pages = {p.strip() for p in args.pages.split(",")} if args.pages else None

    pack, materials = load_pack()
    nodes = []
    for subject, topic, chain, point in node_rows(pack):
        point = dict(point)
        point["id"] = (f"kb:{pack['packId']}:{subject.lower()}:atomic:{point['slug']}")
        nodes.append((subject, topic, chain, point))
    bound = {b["knowledgeNodeId"] for _f, m in materials for b in (m.get("bindings") or [])}

    print("=" * 72)
    print("① 嵌套结构")
    print("=" * 72)
    nesting = check_nesting(pack, args.sample)
    for subject, info in nesting.items():
        bad = {k: v for k, v in info.items() if isinstance(v, list)}
        head = f"  {subject:<10} 主题 {info['主题数']:>4}  知识点 {info['知识点数']:>5}  "
        if not bad:
            print(head + "无结构问题")
            continue
        print(head + "；".join(f"{k}={len(v)}" for k, v in bad.items()))
        for k, v in bad.items():
            for item in v[:args.sample]:
                print(f"        [{k}] {item}")

    print()
    print("=" * 72)
    print("② 空白节点（无任何材料绑定 → App 里检索不到）")
    print("=" * 72)
    blank = check_blank(nodes, bound, args.sample)
    print(f"  共 {blank['总数']} 个")
    flagc = Counter(row[5] for row in blank["明细"])
    for flag, n in flagc.most_common():
        print(f"    {flag:<18} {n}")
    print(f"  —— 按章（前 {args.sample}）——")
    bychap = Counter(f"{r[0]} {r[1]}" for r in blank["明细"])
    for chap, n in bychap.most_common(args.sample):
        print(f"    {n:>4}  {chap}")

    print()
    print("=" * 72)
    print("③ 缺失知识点（转录件「本页可形成的知识点」对不上库内节点）")
    print("=" * 72)
    missing = check_missing(nodes, args.sample, pages)
    print(f"  转录页 {missing['页数']} 页，其中整页无一命中的 {missing['整页无命中']} 页")
    if pages:
        print("  —— 逐页（命中/总数，未命中的全部列出）——")
        for page, total, hits, unmatched in missing["逐页"]:
            print(f"    {page}: {hits}/{total}")
            for item in unmatched:
                print(f"        · {item}")
        return 0
    for subject, items in missing["明细"].items():
        uniq = sorted({i for _p, i in items})
        print(f"  [{subject}] 未命中 {len(uniq)} 条（去重后）")
        for item in uniq[:args.sample]:
            print(f"        {item}")

    print()
    print("=" * 72)
    print("④ 归类错误")
    print("=" * 72)
    mis = check_misclass(nodes, materials, args.sample)
    for key, items in mis.items():
        print(f"  {key}: {len(items)}")
        for item in items[:args.sample]:
            print(f"        {item}")

    print()
    print("=" * 72)
    print("⑤ 内容质量")
    print("=" * 72)
    q = check_quality(materials, args.sample)
    print(f"  材料总数 {q['材料总数']}")
    for key, items in q["问题"].items():
        print(f"  {key}: {len(items)}" + (f"  例: {items[:3]}" if items else ""))
    print(f"  重复标题: {len(q['重复标题'])}" + (f"  例: {q['重复标题'][:3]}" if q["重复标题"] else ""))
    print(f"  同节点同标题重复: {len(q['同节点同标题重复'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
