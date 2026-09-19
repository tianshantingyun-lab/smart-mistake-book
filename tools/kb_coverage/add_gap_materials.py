"""为零材料节点「自然资源的开发利用」补两条材料（幂等追加，避免重复写表）。

为什么这样补：块池里本节没有"整段干净知识"的块——能命中的都是带填空/答案的习题页。
但两条主线的事实有确凿来源：
  · 专题04 高效培优讲义的备考目标明确写「认识化学在金属矿物等自然资源综合利用和实现
    物质间转化等方面的实际应用」——这是本节的教学目标原文；
  · 知识清单专题01 的易混变化清单直接给出"煤的干馏、气化、液化，石油的裂化与裂解属
    化学变化；石油的分馏、蒸馏、升华、潮解属物理变化"。
因此按 REVIEWED_SYNTHESIS 纪律写成两条**改写**材料（不抄原文），各挂一块作来源。
"""
import csv
import hashlib
import sys
from pathlib import Path

NODE = "自然资源的开发利用"
ROWS = [
    {
        "chunk_rel": "2026年新高考资料(3)/一轮复习/2026年高考化学一轮复习考点精讲精练（新高考通用）/2026版/专题04  金属材料及金属矿物的开发利用（高效培优讲义）（学生版）.docx",
        "chunk_id": "3b8d34b524-002",
        "action": "MATERIAL", "node_slug": NODE, "type": "CONCEPT_EXPLANATION",
        "title": "自然资源的开发利用：三条主线与基本思路",
        "summary": "从化学视角看自然资源开发利用的三条主线——金属矿物、海水资源、化石能源，"
                   "以及贯穿其中的两个目标：提高资源利用率、从源头减少污染。",
        "applicability": "需要整体说明化学在自然资源开发利用中起什么作用、或梳理本章框架时使用。",
        "content": "## 三条主线\\n"
                   "- 金属矿物：把化合态的金属还原为单质（冶炼），方法由金属活动性决定——"
                   "不活泼金属用热分解法；中等活泼金属用热还原法（焦炭、$\\mathrm{CO}$、$\\mathrm{H_2}$、铝热反应）；"
                   "活泼金属用熔融电解法（如电解熔融 $\\mathrm{MgCl_2}$ 制镁、电解熔融 $\\mathrm{Al_2O_3}$ 制铝）。\\n"
                   "- 海水资源：海水淡化（蒸馏法、电渗析法）；从海水中提取镁、溴、碘与食盐；"
                   "氯碱工业电解饱和食盐水得到 $\\mathrm{NaOH}$、$\\mathrm{H_2}$、$\\mathrm{Cl_2}$。\\n"
                   "- 化石能源：煤的干馏、气化、液化与石油的分馏、裂化、裂解，"
                   "把煤和石油转化为可燃性气体与轻质液体燃料。\\n"
                   "## 两个目标\\n"
                   "- 提高资源利用率：让原料尽可能转化为目标产物（综合利用、循环利用副产物）。\\n"
                   "- 减少污染与绿色化学：从源头减少或消除污染物的产生，而不是产生后再治理。\\n"
                   "## 与物质变化知识的接口\\n"
                   "- 这些工艺里既有物理变化也有化学变化，判断标准是有没有新物质生成："
                   "干馏、气化、液化、裂化、裂解属化学变化，分馏、蒸馏、萃取属物理变化。",
        "boundary": "定位：化学必修第二册 第八章·化学与可持续发展·自然资源的开发利用。"
                    "只作“三条主线＋两个目标”的框架性认识，不代替各分线的具体工艺细节；"
                    "判断物质变化类型看是否生成新物质，不能按操作名称判断（“分馏”与“干馏”只差一字，前者物理、后者化学）。",
        "note": "零材料节点补料：本节为章级框架。来源为该讲义的备考目标原文"
                "（“认识化学在金属矿物等自然资源综合利用和实现物质间转化等方面的实际应用”），"
                "并按同批知识清单的分线内容作结构化改写（REVIEWED_SYNTHESIS，不抄原文）。",
        "midx": "",
    },
    {
        "chunk_rel": "2026年新高考资料(3)/一轮复习/2026年高考化学一轮复习知识清单（全国通用）/2026版/专题01 物质及其变化（知识清单）（全国通用）（学生版）.docx",
        "chunk_id": "2b8a636a64-017",
        "action": "MATERIAL", "node_slug": NODE, "type": "MISCONCEPTION_GUIDE",
        "title": "资源加工工艺中的物理变化与化学变化",
        "summary": "判断标准只有一条——有没有新物质生成：煤的干馏、气化、液化与石油的裂化、裂解"
                   "属化学变化；石油的分馏、蒸馏、萃取、海水淡化属物理变化。",
        "applicability": "题目问某资源加工工艺属于物理变化还是化学变化时使用。",
        "content": "## 判断标准\\n"
                   "有新物质生成的是化学变化，没有新物质生成的是物理变化——按“有没有新物质”判断，"
                   "不要按操作名称的字面（“分馏”与“干馏”只差一个字）。\\n"
                   "## 属于化学变化的工艺\\n"
                   "- 煤的干馏（隔绝空气加强热，得到焦炭、煤焦油、焦炉气等）；\\n"
                   "- 煤的气化（$\\mathrm{C+H_2O(g)\\xrightarrow{高温} CO+H_2}$）；\\n"
                   "- 煤的液化、石油的裂化与裂解（把大分子烃转化为小分子烃）。\\n"
                   "## 属于物理变化的工艺\\n"
                   "- 石油的分馏（按沸点不同分离，未生成新物质）、蒸馏、萃取；\\n"
                   "- 海水淡化中的蒸馏法、电渗析法；活性炭吸附、盐析、升华、潮解。\\n"
                   "## 两个易错边界\\n"
                   "- 焰色试验是物理变化（元素的性质，没有新物质生成）；\\n"
                   "- 核裂变、核聚变不属于化学变化（原子核变了，不是化学研究的层次）。",
        "boundary": "定位：化学必修第二册 第八章·化学与可持续发展·自然资源的开发利用。"
                    "本节只判“资源加工工艺”的变化类型；结晶水合物脱水、同素异形体转化、蛋白质变性等"
                    "虽同样是化学变化，但属物质变化主题，不在本节范围。",
        "note": "零材料节点补料：与本节“化石能源与海水资源的加工”配套的变化类型判别，"
                "来源为知识清单的易混物质变化清单（煤的干馏/气化/液化、石油的裂化与裂解为化学变化；"
                "石油的分馏、蒸馏、升华、潮解为物理变化），改写为独立材料。",
        "midx": "",
    },
]

TABLE = Path("tools/kb_coverage/tables/material_judgments.csv")
state = {r["rel_path"] for r in csv.DictReader(
    open("tools/kb_coverage/tables/extraction_state.csv", encoding="utf-8"))}
existing = set()
with TABLE.open(encoding="utf-8", newline="") as fh:
    for row in csv.DictReader(fh):
        existing.add((row.get("chunk_id", ""), row.get("node_slug", "")))

added = 0
with TABLE.open("a", encoding="utf-8", newline="") as fh:
    writer = csv.DictWriter(fh, fieldnames=["chunk_rel", "chunk_id", "action", "node_slug",
                                            "type", "title", "summary", "applicability",
                                            "content", "boundary", "note", "midx"],
                            lineterminator="\r\n")
    for row in ROWS:
        assert row["chunk_rel"] in state, f"源文件不在状态机里：{row['chunk_rel']}"
        if (row["chunk_id"], row["node_slug"]) in existing:
            print("已存在，跳过：", row["chunk_id"], row["title"])
            continue
        for k, v in row.items():
            if isinstance(v, str) and ("," in v or '"' in v):
                raise SystemExit(f"字段含 ASCII 逗号/引号，拒绝写入：{k}={v[:40]}")
        writer.writerow(row)
        added += 1
        print("追加：", row["chunk_id"], row["title"])
print("新增行数：", added)
