# -*- coding: utf-8 -*-
"""一次性：把聚合章/截短章名下的节点归到人教规范章。

要修的三类章层缺陷：

1. **聚合章**——一章顶了教材的两三章（`第九十章（…）概率统计`、`物质结构与性质`、
   `生物技术与工程`），于是五三按人教编排的内容无处可归，目录里还会出现聚合章与
   规范章并存的重叠。
2. **截短的章名**——`第六章 平面向量` 少了"及其应用"，形态对但名字不对人教。
3. **按主题错挂**——物理「机械波」的 21 个节点挂在 `第二章 机械振动` 的主题
   `机械振动与机械波` 下，而它们属人教 `第三章 机械波`；`波长` 更离谱，挂在一个
   叫「实验：用双缝干涉测量光的波长」的主题下，实际属 `第四章 光`。

归位写进 `tables/chapter_map.csv`——**已存在的节点级覆盖通道**，不是新机制。
节点一旦有节点级覆盖，`_rebuild_topics` 就按覆盖值挂树；聚合章下不再有节点，
重建时自然消失；规范章由重建过程自动补出空壳（规范要求"先补章节点"）。

`AGGREGATES` 里的章，其下节点**必须全部**有目标——漏一个就会残留聚合章，
所以脚本做双向核对；`NODE_OVERRIDES` 是零散的单点归位（不进聚合章核对）。

  用法： PYTHONPATH=tools python tools/kb_build/regroup_aggregates.py --write
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from kb_build import gen_chapter_table, pack_io

TABLES = pack_io.REPO / "tools" / "kb_build" / "tables"
STAGING = pack_io.REPO / "build" / "kb-staging"

M_B1 = "数学必修第一册"
M_B2 = "数学必修第二册"
M_X1 = "数学选择性必修第一册"
M_X2 = "数学选择性必修第二册"
M_X3 = "数学选择性必修第三册"
P_B1 = "物理必修第一册"
P_B2 = "物理必修第二册"
P_B3 = "物理必修第三册"
P_X1 = "物理选择性必修第一册"
P_X2 = "物理选择性必修第二册"
P_X3 = "物理选择性必修第三册"
C_B1 = "化学必修第一册"
C_B2 = "化学必修第二册"
C_X1 = "化学选择性必修1"
C_X2 = "化学选择性必修2"
C_X3 = "化学选择性必修3"
G_B1 = "生物学必修1"
G_B2 = "生物学必修2"
G_X1 = "生物学选择性必修1"
G_X2 = "生物学选择性必修2"
G_X3 = "生物学选择性必修3"

# 这些章下的节点必须**全部**归位（漏一个就残留聚合章）
AGGREGATES: set[tuple[str, str, str]] = {
    ("MATH", M_B2, "第六章 平面向量"),
    ("MATH", M_B2, "第九十章（条件概率与正态分布见选择性必修第三册） 概率统计"),
    ("PHYSICS", P_B3, "第九十章 静电场"),
    ("PHYSICS", P_B3, "第十一十二章 恒定电流"),
    ("PHYSICS", P_X3, "第四五章 近代物理"),
    ("CHEMISTRY", C_X2, "物质结构与性质"),
    ("CHEMISTRY", C_X3, "有机化学基础"),
    ("BIOLOGY", G_B2, "第三四章 遗传的分子基础"),
    ("BIOLOGY", G_B2, "第五六章 变异与进化"),
}

# 源章是规范章、只拆其中一层主题的：不要求整章盖章归位（留下的节点本就该留），
# 但**这个主题下**的节点必须全部有目标——否则漏一个就静默留在错误主题下。
THEME_SCOPES: set[tuple[str, str, str, str]] = {
    # 主题名横跨两章：振动的留下（并改正主题名），波与光的摘走
    ("PHYSICS", P_X1, "第二章 机械振动", "机械振动与机械波"),
    # 节名本身已经是人教第四章的：节点挂错章，必须搬
    ("PHYSICS", P_X2, "第三章 交变电流", "1 电磁振荡"),
    ("PHYSICS", P_X2, "第三章 交变电流", "4 电磁波的发现及应用"),
}

# 一个来源单元横跨教材的两三章时，单元级表给不出单一归属，`chapter_by_source.csv`
# 里标 `split`、book/chapter 留空，归属全由节点级覆盖给。**单元里每个节点**都必须
# 有目标：漏一个不会报错，而会掉进空章（book/chapter 都是 ""）静默消失。
SPLIT_UNITS: set[str] = {"专题14 交变电流", "专题12 磁场"}

# 定位串本身就写错、又不在上面任何待拆范围里的节点（`keep_per_node` 单元的归属是从
# 定位串反推的，没有别的通道改）。放行时**必须**在 EXTRA_OVERRIDE_REASONS 里写清依据。
LOOSE_NODES: set[tuple[str, str]] = {
    ("PHYSICS", "安培力"),
    ("PHYSICS", "洛伦兹力"),
    ("PHYSICS", "带电粒子在磁场中的运动"),
}
EXTRA_OVERRIDE_REASONS: dict[tuple[str, str], str] = {
    ("PHYSICS", "安培力"): "定位串写必修三第十三章，但人教必修三不含安培力；本表 专题12 磁场 的 current_place 自己标注了「安培力/洛伦兹力…见选择性必修第二册」",
    ("PHYSICS", "洛伦兹力"): "同上；人教版必修三第十三章只讲磁场、磁感线、磁感应强度、磁通量",
    ("PHYSICS", "带电粒子在磁场中的运动"): "同上；人教版编在选必二第一章第3节",
}

# 正向检查允许的"源位置"：聚合章全体，或被点名主题所属的章
SOURCE_PLACES: set[tuple[str, str, str]] = AGGREGATES | {
    (s, b, c) for s, b, c, _t in THEME_SCOPES}

# (科目, slug) → (册, 规范章, 主题)。主题留空＝直挂章下。
NODE_OVERRIDES: dict[tuple[str, str], tuple[str, str, str]] = {}


def _assign(subject: str, book: str, chapter: str, slugs: list[str], theme: str = "") -> None:
    for slug in slugs:
        key = (subject, slug)
        if key in NODE_OVERRIDES:
            raise SystemExit(f"{key} 被分配了两次")
        NODE_OVERRIDES[key] = (book, chapter, theme)


# ---------- 数学：平面向量改名 + 概率统计拆到四章 ----------
_assign("MATH", M_B2, "第六章 平面向量及其应用", [
    "平面向量的概念", "平面向量的线性运算", "平面向量的数量积", "平面向量的坐标运算",
    "平面向量基本定理", "向量的模与夹角", "投影向量", "向量共线定理",
])
# 人教A版：必修第二册 第九章 统计 / 第十章 概率；条件概率与正态分布在选必三
_assign("MATH", M_B2, "第九章 统计", [
    "简单随机抽样", "分层随机抽样", "统计图表的读取", "平均数与中位数",
])
_assign("MATH", M_B2, "第十章 概率", [
    "随机事件", "古典概型", "随机事件的相互独立性",
])
_assign("MATH", M_X3, "第七章 随机变量及其分布", [
    "条件概率", "全概率", "离散型随机变量", "离散型随机变量的期望",
    "离散型随机变量的方差", "二项分布", "超几何分布", "正态分布",
])
_assign("MATH", M_X3, "第八章 成对数据的统计分析", [
    "回归分析", "独立性检验",
])

# ---------- 物理：静电场的能量、恒定电流拆章 ----------
# 人教必修三：第九章 静电场及其应用 / 第十章 静电场中的能量
_assign("PHYSICS", P_B3, "第九章 静电场及其应用", [
    "电荷", "元电荷", "电荷守恒定律", "库仑定律", "电场强度", "电场线",
])
_assign("PHYSICS", P_B3, "第十章 静电场中的能量", [
    "电势", "电势能", "电势差", "等势面", "电容器", "电容", "带电粒子在电场中的运动",
])
# 人教必修三：第十一章 电路及其应用 / 第十二章 电能 能量守恒定律
_assign("PHYSICS", P_B3, "第十一章 电路及其应用", [
    "电流", "欧姆定律", "电阻定律",
])
_assign("PHYSICS", P_B3, "第十二章 电能 能量守恒定律", [
    "电动势", "路端电压", "闭合电路欧姆定律", "电功率", "焦耳定律",
])
# 人教选必三：第四章 原子结构和波粒二象性 / 第五章 原子核和核反应
_assign("PHYSICS", P_X3, "第四章 原子结构和波粒二象性", [
    "光子说", "波粒二象性", "玻尔模型", "光电效应", "原子核式结构",
])
_assign("PHYSICS", P_X3, "第五章 原子核和核反应", [
    "天然放射现象", "半衰期", "核反应", "质量亏损", "核裂变", "核聚变",
])

# ---------- 物理：机械波从「第二章 机械振动」的主题里摘出来 ----------
# 这不是聚合章，是主题错挂；只摘走属波与光的，剩下的振动节点留原地并改正主题名。
_assign("PHYSICS", P_X1, "第三章 机械波", [
    "横波", "纵波", "波速", "波的衍射", "多普勒效应", "波的干涉",
    "机械波-x1", "传播特点", "机械波的分类",
    "波长λ-在波动中振动相位总是相同的两个相邻质点间的距离-用-λ-表示",
    "波速v-波长λ和频率f-周期t的关系-v-λf",
    "波动图象的特点", "波动图象的信息",
    "反射-波在传播过程中遇到介质界面会返回来继续传播的现象叫作波的反射",
    "衍射-波在传播过程中可以绕过障碍物继续传播的现象叫做波的衍射",
    "传播方向和振动方向的判断-图像理解",
    "造成波动问题多解的主要因素有", "解决波的多解问题的思路和步骤",
    "多普勒效应的成因分析", "多普勒效应在生活中的应用",
    "波的干涉中振动加强点和减弱点的判断方法",
])
# `波长` 挂在一个叫「实验：用双缝干涉测量光的波长」的主题下，实属第四章 光
_assign("PHYSICS", P_X1, "第四章 光", ["波长"])
# 留在第二章的振动节点：主题名 `机械振动与机械波` 横跨两章，改正为 `机械振动`
_assign("PHYSICS", P_X1, "第二章 机械振动", [
    "机械振动", "表达式", "位移-速度和加速度", "图象", "图象信息", "周期性", "对称性",
    "固有振动和固有频率", "阻尼振动", "弹簧振子模型", "单摆周期", "运动特点",
    "单摆的受力特征", "回复力", "受迫振动-x1",
], theme="机械振动")

# ---------- 化学：选必2 / 选必3 的书名当章名拆开 ----------
# 人教选必2：第一章 原子结构与性质 / 第二章 分子结构与性质 / 第三章 晶体结构与性质
_assign("CHEMISTRY", C_X2, "第一章 原子结构与性质", [
    "能层", "能级", "构造原理", "电子排布式", "电负性",
])
_assign("CHEMISTRY", C_X2, "第二章 分子结构与性质", [
    "杂化轨道", "vsepr模型", "氢键", "范德华力", "分子的极性",
])
_assign("CHEMISTRY", C_X2, "第三章 晶体结构与性质", [
    "晶体", "分子晶体", "共价晶体", "金属晶体", "离子晶体", "配合物",
])
# 人教选必3：第一章 结构特点与研究方法 / 第二章 烃 / 第三章 烃的衍生物 /
#           第四章 生物大分子 / 第五章 合成高分子
_assign("CHEMISTRY", C_X3, "第一章 有机化合物的结构特点与研究方法", ["有机物的结构特点"])
_assign("CHEMISTRY", C_X3, "第二章 烃", ["苯", "烯烃", "炔烃", "芳香烃"])
_assign("CHEMISTRY", C_X3, "第三章 烃的衍生物", [
    "醇", "酚", "醛", "酮", "酯", "卤代烃", "羧酸", "有机合成",
])
_assign("CHEMISTRY", C_X3, "第四章 生物大分子", ["糖类", "蛋白质", "核酸"])
_assign("CHEMISTRY", C_X3, "第五章 合成高分子", ["合成高分子"])

# ---------- 生物：必修2 的跨章聚合拆开 ----------
# 人教必修2：第三章 基因的本质 / 第四章 基因的表达
_assign("BIOLOGY", G_B2, "第三章 基因的本质", [
    "dna是主要的遗传物质", "dna的双螺旋结构", "dna的复制", "基因",
])
_assign("BIOLOGY", G_B2, "第四章 基因的表达", [
    "基因的表达", "转录", "翻译", "中心法则", "密码子",
])
# 人教必修2：第五章 基因突变及其他变异 / 第六章 生物的进化
_assign("BIOLOGY", G_B2, "第五章 基因突变及其他变异", [
    "基因突变", "基因重组", "染色体变异", "多倍体", "单倍体", "人类遗传病",
])
_assign("BIOLOGY", G_B2, "第六章 生物的进化", [
    "自然选择", "基因频率", "物种的形成", "共同进化", "生物多样性",
])


# ---------- 物理选必二：交变电流单元横跨人教第三、四章 ----------
# 一手依据：五三精讲册物理 p0172 的「对应人教版」栏写的就是
# `选择性必修第二册 第四章 电磁振荡与电磁波`，正文为"一、电磁振荡""二、电磁波"；
# 单元级表的原始定位同样写着 `第三四章·交变电流与电磁波`，此前被 `fix` 强行
# 压成第三章。其余 18 个节点确实属第三章，逐条写出来是为了标为 split 后不漏。
_assign("PHYSICS", P_X2, "第四章 电磁振荡与电磁波", [
    "电磁振荡", "振荡电路过程中各物理量的变化", "电磁波",
    "无线电波的发射", "电磁振动-电磁波和传感器",
])
_assign("PHYSICS", P_X2, "第三章 交变电流", [
    "正弦式交变电流的产生和变化规律", "周期和频率", "周期与频率", "核心规律",
    "理想变压器的关系式", "理想变压器的制约关系", "理想变压器的两类动态分析",
    "匝数比不变的分析思路", "负载电阻不变的分析思路", "等效法在变压器和远距离输电中的应用",
    "有效值的理解", "交变电流有效值的计算",
    "正弦式交变电流的变化规律-线圈在中性面位置时开始计时",
    "交变电流的瞬时值-峰值-有效值和平均值", "电能的远距离输送电路图",
    "电容-通交流-隔直流-通高频-阻低频",
    # 这两个讲的是传感器件（二极管、传感器特性），人教把传感器编在第五章；
    # 但五三精讲册物理没有这一章（目录到"第十三章 交变电流 电磁波"为止，
    # 次页直接进"第十四章 光"），检索通道也没取到该章的独立依据，
    # 所以不凭印象新建章壳——暂留第三章并记账。
    "普通二极管和发光二极管", "常见传感器的特点",
])


# ---------- 物理选必二：磁场单元横跨人教必修三第十三章与选必二第一章 ----------
# 依据有两处一手来源：① 人教编排——必修三第十三章只有「磁场 磁感线」「磁感应强度
# 磁通量」「电磁感应现象及应用」「电磁波的发现及应用」「能量量子化」，安培力、洛伦兹力、
# 带电粒子在磁场中的运动编在选必二第一章；② 本表 专题12 磁场 的 current_place 里
# 抽取时就写明了「安培力/洛伦兹力/感应定律见选择性必修第二册」。
# 人教选必二没有「传感器」章（传感器在选必二第五章），所以"传感器的工作原理"这一条
# 无名可归，暂留必修三第十三章并记账——不凭印象新建章壳。
_assign("PHYSICS", P_B3, "第十三章 电磁感应与电磁波初步", [
    "磁场的基本概念和性质", "概念理解", "对磁感应强度定义式-b-的理解",
    "特点-x1", "磁通量φ-磁通量的变化量δφ及磁通量的变化率的比较",
    "匀强磁场中磁通量的计算", "工作原理",
])
_assign("PHYSICS", P_X2, "第一章 安培力与洛伦兹力", [
    "安培力", "洛伦兹力", "带电粒子在磁场中的运动", "大小",
    "特点-f-b-f-v-即f垂直于b与v决定的平面-f与v始终垂直-洛伦兹力不做功",
    "安培力作用下导体的平衡与加速问题", "仪器构造", "仪器构造-质谱仪由粒子源-加速电场-偏转磁场和照相底片构成",
    "仪器介绍-磁流体发电是一项新兴技术-它可以把内能直接转化为电能",
    "环形磁场", "磁场中的仪器", "解题方法-x1",
    "带电粒子在有界匀强磁场中的运动-临界条件",
    "带电粒子在匀强磁场中运动的动态圆模型", "带电粒子在磁场中运动的多解问题",
    "带电粒子在叠加场-复合场-电场与磁场重叠-中的运动",
    "带电粒子在组合场-电场与磁场各位于一定的区域内-并不重叠-中的运动",
    "带电粒子在交变电磁场中的运动", "带电粒子在立体空间中的运动",
    "磁聚焦模型和磁发散模型",
])


def _staged_nodes() -> dict[tuple[str, str], tuple[str, str, str, str]]:
    """(科目, slug) → (册, 章, 主题, 来源单元) —— 用重建后的树认，不用原包的旧层级。"""
    path = STAGING / pack_io.PACK_NAME
    if not path.exists():
        raise SystemExit(f"缺少 {path}；先跑 PYTHONPATH=tools python -m kb_build.build --write")
    pack = json.loads(path.read_text(encoding="utf-8"))
    found: dict[tuple[str, str], tuple[str, str, str, str]] = {}
    for subject in pack["subjects"]:
        by_slug = {t["slug"]: t for t in subject["topics"]}
        for topic in subject["topics"]:
            chain = []
            cursor = topic
            while cursor:
                chain.append(cursor)
                cursor = by_slug.get(cursor.get("parentSlug"))
            chain.reverse()
            if len(chain) < 2:
                continue
            theme = chain[2]["name"] if len(chain) > 2 else ""
            for point in topic.get("knowledgePoints") or []:
                found[(subject["subject"], point["slug"])] = (
                    chain[0]["name"], chain[1]["name"], theme,
                    gen_chapter_table.source_unit(point.get("sourceLocator", "")))
    return found


def build_rows() -> list[tuple[str, str, str, str, str]]:
    staged = _staged_nodes()

    # 正向：每个覆盖目标要么还在待拆的聚合章下（待搬），要么已经到位（搬过）。
    # 允许"已经到位"是刻意的——这张表可重跑，主题列等值后续才补，
    # 不能因为"已经搬过一次"就把自己的修正挡在门外。
    for key, (book, chapter, _theme) in NODE_OVERRIDES.items():
        actual = staged.get(key)
        if actual is None:
            raise SystemExit(f"{key[0]} 找不到节点 {key[1]}")
        abook, achapter, atheme, aunit = actual
        if (abook, achapter) == (book, chapter):
            continue
        if not ((key[0], abook, achapter) in SOURCE_PLACES
                or (key[0], abook, achapter, atheme) in THEME_SCOPES
                or aunit in SPLIT_UNITS
                or key in LOOSE_NODES):
            raise SystemExit(
                f"{key[0]} {key[1]} 实际在 {abook} / {achapter} / {atheme or '(直挂)'}"
                f"（单元 {aunit!r}），既不在待拆聚合章下，也不在声明的目标 {book} / {chapter}")

    # 反向：待拆聚合章、被点名主题、以及标了 split 的来源单元下的节点，
    # 必须**全部**有目标——漏一个就会残留聚合章、静默留在错误主题下，
    # 或（split 单元）掉进空章。
    for (subject, slug), (book, chapter, theme, unit) in staged.items():
        scoped = ((subject, book, chapter) in AGGREGATES
                  or (subject, book, chapter, theme) in THEME_SCOPES
                  or unit in SPLIT_UNITS)
        if scoped and (subject, slug) not in NODE_OVERRIDES:
            raise SystemExit(
                f"{subject} {slug} 在 {book} / {chapter} / {theme or '(直挂)'}"
                f"（单元 {unit!r}）下但没有分配目标")

    return [(subject, slug, book, chapter, theme)
            for (subject, slug), (book, chapter, theme) in NODE_OVERRIDES.items()]


def mark_split() -> list[str]:
    """把跨章来源单元在单元级表里标成 `split`，归属交给节点级表。

    只有真的跨章才标：单元级表给单一归属会把整单元压到一章，而截短/聚合章名
    反过来又让下游认不出它属于教材哪一章。
    """
    path = TABLES / "chapter_by_source.csv"
    with path.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.reader(fh))
    header, body = rows[0], rows[1:]
    bcol, ccol, dcol = (header.index("book"), header.index("chapter"),
                        header.index("decision"))
    changed = []
    for unit in sorted(SPLIT_UNITS):
        for row in body:
            if not row or row[0] != unit:
                continue
            if row[dcol] == "split" and not row[bcol] and not row[ccol]:
                break
            row[dcol], row[bcol], row[ccol] = "split", "", ""
            changed.append(unit)
            break
        else:
            raise SystemExit(f"chapter_by_source.csv 里没有来源单元 {unit!r}")
    if changed:
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh)
            writer.writerow(header)
            writer.writerows(body)
    print(f"标为 split 的来源单元 {len(changed)} 个：{changed}")
    return changed


def write_rows(rows: list[tuple[str, str, str, str, str]]) -> Path:
    """同键**更新**而不是跳过：主题列是后补的，跳过会让第一次写下的空值永远留下。"""
    path = TABLES / "chapter_map.csv"
    with path.open(encoding="utf-8", newline="") as fh:
        existing = list(csv.reader(fh))
    header, body = existing[0], existing[1:]
    index = {(r[0], r[1]): i for i, r in enumerate(body) if len(r) >= 2}
    added = updated = 0
    for row in rows:
        key = (row[0], row[1])
        if key in index:
            if body[index[key]] != list(row):
                body[index[key]] = list(row)
                updated += 1
            continue
        body.append(list(row))
        added += 1
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(header)
        writer.writerows(body)
    print(f"chapter_map.csv 新增 {added} 行、更新 {updated} 行（现共 {len(body)} 行）")
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)
    rows = build_rows()
    print(f"待归位节点 {len(rows)} 个，待拆聚合章 {len(AGGREGATES)} 个")
    counts: dict[tuple[str, str], int] = {}
    for _subject, _slug, book, chapter, _theme in rows:
        counts[(book, chapter)] = counts.get((book, chapter), 0) + 1
    for (book, chapter), count in sorted(counts.items()):
        print(f"  {count:>3}  {book} {chapter}")
    if args.write:
        mark_split()
        write_rows(rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
