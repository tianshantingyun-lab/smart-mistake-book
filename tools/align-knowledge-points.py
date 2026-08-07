"""Align PEP textbook chapters/sections (knowledge baseline) to curriculum
candidate modules, producing a knowledge-point directory draft.

Matching is by subject + chapter/module name heuristics. Output stays
DRAFT_UNREVIEWED; it is a directory skeleton, not reviewed knowledge.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(r"D:\智能错题本\.worktrees\ui-rebuild")
TOC = PROJECT_ROOT / "knowledge-production" / "pep-textbook-toc-2026-v1.json"
CAND = PROJECT_ROOT / "knowledge-production" / "knowledge-coverage-candidates-2025-v1.json"
OUT = PROJECT_ROOT / "knowledge-production" / "knowledge-point-alignment-2026-v1.json"

# Subject-level module name clues (curriculum module name -> textbook keywords)
MODULE_CLUES: dict[str, dict[str, list[str]]] = {
    "MATH": {
        "required-preparatory-knowledge": ["集合", "常用逻辑用语", "一元二次", "不等式"],
        "required-functions": ["函数", "指数", "对数", "三角函数", "幂函数", "三角恒等"],
        "required-geometry-algebra": ["平面向量", "复数", "立体几何", "解三角形"],
        "required-probability-statistics": ["统计", "概率", "随机"],
        "selective-functions": ["数列", "导数"],
        "selective-geometry-algebra": ["空间向量", "直线", "圆", "圆锥曲线"],
        "selective-probability-statistics": ["计数原理", "排列", "组合", "随机变量", "成对数据"],
    },
    "PHYSICS": {
        "required-physics-1": ["质点", "运动", "匀变速", "相互作用", "力", "牛顿"],
        "required-physics-2": ["抛体", "圆周", "万有引力", "机械能", "曲线"],
        "required-physics-3": ["静电场", "电路", "电能", "电磁", "磁场", "能量"],
        "selective-physics-1": ["动量", "简谐", "机械波", "光", "冲量"],
        "selective-physics-2": ["安培力", "电磁感应", "交变电流", "传感器"],
        "selective-physics-3": ["分子动理论", "气体", "热力学", "原子", "核"],
    },
    "CHEMISTRY": {
        "required-science-experiment": ["实验", "物质及其变化"],
        "required-inorganic-substances": ["钠", "氯", "铁", "金属", "非金属", "硫", "氮", "元素"],
        "required-structure-reaction-laws": ["原子结构", "元素周期", "化学键", "化学反应", "物质的量"],
        "required-organic-compounds": ["有机", "甲烷", "乙烯", "乙醇", "乙酸", "烃"],
        "required-chemistry-social-development": ["化学与", "能源", "环境", "资源"],
        "selective-reaction-principles": ["反应热", "速率", "平衡", "电离", "电解", "原电池"],
        "selective-structure-properties": ["晶体", "分子结构", "原子结构", "配合物", "物质结构"],
        "selective-organic-foundations": ["有机", "烃", "醇", "酸", "聚合物", "高分子"],
    },
    "BIOLOGY": {
        "required-molecules-cells": ["细胞", "分子", "蛋白质", "核酸", "糖类", "酶", "呼吸", "光合"],
        "required-genetics-evolution": ["遗传", "基因", "染色体", "DNA", "变异", "进化", "减数分裂"],
        "selective-homeostasis-regulation": ["稳态", "调节", "神经", "体液", "免疫", "内环境"],
        "selective-biology-environment": ["种群", "群落", "生态系统", "环境"],
        "selective-biotech-engineering": ["基因工程", "细胞工程", "发酵", "生物技术", "胚胎"],
    },
    "HISTORY": {
        "required-chinese-world-history-outline": ["中华文明", "统一多民族", "辽宋夏金", "明清", "晚清", "民国", "古代文明", "资本主义"],
        "selective-state-system-governance": ["国家制度", "政治制度", "治理", "法律"],
        "selective-economy-social-life": ["经济", "社会", "生产", "贸易", "货币"],
        "selective-cultural-exchange": ["文化交流", "传播", "文明"],
    },
    "GEOGRAPHY": {
        "required-geography-1": ["宇宙", "地球", "大气", "水", "植被", "土壤", "地貌"],
        "required-geography-2": ["人口", "聚落", "产业", "交通", "环境", "区域"],
        "selective-physical-geography": ["自然地理", "气候", "地貌", "水体", "土壤"],
        "selective-regional-development": ["区域发展", "资源", "生态"],
        "selective-resources-environment-security": ["资源", "环境", "国家安全"],
    },
    "POLITICS": {
        "required-socialism-with-chinese-characteristics": ["社会主义", "中国特色", "新时代", "复兴"],
        "required-economy-society": ["经济", "市场", "分配", "发展", "社会"],
        "required-politics-rule-of-law": ["政治", "法治", "政府", "人大", "公民"],
        "required-philosophy-culture": ["哲学", "文化", "唯物", "辩证法", "认识论"],
        "selective-international-politics-economy": ["国际", "外交", "经济全球化"],
        "selective-law-life": ["法律", "民事", "合同", "侵权", "婚姻"],
        "selective-logic-thinking": ["逻辑", "思维", "推理", "判断"],
    },
    "CHINESE": {
        "whole-book-reading": ["整本书", "阅读"],
        "contemporary-culture": ["当代文化"],
        "cross-media-literacy": ["跨媒介"],
        "language-accumulation": ["语言", "词语", "字词"],
        "literary-reading-writing": ["文学", "小说", "散文", "诗歌"],
        "critical-reading-expression": ["思辨"],
        "practical-reading-communication": ["实用性", "交流"],
        "traditional-classics-study": ["古诗词", "文言", "传统文化", "诵读"],
    },
    "ENGLISH": {
        "language-knowledge": ["Unit", "UNIT", "Grammar", "Words", "Vocabulary"],
        "language-skills": ["Listening", "Speaking", "Reading", "Writing", "Listening"],
    },
}


def subject_module_align(subject: str, books: list[dict], modules: list[dict]) -> list[dict]:
    clues = MODULE_CLUES.get(subject, {})
    # First pass: count every section once per module by (book, section) key.
    seen: dict[tuple[str, str], list[str]] = {}
    for book in books:
        for sec in book["sections"]:
            for mod_slug, keywords in clues.items():
                if any(k in sec for k in keywords):
                    seen.setdefault((book["title"], sec), []).append(mod_slug)

    # Second pass: assign each section to the module with the most keyword hits.
    per_module: dict[str, list[dict]] = {slug: [] for slug in clues}
    for (book, sec), slugs in seen.items():
        best = max(slugs, key=lambda s: sum(1 for k in clues[s] if k in sec))
        per_module[best].append({"book": book, "section": sec})

    result = []
    for mod in modules:
        mod_slug = mod["slug"]
        matched = per_module.get(mod_slug, [])
        result.append(
            {
                "moduleSlug": mod_slug,
                "moduleName": mod["name"],
                "matchedSectionCount": len(matched),
                "matchedSections": matched[:30],
                "matchConfidence": "HIGH" if len(matched) >= 3 else "LOW",
            }
        )
    return result


def main() -> int:
    toc = json.load(open(TOC, encoding="utf-8"))
    cand = json.load(open(CAND, encoding="utf-8"))
    toc_by_subj = {s["subject"]: s for s in toc["subjects"]}
    cand_by_subj = {s["subject"]: s for s in cand["subjects"]}

    out = {
        "schemaVersion": 1,
        "artifactId": "knowledge-point-alignment-2026-v1",
        "sourceKind": "TEXTBOOK_TOC_TO_CURRICULUM_MODULE_ALIGNMENT",
        "reviewState": "DRAFT_UNREVIEWED",
        "updatedAtEpochMillis": 1784908800000,
        "subjects": [],
    }
    for subject in toc_by_subj:
        if subject not in cand_by_subj:
            continue
        entry = subject_module_align(
            subject,
            toc_by_subj[subject]["books"],
            cand_by_subj[subject]["modules"],
        )
        out["subjects"].append({"subject": subject, "modules": entry})

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("wrote", OUT)
    total = sum(len(s["modules"]) for s in out["subjects"])
    print("subjects:", len(out["subjects"]), "modules:", total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
