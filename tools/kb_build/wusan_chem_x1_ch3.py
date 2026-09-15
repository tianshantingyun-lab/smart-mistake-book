# -*- coding: utf-8 -*-
"""把五三精讲册化学 p0098–p0112（内容页 95–109）的「水溶液中的离子反应与平衡」炼成材料。

正文严格取自转录件（`knowledge-research/candidates/wusan/chemistry/transcript/`），
不使用记忆补内容：凡是那十五页没写的就不写。已实测**没有**出处、因而**刻意不写**的节点：

- `水的电离易错「七」点`／`酸碱中和滴定「八」个提醒`／`酸、碱稀释时的两个误区`／
  `沉淀溶解平衡易错六点`：这十五条转录件里没有「易错」「误区」「提醒」小节
  （全目录 grep 只命中 p0110 的「易错警示」一处，讲的是分布系数图像的电荷守恒）。
- `水电离出的 c(H⁺) 或 c(OH⁻) 的计算` 及其两个配套节点：p0100 只给了「加酸/加碱/
  加盐时 c(H⁺)、c(OH⁻) 往哪个方向变」的定性表，没有写「溶质为酸（碱）的溶液里
  OH⁻（H⁺）全部来自水的电离」这条计算规则。

生成器只管**形式**：键顺序、正文行数、ASCII 引号、绑定目标是否存在。

  用法： PYTHONPATH=tools python tools/kb_build/wusan_chem_x1_ch3.py
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 化学 精讲册》P95-P109"
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
    # ── 第1节 弱电解质的电离平衡（p0098–p0099）────────────────────────────
    _material(
        "wusan-chem-x1c3-weak-electrolyte-ionization", "CONCEPT_EXPLANATION",
        "弱电解质的电离方程式与电离特点",
        "弱酸、弱碱、水与极少数盐在水中只有一部分分子电离，方程式用可逆符号，多元弱酸分步写、多元弱碱一步写。",
        "书写弱电解质的电离方程式，或判断某物质属于强电解质还是弱电解质的题。",
        "特点：弱酸、弱碱、水、极少数盐属于弱电解质，在水中不完全电离，溶液里弱电解质分子与电离出的阴、阳离子共存。\n方程式：用可逆符号；多元弱酸分步电离，多元弱碱一步写到位。\n导电能力：由自由移动离子的浓度与离子所带电荷数决定，浓度越大、电荷数越多，导电能力越强。",
        "弱电解质溶液里分子与离子共存，写粒子数目或浓度时不能按完全电离计算；导电能力弱与电解质强弱没有必然关系，BaSO₄ 难溶却是强电解质。",
        "弱电解质的电离"),
    _material(
        "wusan-chem-x1c3-ionization-equilibrium", "CONCEPT_EXPLANATION",
        "外界条件对电离平衡的影响",
        "升温、稀释、加能与电离产物反应的物质都使电离平衡右移，同离子效应使其左移，但平衡右移时电离程度不一定增大。",
        "改变温度、浓度或加入试剂后，判断电离平衡移动方向、电离程度与离子浓度如何变化的题。",
        "温度：电离是吸热过程，升温平衡右移、电离度增大；加水稀释右移、电离度增大；加入同种弱电解质使浓度增大，平衡右移但电离度减小；同离子效应使平衡左移、电离度减小；加入能与电离产物反应的物质则右移、电离度增大。\n以 CH₃COOH ⇌ CH₃COO⁻ + H⁺ 为例：加少量冰醋酸右移而电离程度减小；加少量浓盐酸或 CH₃COONa 左移；加少量 NaOH 右移。\n电解质溶液的导电能力取决于自由移动离子的浓度和离子所带电荷数。",
        "平衡移动方向与电离程度是两件事——加冰醋酸使平衡右移而电离程度减小；只看「右移」就答「电离程度增大」是这一节最常见的失分点。",
        "电离平衡"),
    _material(
        "wusan-chem-x1c3-ionization-constant", "CONCEPT_EXPLANATION",
        "电离平衡常数的表达式与应用",
        "电离常数只与温度有关，K 越大弱电解质越易电离；可用它判断酸性强弱、强酸制弱酸与粒子浓度比值的变化。",
        "比较弱酸酸性强弱、判断酸与盐能否反应，或推断稀释过程中粒子浓度比值变化的题。",
        "表达式：一元弱酸（碱）AB ⇌ A⁺ + B⁻，K_a（碱用 K_b）＝ c(A⁺)·c(B⁻)/c(AB)；只与温度有关、与浓度无关，升温 K 增大；K 越大表示越易电离。\n应用：判断弱酸相对强弱与强酸制弱酸；判断粒子浓度比值变化时可先转化成含 K 的式子——稀释氨水时 c(OH⁻)/c(NH₃·H₂O) ＝ K_b/c(NH₄⁺)，分子不变、分母减小，故比值增大。\n多元弱酸分步电离且 K₁≫K₂≫K₃，酸性主要取决于第一步电离。",
        "K 只随温度改变，稀释或加同离子都不改变 K 本身；比较酸性强弱必须在相同温度下进行。",
        "电离常数"),

    # ── 第2节 溶液的酸碱性 酸碱中和滴定（p0100–p0102）────────────────────
    _material(
        "wusan-chem-x1c3-ph-measurement", "CONCEPT_EXPLANATION",
        "溶液pH的计算与测定",
        "pH ＝ −lg c(H⁺)，适用于稀溶液、范围 0~14；pH 试纸只能读整数，不能用水润湿，也不能测有漂白性的溶液。",
        "计算溶液的 pH，或选择与操作 pH 试纸、判断测量操作正误的题。",
        "计算：pH ＝ −lg c(H⁺)，适用于稀溶液，范围 0~14；相同温度下 pH 越小酸性越强。\n混合与稀释：先判断最终溶液的酸碱性，酸性溶液先求 n(H⁺)，碱性溶液先求 n(OH⁻) 再由 Kw 换算 c(H⁺)，最后取负对数。\n测量：用玻璃棒蘸取待测液滴在干燥 pH 试纸的中央，变色后与标准比色卡对照读数；也可用 pH 计测量。",
        "试纸不能事先用水润湿；广泛 pH 试纸只读整数值或范围；氯水、次氯酸钠溶液等有漂白性的溶液不能用 pH 试纸测定。",
        "溶液的ph"),
    _material(
        "wusan-chem-x1c3-titration-curve", "CONCEPT_EXPLANATION",
        "酸碱中和滴定曲线的起点与突变范围",
        "强碱滴定强酸、弱酸时强酸的起点 pH 低；强碱与强酸的突变范围大于强碱与弱酸的突变范围。",
        "比较两条滴定曲线的起点高低与突变范围，或由曲线判断酸强弱、选择指示剂的题。",
        "起点不同：强碱滴定强酸、弱酸时强酸起点低；强酸滴定强碱、弱碱时强碱起点高。\n突变范围不同：强碱与强酸反应（强酸与强碱反应）的突变范围大于强碱与弱酸反应（强酸与弱碱反应）的突变范围。\n滴定曲线中的关键点（中和一半点、中性点、恰好中和点、过量一倍点）各对应一种确定的溶质组成。",
        "突变范围决定指示剂的可选区间——弱酸（弱碱）被滴定时突变范围窄，指示剂选错会带来较大误差。",
        "滴定曲线"),

    # ── 第3节 盐类的水解（p0103–p0104）──────────────────────────────────
    _material(
        "wusan-chem-x1c3-hydrolysis-essence", "CONCEPT_EXPLANATION",
        "盐类水解的实质",
        "盐电离出的弱酸阴离子结合 H⁺、弱碱阳离子结合 OH⁻，破坏水的电离平衡，使 c(H⁺) 与 c(OH⁻) 不再相等。",
        "解释某盐溶液为什么显酸性或碱性，或判断某种盐能否水解的题。",
        "过程：盐电离出的弱酸阴离子结合 H⁺、弱碱阳离子结合 OH⁻，从而破坏水的电离平衡，使 c(H⁺) ≠ c(OH⁻)，溶液呈酸性或碱性。\n故有弱才水解：强酸强碱盐（如 NaCl）不水解，溶液显中性。",
        "水解由「盐电离出的离子能否与 H⁺ 或 OH⁻ 结合成弱电解质」决定，与盐的溶解度、溶液浓度无关。",
        "实质"),
    _material(
        "wusan-chem-x1c3-hydrolysis-rules", "CONCEPT_EXPLANATION",
        "盐类水解的规律",
        "有弱才水解、越弱越水解，谁强显谁性、同强显中性，正盐的水解程度大于对应的酸式盐。",
        "由盐的组成判断溶液酸碱性，或比较同浓度几种盐溶液 pH 大小的题。",
        "有弱才水解、越弱越水解：酸性 HCN < CH₃COOH，则相同条件下碱性 NaCN > CH₃COONa。\n谁强显谁性、同强显中性：NH₄Cl 显酸性、CH₃COONa 显碱性；K_a(CH₃COOH) ≈ K_b(NH₃·H₂O) 时 CH₃COONH₄ 溶液接近中性，而 K_a(HCOOH) > K_b(NH₃·H₂O) 时 HCOONH₄ 显酸性。\n正盐水解程度大于对应的酸式盐：相同条件下 pH(Na₂CO₃) > pH(NaHCO₃)。",
        "酸越弱其酸根水解程度越大、溶液碱性越强——比较方向按「越弱越水解」推，别按酸的强弱方向推。",
        "规律"),
    _material(
        "wusan-chem-x1c3-hydrolysis-equation-features", "CONCEPT_EXPLANATION",
        "盐类水解反应的特点与方程式写法",
        "水解程度一般不大，方程式用可逆符号，且一般不产生沉淀和气体，因此不标「↓」与「↑」。",
        "书写盐类水解的离子方程式，或判断该用可逆号还是等号、要不要标沉淀气体的题。",
        "程度与符号：水解程度不大，用可逆符号；一般不产生沉淀和气体，故不用「↓」和「↑」，如 Cu²⁺ + 2H₂O ⇌ Cu(OH)₂ + 2H⁺、NH₄⁺ + H₂O ⇌ NH₃·H₂O + H⁺。\n多元弱酸盐分步水解，方程式分步写，如 Na₂CO₃：CO₃²⁻ + H₂O ⇌ HCO₃⁻ + OH⁻，HCO₃⁻ + H₂O ⇌ H₂CO₃ + OH⁻。\n相互促进的水解：产物均为容易脱离体系的溶解度较小的物质时水解认为完全进行，用等号并标「↑」「↓」，如 Al³⁺ + 3HCO₃⁻ ＝ Al(OH)₃↓ + 3CO₂↑；产物溶解度较大时仍用可逆号。",
        "用等号还是可逆号取决于相互促进的水解能否把产物带离体系——NH₄⁺ 与 CH₃COO⁻ 的产物溶解度较大，仍用可逆号，二者可以大量共存。",
        "特点"),

    # ── 第4节 沉淀溶解平衡（p0105–p0106）───────────────────────────────
    _material(
        "wusan-chem-x1c3-ksp-expression", "CONCEPT_EXPLANATION",
        "溶度积的表达式与溶度积规则",
        "对 AₘBₙ(s) ⇌ mAⁿ⁺ + nBᵐ⁻，Ksp 等于两种离子浓度幂之积；Q 与 Ksp 的大小关系决定沉淀生成还是溶解。",
        "由离子浓度计算 Q 并与 Ksp 比较，判断混合后是否有沉淀生成或沉淀能否溶解的题。",
        "表达式：AₘBₙ(s) ⇌ mAⁿ⁺ + nBᵐ⁻，Ksp ＝ [c(Aⁿ⁺)]ᵐ·[c(Bᵐ⁻)]ⁿ。\n溶度积规则：Q < Ksp 时溶液不饱和、无沉淀析出；Q ＝ Ksp 时沉淀与溶解处于平衡状态；Q > Ksp 时有沉淀析出，直至达到平衡。\n判断两种溶液混合时，要先按混合后的离子浓度算出 Q（混合会改变体积进而改变浓度），再与 Ksp 比较。",
        "算 Q 必须用混合后的浓度；直接拿混合前的浓度比较会得出错误结论。",
        "溶度积"),
    _material(
        "wusan-chem-x1c3-ksp-factors", "CONCEPT_EXPLANATION",
        "溶度积的影响因素",
        "溶度积只受温度影响，与浓度、所加试剂和体积都无关；同一温度下同一难溶电解质的 Ksp 相同。",
        "判断稀释、加入同离子或其他试剂后 Ksp 是否变化，以及比较曲线上各点 Ksp 大小的题。",
        "溶度积只受温度影响：加水稀释、改变离子浓度、加入其他试剂都不改变 Ksp。\n在同一曲线上的点 Ksp 相同——沉淀溶解平衡曲线上的任意一点都已达到沉淀溶解平衡，此时 Q ＝ Ksp；曲线上方的点 Q > Ksp 会析出沉淀，曲线下方的点 Q < Ksp 无沉淀析出。",
        "沉淀转化的方向要按类型分开判断：类型相同时 Ksp 较大的沉淀易转化为 Ksp 较小的；类型不同时看溶解度的差别。",
        "ksp的影响因素"),
    _material(
        "wusan-chem-x1c3-ksp-vs-q", "CONCEPT_EXPLANATION",
        "溶度积与离子积的大小关系",
        "温度不变则 Ksp 不变，改变离子浓度只改变 Q，Q 与 Ksp 的大小关系决定平衡移动的方向。",
        "给定条件判断沉淀是否生成、能否溶解，或解释沉淀溶解平衡如何移动的题。",
        "Q > Ksp 时有沉淀析出直至平衡；Q ＝ Ksp 时沉淀与溶解处于平衡；Q < Ksp 时溶液不饱和、无沉淀析出，已有沉淀会溶解。\n温度不变时 Ksp 是定值，调节离子浓度改变的是 Q，因此改变浓度就能改变平衡移动的方向。\n平衡向沉淀溶解方向移动的前提是体系中存在固体；体系中没有固体时平衡不移动。",
        "「向沉淀溶解方向移动的前提是体系中有固体」是这条规则的边界——没有固体时哪怕 Q < Ksp 也没有溶解过程发生。",
        "溶度积和离子积的关系"),
    _material(
        "wusan-chem-x1c3-ksp-ph-range", "METHOD_MODEL",
        "由 Ksp 计算沉淀某离子的 pH 范围",
        "先由杂质离子沉淀完全时的残留浓度与 Ksp 求出 c(OH⁻) 得到 pH 下限，再由目标离子开始沉淀的 pH 定出上限。",
        "除去除去溶液中某些杂质离子而不使目标离子沉淀、要求给出 pH 范围的题。",
        "一般认为残留在溶液中的离子浓度小于 1×10⁻⁵ mol·L⁻¹ 时沉淀完全。\n以除去 MnSO₄ 溶液中的 Fe³⁺、Al³⁺ 而不使 Mn²⁺ 沉淀为例：由 Ksp[Al(OH)₃] > Ksp[Fe(OH)₃] 可知 Al³⁺ 沉淀完全时 Fe³⁺ 早已沉淀完全；令 c(Al³⁺) ＝ 10⁻⁵ mol·L⁻¹，由 Ksp[Al(OH)₃] ＝ 1×10⁻³³ 得 c(OH⁻) ≈ 10⁻⁹·³ mol·L⁻¹，再由 Kw 得 c(H⁺) ＝ 10⁻⁴·⁷ mol·L⁻¹，即 pH ＝ 4.7。\nMn(OH)₂ 在 pH ＝ 7.1 时开始沉淀，故需调节 pH 范围为 4.7 ≤ pH < 7.1。",
        "上下限由两个条件夹出——先用「杂质离子沉淀完全」定下限，再用「目标离子开始沉淀」定上限，只算一半会选错区间。",
        "沉淀溶解平衡体系中相关计算方法"),
    _material(
        "wusan-chem-x1c3-ksp-pitfalls", "MISCONCEPTION_GUIDE",
        "有关 Ksp 的易错点",
        "离子不能被完全沉淀除去、混合后要用新浓度算 Q，且溶解度小的沉淀也能转化为溶解度大的沉淀。",
        "判断结论正误，或解释除杂、沉淀转化类现象时自查的题。",
        "除杂不可能把要除去的离子全部沉淀除去——一般认为残留在溶液中的离子浓度小于 1×10⁻⁵ mol·L⁻¹ 时即沉淀完全。\n两种溶液混合时要先算混合后的离子浓度再求 Q，忽略体积变化会算错。\n沉淀转化的实质是沉淀溶解平衡的移动：一般类型相同时 Ksp 较大的沉淀易转化为 Ksp 较小的，但这不意味着溶解度小的沉淀不能转化溶解度大的——把 BaSO₄ 加入饱和 Na₂CO₃ 溶液，只要 c(Ba²⁺)·c(CO₃²⁻) > Ksp(BaCO₃)，BaSO₄ 就能缓慢转化为 BaCO₃。",
        "「难溶物只能向更难溶的方向转化」是错的；转化能否发生只看 Q 与 Ksp 的比较。",
        "有关ksp的易错点"),

    # ── 考法突破15 溶液中微粒浓度关系判断（p0107–p0108）──────────────────
    _material(
        "wusan-chem-x1c3-ion-concentration-basis", "CONCEPT_EXPLANATION",
        "微粒浓度大小比较的依据",
        "单一盐溶液看水解或电离，不同盐溶液看其他离子的影响，混合溶液要同时考虑电离与水解，并先确定溶质组成。",
        "比较不同盐溶液中同一离子浓度大小，或分析混合溶液粒子浓度顺序的题。",
        "单一盐溶液：要考虑离子的水解或电离。如 Na₂CO₃ 溶液中 c(Na⁺) > c(CO₃²⁻) > c(OH⁻) > c(HCO₃⁻) > c(H₂CO₃)；NaHCO₃ 溶液中 HCO₃⁻ 水解程度大于电离程度，故 c(Na⁺) > c(HCO₃⁻) > c(H₂CO₃) > c(CO₃²⁻)。\n不同盐溶液中同一离子：要看其他离子的影响。相同浓度的 NH₄Cl（a）、CH₃COONH₄（b）、NH₄HSO₄（c）中 c(NH₄⁺) 由大到小为 c > a > b——Cl⁻ 无影响、CH₃COO⁻ 促进 NH₄⁺ 水解、H⁺ 抑制其水解。\n混合溶液：要综合分析水解与电离因素，如等浓度 NH₄Cl 与 NH₃·H₂O 混合时 NH₃·H₂O 电离程度大于 NH₄⁺ 水解程度，故 c(NH₄⁺) > c(Cl⁻) > c(NH₃·H₂O) > c(OH⁻) > c(H⁺)。",
        "两份溶液混合若发生反应，必须先确定混合后的溶质组成与浓度再比较；沿用混合前的组成会推错顺序。",
        "微粒浓度的大小比较理论依据"),
    _material(
        "wusan-chem-x1c3-ion-concentration-methods", "METHOD_MODEL",
        "离子浓度大小比较的定性与定量方法",
        "定性按「溶质微粒 > 主要平衡产物 > 次要平衡产物」分层比较，定量用守恒法或常数法算出差值。",
        "比较单一盐溶液或混合溶液中离子浓度大小的题，尤其是只靠定性排不出顺序的情形。",
        "定性比较法按层次：以 CH₃COONa 溶液为例，第一层次为溶质微粒、第二层次为主要平衡产物、第三层次为次要平衡产物，结果为 c(Na⁺) > c(CH₃COO⁻) > c(OH⁻) > c(CH₃COOH) > c(H⁺)。\n定量守恒法：如 CH₃COONa 与 CH₃COOH 混合溶液呈碱性时，由电荷守恒 c(CH₃COO⁻) + c(OH⁻) ＝ c(H⁺) + c(Na⁺) 与 c(OH⁻) > c(H⁺) 可得 c(Na⁺) > c(CH₃COO⁻)。\n定量常数法：pH ＝ 6 的 CH₃COONa 与 CH₃COOH 混合液，代入 K_a ＝ 1.8×10⁻⁵ 得 c(CH₃COO⁻)/c(CH₃COOH) ＝ 18，故 c(CH₃COO⁻) > c(CH₃COOH)。",
        "先判断溶液的酸碱性再定序：脱离 c(H⁺) 与 c(OH⁻) 的大小关系直接排序，容易把中间两项写反。",
        "离子浓度大小比较的方法"),
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
