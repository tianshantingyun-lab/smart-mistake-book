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
    ("CHEMISTRY", "查阅资料-了解工业生产中提高电镀质量的方法"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "硫酸是中学化学实验室的常见药品-其性质有"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "在一定温度下-已知以下三个反应的平衡常数"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "1-根据的结构特征-预测其可能的化学性质"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "黄酮哌酯是一种解痉药-可通过如下路线合成"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "以fe3o4为原料炼铁-主要发生如下反应"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "步骤③和步骤④的操作顺序能否颠倒-为什么"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "手机-电脑的锂电池是一次电池还是二次电池"): "有 1 条材料绑定；见下方删除类通则",
    ("CHEMISTRY", "na22"): "有 1 条材料绑定、且被别的点当前置引用；见下方删除类通则",
    ("PHYSICS", "表达式-f-g"): "有 1 条材料绑定；见下方删除类通则",
}
# 通则：`point_delete.csv` 的准入条件是"无材料绑定"（orphan 风险＝0），而这 10 条各绑着
# 1 条材料——它们要先逐条读那条材料，决定是"改绑到正确节点后删点"还是"连材料一起删"
# （材料本身可能就是同一段题干）。这是内容判定，不是搬运，故不擅自应用。


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
