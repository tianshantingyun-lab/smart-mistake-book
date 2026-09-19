# -*- coding: utf-8 -*-
"""`fix_bad_names` 的定稿动作必须真的落到成品上。

## 它消灭的失败（登记册 M-05）

`fix_bad_names.py` 把 65 条"坏名分流"定稿（rename / merge / delete，判据都写明了引用的
原文内容）——但它只把决定写进 `node_actions.csv`，而**那张表已作废**（README：其 slug 与
成品不是同一坐标系），执行者 `build.py` 也已停用。于是 2026-09-19 实测：

- 23 条改名、12 条合并、10 条删除**仍停在纸上**，成品里那些名字还是残句
  （`定义：通电导线在磁场中受到的力叫安培力。`、`(1)写出分子式为C5H12的烷烃的结构简式：`）
- 其中绝大多数用的**就是成品的 slug**——"坐标系不一致"这个作废理由对它们并不成立

这是"决策已定、无人执行"的静默停摆：没有门会红，因为门只看成品。
本用例把它钉住：**凡是 slug 在成品里的定稿行，要么已生效，要么在这份显式例外表里**；
例外表本身也被反向校验（例外过时了必须删掉），不让它变成藏东西的地方。
"""

from __future__ import annotations

import unittest

from kb_build import fix_bad_names as F, pack_io

# 逐条写清"为什么这条**不**该被应用"，而不是留白。
NOT_APPLIED: dict[tuple[str, str], str] = {
    ("PHYSICS", "工作原理"): "权威改名表已给过另一个决定（point_rename.csv 优先）",
    ("PHYSICS", "实验结论"): "幸存者「实验目的」不在成品里；且它自己挂在 PHYSICS·综合 这类退化主题下",
    ("PHYSICS", "实验误差"): "同上",
    ("PHYSICS", "应用-光纤通信-激光测距-激光武器等"):
        "边界讲的是全反射（光纤通信的原理），不是激光——并入「激光」会把内容放到错的节点上，"
        "应先改绑材料再定",
    ("CHEMISTRY", "查阅资料-了解工业生产中提高电镀质量的方法"): "FIXES 判 delete，读材料后改判 rename（已执行）；见下方通则",
    ("CHEMISTRY", "硫酸是中学化学实验室的常见药品-其性质有"): "同上",
    ("CHEMISTRY", "在一定温度下-已知以下三个反应的平衡常数"): "同上",
    ("CHEMISTRY", "1-根据的结构特征-预测其可能的化学性质"): "同上",
    ("CHEMISTRY", "黄酮哌酯是一种解痉药-可通过如下路线合成"): "同上",
    ("CHEMISTRY", "以fe3o4为原料炼铁-主要发生如下反应"): "同上",
    ("CHEMISTRY", "步骤③和步骤④的操作顺序能否颠倒-为什么"): "同上",
    ("CHEMISTRY", "手机-电脑的锂电池是一次电池还是二次电池"): "同上",
    ("CHEMISTRY", "na22"): "同上（它还曾被别的点当前置引用，改名后 slug 不变，引用不受影响）",
    ("PHYSICS", "表达式-f-g"): "同上",
}
# 通则（2026-09-19 用户批准后更新）：这 10 条上表写的动作是 delete，但**读完它们各自绑定的
# 材料后改判为 rename**——材料是成体系的知识内容（不是题干），而材料标题本身就是该知识点应有的
# 名字（`na22` 绑着《过氧化钠的强氧化性与还原性》）。改名已执行，落在 `point_rename.csv`；
# FIXES 里的 delete 因此**永远不该被执行**，故列为例外。判据见 `docs/kb-cleanup-decisions-2026-09-19.md`。
# 教训（连同这条一起留下）：判"残渣"之前要用它绑定的材料复核一遍——材料标题常常就是正确的节点名。


def _state(subject: str, slug: str, act: str, new_name: str, new_slug: str,
           nodes: dict) -> str:
    """返回 'applied' / 'pending' / 'absent'（slug 不在成品里）。"""
    key = (subject, slug)
    if act != "merge" and key not in nodes:
        return "absent"
    if act == "merge":
        return "pending" if key in nodes else "applied"
    if key not in nodes:
        return "absent"
    return "applied" if nodes[key]["name"] == new_name else "pending"


class BadNameActionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        pack = pack_io.load_json(pack_io.pack_path())
        cls.nodes = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}

    def test_every_in_pack_decision_is_applied_or_explicitly_exempted(self):
        stranded = []
        for subject, slug, act, new_name, new_slug, _reason in F.FIXES:
            if _state(subject, slug, act, new_name, new_slug, self.nodes) != "pending":
                continue
            if (subject, slug) in NOT_APPLIED:
                continue
            stranded.append(f"[{subject}] {act} {slug[:44]}")
        self.assertEqual(
            [],
            stranded,
            "这些定稿动作仍停在纸上（该移植进 point_rename.csv / point_merge.csv / "
            "point_delete.csv 后跑执行器）：" + str(stranded[:8]),
        )

    def test_the_exemption_list_has_no_stale_entries(self):
        """例外表不许腐烂：条目一旦不再 pending（已生效或 slug 消失），必须从这里删掉。

        没有这条反向校验，例外表就会变成"什么都能塞进来"的垃圾桶——那等于把门关掉。
        """
        stale = [
            f"{key[0]}/{key[1]}"
            for key in NOT_APPLIED
            for subject, slug, act, new_name, new_slug, _r in F.FIXES
            if (subject, slug) == key
            and _state(subject, slug, act, new_name, new_slug, self.nodes) != "pending"
        ]
        self.assertEqual([], stale, "例外表里的这些行已不再 pending，请删掉例外条目")

    def test_every_exemption_key_is_a_real_decision(self):
        keys = {(subject, slug) for subject, slug, *_ in F.FIXES}
        self.assertEqual(set(), set(NOT_APPLIED) - keys, "例外表里有 FIXES 里不存在键")


if __name__ == "__main__":
    unittest.main()
