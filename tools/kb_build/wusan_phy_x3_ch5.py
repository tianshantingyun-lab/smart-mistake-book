# -*- coding: utf-8 -*-
"""五三精讲册物理 p0198–p0199（内容页 191–192）「原子核和核反应」→ 材料。

正文严格取自转录件。**刻意不写**的六个节点：

- `常用公式(N=N₀(1/2)^(t/τ))`、`意义：表示放射性元素衰变的快慢．`：名字是抽取残片
  （把公式本体与半句话当成了名字），不是知识点名——应走改名，写材料会造出
  「标题与节点名零 2-gram 重合」的弱匹配。
- `基本概念理解`：行首标签，不是知识点名。
- `同位素`：这两页只出现「人工放射性同位素」这个短语，没有同位素的定义或判据。
- `原子核`、`核力`：这两页讲的是衰变、核反应与质量亏损；`原子核的组成` 与 `核力`
  的定义在这两页里没有（核力只在章标题出现）。
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 物理 精讲册》P191-P192"
PACK_ID = "moe-2025-four-subjects-v1"


def _material(slug: str, kind: str, title: str, summary: str, applicability: str,
              content: str, boundary: str, node: str) -> dict:
    return {
        "slug": slug, "subject": "PHYSICS", "type": kind, "title": title,
        "summaryMarkdown": summary, "applicabilityMarkdown": applicability,
        "contentMarkdown": content, "boundaryMarkdown": boundary,
        "derivationKind": "REVIEWED_SYNTHESIS",
        "sourceId": "registry-wusan-2027-a-version-jingjiang-physics:physics",
        "sourceLocator": LOCATOR,
        "reviewedAtEpochMillis": 1789200000000,
        "bindings": [{"knowledgeNodeId": f"kb:{PACK_ID}:physics:atomic:{node}",
                      "role": "PRIMARY"}],
    }


MATERIALS = [
    _material(
        "wusan-phy-x3c5-natural-radioactivity", "CONCEPT_EXPLANATION",
        "天然放射现象的三种射线",
        "α 射线是高速氦核流、β 射线是高速电子流、γ 射线是光子流；电离本领与穿透本领的顺序相反。",
        "比较三种射线的本质与穿透电离能力、或判断某射线能否被什么挡住的题。",
        "α 射线：高速氦核流，速度约 0.1c，电离本领很强、穿透本领弱（纸片即可挡住）。\nβ 射线：高速电子流，速度约 0.99c，电离本领较弱、穿透本领较强（能穿透几毫米厚的铝板）。\nγ 射线：光子流，速度等于光速，电离本领很弱、穿透本领很强（能穿透几厘米厚的铅板），且经常是伴随着 α 射线或 β 射线同时产生的。",
        "电离本领与穿透本领的顺序相反：α 电离最强而穿透最弱、γ 电离最弱而穿透最强——把 β 说成穿透最强是这类题的常见错。",
        "天然放射现象"),
    _material(
        "wusan-phy-x3c5-fission", "CONCEPT_EXPLANATION",
        "重核裂变",
        "重核裂变是重核俘获中子后分裂成中等核并放出中子的反应，是原子弹与核电站的原理。",
        "书写裂变方程、或判断某方程是否属于裂变的题。",
        "典型方程：²³⁵U + ¹n → ¹⁴⁴Ba + ⁸⁹Kr + 3¹n；²³⁵U + ¹n → ¹³⁶Xe + ⁹⁰Sr + 10¹n。\n实际意义：原子弹、核电站的原理。\n裂变反应同样遵循质量数守恒与电荷数守恒。",
        "裂变方程里的中子数要数清（一个反应放出 3 个、另一个放出 10 个）——配平时漏掉右侧的中子数量会对不上质量数。",
        "核裂变"),
    _material(
        "wusan-phy-x3c5-fusion", "CONCEPT_EXPLANATION",
        "轻核聚变",
        "轻核聚变是轻核结合成较重核的反应，典型方程是 ²H + ³H → ⁴He + ¹n，是太阳与氢弹的原理。",
        "书写聚变方程、或判断某方程属于裂变还是聚变的题。",
        "典型方程：²H + ³H → ⁴He + ¹n。\n实际意义：太阳、氢弹的原理。\n聚变与裂变都向「中等大小的核」靠拢，因此都能释放核能；区别在于聚变由轻核结合、裂变由重核分裂。",
        "判断裂变还是聚变只看反应前后核的轻重方向：轻核结合成较重核是聚变，重核分裂成中等核是裂变——只凭是否放出中子判会错。",
        "核聚变"),
    _material(
        "wusan-phy-x3c5-neutron", "CONCEPT_EXPLANATION",
        "中子的发现",
        "查德威克用 α 粒子轰击铍核发现了中子：⁴He + ⁹Be → ¹²C + ¹n。",
        "写出发现中子的核反应方程、或判断方程中粒子符号的题。",
        "中子的发现：⁴He + ⁹Be → ¹²C + ¹n，查德威克由此发现中子（方程也可写作 ⁹Be + ⁴He → ¹²C + ¹n）。\n中子不带电，在核反应方程中记作 ¹n（质量数 1、电荷数 0）。",
        "中子写作 ¹₀n、质子写作 ¹₁H——两者质量数都是 1，靠电荷数区分，写错就等于把发现中子的反应写成了另一种反应。",
        "中子-卢瑟福预言中子的存在-查德威克证实"),
    _material(
        "wusan-phy-x3c5-first-artificial-reaction", "CONCEPT_EXPLANATION",
        "第一次人工核反应与三个人工转变",
        "卢瑟福用 α 粒子轰击氮核第一次实现人工核反应并发现质子；此后的人工转变分别发现了中子和人工放射性同位素。",
        "把发现者与人工核反应方程对号、或书写人工转变方程的题。",
        "第一次人工核反应（卢瑟福发现质子）：⁴He + ¹⁴N → ¹⁷O + ¹H。\n发现中子：⁴He + ⁹Be → ¹²C + ¹n（查德威克）。\n发现人工放射性同位素：²⁷Al + ⁴He → ³⁰P + ¹n，随后 ³⁰P → ³⁰Si + ⁰₊₁e（约里奥-居里夫妇）。",
        "三条人工转变方程各对应一项发现——质子、中子、人工放射性同位素，考试常把发现者对错位。",
        "第一次人工核反应"),
    _material(
        "wusan-phy-x3c5-binding-energy", "CONCEPT_EXPLANATION",
        "结合能与比结合能",
        "结合能只对由核子组成的原子核而言，等于比结合能乘核子数；比结合能越大原子核越稳定，中等核最稳定。",
        "比较不同原子核的结合能与比结合能、或解释裂变与聚变都能释放核能的题。",
        "结合能：针对由核子组成的原子核而言，孤立核子如质子或中子没有结合能；质量数越大的原子核结合能越大。\n比结合能：大小反映原子核的稳定程度，比结合能越大、原子核越难被拆开、越稳定。核子数较小的轻核与核子数较大的重核，比结合能都比较小；中等大小的核比结合能较大。\n当比结合能较小的原子核变成比结合能较大的原子核时就释放核能——这就是重核裂变与轻核聚变都能释放核能的原因。\n原子核的结合能 ＝ 比结合能 × 核子数。",
        "结合能随质量数单调增大，比结合能却是中等核最大——把两者当成同一趋势，就无法解释「裂变与聚变都放能」。",
        "结合能"),
]


def main() -> int:
    """同 slug **更新**而不是跳过：这一层是"表"，不是只能追加的日志。"""
    lines = OUT.read_text(encoding="utf-8").splitlines() if OUT.exists() else []
    index = {json.loads(line)["slug"]: i for i, line in enumerate(lines) if line.strip()}
    added = updated = 0
    for material in MATERIALS:
        blob = json.dumps(material, ensure_ascii=False)
        slug = material["slug"]
        if slug in index:
            if lines[index[slug]] != blob:
                lines[index[slug]] = blob
                updated += 1
            continue
        lines.append(blob)
        added += 1
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"materials.jsonl 新增 {added} 条、更新 {updated} 条（现共 {len(lines)} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
