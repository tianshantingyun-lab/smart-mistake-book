# -*- coding: utf-8 -*-
"""五三精讲册化学 p0113–p0117、p0123（内容页 110–114、120）「烃」→ 材料。

正文严格取自转录件。**刻意不写**的三个节点，理由是"没有独立的面"或"本页没有出处"：

- `苯`：这六页只给苯及其同系物的**反应**与**空间结构**，没有苯的组成、物理性质或用途；
  而反应与结构已经分别写给 `苯的化学性质` 与 `苯分子的空间结构`，再写一条就是同义重复。
- `天然气的综合利用`：本页组讲的是"煤和石油的利用"，没有天然气。
- `芳香烃的用途及对环境的影响`：全目录无芳香烃用途/环境影响的成段内容（只有
  SO₂、氮氧化物污染那类，属别的章）。

**标题按学生语汇写**：别名通道 = 当前绑定材料的标题，所以"银镜""铵根"这类学生说法
必须出现在标题里才进得了别名——这是逐章测量揭示的召回输入，不是修辞。
"""

from __future__ import annotations

import json

from kb_build import pack_io

OUT = pack_io.REPO / "knowledge-production" / "wusan" / "materials.jsonl"
LOCATOR = "《2027 5·3 A版 高考总复习 化学 精讲册》P110-P114、P120"
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
        "wusan-chem-x3c2-alkene", "CONCEPT_EXPLANATION",
        "烯烃的通式与碳碳双键",
        "单烯烃的通式是 CₙH₂ₙ（n≥2），官能团为碳碳双键；能使酸性高锰酸钾溶液褪色。",
        "由分子式判断是否可能为烯烃、或比较烯烃与烷烃性质的题。",
        "单烯烃通式 CₙH₂ₙ（n≥2），官能团为碳碳双键（C=C）。\n含双键的碳原子不能旋转，对原子空间位置有「定格」作用，因此存在顺反异构（如顺-2-丁烯与反-2-丁烯）。\n烯烃能使酸性高锰酸钾溶液褪色，烷烃不能，可据此鉴别。",
        "通式 CₙH₂ₙ 同时覆盖烯烃与环烷烃（n≥3 时为官能团异构）——只看通式判不出是不是烯烃，要看有没有碳碳双键。",
        "烯烃"),
    _material(
        "wusan-chem-x3c2-alkyne", "CONCEPT_EXPLANATION",
        "炔烃的通式与碳碳三键",
        "单炔烃的通式是 CₙH₂ₙ₋₂（n≥2），官能团为碳碳三键；乙炔可由电石与水反应制得。",
        "由通式判断有机物类别、或书写乙炔制法的题。",
        "单炔烃通式 CₙH₂ₙ₋₂（n≥2），官能团为碳碳三键（C≡C）。\n乙炔的制法：CaC₂ + 2H₂O → Ca(OH)₂ + CH≡CH↑（电石与水反应）。\n三键为直线形结构，不能旋转，对原子的空间位置有「定格」作用。",
        "CₙH₂ₙ₋₂ 同时是二烯烃、环烯烃与含两个环的环烷烃的通式——判类别必须看官能团，不能只看通式。",
        "炔烃"),
    _material(
        "wusan-chem-x3c2-aromatic-hydrocarbon", "CONCEPT_EXPLANATION",
        "芳香烃的定义",
        "芳香烃是分子里含有一个或多个苯环的烃。",
        "判断某有机物是否属于芳香烃、或区分芳香烃与脂环化合物的题。",
        "定义：分子里含有一个或多个苯环的烃。\n苯及其同系物属于芳香烃；苯环上连有链状取代基的化合物（如苯乙烯）仍属芳香烃。\n对照：环己烷不含苯环，属于脂环化合物，不是芳香烃。",
        "判据只有「含苯环」这一条——把有环的烃都当成芳香烃（或把环己烷当芳香烃）是最常见的错。",
        "芳香烃"),
    _material(
        "wusan-chem-x3c2-alkane-chemistry", "CONCEPT_EXPLANATION",
        "烷烃的取代反应与燃烧",
        "烷烃在光照下与卤素发生取代，能燃烧生成二氧化碳和水，但不能使酸性高锰酸钾溶液褪色。",
        "判断烷烃能发生哪类反应、或书写取代与燃烧方程式的题。",
        "取代反应（光照）：CH₃CH₃ + Cl₂ →光照 CH₃CH₂Cl + HCl。\n燃烧通式：CₓH_y + (x + y/4)O₂ →点燃 xCO₂ + (y/2)H₂O。\n烷烃不能使酸性高锰酸钾溶液褪色——这正是它与烯烃、炔烃的鉴别点。",
        "烷烃的取代需要**光照**（与苯环取代需要催化剂不同）；取代产物是多种卤代烃的混合物，只写一种产物并不代表反应只发生这一步。",
        "烷烃的化学性质"),
    _material(
        "wusan-chem-x3c2-alkene-chemistry", "CONCEPT_EXPLANATION",
        "烯烃的加成、加聚与氧化",
        "烯烃能与卤素、卤化氢、水加成，能加聚成高分子，也能被酸性高锰酸钾氧化；高温下还能取代烯丙位氢。",
        "判断烯烃在不同条件下生成什么产物、或书写加成与加聚方程式的题。",
        "加成：CH₂=CH₂ + Br₂ → CH₂Br—CH₂Br；与 HX、H₂O 也能加成。\n加聚：n CH₂=CH₂ →催化剂 [-CH₂—CH₂-]ₙ；也能使酸性高锰酸钾溶液褪色。\n高温取代：CH₃CH=CH₂ + Cl₂ →Δ CH₂ClCH=CH₂ + HCl（取代烯丙位上的氢）。\n二烯烃的加成有两种：CH₂=CH—CH=CH₂ + Br₂ 可得 1,4-加成产物 CH₂Br—CH=CH—CH₂Br 或 1,2-加成产物 CH₂Br—CHBr—CH=CH₂。",
        "烯烃与卤素在**常温加成、高温取代烯丙位**——条件不同产物完全不同，先看条件再写产物。",
        "烯烃的化学性质"),
    _material(
        "wusan-chem-x3c2-alkyne-chemistry", "CONCEPT_EXPLANATION",
        "炔烃的加成与加聚",
        "乙炔能与氯化氢加成制氯乙烯、与水加成制乙醛，也能加聚；不能直接得到不稳定的烯醇。",
        "书写乙炔参与反应的方程式、或由原料推断产物的题。",
        "加成：CH≡CH + HCl →催化剂,Δ CH₂=CHCl（氯乙烯）；CH≡CH + H₂O →催化剂,Δ CH₃CHO（乙炔水化制乙醛）。\n加聚：n CH≡CH →催化剂 [-CH=CH-]ₙ。\n氧化：能使酸性高锰酸钾溶液褪色。\n注意：CH₂=CH—OH 不稳定，易转化为乙醛。",
        "乙炔与水的加成产物直接写成乙醛——烯醇式（CH₂=CH—OH）不稳定，不能把它当作终产物。",
        "炔烃的化学性质"),
    _material(
        "wusan-chem-x3c2-benzene-chemistry", "CONCEPT_EXPLANATION",
        "苯的取代与加成反应",
        "苯在催化剂下发生环上卤代、硝化与磺化，能与氢加成；但苯不能被酸性高锰酸钾溶液氧化。",
        "判断苯能发生哪些反应、或书写苯的溴代与硝化方程式的题。",
        "卤代（苯环取代，催化剂）：苯 + 3Br₂(l) →FeBr₃ 1,3,5-三溴苯 + 3HBr↑。\n硝化：苯 + HNO₃ →浓硫酸,Δ 硝基苯 + H₂O。\n磺化：苯 + HO—SO₃H →Δ 苯磺酸 + H₂O。\n加成：苯 + 3H₂ →催化剂,Δ 环己烷；苯不能被酸性高锰酸钾溶液氧化。",
        "苯的取代要催化剂（FeBr₃、浓硫酸）而**不是光照**；「苯不能被酸性高锰酸钾氧化」是它与烯烃、炔烃的关键区别。",
        "苯的化学性质"),
    _material(
        "wusan-chem-x3c2-toluene-derivatives", "CONCEPT_EXPLANATION",
        "苯的同系物的侧链取代与苯环取代",
        "甲苯在光照下取代侧链、在催化剂下取代苯环；烷基活化苯环、苯环活化烷基，两者相互影响。",
        "判断甲苯等苯的同系物在给定条件下取代哪个位置的题。",
        "侧链取代（光照）：甲苯 + Br₂(g) →光照 苄溴（C₆H₅CH₂Br）+ HBr。\n苯环取代（催化剂）：卤代、硝化、磺化都发生在苯环上，如甲苯 + 3HNO₃ →浓硫酸,Δ 2,4,6-三硝基甲苯（TNT）+ 3H₂O——烷基活化苯环。\n氧化：乙苯 →KMnO₄, H⁺ 苯甲酸——苯环活化烷基。\n加成：甲苯 + 3H₂ →催化剂,Δ 甲基环己烷。",
        "同一个甲苯，**光照取代侧链、催化剂取代苯环**，条件写错产物就全错；氧化反应的前提是与苯环直接相连的碳上有氢。",
        "苯及其同系物的性质"),
    _material(
        "wusan-chem-x3c2-aromatic-pitfall", "MISCONCEPTION_GUIDE",
        "芳香烃的两个易错点：苯不被氧化、侧链氧化有前提",
        "苯不能被酸性高锰酸钾氧化；苯的同系物只有与苯环直接相连的碳上有氢时才能被氧化。",
        "判断苯及其同系物能否使酸性高锰酸钾褪色、或解释氧化现象的题。",
        "苯不能被酸性高锰酸钾溶液氧化——与烯烃、炔烃放在一起比较时，苯是那个不褪色的。\n苯的同系物中，只有与苯环直接相连的碳原子上有氢原子时，才能被酸性高锰酸钾溶液氧化为苯甲酸类产物。\n苯环与侧链互相影响：烷基活化苯环（环上取代更容易），苯环活化烷基（侧链可被氧化）。",
        "「苯的同系物都能被酸性高锰酸钾氧化」是错的——与苯环相连的碳上没有氢（如叔丁基苯）就不能被氧化。",
        "芳香烃的结构和性质易混易错点"),
    _material(
        "wusan-chem-x3c2-benzene-geometry", "CONCEPT_EXPLANATION",
        "苯分子的空间结构与原子共面共线判断",
        "苯是平面结构，一个苯环至少 12 个原子共平面；单键可旋转、双键与三键不能旋转，是共面判断的核心依据。",
        "判断有机物分子中最多/最少有多少原子共面或共线、或判断「一定共面」的题。",
        "基础模型：甲烷正四面体、乙烯平面、乙炔直线、苯平面、甲醛平面。\n结论：结构中若存在饱和碳原子，则分子中所有原子一定不共面；存在 1 个苯环至少有 12 个原子共平面；存在 1 个碳碳双键至少有 6 个原子共平面。\n两条规律：定平面规律（共平面的不在同一直线上的 3 个原子处于另一平面时，两平面必定重叠，两平面内所有原子必定共平面）；定直线规律（直线形分子中有 2 个原子处于某一平面内时，该分子所有原子也在此平面内）。",
        "单键可以旋转、双键与三键不能旋转（对空间位置有「定格」作用）；环状结构中直接参与成环的单键一般也不能旋转——转动单键能让两个平面重合，但改变不了已定格的键。",
        "苯分子的空间结构"),
    _material(
        "wusan-chem-x3c2-aliphatic-hydrocarbon", "CONCEPT_EXPLANATION",
        "脂肪烃的通式与物理性质递变",
        "烷烃 CₙH₂ₙ₊₂、单烯烃 CₙH₂ₙ、单炔烃 CₙH₂ₙ₋₂；常温下碳数≤4 为气体，沸点随碳数升高、随支链增多而降低。",
        "比较脂肪烃的熔沸点与溶解性、或由碳数判断状态的题。",
        "通式：烷烃 CₙH₂ₙ₊₂（n≥1）、单烯烃 CₙH₂ₙ（n≥2）、单炔烃 CₙH₂ₙ₋₂（n≥2）。\n物理性质：常温下碳原子数 ≤4 时为气体（新戊烷常温下也是气体）；随碳原子数增多，熔、沸点升高，密度增大，但密度均比水小；碳原子数相同时，支链越多熔、沸点越低；均难溶于水。\n化学性质受不饱和键影响：烷烃主要发生取代，烯烃与炔烃还能加成、加聚并被酸性高锰酸钾氧化。",
        "「碳数越多沸点越高」与「支链越多沸点越低」是两个不同口径——前者比同系物之间，后者比同碳数的异构体之间。",
        "脂肪烃的结构和性质"),
    _material(
        "wusan-chem-x3c2-coal-utilization", "CONCEPT_EXPLANATION",
        "煤的气化、液化与干馏",
        "煤的气化生成水煤气、液化得到液体燃料、干馏得到焦炭与煤焦油，三者都是化学变化；只有石油的分馏是物理变化。",
        "判断煤和石油各加工过程的产物与变化类型的题。",
        "煤的气化：C + H₂O(g) →高温 CO + H₂（水煤气），属化学变化。\n煤的液化：直接液化是使煤与氢气作用生成液体燃料；间接液化一般先转化为 CO 和 H₂，再在催化剂作用下合成甲醇等，属化学变化。\n煤的干馏：获得焦炭、煤焦油、粗氨水、粗苯、焦炉气等，属化学变化。\n对照石油：分馏按沸点不同分离得汽油、煤油、柴油等轻质油（物理变化）；裂化把重油转化为汽油；裂解得到乙烯、丙烯、甲烷等（后两者属化学变化）。",
        "六个过程里**只有石油的分馏是物理变化**——把分馏与干馏、裂化、裂解混成一类，是这一节最常见的错。",
        "煤的综合利用"),
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
