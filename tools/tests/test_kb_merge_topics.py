# -*- coding: utf-8 -*-
"""`merge_topics` 的用例。

它消灭的失败（登记册 P 节）：同名主题在旧教材位置与 2019 版位置各存一份、节点散在两边
（「化学平衡」曾 4 份、「物质的量」4 份）。去重后：
- 表里 80 条动作在成品上必须全部"已完成"（重放 0 改动）；
- 除两个**显式登记**的同名对（跨册综合桶 vs 教材位置），不该再出现"同名且都带点"的主题；
- CLI 必须真的能跑（M-08 的教训：库函数用例全绿、`main()` 一跑就崩）。
"""

from __future__ import annotations

import unittest

from kb_build import merge_topics as MT, pack_io

# 允许保留的同名对（都有登记在案的正当理由，见 test 里的注释）。
REGISTERED_CONFLICTS = {
    ("PHYSICS", "3 电磁感应现象及应用"),   # 教材位置（选必二内容）+ 跨册综合桶（线框模型）
    ("BIOLOGY", "细胞的多样性和统一性"),    # 教材位置 + 细胞的生命历程下（细胞的全能性，待归位）
}


def _same_name_conflicts(pack: dict) -> set[tuple[str, str]]:
    out = set()
    from collections import defaultdict
    for s in pack["subjects"]:
        by_name = defaultdict(list)
        for t in s["topics"]:
            by_name[t["name"]].append(t)
        for name, ts in by_name.items():
            if sum(1 for t in ts if t.get("knowledgePoints")) > 1:
                out.add((s["subject"], name))
    return out


class MergeTopicsTest(unittest.TestCase):
    def test_cli_runs_without_crashing(self):
        self.assertEqual(0, MT.main([]), "merge_topics 的 CLI 在成品上应当跑通且 0 改动")

    def test_all_actions_already_applied_on_shipped(self):
        """重放必须 0 改动——表是"已执行账本"，不是待办。"""
        pack = pack_io.load_json(pack_io.pack_path())
        stats = MT.apply_actions(pack, MT.load_actions())
        self.assertEqual(0, stats["merged_topics"] + stats["points_moved"] +
                         stats["deleted_empty"] + stats.get("created", 0),
                         "还有没执行完的动作")

    def test_no_unregistered_same_name_conflicts(self):
        """除两个显式登记的同名对外，成品里不该再有"同名且都带点"的主题。

        若这条红了：要么是新的同名重复出现了（去重没跟上入库），要么是登记对失效了
        （该收口了）——两种都必须被看见。
        """
        pack = pack_io.load_json(pack_io.pack_path())
        self.assertEqual(REGISTERED_CONFLICTS, _same_name_conflicts(pack))

    def test_merge_moves_points_and_retires_topic(self):
        """合成包：merge 把点搬到 canonical、删空 stale、台账记一条 MERGE。"""
        pack = {"packId": "t", "subjects": [{
            "subject": "MATH",
            "topics": [
                {"slug": "root", "name": "根", "sourceLocator": "", "parentSlug": None, "knowledgePoints": []},
                {"slug": "root·old", "name": "旧", "sourceLocator": "", "parentSlug": "root",
                 "knowledgePoints": [{"slug": "p1", "name": "点一", "boundary": "b"}]},
                {"slug": "root·new", "name": "新", "sourceLocator": "", "parentSlug": "root",
                 "knowledgePoints": [{"slug": "p2", "name": "点二", "boundary": "b"}]},
            ]}]}
        actions = [{"subject": "MATH", "action": "merge", "slug": "root·old",
                    "target_slug": "root·new", "reason": "t"}]
        errors, _ = MT.preflight(pack, actions)
        self.assertEqual([], errors)
        stats = MT.apply_actions(pack, actions)
        topics = {t["slug"] for t in pack["subjects"][0]["topics"]}
        self.assertNotIn("root·old", topics)
        self.assertEqual(1, stats["points_moved"])
        new_topic = next(t for t in pack["subjects"][0]["topics"] if t["slug"] == "root·new")
        self.assertEqual({"p1", "p2"}, {p["slug"] for p in new_topic["knowledgePoints"]})
        self.assertEqual("kb:t:math:topic:root·old", stats["ledger"][0]["nodeId"])
        self.assertEqual("kb:t:math:topic:root·new", stats["ledger"][0]["supersededBy"])
        # 幂等
        again = MT.apply_actions(pack, actions)
        self.assertEqual(0, again["merged_topics"] + again["points_moved"])


if __name__ == "__main__":
    unittest.main()
