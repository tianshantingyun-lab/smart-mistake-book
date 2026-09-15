# -*- coding: utf-8 -*-
"""文本级修正：边界去原文摘录、LaTeX 命令修复。

两件事都属于"能机械判定对错"的修正，因此放在同一处并各自配单测。
"""

from __future__ import annotations

import re

# 边界字段的形态是 `定位：<册 章·主题>。<可选内容>`。
_LOCATOR = re.compile(r"^定位：(?P<position>.+?)。")
# 未定稿时的占位写法
_PLACEHOLDER = "（见知识清单/教材）"


def split_boundary(boundary: str) -> tuple[str, str]:
    """拆出 (定位, 其余内容)。没有定位前缀时定位为空串。"""
    match = _LOCATOR.match(boundary or "")
    if not match:
        return "", (boundary or "")
    return match.group("position"), boundary[match.end():]


def strip_boundary_excerpt(boundary: str) -> str:
    """去掉边界里的第三方原文摘录，只留定位。

    为什么必须去掉：来源登记里这些教辅的使用政策是"仅提取结构化结论与知识目录
    节点，不保存原文段落"，包会被打进 APK 分发。

    **粒度是整段替换，这是刻意的保守选择。** 实测被判为"疑似原文"的 1984 条里有相当
    一部分是"知识结论 + 典例/答案"的拼接体（例如 `利用导数研究函数的最值` 的前三条是
    求最值步骤，后面才跟 `【典例1】（2025·江苏南京·三模）`）。整段替换会连真知识
    一起删掉，但漏放第三方原文是**权利问题**，删掉真知识只是**质量问题**；两者不可
    相提并论。

    **按段过滤已实测试过，不可行（2026-09-13）**：拿成品包 1984 条边界按分号/句号切段、
    段首编号先剥掉、再逐段套 `has_verbatim_excerpt` 的特征，结果是 1857 条"仍有内容
    可留"——但留下的东西里既有整张教材表格、成组选项，也有 `（2025·广东·模拟预测）
    如图所示…` 这样的真题原文（年份特征落在段中间的括号里也能漏），而 `电解质溶液的
    导电性` 那条正好相反：把真结论丢了、把"现有下列8种物质…"留下。这些特征是为
    **条级**判定设计的，段级用会同时漏放原文与误删知识，且错的方向是权利风险。
    要真做这件事，判据只能是**逐条语义改写**，不是形态特征——那是另一个量级的工作，
    不能靠把本函数的判据换个粒度糊过去。
    """
    position, _rest = split_boundary(boundary)
    if not position:
        return boundary or ""
    return f"定位：{position}。"


def has_unvetted_content(boundary: str) -> bool:
    """边界里除了定位串之外**还有任何内容**，即"未经逐段核验的正文"。

    这是 `strip_boundary_excerpt` 的判据：**宁可多删**。它刻意不做摘录识别——
    识别得再准也只是质量判断，而放行一条真原文是权利问题。
    """
    _position, rest = split_boundary(boundary)
    rest = rest.strip()
    return bool(rest) and rest != _PLACEHOLDER


# 边界里出现这些形态，说明粘着的是题目/答案原文，而不是知识结论。
# 特征与命中量来自对成品包 2573 条边界的语料实测（括号里是 1984 条疑似条目上的命中数）。
_EXCERPT_FEATURES = (
    re.compile(r"【\s*(?:典例|变式|例)\s*\d*\s*】"),                        # 144
    re.compile(r"[（(]\s*(?:19|20)\d{2}\s*[·・]"),                          # 161
    re.compile(r"_{2,}|＿{2,}"),                                           # 366
    re.compile(r"[（(][　 ]{2,}[)）]"),                                 # 205
    re.compile(r"正误判断|易错辨析|判断正误|打\s*[“\"]?[√×]"),               # 37
    re.compile(r"(?:^|[；;]\s*)\d{1,2}\s*．\s*\S"),                         # 249
    re.compile(r"(?:下列|以下)[^。；]{0,40}(?:正确的是|不正确的是|错误的是|说法|叙述|判断)"
               r"|正确的是|不正确的是|错误的是"),                            # 176
    re.compile(r"(?:^|[；;]\s*)【?(?:答案|解析|详解|点评)】?\s*[：:　 ]|故选"),  # 3
)
# 选项成组：≥2 个 `A.`/`B.` 且锚在行首或分号之后。
# 不能放宽成"出现 A. 就算"——`A∪B＝B∪A` 这类集合式会污染（宽口径实测 78 条误命中）。
_OPTION_GROUP = re.compile(r"(?:^|[；;]\s*)[A-D]\s*[．.、]\s*\S")
# 填空留白：连跑 ≥4 个空白。必须带语境护栏，否则会命中版面噪声
# （`上升趋势                 下降趋势`、`甲        乙        丙` 这类图注/对照表）。
_BLANK_RUN = re.compile(r"[ 　]{4,}")
_BLANK_CONTEXT_RIGHT = set("。；，、）)（(．./？?！!：:")
_BLANK_CONTEXT_LEFT = set("为是＝=：:填写用出有到")


def _has_blank_run(text: str) -> bool:
    for match in _BLANK_RUN.finditer(text):
        run = match.group()
        if "　" in run:
            return True
        right = text[match.end()] if match.end() < len(text) else ""
        left = text[match.start() - 1] if match.start() > 0 else ""
        if right in _BLANK_CONTEXT_RIGHT or left in _BLANK_CONTEXT_LEFT:
            return True
    return False


def has_verbatim_excerpt(boundary: str) -> bool:
    """边界里是否粘着题目/答案原文（判据是**形态特征**，不是"有内容就算"）。

    与 `has_unvetted_content` 的区别要分清：那个是生成期的保守闸门（除定位外一律不留），
    这个是质量门的**度量**——它要回答"已经收进包里的边界，有多少还带着第三方原文"。
    旧实现把两者混为一谈（除定位外都算摘录），导致质量门的两项指标一项恒真、一项恒假，
    互斥且都没有信息量（成品包 2573 条上实测：`locator_boundary` 2573、两项皆假 0）。

    已知漏判（约 30 条量级，见 gate 的 `name_excerpt` 讨论）：题干信号落在**知识点名**上
    而不在边界上的条目（如名字是 `（2025·江苏苏州·三模）已知数列`、边界只剩 `(1)求的值；`）
    这里看不出来——判据只看边界。
    """
    _position, rest = split_boundary(boundary)
    rest = rest.strip()
    if not rest or rest == _PLACEHOLDER:
        return False
    if any(feature.search(rest) for feature in _EXCERPT_FEATURES):
        return True
    if _has_blank_run(rest):
        return True
    return len(_OPTION_GROUP.findall(rest)) >= 2


def is_locator_only(boundary: str) -> bool:
    """边界里**没有真边界内容**：只有定位串、只有占位，或者干脆为空。

    这是 `locator_boundary` 的正确含义。旧实现判的是"以 `定位：` 开头"，而这个包的
    规定格式**就是** `定位：…。`——该指标因此恒等于全部节点，毫无信息量。
    """
    text = (boundary or "").strip()
    if not text:
        return True
    position, rest = split_boundary(text)
    if not position:
        return False
    rest = rest.strip()
    return not rest or rest == _PLACEHOLDER


# ---------------------------------------------------------------------------
# LaTeX 命令修复
#
# 损坏机理（2026-09-12 语料普查确认，两种形态并存）：
#   1. 被剥掉的控制字符：\a(BEL) \b(BS) \f(FF) \v(VT) 被当不可打印字符删掉，
#      于是命令名被前一个命令吞并 —— \cos\alpha 变成 \coslpha、\cdot\vec 变成 \cdotec。
#   2. 被保留的控制字符：\t(TAB) \r(CR) \n(LF) 作为常见空白留下，
#      于是命令尾巴被孤立 —— \theta 变成 <TAB>heta、\right 变成 <CR>ight、\notin 变成 <LF>otin。
#
# 因此修复规则可以从命令表自动推出，不需要手工枚举每种组合：
#   命令首字母 ∈ {a,b,f,v} -> 形态 1，补齐被吞的反斜杠
#   命令首字母 ∈ {t,r,n}   -> 形态 2，把控制符换回反斜杠
# ---------------------------------------------------------------------------

_STRIPPED_ESCAPES = ("a", "b", "f", "v")   # 被剥掉的控制字符
_KEPT_ESCAPES = {"a": None, "b": None, "f": None, "v": None,
                 "t": "\t", "r": "\r", "n": "\n"}

_TOKEN = re.compile(r"\\([A-Za-z]+)")


def _merged_targets(real_commands) -> dict[str, str]:
    """形态 1：tail -> 完整命令。tail 是命令去掉首字母后的部分。"""
    out: dict[str, str] = {}
    for name in real_commands:
        if name and name[0] in _STRIPPED_ESCAPES and len(name) > 1:
            out.setdefault(name[1:], "\\" + name)
    return out


def _prefixed_targets(real_commands) -> dict[str, str]:
    """形态 2：<控制符>+tail -> 完整命令。"""
    out: dict[str, str] = {}
    for name in real_commands:
        if name and name[0] == "t" and len(name) > 1:
            out.setdefault("\t" + name[1:], "\\" + name)
        elif name and name[0] == "r" and len(name) > 1:
            out.setdefault("\r" + name[1:], "\\" + name)
        elif name and name[0] == "n" and len(name) > 1:
            out.setdefault("\n" + name[1:], "\\" + name)
    return out


def repair_latex_commands(text: str, real_commands) -> str:
    """把被吞掉或被打散的命令补回来。

    real_commands 由调用方传入（gate._REAL_LATEX_COMMANDS），避免两处命令表漂移。
    只做可确证的替换：左侧必须拼出一个真实命令，否则不动。
    """
    if not text:
        return text

    # 形态 3：裸残片（$rac{ -> $\frac{）。歧义残片只在 $...$ 内替换，
    # 否则英文单词里的 ar/eta 会被误伤。
    bare = _bare_tail_repairs(real_commands)
    safe_bare = {k: v for k, v in bare.items() if k not in _MATH_ONLY_TAILS}
    math_bare = {k: v for k, v in bare.items() if k in _MATH_ONLY_TAILS}

    rebuilt = []
    for in_math, chunk in _split_math(text):
        for tail, command in safe_bare.items():
            chunk = _replace_bare_tail(chunk, tail, command, real_commands)
        if in_math:
            for tail, command in math_bare.items():
                chunk = _replace_bare_tail(chunk, tail, command, real_commands)
        rebuilt.append(chunk)
    out = "$".join(rebuilt)

    # 形态 2：控制符前缀（<TAB>heta -> \theta）
    for broken, fixed in _prefixed_targets(real_commands).items():
        out = out.replace(broken, fixed)

    # 形态 1：命令名吞并（\coslpha -> \cos\alpha）。
    # 逐个反斜杠 token 处理：token 名以某命令的 tail 结尾，且剩余前缀是真实命令。
    merged = _merged_targets(real_commands)

    def fix_token(match: re.Match) -> str:
        name = match.group(1)
        if name in real_commands:
            return match.group(0)
        for tail, command in sorted(merged.items(), key=lambda kv: -len(kv[0])):
            if len(name) > len(tail) and name.endswith(tail):
                prefix = name[:len(name) - len(tail)]
                if prefix in real_commands:
                    return "\\" + prefix + command
        return match.group(0)

    out = _TOKEN.sub(fix_token, out)
    return out


def _bare_tail_repairs(real_commands) -> dict[str, str]:
    r"""形态 3 的映射：裸残片 -> 完整命令。

    残片表由命令名推出：`\frac` 掉解析反斜杠后留 `rac`，故 tail -> `\`+name。
    歧义的 `ar`(=\bar) 与 `eta`(=\beta) 也在表内，但调用方只在 $...$ 内使用它们
    （见 _MATH_ONLY_TAILS），避免误伤英文单词。
    """
    out: dict[str, str] = {}
    for name in real_commands:
        if len(name) < 2 or name[0] not in _STRIPPED_ESCAPES:
            continue
        tail = name[1:]
        out.setdefault(tail, "\\" + name)
        out.setdefault(tail + "{", "\\" + name + "{")
        out.setdefault(tail + "(", "\\" + name + "(")
    # 长的优先，避免短残片抢先匹配
    return dict(sorted(out.items(), key=lambda kv: -len(kv[0])))


# 只在数学模式 $...$ 内才敢动的歧义残片（英文单词里也会出现 ar / eta）
_MATH_ONLY_TAILS = ("ar", "ar{", "eta", "eta{")


def _split_math(text: str):
    """按 $ 切成 (是否在数学模式内, 片段) 序列。奇数段位于 $...$ 之内。"""
    for index, part in enumerate(text.split("$")):
        yield index % 2 == 1, part


def _replace_bare_tail(text: str, tail: str, command: str, real_commands) -> str:
    r"""替换裸残片。

    判定"是不是裸的"：往前收同一个字母串；若该串本身是一条真实命令，说明残片
    属于那条命令（由形态 1 处理），不动。否则残片是一条丢了解析反斜杠的命令开头，
    补回反斜杠。这样 `xec{i}`（前缀 x 是变量不是命令）能修，而 `\parallelec` 不被误动。
    """
    out = []
    cursor = 0
    i = text.find(tail)
    while i >= 0:
        # 不带分隔符的残片必须位于词尾：否则会命中 \nRightarrow 内部的 "ar"
        # 这类中缀，把正确文本改坏。带分隔符的（rac{ / ec{ / ar{）自带终止符，不受此限。
        if not tail.endswith(("{", "(")):
            after = i + len(tail)
            if after < len(text) and text[after].isascii() and text[after].isalpha():
                i = text.find(tail, i + 1)
                continue
        j = i
        while j > 0 and text[j - 1].isascii() and text[j - 1].isalpha():
            j -= 1
        belongs_to_command = j > 0 and text[j - 1] == "\\"
        if belongs_to_command or text[j:i] in real_commands:
            i = text.find(tail, i + 1)
            continue
        out.append(text[cursor:i])
        out.append(command)
        cursor = i + len(tail)
        i = text.find(tail, cursor)
    out.append(text[cursor:])
    return "".join(out)


_CONTROL_JUNK = "\x0c\t\r\x08\x07\x0b"


def strip_control_junk(text: str) -> str:
    r"""清掉材料正文里的非空白控制字符，保留换行。

    保留 \n 是因为它标点分段；\t \r \f \v \b(BEL) 在中文正文里没有合法用途，
    它们是上游剥转义字符时留下的残迹（见 LaTeX 修复一节的机理说明）。
    TAB 换成空格而不是直接删，避免把两侧的字粘在一起。
    """
    if not text:
        return text
    # CRLF 是合法换行；单独的 CR 是上游残留的转义残迹，直接去掉而不是换成换行
    out = text.replace("\r\n", "\n").replace("\t", " ").replace("\r", "")
    for ch in _CONTROL_JUNK:
        out = out.replace(ch, "")
    return out


def find_unrepairable_latex(text: str, real_commands) -> list[str]:
    """返回修复后仍不合法、需要人工确认的命令名。"""
    remaining = []
    for match in _TOKEN.finditer(repair_latex_commands(text, real_commands)):
        name = match.group(1)
        if name not in real_commands:
            remaining.append(name)
    return remaining
