# -*- coding: utf-8 -*-
"""五三精讲册化学 p0034、p0038–p0041（内容页 32、36–39）「化工生产中的重要非金属元素」→ 材料。

正文严格取自转录件。**刻意不写**的八个节点（都不是「还不够好」，是「这四页里没有」）：

- `酸雨`：p0040 只在污染一行提到「①光化学烟雾；②酸雨」，没有成因/防治的成段内容。
- `常见的装置`：名字是行首标签，本页没有以「常见装置」为题的段落（装置描写都附在
  具体实验里，已分别写进氨的制法与喷泉实验两条）。
- `碳中和`：全目录无此内容。
- `氮及化合物的"十大"误区`、`硅及其化合物的"七大"误区`：名字里的计数无法核实，
  且这两页的「注意」条目已分别写成硫/硅的知识盲点两条，再写就是同义重复。
- `吸收氨气(或HCl)时要注意防止倒吸`：本页只讲喷泉实验的压强差原理，没有防倒吸装置。
- `各种硅酸的结构`：p0034 只给硅酸的物理性质与化学性质，没有任何结构式。
- `硅在自然界的存在形式`：p0034 只列 SiO₂ 的存在形式（石英、水晶、玛瑙、沙子），
  没有「地壳中储量仅次于氧」这句。
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 化学 精讲册》P32、P36-P39"
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
    # ── 硅及其化合物 无机非金属材料（p0034）──────────────────────────
    _material(
        "wusan-chem-b2c5-silicon", "CONCEPT_EXPLANATION",
        "硅的制备与用途",
        "粗硅由 SiO₂ 与 C 高温反应制得，再经 SiHCl₃ 提纯；硅作半导体材料，用于芯片与太阳能电池。",
        "写出粗硅制备与提纯的方程式、或回答硅用途的题。",
        "制备：SiO₂ + 2C →1800~2000 ℃ Si（粗硅） + 2CO↑；Si + 3HCl →300 ℃ SiHCl₃ + H₂；SiHCl₃ + H₂ →1100 ℃ Si + 3HCl。\n用途：作半导体材料，用于制造芯片、太阳能电池。",
        "三步是一串：先还原得粗硅、再转成 SiHCl₃ 提纯、最后还原得纯硅——漏掉中间一步写不出高纯硅的制法。",
        "硅"),
    _material(
        "wusan-chem-b2c5-silicic-acid-silicate", "CONCEPT_EXPLANATION",
        "硅酸与硅酸钠",
        "硅酸是难溶于水的白色胶状物质，酸性弱于碳酸、受热分解；硅酸钠水溶液俗称水玻璃。",
        "书写制取硅酸的方程式、或判断硅酸与硅酸盐性质的题。",
        "硅酸：难溶于水的白色胶状物质，酸性弱于碳酸，可由 Na₂SiO₃ + CO₂ + H₂O = Na₂CO₃ + H₂SiO₃↓ 制取；受热易分解 H₂SiO₃ →Δ SiO₂ + H₂O；干燥脱水后得硅胶，可作催化剂载体、干燥剂。\n硅酸钠 Na₂SiO₃ 可溶于水，水溶液俗称水玻璃（无色黏稠液体），可作黏合剂、防火剂。",
        "「酸性弱于碳酸」是能用 CO₂ 制硅酸的依据——反过来用强酸制弱酸的通则记，别记成硅酸比碳酸强。",
        "硅酸和硅酸盐"),
    _material(
        "wusan-chem-b2c5-ceramics", "CONCEPT_EXPLANATION",
        "陶瓷的成分与原料",
        "陶瓷属于硅酸盐材料，主要成分是硅酸盐，原料是黏土。",
        "判断硅酸盐材料的成分与原料的题。",
        "陶瓷：主要成分为硅酸盐，原料为黏土。\n同类硅酸盐材料对比：玻璃的主要成分为 Na₂SiO₃、CaSiO₃、SiO₂，原料是纯碱、石灰石、石英砂；水泥的主要成分为硅酸盐，原料是黏土、石灰石、石膏。",
        "三种材料别混：只有玻璃的主要成分写成氧化物/盐的混合，陶瓷与水泥都写「硅酸盐」；原料也各不相同。",
        "陶瓷"),
    _material(
        "wusan-chem-b2c5-cement", "CONCEPT_EXPLANATION",
        "水泥的成分与原料",
        "水泥属于硅酸盐材料，主要成分是硅酸盐，原料是黏土、石灰石与石膏。",
        "判断水泥的成分与原料、或比较三种硅酸盐材料的题。",
        "水泥：主要成分为硅酸盐，原料为黏土、石灰石、石膏。\n对照：陶瓷的原料只有黏土；玻璃的原料是纯碱、石灰石、石英砂，主要成分为 Na₂SiO₃、CaSiO₃、SiO₂。",
        "石膏是水泥原料里独有的一项——把它漏掉或加到陶瓷、玻璃上去都会判错。",
        "水泥"),
    _material(
        "wusan-chem-b2c5-silicon-compound-pitfall", "MISCONCEPTION_GUIDE",
        "硅及其化合物的知识盲点",
        "SiO₂ 能与氢氟酸反应但不是碱性氧化物，盛碱试剂瓶要用橡胶塞，硅酸酸性弱于碳酸。",
        "判断硅及其化合物性质表述正误的题。",
        "SiO₂ + 4HF = SiF₄↑ + 2H₂O 可用于刻蚀玻璃，但不能由此认为 SiO₂ 是碱性氧化物或两性氧化物——它仍是酸性氧化物。\nSiO₂ + 2NaOH = Na₂SiO₃ + H₂O，故盛有碱性物质的试剂瓶要用橡胶塞，不能用玻璃塞。\n硅酸酸性弱于碳酸，因而可用 CO₂ 与硅酸钠溶液反应制硅酸。",
        "「能与氢氟酸反应」是 SiO₂ 的特性而非碱性氧化物的证据——这两件事混起来是本节最常见的错。",
        "碳-硅及其化合物的知识盲点"),

    # ── 硫及其化合物（p0038–p0039）────────────────────────────────────
    _material(
        "wusan-chem-b2c5-sulfur", "CONCEPT_EXPLANATION",
        "硫单质的性质",
        "硫是黄色固体，难溶于水、微溶于酒精、易溶于 CS₂；既能表现氧化性也能表现还原性。",
        "判断硫单质的溶解性、或写出硫与非金属、金属反应产物的题。",
        "物理性质：黄色固体，质脆，难溶于水，微溶于酒精，易溶于 CS₂。\n还原性：在 O₂ 中燃烧生成 SO₂。\n氧化性：与变价金属在加热条件下反应生成低价硫化物（如 FeS、Cu₂S）；与 H₂ 在加热条件下反应生成 H₂S。",
        "硫与变价金属反应一律生成低价硫化物——写 Fe₂S₃、CuS 之类都是错的；溶解性记「不溶于水、溶于 CS₂」这一组。",
        "硫"),
    _material(
        "wusan-chem-b2c5-sulfur-to-ferrous-sulfide", "CONCEPT_EXPLANATION",
        "硫与铁反应生成硫化亚铁",
        "硫与铁加热生成 FeS，因为硫的氧化性弱，只能把铁氧化到 +2 价。",
        "书写硫与金属反应的产物、或判断产物价态的题。",
        "Fe + S →Δ FeS。\n原因：硫是较弱的氧化剂，与变价金属反应时只能生成低价硫化物；相比之下 Cl₂ 等强氧化剂会把铁氧化到 +3 价（2Fe + 3Cl₂ →Δ 2FeCl₃）。",
        "同是「铁与非金属反应」，产物价态由非金属单质的氧化性强弱决定——把 FeS 写成 Fe₂S₃ 是最典型的错。",
        "硫转化为硫化亚铁"),
    _material(
        "wusan-chem-b2c5-sulfide-to-sulfur", "CONCEPT_EXPLANATION",
        "硫化钠转化为单质硫",
        "硫元素按台阶式升降：Na₂S 中硫为 -2 价只有还原性，被氧化时升到相邻的 0 价生成单质硫。",
        "判断含硫物质之间能否转化、或书写氧化还原方程式的题。",
        "硫及其化合物间的化合价变化一般升高或降低至其相邻价态，即台阶式升降：-2 价（H₂S、Na₂S）→ 0 价（S）→ +4 价（SO₂、Na₂SO₃）→ +6 价（SO₃、H₂SO₄）。\nNa₂S 中硫为 -2 价、只有还原性，被氧化时升到 0 价生成单质硫。\n同价态的酸、氧化物、盐之间也可互相转化（如 SO₂ ↔ H₂SO₃ ↔ Na₂SO₃）。",
        "「台阶式升降」限制的是相邻价态——不能从 -2 价一步跳到 +6 价写成 Na₂SO₄。",
        "硫化钠转化为单质硫"),
    _material(
        "wusan-chem-b2c5-sulfuric-acid", "CONCEPT_EXPLANATION",
        "硫酸的物理性质与浓硫酸的三大特性",
        "浓硫酸有吸水性、脱水性与强氧化性；与铜、碳加热反应都被还原为 SO₂，常温下使 Fe、Al 钝化。",
        "判断浓硫酸表现哪种特性、或书写浓硫酸与铜、碳反应的题。",
        "物理性质：无色油状液体，密度比水大，沸点高，难挥发。\n三大特性：吸水性、脱水性、强氧化性。\n强氧化性的表现：Cu + 2H₂SO₄(浓) →Δ CuSO₄ + SO₂↑ + 2H₂O；C + 2H₂SO₄(浓) →Δ CO₂↑ + 2SO₂↑ + 2H₂O；常温下浓硫酸可使 Fe、Al 钝化。稀硫酸只具有酸的通性。",
        "吸水（吸收现成的水）与脱水（把有机物里的氢氧按 2:1 脱去）是两种不同的表现，判断题问「表现什么特性」时要按现象选。",
        "硫酸"),
    _material(
        "wusan-chem-b2c5-sulfur-pitfall", "MISCONCEPTION_GUIDE",
        "硫及其重要化合物的知识盲点",
        "浓硫酸能干燥 SO₂、硫不能被氧气一步氧化为 SO₃、Na₂SO₄ 不体现氧化性。",
        "判断含硫物质性质表述正误、或选择干燥剂的题。",
        "浓硫酸可用于干燥 SO₂ 气体——二者硫元素处于相邻价态，不发生氧化还原反应。\nS 不能被 O₂ 一步氧化为 SO₃：H₂S →O₂,点燃 S →O₂,点燃 SO₂ →O₂,催化剂,Δ SO₃ →H₂O H₂SO₄，是一串连续氧化。\nNa₂SO₄ 中硫为 +6 价（最高价），不体现氧化性。",
        "「相邻价态不发生氧化还原」是判断能否用浓硫酸干燥某气体的依据——SO₂ 可以，H₂S 不行。",
        "硫及其重要化合物的知识盲点"),

    # ── 氮及其化合物（p0040–p0041）────────────────────────────────────
    _material(
        "wusan-chem-b2c5-nitrogen", "CONCEPT_EXPLANATION",
        "氮气的结构与化学性质",
        "氮气结构式为 N≡N，化学性质不活泼可作保护气，既能被氧化（与 O₂ 放电生成 NO）也能被还原（与 Mg 生成 Mg₃N₂）。",
        "判断氮气的反应产物、或解释氮气作保护气的题。",
        "结构：电子式以三键相连，结构式为 N≡N。\n不活泼，可作保护气；能被氧化也能被还原：N₂ + O₂ →放电或高温 2NO。\n氧化性：3Mg + N₂ →点燃 Mg₃N₂。\n氮的固定：N₂ 转化为氮的化合物的过程，分为自然固氮和人工固氮。",
        "氮气与氧气只能生成 NO、不能一步生成 NO₂——写「N₂ + O₂ → NO₂」是本节反复出现的错。",
        "氮气"),
    _material(
        "wusan-chem-b2c5-nitric-oxide", "CONCEPT_EXPLANATION",
        "一氧化氮的性质",
        "NO 是无色有毒气体、不溶于水，遇氧气立即生成红棕色的 NO₂，可据颜色变化判断它的存在。",
        "判断 NO 与 O₂ 混合后的现象、或选择收集方法的题。",
        "NO：无色、有毒的气体，不溶于水，易与 O₂ 反应：2NO + O₂ = 2NO₂，可根据气体颜色变化鉴别 NO 的存在。\n对照 NO₂：红棕色、有刺激性气味的有毒气体，易与水反应 3NO₂ + H₂O = 2HNO₃ + NO，故 NO₂ 溶于水时既生成酸又放出 NO。",
        "NO 不溶于水但能与 O₂ 反应，所以不能用排空气法收集——「不溶于水」与「能否用排水法」要分开记。",
        "一氧化氮"),
    _material(
        "wusan-chem-b2c5-nitrogen-oxides", "CONCEPT_EXPLANATION",
        "氮的氧化物与 NO₂—N₂O₄ 平衡",
        "氮的氧化物有 N₂O、NO、N₂O₃、NO₂、N₂O₄、N₂O₅ 等，其中 NO₂ 与 N₂O₄ 之间存在平衡。",
        "判断氮氧化物种类、或计算 NO 与 O₂ 混合后气体物质的量的题。",
        "氮氧化物主要有 N₂O、NO、N₂O₃、NO₂、N₂O₄、N₂O₅ 等。\nNO₂ 能与 N₂O₄ 相互转化：2NO₂ ⇌ N₂O₄，由于此平衡的存在，通常所说的 NO₂ 实际是 NO₂ 与 N₂O₄ 的混合气体。\n例：2 mol NO 与 1 mol O₂ 充分反应，由于生成的 NO₂ 部分转化为 N₂O₄，实际所得 NO₂ 少于 2 mol。\n污染：①光化学烟雾；②酸雨。",
        "凡涉及 NO₂ 物质的量的计算都要考虑 N₂O₄ 平衡——按方程式算出的 NO₂ 是上限，实际小于该值。",
        "氮的氧化物"),
    _material(
        "wusan-chem-b2c5-nitric-acid", "CONCEPT_EXPLANATION",
        "硝酸的性质、制法与不稳定性",
        "硝酸有强酸性、强氧化性与不稳定性；浓硝酸与铜、碳反应被还原为 NO₂，稀硝酸与铜反应生成 NO。",
        "书写硝酸与金属、非金属反应的方程式、或解释久置浓硝酸呈黄色的题。",
        "物理性质：无色、有刺激性气味的液体，易挥发。\n强酸性；强氧化性：浓 HNO₃ 与 Fe、Al 常温钝化；Cu 与浓 HNO₃ 反应得 Cu²⁺、NO₂↑、H₂O，与稀 HNO₃ 反应得 Cu²⁺、NO↑、H₂O；C 在加热条件下与浓 HNO₃ 反应得 CO₂↑、NO₂↑、H₂O。\n不稳定性：加热或光照下分解生成 NO₂↑、O₂↑、H₂O——久置浓硝酸呈黄色是因为溶有 NO₂。\n工业制法：NH₃ →催化剂,Δ NO →O₂ NO₂ →H₂O HNO₃。",
        "浓硝酸的还原产物是 NO₂、稀硝酸是 NO——判产物必须先看浓度；久置变黄是分解出的 NO₂ 溶在酸里，不是硝酸本身变色。",
        "硝酸"),
    _material(
        "wusan-chem-b2c5-ammonia", "CONCEPT_EXPLANATION",
        "氨的结构、性质与制法",
        "氨分子是三角锥形，极易溶于水显碱性，能被氧化为 NO，实验室用铵盐与碱加热制取、向下排空气法收集。",
        "判断氨的分子构型、书写氨参与反应的方程式、或回答氨的实验室制法的题。",
        "结构：氮上有一对孤对电子，分子空间结构为三角锥形。\n物理性质：无色、有刺激性气味的气体，密度比空气小，易液化，极易溶于水（常温常压下 1 体积水约溶解 700 体积氨气）。\n化学性质：NH₃·H₂O ⇌ NH₄⁺ + OH⁻（显碱性）；与 HCl、HNO₃ 反应生成白烟（NH₄Cl、NH₄NO₃）；被 O₂ 在催化剂、加热条件下氧化为 NO；与 AgCl 作用生成 [Ag(NH₃)₂]Cl（配位性）。是高中阶段唯一能使湿润红色石蕊试纸变蓝的气体。\n制法：实验室 2NH₄Cl + Ca(OH)₂ →Δ CaCl₂ + 2NH₃↑ + 2H₂O，固固加热型装置、试管口塞一团棉花、向下排空气法收集、碱石灰干燥；工业 N₂ + 3H₂ ⇌催化剂,高温高压 2NH₃；快速制取可把浓氨水滴到 NaOH 固体或生石灰上。",
        "收集必须用向下排空气法（密度比空气小）、干燥剂只能用碱石灰（不能用浓硫酸或无水氯化钙）——这两条是氨的制法最常考的两个点。",
        "氨"),
    _material(
        "wusan-chem-b2c5-fountain-experiment", "CONCEPT_EXPLANATION",
        "喷泉实验的原理与常见组合",
        "喷泉实验靠短时间内烧瓶内外的较大压强差；常见组合有 HCl—水/NaOH、NH₃—水/酸、酸性气体—NaOH、NO₂—水。",
        "判断某气体与液体能否形成喷泉、或解释喷泉现象的题。",
        "原理：短时间内，烧瓶内外产生较大的压强差。\n常见组合：HCl 气体和水／NaOH 溶液；NH₃ 和水／酸溶液；酸性气体和 NaOH 溶液；NO₂ 和水。\n由此可延伸判断：气体极易溶于该液体、或能与该液体迅速反应，就能形成喷泉。",
        "判断能不能形成喷泉，看的是「气体在该液体中溶解度极大或迅速反应」——只看颜色或密度都会判错。",
        "常见喷泉的类型"),
    _material(
        "wusan-chem-b2c5-ammonium-salt", "CONCEPT_EXPLANATION",
        "铵盐的性质与检验",
        "铵盐一般是白色易溶于水的固体，与碱加热放出氨气、受热易分解，可用浓 NaOH 与湿润红色石蕊试纸检验。",
        "判断铵盐的性质、或设计检验 NH₄⁺ 的实验的题。",
        "物理性质：一般为白色、易溶于水的固体。\n与碱反应：NH₄⁺ + OH⁻ →Δ NH₃↑ + H₂O；受热分解：NH₄Cl →Δ NH₃↑ + HCl↑，NH₄HCO₃ →Δ NH₃↑ + CO₂↑ + H₂O。\n水解反应：NH₄⁺ + H₂O ⇌ NH₃·H₂O + H⁺。\n检验：样品与浓 NaOH 溶液共热，用湿润的红色石蕊试纸检验是否变蓝。",
        "检验必须用「浓 NaOH 并加热」——只在常温下加碱不放氨气；试纸要湿润才能显色。",
        "铵盐"),
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
