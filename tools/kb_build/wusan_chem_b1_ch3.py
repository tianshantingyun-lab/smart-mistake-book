# -*- coding: utf-8 -*-
"""五三精讲册化学 p0026–p0029（内容页 24–27）「铁 金属材料」→ 材料。

正文严格取自转录件。**刻意不写**的四个节点：

- `铝元素的原子结构和存在`：这四页只给铝的物理性质与化学性质，没有原子结构或存在形式。
- `一种重要的复盐——明矾`：全目录里明矾只出现在 p0003（胶体聚沉）与 p0104（盐类水解应用），
  都不在本页组，且「复盐」的定义这四页没有。
- `基于"两性"类比推测 Be、Zn 及其化合物的性质`：全目录无此内容。
- `等质量的铝与足量的盐酸、氢氧化钠溶液分别反应`：这四页没有这条计算。

**一处跨章绑定（有意，写明理由）**：`电子工业常用 30% 的 FeCl₃ 溶液腐蚀铜箔…` 的正文
只在 p0008（第一章 物质及其变化）出现：`Cu + 2FeCl₃ = 2FeCl₂ + CuCl₂`。
该节点名就是这条反应本身，绑它是内容正确的；若坚持「只绑本页组所在章」，这个节点
会永远空着。故按内容正确性优先，来源定位串写明 P8。
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 化学 精讲册》P24-P27"
PACK_ID = "moe-2025-four-subjects-v1"


def _material(slug: str, kind: str, title: str, summary: str, applicability: str,
              content: str, boundary: str, node: str,
              locator: str = LOCATOR) -> dict:
    return {
        "slug": slug, "subject": "CHEMISTRY", "type": kind, "title": title,
        "summaryMarkdown": summary, "applicabilityMarkdown": applicability,
        "contentMarkdown": content, "boundaryMarkdown": boundary,
        "derivationKind": "REVIEWED_SYNTHESIS",
        "sourceId": "registry-wusan-2027-a-version-jingjiang-chemistry:chemistry",
        "sourceLocator": locator,
        "reviewedAtEpochMillis": 1789200000000,
        "bindings": [{"knowledgeNodeId": f"kb:{PACK_ID}:chemistry:atomic:{node}",
                      "role": "PRIMARY"}],
    }


MATERIALS = [
    # ── 铁及其化合物（p0026）──────────────────────────────────────────
    _material(
        "wusan-chem-b1c3-ferrous-salt", "CONCEPT_EXPLANATION",
        "亚铁盐的检验与性质",
        "亚铁盐溶液呈浅绿色，加碱生成白色沉淀并迅速变灰绿、最后变红褐，遇铁氰化钾生成蓝色沉淀。",
        "检验溶液中的亚铁离子、或判断亚铁盐参与反应现象的题。",
        "观察法：含 Fe²⁺ 的溶液呈浅绿色，含 Fe³⁺ 的溶液呈棕黄色。\n加碱法：加 NaOH 溶液出现白色沉淀 → 灰绿色 → 红褐色，即为 Fe²⁺；直接生成红褐色沉淀的是 Fe³⁺。\n特征试剂：加 K₃[Fe(CN)₆]（铁氰化钾）生成蓝色沉淀、加邻二氮菲溶液呈橙红色，都说明是 Fe²⁺；加溴水或 KMnO₄ 溶液褪色也说明是 Fe²⁺。",
        "检验 Fe²⁺ 不能先加氯水后加 KSCN，也不能把加过 KSCN 的混合液倒入足量新制氯水（新制氯水可能氧化 SCN⁻）；Fe³⁺、Fe²⁺、Cl⁻ 同时存在时不能用酸性 KMnO₄ 检验 Fe²⁺（Cl⁻ 也还原 KMnO₄）。",
        "亚铁盐"),
    _material(
        "wusan-chem-b1c3-iron-chemistry", "CONCEPT_EXPLANATION",
        "铁的化学性质与钝化",
        "铁与非金属、水蒸气、酸和盐溶液反应，遇冷浓硝酸与浓硫酸钝化；产物中铁是 +2 还是 +3 由氧化剂强弱决定。",
        "判断铁与某试剂反应的产物价态、或书写铁与稀硝酸反应方程式的题。",
        "与非金属单质：2Fe + 3Cl₂ →Δ 2FeCl₃；Fe + S →Δ FeS——非金属单质的氧化性强弱决定铁显 +3 还是 +2。\n与稀硝酸：铁过量或硝酸少量时生成 Fe²⁺（3Fe + 2NO₃⁻ + 8H⁺ = 3Fe²⁺ + 2NO↑ + 4H₂O），硝酸过量时生成 Fe³⁺（Fe + NO₃⁻ + 4H⁺ = Fe³⁺ + NO↑ + 2H₂O）。\n与水和酸：高温下与水蒸气反应生成 Fe₃O₄ 和 H₂；与非氧化性酸反应生成 Fe²⁺ 与 H₂；与氧化性酸反应生成 Fe³⁺。",
        "氧化性酸把铁氧化为 Fe³⁺，但铁过量时发生的 Fe³⁺ + Fe → Fe²⁺ 会把产物拉回 +2 价——判价态必须先看谁过量。",
        "铁的化学性质"),
    _material(
        "wusan-chem-b1c3-ferric-salt", "CONCEPT_EXPLANATION",
        "铁盐的性质与 Fe³⁺ 的检验",
        "铁盐溶液呈棕黄色，加 KSCN 溶液变红即可确认 Fe³⁺；苯酚显紫色、淀粉碘化钾试纸变蓝也是它的特征。",
        "检验 Fe³⁺、或判断铁盐参与反应现象的题。",
        "加 KSCN 溶液变红色即为 Fe³⁺；加 NaOH 溶液直接得到红褐色沉淀也是 Fe³⁺。\n特征试剂法：加苯酚溶液呈紫色、用淀粉—KI 试纸检验变蓝（Fe³⁺ 把 I⁻ 氧化成 I₂），都是 Fe³⁺ 的证据。\nFe²⁺ 与 Fe³⁺ 可以相互转化：Fe²⁺ 被 Cl₂、KMnO₄、HNO₃ 等氧化剂氧化为 Fe³⁺，Fe³⁺ 被 Fe、Cu、KI 等还原剂还原为 Fe²⁺。",
        "检验 Fe²⁺ 时不能先加氯水后加 KSCN——那样会把本来没有的 Fe³⁺ 造出来；正确顺序是先加 KSCN 无现象、再加氯水变红。",
        "铁盐的性质及应用"),
    _material(
        "wusan-chem-b1c3-iron-oxide-hydroxide-pitfall", "MISCONCEPTION_GUIDE",
        "铁、铁的氧化物与氢氧化物的易错点",
        "Fe(OH)₂ 在空气中迅速变色、Fe₃O₄ 同时含 +2 与 +3 价、铁的产物价态随氧化剂与用量而变。",
        "判断铁及其化合物性质的表述正误、或描述 Fe(OH)₂ 制备与保存的题。",
        "Fe(OH)₂ 是白色固体，在空气中极易被氧化：4Fe(OH)₂ + O₂ + 2H₂O = 4Fe(OH)₃，现象是白色絮状沉淀迅速变成灰绿色、最后变成红褐色。\nFe₃O₄ 俗称磁性氧化铁，黑色晶体，铁显 +2 与 +3 两种价态，能被磁铁吸引；Fe₂O₃ 俗称铁红、红棕色，可作红色颜料；FeO 不稳定，在空气中受热转化为 Fe₃O₄。\nFe(OH)₃ 受热分解：2Fe(OH)₃ →Δ Fe₂O₃ + 3H₂O。",
        "Fe₃O₄ 不是「铁显 +2 价」或「+3 价」的单价氧化物，写「价—类」图时它位于 +2 与 +3 之间；把 Fe(OH)₂ 的变色描述成「白色直接变红褐色」也不符合现象。",
        "铁-铁的氧化物和氢氧化物的易错点"),
    _material(
        "wusan-chem-b1c3-iron-conversion", "CONCEPT_EXPLANATION",
        "铁及其化合物的转化关系",
        "用「价—类」二维图把铁的单质、氧化物、盐、碱按 0、+2、+3 价串起来，相互转化由氧化剂与还原剂决定。",
        "书写铁及其化合物之间的转化方程式、或判断某步转化需要加什么的题。",
        "价—类二维图：0 价只有 Fe；+2 价有 FeO、Fe²⁺、Fe(OH)₂；+3 价有 Fe₂O₃、Fe³⁺、Fe(OH)₃；Fe₃O₄ 位于 +2 与 +3 之间。\nFe 可分别转化为 FeO、Fe₂O₃，也可直接转化为 Fe²⁺、Fe³⁺。\nFe²⁺ ⇌ Fe³⁺：Cl₂、KMnO₄、HNO₃ 等氧化剂把 Fe²⁺ 氧化为 Fe³⁺；Fe、Cu、KI 等还原剂把 Fe³⁺ 还原为 Fe²⁺。",
        "横轴是物质类别、纵轴是化合价，两步之间要同时满足「类别变化」与「价态变化」——只按类别连线会把 Fe²⁺ 与 Fe(OH)₃ 直接连起来。",
        "铁及其化合物的转化关系"),
    _material(
        "wusan-chem-b1c3-iron-preparation", "CONCEPT_EXPLANATION",
        "铁的冶炼与铁的性质要点",
        "工业上用热还原法炼铁：Fe₂O₃ + 3CO →高温 2Fe + 3CO₂；铁遇冷浓硝酸、浓硫酸钝化。",
        "判断铁的冶炼方法、或回答铁的钝化与反应的题。",
        "冶炼：铁属于较活泼金属，用热还原法，Fe₂O₃ + 3CO →高温 2Fe + 3CO₂；也可用铝热反应 2Al + Fe₂O₃ →高温 2Fe + Al₂O₃。\n性质要点：铁导热、延展、有导电性、易被磁铁吸引；遇冷的浓硝酸、浓硫酸发生钝化。",
        "铝热反应既是冶炼也是放热反应，判断时要与「热还原法」这一分类对上——铁不属于电解法冶炼的活泼金属。",
        "铁的制备和性质"),
    _material(
        "wusan-chem-b1c3-ferric-chloride-etching", "CONCEPT_EXPLANATION",
        "FeCl₃ 溶液腐蚀铜箔制印刷电路板",
        "电子工业用 FeCl₃ 溶液腐蚀铜箔：Cu + 2FeCl₃ = 2FeCl₂ + CuCl₂，利用的是 Fe³⁺ 的氧化性。",
        "书写印刷电路板蚀刻的离子方程式、或判断 Fe³⁺ 氧化性的题。",
        "电子工业常用 30% 的 FeCl₃ 溶液腐蚀敷在绝缘板上的铜箔，制造印刷电路板：Cu + 2FeCl₃ = 2FeCl₂ + CuCl₂。\n反应的实质是 Fe³⁺ 把 Cu 氧化为 Cu²⁺，自身被还原为 Fe²⁺，离子方程式写作 Cu + 2Fe³⁺ = Cu²⁺ + 2Fe²⁺。",
        "这是 Fe³⁺ 氧化性的应用，不是置换反应——产物是 Fe²⁺ 而不是 Fe，写成 Cu + FeCl₃ → CuCl + Fe 之类都错。",
        "电子工业常用30-的fecl3溶液腐蚀敷在绝缘板上的铜箔-制造印刷电路板",
        locator="《2027 5·3 A版 高考总复习 化学 精讲册》P8"),

    # ── 金属材料与金属矿物的开发利用（p0027–p0029）────────────────────
    _material(
        "wusan-chem-b1c3-alloy", "CONCEPT_EXPLANATION",
        "合金的组成与性能",
        "合金是两种或两种以上金属（或金属与非金属）熔合而成的、具有金属特性的物质，熔点比成分金属低、硬度强度比成分金属大。",
        "判断合金的性质、或比较合金与纯金属性质的题。",
        "组成：两种或两种以上的金属（或金属与非金属）加热熔合生成的具有金属特性的物质。\n性能：熔点一般比各成分金属低，硬度和强度一般比各成分金属大。",
        "合金的熔点「低」、硬度强度「大」是两条方向相反的变化，答题时别写反；合金仍是混合物，没有固定熔点。",
        "合金"),
    _material(
        "wusan-chem-b1c3-common-metal-materials", "CONCEPT_EXPLANATION",
        "常见金属材料：铁合金、铝合金与新型合金",
        "铁合金有生铁和钢（碳素钢、合金钢），铝合金密度小强度高，新型合金包括储氢合金、钛合金、形状记忆合金等。",
        "判断某材料属于哪类合金、或回答新型合金用途的题。",
        "铁合金：生铁和钢，钢可分为碳素钢和合金钢；碳素钢按含碳量分为低碳钢、中碳钢、高碳钢。\n铝合金：如硬铝密度小、强度高，抗腐蚀能力强，是制造飞机和宇宙飞船的理想材料。\n新型合金：储氢合金、钛合金、耐热合金、形状记忆合金等，广泛用于航空航天、生物工程和电子工业等领域。",
        "钢是铁合金而不是纯铁，合金钢是在碳素钢基础上加入其他元素——把「钢」当成纯净物会判错。",
        "常见金属材料"),
    _material(
        "wusan-chem-b1c3-metal-materials-pitfall", "MISCONCEPTION_GUIDE",
        "金属材料与金属冶炼的易错点",
        "冶炼方法按金属活动性选（电解、热还原、热分解），钾可用钠置换制得，合金的熔点低于成分金属。",
        "判断冶炼方法是否正确的题，或判断合金性能表述正误的题。",
        "冶炼方法与活动性的对应：Ca、Na、Mg、Al 用电解法；Zn、Fe、Sn、Pb、Cu 用热还原法；Hg、Ag、Pt、Au 用热分解法或物理方法。\n钾的沸点低于钠，据此可用钠置换出钾：KCl + Na →高温 K↑ + NaCl。\n合金的熔点一般比各成分金属低，硬度和强度一般比各成分金属大。",
        "「活泼金属一定用电解法」要按活动性顺序逐段核对，钾是靠沸点差异置换出来的、不是电解；合金性能的两条方向别写反。",
        "金属材料和金属的冶炼易错点"),
    _material(
        "wusan-chem-b1c3-metal-preparation", "METHOD_MODEL",
        "金属及其化合物的制备路径选择",
        "活泼金属电解熔融盐、较活泼金属热还原、不活泼金属热分解；化合物制备则按转化关系选择试剂与条件。",
        "要求写出冶炼方程式、或选择某金属/化合物制备方法的题。",
        "电解法（活泼金属）：2NaCl(熔融) →电解 2Na + Cl₂↑；MgCl₂(熔融) →电解 Mg + Cl₂↑；2Al₂O₃(熔融) →冰晶石,电解 4Al + 3O₂↑。\n热还原法（较活泼金属）：Fe₂O₃ + 3CO →高温 2Fe + 3CO₂；2Al + Cr₂O₃ →高温 2Cr + Al₂O₃。\n热分解法（不活泼金属）：2HgO →Δ 2Hg + O₂↑；2Ag₂O →Δ 4Ag + O₂↑。\n化合物制备举例：从海水中提取镁（贝壳煅烧制石灰乳 → 沉淀 Mg(OH)₂ → 盐酸酸化得 MgCl₂·6H₂O → 脱水得 MgCl₂ → 电解得 Mg，HCl 循环使用）。",
        "电解的对象必须是熔融物：电解 MgCl₂ 溶液得不到 Mg；MgCl₂·6H₂O 脱水要在 HCl 气氛中进行，防止 MgCl₂ 水解。",
        "金属及其化合物的制备"),
    _material(
        "wusan-chem-b1c3-magnesium-chemistry", "CONCEPT_EXPLANATION",
        "镁的化学性质",
        "镁能与氮气、二氧化碳、氧气和沸水反应，在 CO₂ 中燃烧生成 MgO 和 C，与沸水反应放出氢气。",
        "判断镁与非金属、水、二氧化碳反应的产物、或解释镁着火不能用的灭火剂的题。",
        "与 N₂ 点燃生成 Mg₃N₂；与 CO₂ 点燃生成 MgO + C；与 O₂ 点燃生成 MgO；与煮沸的水反应生成 Mg(OH)₂ + H₂。\n物理性质：银白色、有金属光泽的固体，密度、硬度较小，导电性、导热性、延展性良好；用于信号弹和焰火，镁合金用于制造航天部件。",
        "镁能在 CO₂ 中继续燃烧，所以镁着火不能用 CO₂ 灭火；与冷水反应很弱、与沸水才明显反应——条件和产物要一起记。",
        "镁的化学性质"),
    _material(
        "wusan-chem-b1c3-magnesium-from-seawater", "METHOD_MODEL",
        "从海水中提取金属镁的流程",
        "贝壳煅烧制石灰乳 → 沉淀出 Mg(OH)₂ → 盐酸酸化得 MgCl₂·6H₂O → 脱水得 MgCl₂ → 电解熔融 MgCl₂ 得 Mg。",
        "书写海水提镁各步的方程式、或判断题给流程正误的题。",
        "制石灰乳与沉淀：贝壳 →煅烧 CaO →水 石灰乳，海水引入沉淀池后过滤得 Mg(OH)₂。\n酸化浓缩：Mg(OH)₂ →盐酸 MgCl₂·6H₂O。\n脱水：MgCl₂·6H₂O →脱水 MgCl₂（应在 HCl 气氛中进行，防止 MgCl₂ 水解）。\n电解：MgCl₂ →电解 Mg + Cl₂，HCl 循环使用。",
        "两步最容易错：脱水必须在 HCl(g) 氛围中；制镁必须电解熔融 MgCl₂，电解其溶液得不到镁。",
        "海水提取镁"),
    _material(
        "wusan-chem-b1c3-magnesium-hard-points", "MISCONCEPTION_GUIDE",
        "镁及其化合物的难点释疑",
        "镁在 CO₂ 中燃烧、与沸水反应、MgO 是碱性氧化物、Mg(OH)₂ 溶解度小于 MgCO₃——四条都容易判错。",
        "判断镁及其化合物性质表述正误、或解释实验现象的题。",
        "镁与 CO₂ 点燃反应生成 MgO 和 C——说明燃烧不一定需要氧气，也说明镁着火不能用 CO₂ 灭火。\n镁与沸水反应生成 Mg(OH)₂ 和 H₂，而铝在常温下因致密氧化膜钝化、不与水反应。\nMgO 是白色粉末、熔点高，可作耐高温耐火材料，属于碱性氧化物。\nMg(OH)₂ 是白色固体、难溶于水，溶解度小于碳酸镁，属于中强碱。",
        "MgO 与 Mg(OH)₂ 的溶解性和酸碱性是两条独立性质，别混；「Mg(OH)₂ 溶解度小于 MgCO₃」是海水提镁能用石灰乳沉淀镁离子的依据。",
        "镁及其化合物的难点释疑"),
    _material(
        "wusan-chem-b1c3-aluminium-physical", "CONCEPT_EXPLANATION",
        "铝的物理性质",
        "铝是银白色、有金属光泽的固体，密度与硬度较小，导电性、导热性、延展性良好。",
        "比较金属物理性质、或回答铝的用途依据的题。",
        "物理性质：银白色、有金属光泽的固体，密度、硬度较小，导电性、导热性、延展性良好。\n用途：制作导线；铝合金用于制造汽车、飞机、生活用品等。",
        "「密度小」与「硬度小」是并列的两条，不能合成一句「质地较硬」；铝的用途正是靠密度小、导电性好这两条。",
        "铝的物理性质"),
    _material(
        "wusan-chem-b1c3-aluminium-preparation", "CONCEPT_EXPLANATION",
        "铝的制备与用途",
        "工业上电解熔融氧化铝制铝：2Al₂O₃(熔融) →冰晶石,电解 4Al + 3O₂↑；铝用于导线与铝合金制品。",
        "书写铝的冶炼方程式、或判断铝的用途依据的题。",
        "制备：2Al₂O₃(熔融) →冰晶石,电解 4Al + 3O₂↑（冰晶石作助熔剂）。\n用途：制作导线；铝合金用于制造汽车、飞机、生活用品等。\n化学性质要点：常温下与 O₂ 反应在表面生成致密的 Al₂O₃ 氧化膜；遇冷的浓硝酸、浓硫酸发生钝化。",
        "电解的是熔融 Al₂O₃ 而不是 AlCl₃——写冶炼方程式时别换成氯化铝。",
        "铝的制备和用途"),
    _material(
        "wusan-chem-b1c3-aluminium-compounds", "CONCEPT_EXPLANATION",
        "铝及其化合物的性质与应用",
        "Al、Al₂O₃、Al(OH)₃ 都能与强酸和强碱反应，Al₂O₃ 是两性氧化物、Al(OH)₃ 是两性氢氧化物。",
        "书写铝及其化合物与酸碱反应的方程式、或判断两性表现的题。",
        "Al：与强碱反应生成 [Al(OH)₄]⁻ 和 H₂，与非氧化性酸反应生成 Al³⁺ 和 H₂，与 Fe₂O₃ 高温下发生铝热反应生成 Al₂O₃ 和 Fe。\nAl₂O₃（两性氧化物）：Al₂O₃ + 6H⁺ = 2Al³⁺ + 3H₂O；Al₂O₃ + 2OH⁻ + 3H₂O = 2[Al(OH)₄]⁻；熔融电解可制铝。\nAl(OH)₃（两性氢氧化物）：Al(OH)₃ + 3H⁺ = Al³⁺ + 3H₂O；Al(OH)₃ + OH⁻ = [Al(OH)₄]⁻；不溶于水，可用于制药（治疗胃酸过多）。",
        "两性的是 Al₂O₃ 与 Al(OH)₃，铝单质与强碱反应另有氢气生成——写方程式时别把「两性」和「与碱反应放氢」混成一件事。",
        "铝及其化合物之间的性质与应用"),
    _material(
        "wusan-chem-b1c3-aluminate-conversion", "METHOD_MODEL",
        "四羟基合铝酸钠的转化与含铝物质的制备",
        "Na[Al(OH)₄] 与少量或足量盐酸、足量 CO₂、AlCl₃ 反应分别得到不同产物，是含铝物质制备的关键分岔。",
        "书写含铝物质相互转化的方程式、或判断题给用量下产物的题。",
        "与少量盐酸：[Al(OH)₄]⁻ + H⁺ = Al(OH)₃↓ + H₂O；与足量盐酸：[Al(OH)₄]⁻ + 4H⁺ = Al³⁺ + 4H₂O。\n与足量 CO₂：[Al(OH)₄]⁻ + CO₂ = Al(OH)₃↓ + HCO₃⁻。\n与 AlCl₃ 溶液：3[Al(OH)₄]⁻ + Al³⁺ = 4Al(OH)₃↓。",
        "盐酸用量的「少量」与「足量」给出不同产物——这是本节点唯一的分岔点，答题必须先看用量再写方程式。",
        "含铝物质的制备和转化"),
    _material(
        "wusan-chem-b1c3-aluminium-pitfall", "MISCONCEPTION_GUIDE",
        "铝及其化合物的易错点",
        "铝遇冷浓硝酸浓硫酸钝化但不代表不反应，Al(OH)₃ 溶于强碱而非氨水，[Al(OH)₄]⁻ 的产物随酸用量变。",
        "判断铝及其化合物性质表述正误的题。",
        "钝化：铝遇冷的浓硝酸、浓硫酸发生钝化，所以可用铝槽车运输浓硝酸、浓硫酸。\n两性：Al₂O₃ 与 Al(OH)₃ 都能与强酸、强碱反应，离子方程式分别是 Al₂O₃ + 2OH⁻ + 3H₂O = 2[Al(OH)₄]⁻、Al(OH)₃ + OH⁻ = [Al(OH)₄]⁻。\n用量：与少量盐酸生成 Al(OH)₃，与足量盐酸生成 Al³⁺。",
        "Al(OH)₃ 只溶于强酸强碱——弱碱（氨水）不溶，这是实验室制 Al(OH)₃ 时用氨水而不用 NaOH 的原因。",
        "铝及其化合物的易错点"),
    _material(
        "wusan-chem-b1c3-aluminium-triangle", "CONCEPT_EXPLANATION",
        "铝三角：Al³⁺、Al(OH)₃、[Al(OH)₄]⁻ 的相互转化",
        "Al³⁺ 加碱生成 Al(OH)₃、碱过量时继续转化为 [Al(OH)₄]⁻；反向加酸则依次变回。",
        "判断加碱或加酸过程中沉淀的生成与溶解、或书写相关离子方程式的题。",
        "Al³⁺ → Al(OH)₃：加适量强碱或氨水，Al³⁺ + 3OH⁻ = Al(OH)₃↓（与氨水反应同样生成沉淀）。\nAl(OH)₃ → [Al(OH)₄]⁻：继续加强碱，Al(OH)₃ + OH⁻ = [Al(OH)₄]⁻。\n[Al(OH)₄]⁻ → Al(OH)₃：通入足量 CO₂ 或加少量盐酸，[Al(OH)₄]⁻ + CO₂ = Al(OH)₃↓ + HCO₃⁻。\nAl(OH)₃ → Al³⁺：加足量强酸，Al(OH)₃ + 3H⁺ = Al³⁺ + 3H₂O。",
        "「铝三角」的每一步都取决于试剂用量：同一试剂少量与过量给出不同产物，做题要先定用量再定产物。",
        "al3-al-oh-3-alo-al-oh-4-之间的转化"),
    _material(
        "wusan-chem-b1c3-copper-salt", "CONCEPT_EXPLANATION",
        "铜盐与含铜化合物的转化",
        "CuO 与硫酸反应得 CuSO₄；CuSO₄ 遇水生成胆矾、受热又脱水；无水 CuSO₄ 遇水变蓝可检验水。",
        "判断含铜物质之间的转化、或解释无水硫酸铜检验水的题。",
        "CuO 与硫酸反应得到 CuSO₄；CuSO₄ 与 CuSO₄·5H₂O（胆矾）可相互转化——CuSO₄ 遇水生成 CuSO₄·5H₂O，CuSO₄·5H₂O 受热脱水又得到无水 CuSO₄。\nCu₂(OH)₂CO₃（铜绿）受热分解得到 CuO；CuO 与 Cu(OH)₂ 之间可相互转化，Cu(OH)₂ 受热分解可制得 Cu₂O。\n无水 CuSO₄ 遇水变蓝色，可用于检验水。",
        "检验水要用「无水」硫酸铜——蓝色的是五水合物；两者受热与水合的方向相反，答题时把方向和条件写全。",
        "铜盐"),
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
