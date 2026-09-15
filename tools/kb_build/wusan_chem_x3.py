# -*- coding: utf-8 -*-
"""把五三精讲册化学 p0117–p0119（内容页 114–116）的「卤代烃 / 醇和酚」炼成材料。

正文严格取自转录件（`knowledge-research/candidates/wusan/chemistry/transcript/`），
不使用记忆补内容：凡是那三页没写的（如卤代烃与 NaCN 增长碳链），就不写。
sourceLocator 记到内容页，便于逐句回查。

生成器只管**形式**：键顺序、正文行数、ASCII 引号、绑定目标是否存在。

  用法： PYTHONPATH=tools python tools/kb_build/wusan_chem_x3.py
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 化学 精讲册》P114-P116"
PACK_ID = "moe-2025-four-subjects-v1"


def _material(slug: str, kind: str, title: str, summary: str, applicability: str,
              content: str, boundary: str, node: str) -> dict:
    return {
        "slug": slug, "subject": "CHEMISTRY", "type": kind, "title": title,
        "summaryMarkdown": summary, "applicabilityMarkdown": applicability,
        "contentMarkdown": content, "boundaryMarkdown": boundary,
        "derivationKind": "REVIEWED_SYNTHESIS",
        "sourceId": "registry-wusan-2027-a-version-jingjiang-chemistry:chemistry",
        "sourceLocator": LOCATOR,
        "reviewedAtEpochMillis": 1789200000000,
        "bindings": [{"knowledgeNodeId": f"kb:{PACK_ID}:chemistry:atomic:{node}",
                      "role": "PRIMARY"}],
    }


MATERIALS = [
    _material(
        "wusan-chem-x3-haloalkane-hydrolysis-elimination", "CONCEPT_EXPLANATION",
        "卤代烃的水解（取代）与消去",
        "卤代烃的官能团是碳卤键，与 NaOH 水溶液共热发生水解生成醇，与 NaOH 醇溶液共热发生消去生成不饱和键。",
        "判断卤代烃在给定试剂与条件下发生水解还是消去，并写出产物。",
        "水解（取代）：CH₃CH₂Br + NaOH →Δ(水) CH₃CH₂OH + NaBr。消去：CH₃CH₂Br + NaOH →Δ(乙醇) CH₂=CH₂↑ + NaBr + H₂O。\n发生消去必须在 β-C 上有氢：与官能团碳相连的碳为 α-C，再往外一个碳为 β-C；没有 β-H 的卤代烃不能消去。",
        "两种反应的条件只差溶剂是水还是乙醇，判断产物前必须先看清溶剂写法。",
        "卤代烃"),
    _material(
        "wusan-chem-x3-haloalkane-definition", "CONCEPT_EXPLANATION",
        "卤代烃的通式与官能团",
        "饱和一元卤代烃的通式是 CₙH₂ₙ₊₁X，官能团为碳卤键，碳卤键极性较强因此易断裂。",
        "由结构简式判断是否为饱和一元卤代烃，或写出其通式。",
        "饱和一元卤代烃的通式为 CₙH₂ₙ₊₁X（X 为卤素原子）；官能团是碳卤键（卤素原子）。\n碳卤键中卤素吸电子能力强，键的极性较强，是卤代烃容易发生取代反应的根源。",
        "通式只管饱和一元卤代烃；含双键的卤代烃和多卤代烃不满足这一通式，要按烃基不饱和度另行判断。",
        "卤代烃的概念"),
    _material(
        "wusan-chem-x3-haloalkane-physical", "CONCEPT_EXPLANATION",
        "卤代烃的物理性质递变规律",
        "卤代烃不溶于水、可溶于有机溶剂；密度和沸点都高于相应的烃，密度随碳原子数增加而减小，沸点随碳原子数增加而升高。",
        "比较卤代烃与相应烃的沸点密度，或比较同系物之间的沸点高低。",
        "常温下除 CH₃Cl 等少数为气体外，大多数卤代烃是液体或固体；不溶于水，可溶于有机溶剂。\n密度和沸点都大于、高于相应的烃；同系物中密度一般随碳原子数增加而减小，沸点随碳原子数增加而升高。",
        "看同系物趋势必须把两项分开：碳数增加时沸点升高、密度反而减小，不能按同一方向推。",
        "卤代烃的物理性质"),
    _material(
        "wusan-chem-x3-haloalkane-halogen-test", "METHOD_MODEL",
        "卤代烃中卤素原子的检验与消去条件判断",
        "检验卤素原子要先用 NaOH 水溶液加热水解、再用稀硝酸酸化、最后加 AgNO₃ 溶液看沉淀颜色；能否消去看有没有 β-H。",
        "设计实验检验某卤代烃中的卤素原子，或判断该卤代烃能否发生消去反应。",
        "检验步骤：RX 加 NaOH 水溶液加热水解，再加稀硝酸酸化，最后加 AgNO₃ 溶液，据沉淀颜色判断——白色为 Cl、浅黄色为 Br、黄色为 I。\n消去条件：只有存在 β-H 的卤代烃才能消去，如 CH₃CH₂Br 可以，没有 β-H 的则不能。",
        "酸化必须用稀硝酸：用硫酸会引入 SO₄²⁻ 干扰，不酸化则残留的 OH⁻ 会与 Ag⁺ 生成沉淀造成误判。",
        "卤代烃的性质及应用"),
    _material(
        "wusan-chem-x3-haloalkane-solvent-trap", "MISCONCEPTION_GUIDE",
        "卤代烃消去与水解的溶剂混淆",
        "卤代烃与 NaOH 反应的产物由溶剂决定：水溶液水解得醇、醇溶液消去得烯烃，只看 NaOH 不看溶剂就会写错产物。",
        "题目只写「与 NaOH 溶液共热」时的处理。",
        "在 NaOH 水溶液中加热发生水解（取代），产物是醇；在 NaOH 的乙醇溶液中加热发生消去，产物是不饱和烃。\n题目没有写明溶剂时不能默认成水解，要先回题干确认试剂的溶剂写法。",
        "没有 β-H 的卤代烃不论用哪种溶剂都只能水解，此时产物与溶剂选择无关。",
        "卤代烃的结构和性质易混易错点"),

    _material(
        "wusan-chem-x3-alcohol-structure-bonds", "CONCEPT_EXPLANATION",
        "醇的分子结构与断键位置",
        "醇是羟基与烃基或苯环侧链上的碳原子相连的化合物；发生哪一类反应，由乙醇分子中哪个键断裂决定。",
        "由反应条件推断断键位置，或由断键位置推断产物。",
        "醇的官能团是羟基，饱和一元醇通式为 CₙH₂ₙ₊₁OH。乙醇中与羟基相连的碳为 α-C，O—H 键记作 a、C—O 键记作 b。\n与钠、与羧酸（酯化）反应断 a 键；与浓氢溴酸、发生消去反应时断 b 键；催化氧化时同时断 a 键与 α-C 上的 C—H。",
        "断键位置由试剂决定，同一个醇在不同试剂下断不同的键，不能一律说「醇与某某反应」而不指明断键。",
        "醇"),
    _material(
        "wusan-chem-x3-alcohol-elimination-oxidation", "METHOD_MODEL",
        "醇的消去与催化氧化的结构条件",
        "醇能否消去看有没有 β-H；催化氧化的产物由 α-H 的个数决定：2~3 个生成醛、1 个生成酮、没有 α-H 不能被催化氧化。",
        "判断给定的醇能否消去或催化氧化，并写出产物类别。",
        "消去：没有 β-H 的醇不能发生消去，如 CH₃OH、C₆H₅CH₂OH。\n催化氧化按 α-H 的个数分类：2~3 个生成醛（R—CH₂OH → R—CHO）；1 个生成酮；没有 α-H 的不能被催化氧化。",
        "两条判据都只看结构——β-H 与 α-H 的有无和个数，与分子式、相对分子质量无关。",
        "醇的结构和性质"),

    _material(
        "wusan-chem-x3-phenol-properties", "CONCEPT_EXPLANATION",
        "酚的结构与苯酚的化学性质",
        "酚是羟基与苯环直接相连形成的化合物；苯环与羟基互相影响，酚羟基比醇羟基活泼，苯环上的氢也比苯中的氢活泼。",
        "判断苯酚能与哪些试剂反应并写出产物，或比较苯酚与其他弱酸的酸性强弱。",
        "苯酚能与 Na 反应：2C₆H₅OH + 2Na → 2C₆H₅ONa + H₂↑；能与 NaOH 反应：C₆H₅OH + NaOH → C₆H₅ONa + H₂O。\n与浓溴水发生取代：苯酚 + 3Br₂ → 2,4,6-三溴苯酚↓ + 3HBr；遇 FeCl₃ 溶液显紫色，可用于检验酚羟基。",
        "酸性强弱顺序为 H₂CO₃ > 苯酚 > HCO₃⁻：苯酚钠通 CO₂ 只生成 NaHCO₃，苯酚与 Na₂CO₃ 反应也只生成 NaHCO₃，不能放出 CO₂。",
        "酚"),
    _material(
        "wusan-chem-x3-phenol-acidity", "METHOD_MODEL",
        "苯酚的弱酸性及与碳酸盐的反应判断",
        "苯酚酸性比碳酸弱、比 HCO₃⁻ 强，这个位置决定了它与碳酸盐反应的产物是 NaHCO₃ 而不是 Na₂CO₃ 或 CO₂。",
        "判断苯酚钠溶液通入 CO₂ 或苯酚与 Na₂CO₃ 反应后的产物。",
        "酸性强弱：H₂CO₃ > 苯酚 > HCO₃⁻。\nC₆H₅ONa + CO₂ + H₂O → C₆H₅OH + NaHCO₃（不能生成 Na₂CO₃）；C₆H₅OH + Na₂CO₃ → C₆H₅ONa + NaHCO₃（不能生成 CO₂）。",
        "两条式子都停在 NaHCO₃：苯酚酸性弱于碳酸、强于 HCO₃⁻，所以既能被 CO₂ 从酚钠中置换出苯酚，又不足以把 HCO₃⁻ 再往下推。",
        "酚的结构和性质"),
    _material(
        "wusan-chem-x3-phenol-physical", "CONCEPT_EXPLANATION",
        "苯酚的物理性质与久置变色",
        "纯净的苯酚是无色晶体，易溶于乙醇等有机溶剂，常温下在水中溶解度不大，温度高于 65 ℃ 时能与水混溶。",
        "判断苯酚在不同温度下的溶解情况，或解释久置苯酚呈粉红色的原因。",
        "纯净苯酚为无色晶体，易溶于乙醇等有机溶剂；常温下在水中溶解度不大，温度高于 65 ℃ 时能与水混溶。\n久置的苯酚呈粉红色，是部分苯酚被空气中的氧气氧化所致。",
        "变粉红是氧化造成的，与溶解性或晶型变化无关；不能据此认为苯酚变成了另一类物质。",
        "酚的概念和物理性质"),
    _material(
        "wusan-chem-x3-phenol-reactivity-reason", "CONCEPT_EXPLANATION",
        "酚羟基比醇羟基活泼的原因",
        "苯环和羟基直接相连使电子云重新分布：苯环使酚羟基的氢更易电离，羟基使苯环上的氢更易被取代。",
        "解释为什么苯酚能与 NaOH 和浓溴水反应而乙醇不能。",
        "由于苯环对羟基的影响，酚羟基比醇羟基活泼，苯酚能电离出 H⁺ 而显弱酸性，乙醇不能电离。\n由于羟基对苯环的影响，苯酚中苯环上的氢原子比苯中的氢原子活泼，常温下就能与浓溴水发生三取代。",
        "比较醇与酚的性质差异，要从羟基所连的基团入手，不能只看分子里有没有羟基。",
        "酚的结构和性质"),
    _material(
        "wusan-chem-x3-alcohol-phenol-distinguish", "MISCONCEPTION_GUIDE",
        "醇与酚的区分及性质混淆",
        "羟基连在苯环上的是酚、连在烃基或苯环侧链碳上的是醇；两者对 NaOH、浓溴水、FeCl₃ 的表现不同。",
        "给定一个含羟基的有机物，判断它属于醇还是酚并预测其反应。",
        "酚能与 NaOH 反应、与浓溴水生成沉淀、遇 FeCl₃ 溶液显紫色；醇对这三种试剂都不反应。\n把 C₆H₅CH₂OH 当成酚是常见错误：它的羟基连在苯环侧链的碳上，属于醇。",
        "判断只看一条——羟基所连的碳是不是苯环上的碳。",
        "醇和酚的结构和性质易混易错点"),
]


def main() -> int:
    """同 slug **更新**而不是跳过：这一层是"表"，不是只能追加的日志；
    跳过会让第一次写下的错值永远留在文件里，而重跑看起来还成功。"""
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
