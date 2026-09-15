# -*- coding: utf-8 -*-
"""知识库内容质量门：把"缺陷归零"变成可执行断言。

每一项都是 2026-09-12 全量审计里实测到的缺陷类别，目标值均为 0。
修前跑必然失败，进度就是指标单调降到 0。

判定原则：能用结构证明的就不靠人工承诺。例如"前置图是假链"不靠抽样，
而是要求每条前置都能回指权威表 prereq_map.csv；表里没有的即为未声明。

用法：python tools/build-knowledge-pack.py --gate [--json]
"""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, field

from kb_build import pack_io, tables

# 名称不是知识点名的若干形态（与审计脚本一致的口径）
_GENERIC_NOUNS = {
    "定义", "意义", "公式", "分类", "性质", "概念", "基本规律", "基本概念", "解题思路",
    "解题方法", "解题的思路", "解题关键", "解题的关键", "分析方法", "应用场景", "关键物理量",
    "产生条件", "模型示意图", "运动特征", "核心思想", "两类问题", "特点", "内容", "作用",
    "规律", "方法", "步骤", "条件", "结果", "现象", "原理", "结构", "功能", "组成", "类型",
    "基本方法", "研究对象", "思路", "要点", "注意事项", "表现形式", "基本思路", "解题策略",
}
_EXAM_STEM = re.compile(r"^（?\d{4}·")
_TRAILING_PUNCT = re.compile(r"[．。，、；：]$")
_STRIPPED_FORMULA = re.compile(r"（\s*）|\(\s*\)|，\s*，|即中")
_TITLE_ONLY = re.compile(r"[^：:]{0,10}[：:]$")

# 抽取事故：教辅的题干引导语、练习指令、答案标记被当成知识点抽了出来。
# 形态是"指令词 + 终止符且没有知识对象"，与真知识点可区分：
#   `已知函数.`            是残片（删除）
#   `已知函数单调性求参数`   是真知识点（保留）
_RESIDUE_PATTERNS = tuple(re.compile(p) for p in (
    r"^已知.{0,20}[：。.]$",
    r"^按要求",
    r"^判断正误|^正误判断",
    r"^按系统命名法",
    r"^易错辨析|^深度思考",
    r"^(结果讨论|注意事项|思考|讨论|解析|点评|预测|答案|分析)\s*[：:。.]?$",
    r"^下列",
    r"^【典例|^【变式",
    r"正确的是|不正确的是",
    r"^组：",
    # 练习题干的指令式与填空式形态（2026-09-13 补）。
    # 判据是"要学生做一个动作"，而不是陈述一个知识点，因此指令词只在句首算数：
    # `写出下列物质的化学式` 是题干，而 `竞争型离子方程式的书写(先强后弱法)` 是真方法。
    r"_{2,}|　",                                  # 填空下划线 / 全角空格占位
    r"下列|上表|下表|如下图|如图|如图所示",
    r"^(请)?(写出|书写|回答|填写|填空|填序号|完成下列|配平下列|指出下列|判断下列|计算下列)",
    r"打“√”|打√|打“×”",
    r"设计实验|进行如下实验|改进前后|装置图|流程如图",
))

# LaTeX 命令被吃掉反斜杠后留下的词尾残片（不含分隔符，用于判断命令名结尾）。
#
# 机理（2026-09-12 由 latex_command_census 普查确认）：上游生成时把 \a(BEL)
# \b(BS) \f(FF) \v(VT) 这些控制字符当成不可打印字符剥掉了，而 \t \r \n 作为
# 常见空白保留。于是 `\cdot\vec` 变成 `\cdotec`、`\cos\alpha` 变成 `\coslpha`：
# 损坏表现为"后一个命令名被前一个命令吞掉"，形成 \coslpha 这类不存在的命令。
_LATEX_TAILS = ("rac", "ec", "lpha", "heta", "eta", "ngle", "imes", "ext",
                "ight", "eft", "otin", "eqslantless", "arallel", "athbb")

# 语料里真实存在的 LaTeX 命令（由 latex_command_census / latex_prefix_census 普查得到）。
# 凡是以残片结尾却不在此表的命令名，都是被吞掉反斜杠的产物。
# 注意必须同时收录"只作为前缀出现"的命令（\cdot \cos \sin \pm …）：
# 漏掉它们会把 \cdotec 误留为损坏、或让修复拼不出左侧命令。
_REAL_LATEX_COMMANDS = {
    # 分式族
    "frac", "dfrac", "tfrac", "cfrac",
    # 希腊字母与算符
    "alpha", "beta", "gamma", "delta", "theta", "Theta", "lambda", "mu", "nu",
    "eta", "zeta", "pi", "Pi", "rho", "sigma", "tau", "phi", "varphi", "omega",
    "cdot", "times", "div", "pm", "mp",
    # 三角与函数
    "sin", "cos", "tan", "cot", "sec", "csc", "cosec", "log", "ln", "lg", "exp",
    "lim", "sum", "prod", "int", "partial", "nabla",
    # 关系与集合
    "le", "ge", "ne", "leq", "geq", "neq", "ll", "gg",
    "leqslant", "eqslantless", "nleqslant",
    "in", "notin", "ni", "subset", "subseteq", "supset", "supseteq",
    "cap", "cup", "mid", "parallel", "nparallel", "nshortparallel", "perp",
    "forall", "exists", "emptyset", "varnothing", "complement",
    # 括号、角与向量
    "left", "right", "angle", "triangle", "langle", "rangle", "vec",
    "bar", "hat", "tilde", "dot", "overline", "underline", "sqrt",
    # 排版
    "text", "mathbb", "mathrm", "operatorname", "quad", "qquad",
    "begin", "end", "left.", "right.",
    # 箭头族
    "rightarrow", "Rightarrow", "leftarrow", "Leftarrow",
    "leftrightarrow", "Leftrightarrow",
    "longrightarrow", "Longrightarrow", "longleftarrow", "Longleftarrow",
    "to", "mapsto", "uparrow", "downarrow", "updownarrow", "iff",
    # 否定关系符（amssymb）。语文上就是"不推出/不左推"等，
    # 出现于充分必要条件的材料；漏掉会把 \nRightarrow 误判成损坏。
    "nRightarrow", "nLeftarrow", "nleftarrow", "nLeftrightarrow", "nleftrightarrow",
    "nleq", "ngeq", "nleqq", "ngeqq", "nsim", "ncong", "nequiv",
    "nmid", "nsubset", "nsupset", "nsubseteq", "nsupseteq",
    "nprec", "nsucc", "nvdash", "nvDash", "nVdash", "ntriangleleft", "ntriangleright",
}

_LATEX_TOKEN = re.compile(r"\\([A-Za-z]+)")

# 第三种损坏形态：命令位于 token 开头，前面没有可吞并的命令，于是剥掉转义字符后
# 只剩光秃秃的残片（$rac{...}、$ec{...}）。
# 只列在中文散文里不会误伤的残片：`ar`（\bar）与 `eta`（\beta）在英文单词里
# 遍地都是，无法与普通文字区分，交人工确认，不在此列。
_BARE_TAILS = ("rac{", "ec{", "arphi", "ar{", "lpha", "orall", "ngle",
               "arnothing", "egin{", "arallel", "arphi{")


def _bare_tail_positions(text: str):
    """裸残片的位置：前面既不是反斜杠，也不是 ASCII 字母。"""
    for tail in _BARE_TAILS:
        start = 0
        while True:
            i = text.find(tail, start)
            if i < 0:
                break
            start = i + 1
            if i == 0 or not (text[i - 1] == "\\" or
                              (text[i - 1].isascii() and text[i - 1].isalpha())):
                yield tail, i


def _is_latex_damaged(text: str) -> bool:
    """按命令 token 与裸残片判定损坏。

    不逐残片回扫，避免把 \\theta 算成 "htheta"、把 \\notin 算成 "notin" 这类自伤。
    """
    for match in _LATEX_TOKEN.finditer(text):
        name = match.group(1)
        if name in _REAL_LATEX_COMMANDS:
            continue
        if any(name.endswith(tail) for tail in _LATEX_TAILS):
            return True
    return next(_bare_tail_positions(text), None) is not None


def _is_latex_damaged(text: str) -> bool:
    for tail in _LATEX_TAILS:
        start = 0
        while True:
            i = text.find(tail, start)
            if i < 0:
                break
            start = i + 1
            # 只判命令名末尾。带分隔符的残片（rac{ / ec{ / ext{ / eft(）自带终止符，
            # 后面跟参数首字母是正常的；不带分隔符的（ight / lpha …）若后面还跟字母，
            # 说明是 \Rightarrow 里的 "ight" 这类中缀，跳过。
            if not tail.endswith(("{", "(")):
                after = i + len(tail)
                if after < len(text) and text[after].isascii() and text[after].isalpha():
                    continue
            # 回头收集同一个命令名里的字母（不含反斜杠本身）
            j = i
            while j > 0 and text[j - 1].isascii() and text[j - 1].isalpha():
                j -= 1
            if j == 0 or text[j - 1] != "\\":
                return True                      # 命令名前面没有反斜杠：反斜杠被吃
            name = text[j:i] + tail.rstrip("{(")
            if name not in _REAL_LATEX_COMMANDS:
                return True                      # 拼出不存在的命令：被前一个命令吞掉
    return False

_CONTROL_CHARS = "\x0c\t\r\x08\x07\x0b"


@dataclass
class Metric:
    key: str
    title: str
    value: int = 0
    detail: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return self.value == 0


def is_extraction_residue(name: str) -> bool:
    """是否为抽取事故（题干引导语/练习指令/答案标记），而不是知识点。"""
    stripped = name.strip()
    return any(pattern.search(stripped) for pattern in _RESIDUE_PATTERNS)


def _is_bad_name(name: str) -> str | None:
    stripped = name.strip()
    bare = stripped.rstrip("：:．。 ")
    # 抽取残渣先判：它们要删除，而"只有标题冒号"要改名合并，动作不同不能混为一类
    if is_extraction_residue(stripped):
        return "题干/答案标记"
    if _TITLE_ONLY.fullmatch(stripped) and len(bare) <= 10:
        return "只有标题冒号"
    if bare in _GENERIC_NOUNS:
        return "通用名词无主语"
    if _EXAM_STEM.match(stripped):
        return "高考题干残句"
    if _STRIPPED_FORMULA.search(stripped):
        return "公式被剥离"
    # 知识点名不该是句子。实测 >24 字的名称全部是残句/题干/公式串，
    # 没有一条是真知识点，所以不附加"以标点结尾"这种会被 rstrip 绕过的条件。
    if len(stripped) > 24:
        return "整句话当名称"
    return None


def _latex_damaged(text: str) -> bool:
    return _is_latex_damaged(text)


def _place_of(boundary: str) -> tuple[str, str]:
    """从定位串取（册, 章）。两种老写法都认：`册 章号·章名` 与 `册·章名`。"""
    match = re.match(r"^定位：(?P<body>[^。]*)", boundary or "")
    if not match:
        return "", ""
    body = match.group("body").replace("（见知识清单/教材）", "").strip()
    segments = [seg.strip() for seg in body.split("·") if seg.strip()]
    if not segments:
        return "", ""
    book, _, tail = segments[0].partition(" ")
    if not tail:
        book, tail = segments[0], ""
    chapter = segments[1] if len(segments) > 1 else tail
    return book, chapter


def evaluate() -> list[Metric]:
    pack = pack_io.load_json(pack_io.pack_path())
    materials = pack_io.load_materials()
    declared_prereq = tables.load_prereq_map()

    points = list(pack_io.iter_points(pack))
    bound_ids = {
        b["knowledgeNodeId"]
        for _path, m in materials
        for b in (m.get("bindings") or [])
    }

    metrics: list[Metric] = []

    # 1) 名称不是知识点名
    m1 = Metric("bad_names", "名称不是知识点名")
    for subject, _t, point in points:
        reason = _is_bad_name(point["name"])
        if reason:
            m1.value += 1
            m1.detail.append(f"[{subject}] {point['name'][:60]} ({reason})")
    metrics.append(m1)

    # 2) 名称含教辅难度星号
    m2 = Metric("starred_names", "名称含 ★ 难度星号")
    for subject, _t, point in points:
        if "★" in point["name"]:
            m2.value += 1
            m2.detail.append(f"[{subject}] {point['name'][:60]}")
    metrics.append(m2)

    # 3) 同名重复（去星号后同名）；统计"多出的份数"，即需要合并掉的节点数
    m3 = Metric("duplicate_names", "同名重复知识点（需合并掉的份数）")
    seen: dict[tuple[str, str], list[str]] = {}
    for subject, _t, point in points:
        canon = re.sub(r"[★☆]+", "", point["name"]).rstrip("．。，、 ")
        seen.setdefault((subject, canon), []).append(point["name"])
    for (subject, canon), names in seen.items():
        if len(names) > 1:
            m3.value += len(names) - 1
            m3.detail.append(f"[{subject}] {canon[:40]} x{len(names)}")
    metrics.append(m3)

    # 4) 零绑定知识点
    m4 = Metric("unbound_points", "无教学材料绑定的知识点")
    pack_id = pack["packId"]
    for subject, _t, point in points:
        node_id = f"kb:{pack_id}:{subject.lower()}:atomic:{point['slug']}"
        if node_id not in bound_ids:
            m4.value += 1
            if len(m4.detail) < 40:
                m4.detail.append(f"[{subject}] {point['name'][:50]}")
    metrics.append(m4)

    # 5) 零绑定材料
    m5 = Metric("unbound_materials", "无任何绑定的教学材料")
    for _path, material in materials:
        if not (material.get("bindings") or []):
            m5.value += 1
            if len(m5.detail) < 40:
                m5.detail.append(f"[{material.get('subject')}] {material.get('title', '')[:50]}")
    metrics.append(m5)

    # 6) 幽灵别名：别名既不等于主名、也不来自当前绑定材料的标题
    titles_by_node: dict[str, set[str]] = {}
    for _path, material in materials:
        for b in (material.get("bindings") or []):
            titles_by_node.setdefault(b["knowledgeNodeId"], set()).add(material.get("title", ""))
    m6 = Metric("ghost_aliases", "幽灵别名（与当前绑定脱节）")
    for subject, _t, point in points:
        node_id = f"kb:{pack_id}:{subject.lower()}:atomic:{point['slug']}"
        titles = titles_by_node.get(node_id, set())
        for alias in point.get("aliases") or []:
            if alias == point["name"]:
                continue
            if alias not in titles:
                m6.value += 1
                if len(m6.detail) < 40:
                    m6.detail.append(f"[{subject}] {point['name'][:30]} -> {alias[:44]}")
                break
    metrics.append(m6)

    # 7) 未声明前置（权威表里没有的一律算违规；定稿不了就该置空）
    m7 = Metric("undeclared_prereq", "未在权威表中声明的前置")
    for subject, _t, point in points:
        for prereq in point.get("prerequisiteSlugs") or []:
            if not tables.is_declared_prereq(declared_prereq, point["slug"], prereq):
                m7.value += 1
                if len(m7.detail) < 40:
                    m7.detail.append(f"[{subject}] {prereq} -> {point['name'][:36]}")
    metrics.append(m7)

    # 8) 边界含第三方原文摘录（权利问题：来源政策不允许保存原文段落）
    from kb_build import textfix
    m8a = Metric("boundary_excerpt", "boundary 含第三方原文摘录（分发风险）")
    # 8b) 边界没写真边界（只有定位串/占位/为空）
    # 这两项过去是互补的：8b 判"以 `定位：` 开头"，而这个包的规定格式就是 `定位：…。`，
    # 于是 8b 恒等于全部节点、8a 在其补集上恒真，两项都没有信息量（实测成品包 2573 条：
    # 8b 2573、两项皆假 0）。现在 8b 判"定位串之外没有内容"，一条写了真边界的节点
    # 可以同时通过两项。
    m8 = Metric("locator_boundary", "boundary 只有定位串/占位，没有真边界")
    for subject, _t, point in points:
        boundary = point.get("boundary") or ""
        if textfix.has_verbatim_excerpt(boundary):
            m8a.value += 1
            if len(m8a.detail) < 40:
                m8a.detail.append(f"[{subject}] {point['name'][:30]}: {boundary[:60]}")
        if textfix.is_locator_only(boundary):
            m8.value += 1
            if len(m8.detail) < 40:
                m8.detail.append(f"[{subject}] {point['name'][:30]}: {boundary[:50]}")
    metrics.append(m8a)
    metrics.append(m8)

    # 9) 公式损坏
    m9 = Metric("latex_damage", "LaTeX 命令丢失反斜杠")
    for _path, material in materials:
        if any(_latex_damaged(material.get(k) or "") for k in
               ("title", "summaryMarkdown", "applicabilityMarkdown",
                "contentMarkdown", "boundaryMarkdown")):
            m9.value += 1
            if len(m9.detail) < 40:
                m9.detail.append(f"[{material.get('subject')}] {material.get('slug', '')[:60]}")
    metrics.append(m9)

    # 10) 控制字符
    m10 = Metric("control_chars", "材料含控制字符")
    for _path, material in materials:
        blob = "".join(material.get(k) or "" for k in
                       ("title", "summaryMarkdown", "applicabilityMarkdown",
                        "contentMarkdown", "boundaryMarkdown"))
        if any(ch in blob for ch in _CONTROL_CHARS):
            m10.value += 1
            if len(m10.detail) < 40:
                m10.detail.append(f"[{material.get('subject')}] {material.get('slug', '')[:60]}")
    metrics.append(m10)

    # 11) 章节归属：单元覆盖率与"定位串必须由章表（含节点级覆盖）推导"
    from kb_build import gen_chapter_table
    chapter_table = tables.load_chapter_by_source()
    chapter_by_node = tables.load_chapter_map()
    m11 = Metric("chapter_uncovered_units", "章节表未覆盖的来源单元")
    m12 = Metric("chapter_locator_mismatch", "定位串与章节表（含节点级覆盖）不一致的节点")
    m13 = Metric("chapter_no_book", "归属缺失（册/章为空）的节点")
    m14 = Metric("chapter_split_missing_override", "标记为跨章拆分却缺节点级覆盖的节点")
    seen_units: set[str] = set()
    for subject, _t, point in points:
        unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
        seen_units.add(unit)
        entry = chapter_table.get(unit)
        current = _place_of(point.get("boundary") or "")
        if not current[0] or not current[1] or "未归类" in current:
            m13.value += 1
            if len(m13.detail) < 40:
                m13.detail.append(f"[{subject}] {point['name'][:30]}: {point['boundary'][:50]}")
            continue
        override = chapter_by_node.get((subject, point["slug"]))
        if override:
            declared = (override[0], override[1])
        elif entry is None:
            continue
        elif entry["decision"] == "keep_per_node":
            continue
        elif entry["decision"] == "split":
            # 跨章拆分的单元：每个节点都必须有节点级覆盖，否则归属无从确定
            m14.value += 1
            if len(m14.detail) < 40:
                m14.detail.append(f"[{subject}] {unit[:26]} 缺覆盖: {point['name'][:26]}")
            continue
        else:
            declared = (entry["book"], entry["chapter"])
        if current != declared:
            m12.value += 1
            if len(m12.detail) < 40:
                m12.detail.append(
                    f"[{subject}] {point['name'][:26]}: 定位于 {current[0]} {current[1]}，章表为 {declared[0]} {declared[1]}"
                )
    for unit in sorted(seen_units):
        if unit and unit not in chapter_table:
            m11.value += 1
            if len(m11.detail) < 40:
                m11.detail.append(unit[:60])
    metrics.extend((m11, m12, m13, m14))

    # 15) 主题名必须只承载本层信息，路径由树（parentSlug）表达。
    # 改前 445 个 topic 里有 421 个的名字重复了父名全文，于是逐层展开时同一段文字会被
    # 重复四遍——渐进式披露在数据层就不可表达。判定与转换共用一处（shorten_topic_names），
    # 避免门与工具各写一份规则后漂移。
    from kb_build import shorten_topic_names
    offenders = shorten_topic_names.names_carrying_parent_path(pack)
    m15 = Metric("topic_name_carries_path", "主题名重复了父名的路径")
    m15.value = len(offenders)
    m15.detail = offenders[:40]
    metrics.append(m15)

    return metrics


def main(as_json: bool = False) -> int:
    metrics = evaluate()
    failed = [m for m in metrics if not m.ok]
    if as_json:
        print(json.dumps({
            "ok": not failed,
            "metrics": [
                {"key": m.key, "title": m.title, "value": m.value, "ok": m.ok}
                for m in metrics
            ],
        }, ensure_ascii=False, indent=1))
        return 1 if failed else 0

    width = max(len(m.title) for m in metrics)
    print("知识库内容质量门（目标：全部为 0）")
    print("-" * (width + 18))
    for m in metrics:
        mark = "OK " if m.ok else "FAIL"
        print(f"  {mark} {m.title:<{width}} {m.value:>7}")
    if failed:
        print()
        print(f"未通过 {len(failed)} / {len(metrics)} 项；样例：")
        for m in failed:
            print(f"\n  ▶ {m.title}（{m.value}）")
            for line in m.detail[:8]:
                print(f"      {line}")
        return 1
    print("\n全部通过：缺陷归零")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
