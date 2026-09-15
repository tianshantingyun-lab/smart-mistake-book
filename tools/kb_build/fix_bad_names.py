# -*- coding: utf-8 -*-
"""一次性：修掉由机械 rename 造出来的坏节点名，并处理题干/属性条目节点。

**缺陷的来路**：`node_actions.csv` 里 312 条机械 rename 中有一批的规则是"把 slug 的
连字符换成标点"，比如 `技巧-实验数据-与-9-3-3-1及其变式-间的转化方法` →
`技巧“实验数据”与“9`。它只是给残句加了标点，名字并没有成形，却让名字变短、
绕过了门禁的 `_is_bad_name`（该判据以 24 字为界），于是满库残句名而 `bad_names` 显示 0。

同一个来路还漏出两类：`定义：…`/`表达式：…`/`应用：…` 是**知识点的属性条目**被提成了
独立节点（主节点都在库里，应当并入）；`…能否颠倒？为什么？` 一类是**题干**。

动作只有三种，判据都是学科内容而不是字面：
- **rename**：名字是残句但节点本身是个真知识点（绑定数不为 0 就是证据——材料是照着
  这个知识点抽的），给一个人能读的名字，绑定原样保留。
- **merge**：属性条目并入它所属的主节点，绑定改指主节点。
- **delete**：题干/答案原文、抽取截断，节点不含知识点。

  用法： PYTHONPATH=tools python tools/kb_build/fix_bad_names.py [--write]
"""

from __future__ import annotations

import argparse
import csv

from kb_build import pack_io

TABLE = pack_io.REPO / "tools" / "kb_build" / "tables" / "node_actions.csv"

_SLUG = "残句当名称（机械 rename 只把 slug 的连字符换成标点，名字并未成形）"
_MERGE = "知识点的属性条目被提成独立节点（定义：/表达式：/应用：），并入主节点"
_DELETE = "题干/答案原文或抽取截断，节点不含知识点"
_LABEL = "列表项的**行首标签**被当成了名字，名字脱离父主题就不成知识点名；"

# (subject, slug, action, new_name, new_slug, reason)
FIXES: list[tuple[str, str, str, str, str, str]] = [
    ("MATH", "构造函数-运用函数的单调性比较", "rename", "构造函数法比较大小", "", _SLUG),
    ("MATH", "等差乘等比数列求和-令-可以用错位相减法", "rename", "错位相减法求和", "", _SLUG),
    ("PHYSICS", "以上各式均为矢量式-应用时应规定正方向", "rename", "列方程时规定正方向", "", _SLUG),
    ("PHYSICS", "按实际作用效果分解-最典型的是将速度分解为", "rename", "按力的作用效果分解", "", _SLUG),
    ("BIOLOGY", "技巧-实验数据-与-9-3-3-1及其变式-间的转化方法", "rename",
     "“9∶3∶3∶1”及其变式的转化方法", "", _SLUG),
    ("CHEMISTRY", "有机物的结构可用键线式表示-如", "rename", "键线式", "", _SLUG),
    ("CHEMISTRY", "为了检验淀粉水解产物-某学生设计了如下实验方案", "rename",
     "淀粉水解产物的检验", "", _SLUG),
    ("CHEMISTRY", "石油裂解是一个复杂的过程-其产物为混合物-例如", "rename",
     "石油裂解的产物", "", _SLUG),
    ("CHEMISTRY", "实验室闻cl2及其他气体的方法是怎样的", "rename", "闻气体的方法", "", _SLUG),
    ("CHEMISTRY", "铁粉与硫粉在空气中混合燃烧时-可能发生哪些反应", "rename",
     "铁与硫的反应", "", _SLUG),

    ("PHYSICS", "定义-由于地球吸引而使物体受到的力叫做重力", "merge", "", "重力", _MERGE),
    ("PHYSICS", "大小-g-mg-可用弹簧测力计测量", "merge", "", "重力", _MERGE),
    ("PHYSICS", "定义-通电导线在磁场中受到的力叫安培力", "merge", "", "安培力", _MERGE),
    ("PHYSICS", "概念-物体运动轨迹是曲线的运动", "merge", "", "曲线运动", _MERGE),
    ("PHYSICS", "表达式-i-p2-p1或ft-mv2-mv1", "merge", "", "动量定理", _MERGE),
    ("PHYSICS", "应用-光纤通信-激光测距-激光武器等", "merge", "", "激光", _MERGE),
    ("PHYSICS", "表达式-δu-q-w", "merge", "", "热力学第一定律", _MERGE),
    ("PHYSICS", "应用-交流感应电动机", "merge", "", "涡流", _MERGE),
    ("PHYSICS", "表达式-e-n-n为线圈匝数", "merge", "", "法拉第电磁感应定律", _MERGE),
    ("CHEMISTRY", "久置的浓硝酸呈黄色-如何除去-怎样保存浓硝酸", "merge", "", "硝酸", _MERGE),

    ("CHEMISTRY", "查阅资料-了解工业生产中提高电镀质量的方法", "delete", "", "", _DELETE),
    ("CHEMISTRY", "硫酸是中学化学实验室的常见药品-其性质有", "delete", "", "", _DELETE),
    ("CHEMISTRY", "依据图示关系-请回答", "delete", "", "", _DELETE),
    ("CHEMISTRY", "在一定温度下-已知以下三个反应的平衡常数", "delete", "", "", _DELETE),
    ("CHEMISTRY", "1-根据的结构特征-预测其可能的化学性质", "delete", "", "", _DELETE),
    ("CHEMISTRY", "黄酮哌酯是一种解痉药-可通过如下路线合成", "delete", "", "", _DELETE),
    ("CHEMISTRY", "化合物g可用于药用多肽的结构修饰-其人工合成路线如下", "delete", "", "", _DELETE),
    ("CHEMISTRY", "某烷烃的结构简式为", "delete", "", "", _DELETE),
    ("CHEMISTRY", "以fe3o4为原料炼铁-主要发生如下反应", "delete", "", "", _DELETE),
    ("CHEMISTRY", "步骤③和步骤④的操作顺序能否颠倒-为什么", "delete", "", "", _DELETE),
    ("CHEMISTRY", "手机-电脑的锂电池是一次电池还是二次电池", "delete", "", "", _DELETE),
    ("CHEMISTRY", "na22", "delete", "", "", _DELETE),
    ("PHYSICS", "表达式-f-g", "delete", "", "", _DELETE),

    # ---- 第二批（2026-09-14）：抽取时把列表项的**行首标签**当成了名字 ----
    # 判据只认有据可查的：这些节点的名字脱离父主题就不成一个知识点名，
    # 而**成品包里剥离前的原边界**写明了它们到底讲什么（暂存包这边只剩定位串）。
    # 形如 `重点步骤`/`用途`/`图示` 的节点用真名根本检索不到——学生的问题里
    # 不会出现"图示"这种词——所以改名是让它们从死节点变回可召回。
    ("PHYSICS", "重点步骤", "rename", "验证力的平行四边形定则", "", _LABEL
     + "原边界：(1)第一次拉：两把称…(2)第二次拉…作力的图示，再用平行四边形定则合成"),
    ("PHYSICS", "核心分析方法", "rename", "三力平衡的图解法", "", _LABEL
     + "原边界：（1）图解法（矢量三角形法）最常用直观；适用：三力平衡问题…"),
    ("PHYSICS", "公式选用原则", "rename", "匀变速直线运动公式的选用原则", "", _LABEL
     + "原边界：三个公式共涉及五个物理量…不涉及位移选 v=v0+at…"),
    ("PHYSICS", "过程分析", "rename", "静电感应", "", _LABEL
     + "原边界：将金属导体放到外电场 E0 中，自由电子移动，导体内部出现反向电场 E'"),
    ("PHYSICS", "概念理解", "rename", "磁性、磁体与磁极", "", _LABEL
     + "原边界：（1）磁性…（2）磁体…（3）磁极…不存在磁单极"),
    ("PHYSICS", "工作原理", "rename", "电磁流量计的工作原理", "", _LABEL
     + "原边界：流量 Q…导电液体中自由电荷在洛伦兹力作用下偏转，a、b 间出现电势差"),
    ("PHYSICS", "图示", "rename", "折射定律", "", _LABEL
     + "原边界：折射定律：(1)内容：如图所示…(2)表达式：＝n12"),
    ("PHYSICS", "核心规律", "rename", "远距离输电的规律", "", _LABEL
     + "原边界：（1）升压变压器…（3）输电电流…（6）减小输电线电能损失的主要途径"),
    ("PHYSICS", "图例", "rename", "多用电表面板标识的含义", "", _LABEL
     + "原边界：欧姆表的欧姆调零旋钮、指针定位螺丝、表笔的正负插孔"),
    ("PHYSICS", "用途", "rename", "多用电表的使用", "", _LABEL
     + "原边界：①测电压…②测电流…多用电表与被测电路并联/串联"),
    ("CHEMISTRY", "主要方法", "rename", "反应热的计算方法", "", _LABEL
     + "原边界：①根据热化学方程式计算…②总能量…③键能…④盖斯定律"),
    ("CHEMISTRY", "实质", "rename", "盐类水解的实质", "", _LABEL
     + "原边界：盐电离→破坏水的电离平衡→c(H＋)≠c(OH－)→溶液呈酸碱性"),
    # 括号被剥掉，`SO42-` 剩成 `SO`；同科目的材料标题也带这个残缺写法
    ("CHEMISTRY", "so检验的易错点", "rename", "SO42-检验的易错点", "", _LABEL
     + "原边界：（1）误将 Ag＋、Pb2＋判断成 SO42-…（3）用稀盐酸酸化，排除干扰"),
    # 名字是整句陈述，不是知识点名（19 字，绕过了以 24 字为界的判据）
    ("CHEMISTRY", "nox是汽车尾气中的主要污染物之一", "rename", "NOx 对大气的污染", "", _LABEL
     + "名字是陈述句；同科目材料：工业尾气中的 NOx 常用碱液吸收处理"),

    # 与同科目已存在的节点讲同一件事：并进去（目标按 slug 给）
    ("PHYSICS", "模型分类", "merge", "", "模型解读", _MERGE
     + "（目标名 卫星的追及与相遇问题，同一主题下；原边界讲的是两卫星相距最近/最远）"),
    ("PHYSICS", "理解", "merge", "", "动能", _MERGE
     + "（原边界讲动能的瞬时性、相对性、标量性与正负，与节点「动能」是同一件事）"),

    # ---- 第三批（2026-09-14）：同样是行首标签，判据挑得更紧 ----
    # 只收"原边界把该叫什么写死了"的：同一实验的 目的/结论/误差 三个碎片并成一个节点，
    # 其余（如 `测周期`/`约束条件改变`）边界里看不出属于哪个实验，**登记不动**。
    ("PHYSICS", "实验目的", "rename", "探究向心力大小的影响因素", "", _LABEL
     + "原边界：探究向心力与半径、角速度、质量的关系"),
    ("PHYSICS", "实验结论", "merge", "", "实验目的", _MERGE
     + "（角速度、质量相同时向心力与半径成正比…同一实验的结论部分）"),
    ("PHYSICS", "实验误差", "merge", "", "实验目的", _MERGE
     + "（小球质量半径变化、仪器不水平、标尺读数不准…同一实验的误差部分）"),
    ("PHYSICS", "常见力分析", "rename", "重力、弹力与摩擦力的对比", "", _LABEL
     + "原边界：重力 地球吸引 G=mg；弹力 接触形变；摩擦力…三者的产生、方向与大小"),
    ("PHYSICS", "分解法则", "rename", "力的分解法则", "", _LABEL
     + "原边界：（1）平行四边形定则…（2）按力的作用效果分解"),
    ("PHYSICS", "多过程问题", "rename", "多过程运动问题的分析方法", "", _LABEL
     + "原边界：运动包含几个阶段就要分段分析，各段交接处的速度是连接各段的纽带"),
    ("PHYSICS", "函数法", "rename", "追及相遇问题的判别式法", "", _LABEL
     + "原边界：设两物体在 t 时刻相遇，列关于 t 的方程…Δ>0 有两解说明两物体相遇"),
    ("PHYSICS", "图象法", "rename", "追及相遇问题的图象法", "", _LABEL
     + "原边界：分别作出两个物体的位移图像，两图像相交则说明两物体相遇"),
    ("PHYSICS", "守恒条件", "rename", "机械能守恒的条件", "", _LABEL
     + "原边界：「只有重力或弹力做功」并非「只受重力或弹力作用」…"),
    ("PHYSICS", "三类情况分析", "rename", "圆周运动临界问题的三类情况", "", _LABEL
     + "原边界：水平转盘恰好不滑动、绳子被拉断…"),
    ("PHYSICS", "模型临界问题", "rename", "动量守恒中的临界问题", "", _LABEL
     + "原边界：上升到最大高度时 m 与 M 具有共同的水平速度，系统水平方向动量守恒"),
    ("PHYSICS", "两个重要结论", "rename", "带电粒子偏转的两个重要结论", "", _LABEL
     + "原边界：不同带电粒子从静止经同一电场加速再经同一偏转电场射出，偏移量与偏转角相同"),
    ("PHYSICS", "图象", "rename", "简谐运动的振动图象", "", _LABEL
     + "原边界：从平衡位置开始计时，函数表达式为 x＝Asinωt"),
    ("PHYSICS", "图象信息", "rename", "振动图象的信息", "", _LABEL
     + "原边界：由图象可得出振幅 A、周期 T（或频率 f）和初相位 φ0"),
    ("PHYSICS", "周期性", "rename", "简谐运动的周期性", "", _LABEL
     + "原边界：相隔 T/2 的两个时刻弹簧振子的位置关于平衡位置对称"),
    ("PHYSICS", "对称性", "rename", "简谐运动的对称性", "", _LABEL
     + "原边界：A 与 B 间运动，O 为平衡位置，C 与 D 关于 O 对称，tOB＝tOA"),
]


def _retarget_tables(fixes: list[tuple[str, str, str, str, str, str]]) -> None:
    """把**其它权威表**里指向被并/被删节点的行改指目标或删掉。

    删一个节点不止删它自己：章节覆盖、别名、边界、前置、材料绑定五张表都可能按 slug
    引用它，引用留着就是悬空行——门禁会报（`validate_chapter_map_slugs` 会直接拒绝），
    而生成器只是安静跳过。改一处要连同它牵动的引用一起改。
    """
    merge_target = {(s, slug): (s, new_slug) for s, slug, act, _n, new_slug, _r in fixes
                    if act == "merge"}
    gone = {(s, slug) for s, slug, act, _n, _ns, _r in fixes if act in ("merge", "delete")}

    # 表名 -> 含节点 slug 的列；prereq 的第三列也要一起改，否则前置悬空
    tables: dict[str, tuple[str, ...]] = {
        "chapter_map.csv": ("slug",),
        "alias_map.csv": ("slug",),
        "boundary_map.csv": ("slug",),
        "prereq_map.csv": ("slug", "prerequisite"),
        "material_bindings.csv": ("point_slug",),
    }
    for name, columns in tables.items():
        path = TABLE.parent / name
        if not path.exists():
            continue
        with path.open(encoding="utf-8", newline="") as fh:
            rows = list(csv.DictReader(fh))
        if not rows:
            continue
        header = list(rows[0].keys())
        kept, changed, dropped = [], 0, 0
        for row in rows:
            subject = row.get("subject", "")
            hit = any((subject, row.get(col, "")) in gone for col in columns)
            if not hit:
                kept.append(row)
                continue
            # 被并：本行改指目标；被删：整行丢弃
            retargeted = False
            for col in columns:
                target = merge_target.get((subject, row.get(col, "")))
                if target:
                    row[col] = target[1]
                    retargeted = True
            if retargeted:
                kept.append(row)
                changed += 1
            else:
                dropped += 1
        # 改指后可能产生完全相同的两行，按**整行**去重。
        # 不能按"第一列"去重：各表主键不同，material_bindings 的第一列是 point_slug，
        # 多条材料绑到同一节点会被误当成重复删掉（实测误删 9 行）。
        deduped = list({tuple(sorted(row.items())): row for row in kept}.values())
        if changed or dropped or len(deduped) != len(rows):
            with path.open("w", encoding="utf-8", newline="") as fh:
                writer = csv.DictWriter(fh, fieldnames=header)
                writer.writeheader()
                writer.writerows(deduped)
            print(f"  {name}: 改指 {changed} 行、删除 {dropped} 行、去重后 {len(deduped)} 行")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    with TABLE.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.reader(fh))
    header, body = rows[0], rows[1:]
    index = {(r[0], r[1]): i for i, r in enumerate(body) if len(r) >= 2}

    added = updated = 0
    for subject, slug, action, new_name, new_slug, reason in FIXES:
        row = [subject, slug, action, new_name, new_slug, reason]
        key = (subject, slug)
        if key in index:
            if body[index[key]] != row:
                body[index[key]] = row
                updated += 1
        else:
            body.append(row)
            added += 1

    print(f"node_actions.csv：新增 {added} 行、改写 {updated} 行"
          f"（rename {sum(1 for f in FIXES if f[2] == 'rename')}、"
          f"merge {sum(1 for f in FIXES if f[2] == 'merge')}、"
          f"delete {sum(1 for f in FIXES if f[2] == 'delete')}）")
    if args.write:
        with TABLE.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.writer(fh)
            writer.writerow(header)
            writer.writerows(body)
        print(f"已写入 {TABLE}（现共 {len(body)} 行）")
        _retarget_tables(FIXES)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
