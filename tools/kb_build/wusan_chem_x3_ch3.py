# -*- coding: utf-8 -*-
"""五三精讲册化学 p0118–p0137（内容页 115–134）「烃的衍生物 + 有机合成」→ 材料。

正文严格取自转录件：`knowledge-research/candidates/wusan/chemistry/transcript/`。
不使用记忆补内容。**刻意不写**的五个节点，各有理由：

- `醚的结构与性质`：醚的官能团出现在 p0113（属第二章 烃的页），本页组没有醚的小节。
- `油脂`：五三放在 p0127（第十二章 第1节 生命活动的物质基础 / 二、油脂），
  该页路由到第四章；按「材料只绑本章页所覆盖的节点」的口径，留给那一章。
- `醛的应用和对环境、健康产生的影响`：全目录 grep「甲醛」无危害/应用叙述
  （命中的都是氢键、空间构型、电化学等其他主题）。
- `(1)现有以下物质…`：题干残片，是抽取事故，应走改名/删除而不是给材料。
- `惕各酸及其酯类在香精配方中有广泛用途`：整句当名字，且全目录无「惕各酸」。
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 化学 精讲册》P115-P134"
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
    # ── 第4节 醛和酮 羧酸及其衍生物（p0120–p0122）────────────────────────
    _material(
        "wusan-chem-x3c3-aldehyde", "CONCEPT_EXPLANATION",
        "醛的组成与醛基",
        "醛是由烃基或氢原子与醛基相连构成的化合物，官能团是醛基，可写作 RCHO，甲醛是最简单的醛。",
        "判断某有机物是否属于醛，或写出醛的结构简式与官能团的题。",
        "醛：由烃基或氢原子与醛基相连而构成的化合物，官能团为醛基（—CHO），可表示为 RCHO。\n饱和一元醛的通式为 CₙH₂ₙO（n≥1），甲醛是最简单的醛。",
        "醛基中的羰基碳一端连着氢——这是醛区别于酮的唯一结构特征；只看到 C=O 就判成醛会出错。",
        "醛"),
    _material(
        "wusan-chem-x3c3-ketone", "CONCEPT_EXPLANATION",
        "酮的组成与酮羰基",
        "酮由酮羰基与两个烃基相连构成，官能团是酮羰基，最简单的酮是丙酮 CH₃COCH₃。",
        "判断某有机物是否属于酮，或比较醛与酮结构的题。",
        "酮：由酮羰基与两个烃基相连而构成的化合物，官能团是酮羰基（—CO—，即 C 与 O 双键相连、两端各连一个烃基）。\n最简单的酮是丙酮，结构简式为 CH₃—CO—CH₃。",
        "酮羰基两端都是烃基：只要有一端连氢，它就不是酮而是醛；判断类别先看羰基碳两端连的是什么。",
        "酮"),
    _material(
        "wusan-chem-x3c3-aldehyde-ketone-concept", "CONCEPT_EXPLANATION",
        "醛与酮的结构区别",
        "醛与酮都含羰基，区别只在羰基碳上是否连氢：连氢的是醛基，两端都连烃基的是酮羰基。",
        "给出一个含羰基的有机物、要求判断它属于醛还是酮的题。",
        "醛的羰基碳一端连烃基、另一端连氢，写作 —CHO，可表示为 RCHO；酮的羰基碳两端都连烃基，写作 —CO—。\n两者的官能团分别是醛基与酮羰基，甲醛是最简单的醛，丙酮是最简单的酮。",
        "羰基不等于醛基：判断类别只看羰基碳上有没有氢，「含羰基」这一条不足以判成醛。",
        "醛-酮的概念"),
    _material(
        "wusan-chem-x3c3-aldehyde-ketone-formula", "CONCEPT_EXPLANATION",
        "饱和一元醛的通式与醛酮的组成",
        "饱和一元醛的通式是 CₙH₂ₙO（n≥1）；酮由酮羰基连两个烃基构成，通式同样按一个羰基计算。",
        "写同分异构体、或由分子式判断某物质能否是醛或酮的题。",
        "饱和一元醛的通式为 CₙH₂ₙO（n≥1），官能团为醛基（—CHO），可表示为 RCHO，甲醛即 n=1 的情形。\n酮由酮羰基（C=O）与两个烃基相连构成，官能团是酮羰基，最简单的酮是丙酮 CH₃COCH₃。",
        "通式 CₙH₂ₙO 只覆盖饱和一元醛（n≥1）；写同分异构体时先确认碳数满足 n≥1，再按醛基与酮羰基两类分别数。",
        "饱和一元醛-酮的通式"),
    _material(
        "wusan-chem-x3c3-important-aldehydes-ketones", "CONCEPT_EXPLANATION",
        "甲醛、乙醛与丙酮",
        "甲醛是最简单的醛，乙醛是醛类化学性质的代表物，丙酮是最简单的酮。",
        "指出醛酮代表物、或判断某代表物能发生哪些反应的题。",
        "甲醛是最简单的醛。\n乙醛是醛的化学性质的代表物：可发生银镜反应、与新制 Cu(OH)₂ 反应、催化氧化，也能与 H₂、HCN 加成。\n丙酮是最简单的酮，结构简式为 CH₃COCH₃，能与 H₂、HCN、氨及氨的衍生物、醇类发生加成反应。",
        "三个代表物角色不同：甲醛最简单、乙醛作性质代表、丙酮属于酮——把丙酮按醛的性质去套会错。",
        "几种重要的醛-酮"),
    _material(
        "wusan-chem-x3c3-aldehyde-ketone-chemistry", "CONCEPT_EXPLANATION",
        "醛的氧化与加成反应",
        "醛可发生银镜反应、与新制氢氧化铜反应、催化氧化和被强氧化剂氧化，也能与氢气、HCN 加成。",
        "判断某醛能发生哪些反应、或书写银镜反应与新制氢氧化铜反应的题。",
        "氧化反应：银镜反应 CH₃CHO + 2[Ag(NH₃)₂]OH →Δ CH₃COONH₄ + 2Ag↓ + 3NH₃ + H₂O；与新制 Cu(OH)₂ 反应 CH₃CHO + 2Cu(OH)₂ + NaOH →Δ CH₃COONa + Cu₂O↓ + 3H₂O；催化氧化 2CH₃CHO + O₂ →Δ 2CH₃COOH；乙醛还可被酸性 KMnO₄ 溶液或溴水氧化生成乙酸。\n加成反应：催化加氢 CH₃CHO + H₂ →催化剂,Δ CH₃CH₂OH；与 HCN 加成生成 CH₃CH(OH)CN。",
        "配新制 Cu(OH)₂ 时 NaOH 应过量——这是该反应的硬条件，不能按 CuSO₄ 与 NaOH 恰好反应去配。",
        "醛-酮的化学性质"),
    _material(
        "wusan-chem-x3c3-aldehyde-ketone-structure-property", "CONCEPT_EXPLANATION",
        "醛酮羰基的结构与加成反应",
        "醛和酮的官能团都是羰基，羰基碳带部分正电荷所以都能发生加成；酮以丙酮为例能与氢、HCN、胺、醇加成。",
        "由结构推断醛酮能发生哪类反应、或书写丙酮加成产物的题。",
        "醛、酮的官能团都是羰基（C=O），羰基碳带部分正电荷，因而都能与 H₂、HCN、氨及氨的衍生物、醇类发生加成反应。\n丙酮的加成产物：与 H₂ 加成得 CH₃CH(OH)CH₃；与 HCN 加成得 CH₃C(OH)(CH₃)CN；与 RNH₂ 加成得 CH₃C(OH)(CH₃)NHR。\n醛的羰基碳上连氢，因此还能发生银镜反应、与新制 Cu(OH)₂ 反应、催化氧化等氧化反应。",
        "加成是醛酮共有的，氧化（银镜、新制氢氧化铜）只由醛基提供——用这两条区分醛与酮。",
        "醛和酮的结构和性质"),
    _material(
        "wusan-chem-x3c3-aldehyde-ketone-pitfall", "MISCONCEPTION_GUIDE",
        "醛基与碳碳双键共存时的检验顺序",
        "分子中同时有醛基和碳碳双键时，必须先用弱氧化剂氧化醛基、酸化后再检验碳碳双键。",
        "给出含醛基与碳碳双键的有机物、要求设计检验碳碳双键的题。",
        "当有机物分子中同时存在醛基和碳碳双键时，要检验碳碳双键，应先用弱氧化剂（银氨溶液或新制 Cu(OH)₂）把醛基氧化，酸化后再用酸性 KMnO₄ 溶液或溴水检验碳碳双键。\n直接用酸性 KMnO₄ 溶液或溴水会同时氧化醛基，得不出「有碳碳双键」的结论。",
        "三步顺序不能颠倒：氧化醛基 → 酸化 → 检验碳碳双键；酸化这一步不能省，否则前一步残留的碱性试剂会干扰后面的检验。",
        "醛和酮的结构和性质易混易错点"),
    _material(
        "wusan-chem-x3c3-aldehyde-quantitative", "METHOD_MODEL",
        "由银镜与氢氧化铜的定量关系推官能团数目",
        "1 mol 醛基对应 2 mol Ag 或 1 mol Cu₂O，由此可由产物量反推醛基的物质的量。",
        "给出银镜反应或新制氢氧化铜反应的产物质量、要求推分子中醛基数目的题。",
        "银镜反应：1 mol —CHO 对应 2 mol Ag。\n醛基与新制 Cu(OH)₂ 反应：1 mol —CHO 对应 1 mol Cu₂O。\n由生成物的物质的量除以对应系数即得 —CHO 的物质的量，再与有机物的物质的量相比得到官能团数目。",
        "两个系数不同——Ag 是 2、Cu₂O 是 1，代错系数是这类计算最常见的失分点；烷基与碳酸钠、碳酸氢钠的系数也各不相同（2 mol —COOH 对 1 mol CO₂、1 mol —COOH 对 1 mol CO₂）。",
        "有关醛的定量计算"),
    _material(
        "wusan-chem-x3c3-formaldehyde-copper-ratio", "CONCEPT_EXPLANATION",
        "甲醛与氢氧化铜、银氨溶液的定量关系",
        "1 个甲醛分子中相当于有 2 个醛基，所以它的耗量与生成量都按两个醛基计算。",
        "甲醛参与银镜反应或与新制氢氧化铜反应、要求计算耗量与产物量的题。",
        "1 个 HCHO 分子中相当于有 2 个 —CHO，因此 1 mol 甲醛相当于 4 mol Cu(OH)₂、生成 2 mol Cu₂O（每个 —CHO 对应 1 mol Cu₂O），银镜反应则生成 4 mol Ag。",
        "「相当于 2 个醛基」是甲醛独有的结构原因（两个氢都连在羰基碳上），别的醛只有一个醛基——把这条套到乙醛上会把耗量算成两倍。",
        "mol-hcho-4-mol-cu-oh-2-2-mol-cu2o"),
    _material(
        "wusan-chem-x3c3-aldehyde-ketone-synthesis", "METHOD_MODEL",
        "醛、酮在有机合成中增长碳链的三种反应",
        "醛酮与 HCN 加成得氰醇再转化、羟醛缩合得 β-羟基醛并脱水、与格氏试剂加成水解得醇。",
        "要求设计增长碳链的合成路线、或由产物逆推醛酮原料的题。",
        "与 HCN 加成：CH₃CHO + HCN →催化剂 CH₃CH(OH)CN；氰醇水解得 α-羟基酸，催化加氢得氨基醇。\n羟醛缩合：有 α-H 的醛在稀碱溶液中与另一分子醛加成生成 β-羟基醛，受热脱水得 α,β-不饱和醛，如 CH₃CH(OH)CH₂CHO →Δ,−H₂O CH₃CH=CHCHO。\n格氏试剂：醛或酮与 R'MgX 加成，所得产物水解后得到醇。",
        "羟醛缩合按页面表述只有含 α-H 的醛才能发生——判断某醛能否自身缩合，先看它有没有 α-H。",
        "醛-酮在有机合成中的应用"),

    # ── 羧酸与羧酸衍生物（p0121–p0122）────────────────────────────────
    _material(
        "wusan-chem-x3c3-carboxylic-acid", "CONCEPT_EXPLANATION",
        "羧酸的组成与羧基",
        "羧酸由烃基或氢原子与羧基相连构成，官能团是羧基；饱和一元羧酸的通式为 CₙH₂ₙO₂。",
        "判断某有机物是否属于羧酸，或写出羧酸通式与官能团的题。",
        "羧酸：由烃基或氢原子与羧基相连构成的有机化合物，官能团为羧基（—COOH）。\n饱和一元羧酸的通式为 CₙH₂ₙO₂。",
        "羧基是弱酸基团，页面写明的是「弱酸，具有酸的通性」；它与碳酸氢钠反应放出 CO₂ 是区别于酚羟基的特征。",
        "羧酸"),
    _material(
        "wusan-chem-x3c3-carboxylic-acid-chemistry", "CONCEPT_EXPLANATION",
        "羧酸的酸性、取代与还原反应",
        "羧酸有酸的通性并放出 CO₂，能酯化、生成酰胺、发生 α-H 取代，也能被氢化铝锂还原成醇。",
        "书写羧酸参与反应的方程式、或判断反应类型的题。",
        "弱酸、具有酸的通性：RCOOH + NaHCO₃ → RCOONa + CO₂↑ + H₂O。\n取代反应：酯化 R—COOH + R'OH ⇌浓硫酸,Δ R—COOR' + H₂O（酸脱羟基醇脱氢）；与氨生成酰胺 R—COOH + NH₃ →Δ R—CONH₂ + H₂O；α-H 取代 RCH₂COOH + Cl₂ →催化剂,Δ RCHClCOOH + HCl（羧酸分子中的 α-H 较活泼，易被取代）。\n还原反应：R—COOH →LiAlH₄ R—CH₂OH。",
        "酯化是「酸脱羟基、醇脱氢」——把醇的羟基脱掉会写错产物中氧的来源；α-H 取代发生在与羧基相邻的碳上，不在羧基上。",
        "羧酸的化学性质"),
    _material(
        "wusan-chem-x3c3-carboxylic-acid-structure-property", "CONCEPT_EXPLANATION",
        "羧基的结构与氢原子活泼性比较",
        "羧基由羰基与羟基相互影响，使它的氢比醇羟基、酚羟基都活泼；甲酸、苯甲酸、草酸各有专门用途。",
        "比较醇羟基、酚羟基、羧基的活泼性与反应差异的题。",
        "氢原子活泼性顺序为醇羟基 < 酚羟基 < 羧基：与 NaOH 反应时酚羟基与羧基反应而醇羟基不反应；与 Na₂CO₃ 反应时酚羟基生成酚钠和 NaHCO₃、羧基生成羧酸钠并放出 CO₂；与 NaHCO₃ 反应只有羧基反应。\n用途：甲酸可作还原剂并用于合成医药、农药和染料；苯甲酸的钠盐是常用食品防腐剂；乙二酸（草酸）是分析化学中的还原剂。",
        "比较三种羟基要用「与 Na、NaOH、Na₂CO₃、NaHCO₃ 反应」四栏逐个核对——只凭酸碱性排序会在 NaHCO₃ 那一栏把酚羟基判错。",
        "羧酸的结构和性质"),
    _material(
        "wusan-chem-x3c3-acid-ester-pitfall", "MISCONCEPTION_GUIDE",
        "酚酯的水解耗碱量",
        "1 mol 酚酯在 NaOH 溶液中水解要消耗 2 mol NaOH，比醇酯多一倍。",
        "计算酯类在氢氧化钠溶液中水解耗碱量、或判断题给方程的题。",
        "1 mol 酚酯 C₆H₅O—COR 在 NaOH 溶液中水解会消耗 2 mol NaOH：酯基水解生成酚羟基与羧酸钠，酚羟基还要再消耗 1 mol NaOH 生成酚钠。\n酯化反应中「酸脱羟基醇脱氢」，与酯水解的断键位置一致——碱性水解生成羧酸钠和醇（酚）。",
        "酚酯按 2 mol 计碱、醇酯按 1 mol 计碱；一律按 1 mol 计算是这类题最常见的失分点。",
        "羧酸和酯的结构和性质易混易错点"),
    _material(
        "wusan-chem-x3c3-ethyl-acetate-preparation", "METHOD_MODEL",
        "乙酸乙酯的制备原理与装置",
        "乙酸与乙醇在浓硫酸催化下可逆生成乙酸乙酯，装置为斜向上的试管加热、导管通入饱和碳酸钠溶液的液面上方。",
        "回答乙酸乙酯制备的原理、装置要点与收集方式的题。",
        "原理：CH₃COOH + HO—C₂H₅ ⇌浓硫酸,Δ CH₃COOC₂H₅ + H₂O；反应可逆，浓硫酸作催化剂和吸水剂。\n装置：试管斜向上固定在铁架台上，用酒精灯加热，导气管通入盛有饱和 Na₂CO₃ 溶液的试管中，导管口在液面上方。",
        "装置有两处硬要点：试管斜向上（不是垂直或向下倾斜）、导管口在液面之上——后者是为了防倒吸。",
        "实验探究乙酸乙酯的制备"),
    _material(
        "wusan-chem-x3c3-esterification-experiment-notes", "METHOD_MODEL",
        "酯化实验的加料顺序与饱和碳酸钠的作用",
        "试剂按乙醇、浓硫酸、冰醋酸顺序加入；饱和碳酸钠溶液用于吸收乙醇、中和乙酸、降低酯的溶解度。",
        "判断酯化实验操作正误、或回答饱和碳酸钠溶液作用的题。",
        "试剂加入顺序为乙醇、浓硫酸和冰醋酸（乙酸），不能先加浓硫酸。\n导气管不能插入饱和 Na₂CO₃ 溶液中，防止倒吸。\n饱和 Na₂CO₃ 溶液的作用：吸收乙醇、中和乙酸、降低乙酸乙酯的溶解度。",
        "三条注意各管一件事：顺序管安全、导管位置管倒吸、饱和碳酸钠管产物分离——答题时逐条对应，不要混成一条。",
        "酯化反应实验探究"),
    _material(
        "wusan-chem-x3c3-amide", "CONCEPT_EXPLANATION",
        "酰胺的定义与水解",
        "酰胺是羧酸分子中羟基被氨基替代的产物，官能团是酰胺基，通常难水解，强酸或强碱加热下可水解。",
        "判断某含氮有机物是否属于酰胺、或书写酰胺水解方程式的题。",
        "酰胺：羧酸分子中羟基被氨基所替代得到的化合物，官能团是酰胺基（—CONH—）。\n通常情况下难水解，在强酸或强碱存在下加热可水解：RCONH₂ + H₂O + H⁺ →Δ RCOOH + NH₄⁺；RCONH₂ + NaOH →Δ RCOONa + NH₃↑。",
        "水解条件必须写「强酸或强碱并加热」——酰胺不溶于水也不在水中自发水解，漏掉条件等于换了反应。",
        "酰胺"),
    _material(
        "wusan-chem-x3c3-amine-amide-structure", "CONCEPT_EXPLANATION",
        "胺的组成与碱性",
        "胺是氨分子中氢被烃基取代的产物，官能团是氨基，显碱性能与盐酸、醋酸等成盐。",
        "判断某含氮有机物是否属于胺、或书写胺与酸反应方程式的题。",
        "胺：氨分子中氢原子被烃基取代后的有机化合物称为胺，一般可写作 R—NH₂，官能团是氨基（—NH₂）。\n胺类化合物与 NH₃ 类似，具有碱性，能与盐酸、醋酸等反应，如 C₆H₅NH₂ + HCl → C₆H₅NH₃Cl。",
        "胺类化合物属于烃的衍生物，不属于羧酸衍生物——归类时要按「氨基是否替代了羧基上的羟基」判断。",
        "胺-酰的结构和性质"),
    _material(
        "wusan-chem-x3c3-amine-amide-pitfall", "MISCONCEPTION_GUIDE",
        "胺与酰胺的归类与性质区分",
        "酰胺是羧酸衍生物、胺属于烃的衍生物但不属于羧酸衍生物；两者官能团与反应都不同。",
        "判断含氮有机物类别、或区分胺与酰胺性质的题。",
        "酰胺是羧酸分子中羟基被氨基替代得到的化合物，属羧酸衍生物，官能团是酰胺基（—CONH—），在强酸或强碱加热下才水解。\n胺是氨分子中氢被烃基取代得到的化合物，官能团是氨基（—NH₂），显碱性能与酸成盐；胺类化合物属于烃的衍生物，不属于羧酸衍生物。",
        "「含氮」不等于「酰胺」：判断是否属于羧酸衍生物，要看它是不是羧基上的羟基被取代得到的。",
        "胺和酰胺的结构和性质易混易错点"),

    # ── 有机合成（p0131–p0136）────────────────────────────────────────
    _material(
        "wusan-chem-x3c3-organic-synthesis", "METHOD_MODEL",
        "有机合成中的碳骨架构建、官能团衍变与路线设计",
        "碳骨架可增可减可成环，官能团可按需引入、消除与保护；路线设计有正合成与逆合成两种分析法。",
        "设计有机合成路线、或由反应条件推断反应类型的题。",
        "碳骨架：增长（加聚、缩聚、酯化、醛酮与 HCN 加成、羟醛缩合）、缩短（裂化裂解、水解、羧酸盐脱羧、烯烃炔烃被酸性 KMnO₄ 氧化、芳香烃侧链氧化）、成环（分子内/间缩合得环酯、环肽、环醚，乙炔聚合成苯，双烯合成）。\n官能团衍变：烃 ⇌ 卤代烃 ⇌ 醇 ⇌ 醛 → 羧酸 ⇌ 酯；数目与位置也可变，如 CH₃CH₂Cl → CH₂=CH₂ → ClCH₂CH₂Cl、CH₃CH₂CH₂CH₂Br → CH₃CH₂CH=CH₂ → CH₃CH₂CHBrCH₃。\n保护：酚羟基先与 CH₃I 或 (CH₃)₂SO₄ 反应变成 —OCH₃，氧化后再用 HI 变回；碳碳双键先用 HCl 或 HBr 加成保护，氧化后再消去复原；氨基先用乙酸酐变成酰胺基，最后水解复原。\n路线设计：正合成分析法（原料 → 中间体 → 产品）与逆合成分析法（产品 → 中间体 → 原料）。",
        "官能团的保护要成对记住「先保护、再氧化、最后复原」三步，缺一步都得不到目标产物；酚羟基与氨基都易被氧化，是最常需要保护的两个基团。",
        "有机合成"),
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
