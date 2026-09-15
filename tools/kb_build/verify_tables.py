# -*- coding: utf-8 -*-
"""校验人工定稿表的自洽性：新名必须与该节点的文本对得上。

起因：2026-09-13 定稿时把两行的科目对错了（把化学的盐类水解规律写给了物理节点）。
靠"更仔细"无法可靠避免这类错误，所以做成机械检查。

判据：新名的实义字（去掉"的/与/和/及/等"这些通用字）应当大部分出现在该节点自己的
名称+boundary 里。用错节点的证据时，重合度会明显偏低。
不用"引号内文字必须逐字出现"这种判据——reason 里同时有引证和定稿者的转述，分不开。

用法：PYTHONPATH=tools python -m kb_build.verify_tables [--threshold 0.5]
"""

from __future__ import annotations

import argparse
import re

from kb_build import pack_io, tables

# 通用字：几乎所有知识点名都有，不能算作"对得上"的证据
_STOP = set("的与和及等及其了是有在为对用把从到以之其上下中abcxyz")
# 新名里常见的结构性词尾，不参与判定（如"…的规律"里的"规律"）
_GENERIC_SUFFIX = re.compile(r"(的(规律|定义|特点|性质|方法|条件|关系|应用|比较|计算|判断|分析|步骤|本质|意义|作用))$")


def _content_chars(text: str) -> set[str]:
    return {ch for ch in text if ch not in _STOP and not ch.isspace()}


def _is_ascii_only(text: str) -> bool:
    return all(ord(c) < 128 for c in text)


# 已人工核对认可的低重合项：节点实体是公式/符号串，新名读的是内容含义而不是复用原文，
# 所以字面重合度天然低。登记在此是为了让检查能当门禁用，而不是靠放宽阈值掩盖。
ACCEPTED_LOW_OVERLAP: dict[str, str] = {
    "α-γ-β-γ-α-β-l-l-γ-客观题可用": "节点是公式 α⊥γ,β⊥γ,α∩β=l⇒l⊥γ，新名概括其含义",
    "定义": "节点是「受恒定合外力且初速度垂直」的定义句，新名即该概念（类平抛运动）",
    "基本规律": "节点是三条匀变速公式，新名概括其含义",
    "不要漏掉大气压强-同时又要尽可能平衡掉某些大气的压力": "节点是实验注意事项的残句，新名概括其含义",
    "如果要列出各物理量在某一时刻的关系式-可用牛顿第二定律": "节点是三种观点选用的残句，新名概括其含义",
    "al3-al-oh-3-alo-al-oh-4-之间的转化": "节点是 Al3＋/Al(OH)3/[Al(OH)4]－ 的转化式串，新名即「铝三角」",
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--threshold", type=float, default=0.5,
                        help="新名实义字在节点文本中的最低占比")
    args = parser.parse_args(argv)

    rows = tables.load_node_actions()
    overrides = tables._read(tables.NODE_ACTIONS_OVERRIDE)
    pack = pack_io.load_json(pack_io.pack_path())

    node_text: dict[tuple[str, str], str] = {}
    for subject, _topic, point in pack_io.iter_points(pack):
        node_text[(subject, point["slug"])] = f"{point['name']}{point['boundary']}"

    checked = suspicious = accepted = 0
    for row in overrides:
        if row.get("action") != "rename":
            continue
        new_name = (row.get("new_name") or "").strip()
        if not new_name:
            continue
        key = (row["subject"].strip(), row["slug"].strip())
        text = node_text.get(key, "")
        new_chars = _content_chars(_GENERIC_SUFFIX.sub("", new_name))
        if not new_chars:
            continue
        # 纯公式/纯英文的新名没有可比对的汉字，跳过（改由人工在看板上核对）
        if _is_ascii_only("".join(new_chars)):
            continue
        checked += 1
        hit = len(new_chars & _content_chars(text)) / len(new_chars)
        if hit < args.threshold:
            if key[1] in ACCEPTED_LOW_OVERLAP:
                accepted += 1
                continue
            suspicious += 1
            print(f"! 重合 {hit:.0%}  [{key[0]}] {key[1][:48]}")
            print(f"     新名: {new_name}")
            print(f"     节点: {text[:120]}")
            print()

    print(f"参与判定的改命名 {checked} 条；低重合 {suspicious + accepted} 条"
          f"（其中已核对认可 {accepted} 条：节点实体是公式/符号串）")
    if suspicious:
        print("低重合的必须回读原文：常见原因是引错了节点（科目/同名 slug 混淆）。")
        return 1
    print("新名与节点文本全部对得上")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
