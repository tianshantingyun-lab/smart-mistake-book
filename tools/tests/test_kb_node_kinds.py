# -*- coding: utf-8 -*-
"""节点 kind 必须落在 App 契约的 KnowledgeNodeKind 里。

## 它消灭的失败

2026-09-19：`create_points.py` 的 KINDS 里带着 `MISCONCEPTION_GUIDE`（那是**材料类型**的
枚举值），本轮 2 个新节点因此越界；`BundledTeachingMaterialsContractTest` 解析内置包时
直接抛 `Knowledge-pack field kind has unknown value 'MISCONCEPTION_GUIDE'`，7 条用例连锁变红。
Python 侧当时没有任何门会提前发现 —— 只能等 Kotlin 用例在最后一刻兜住。

本用例把两侧绑在一起：工具允许的集合必须等于 App 枚举，且**已入包的**节点 kind 必须在其内。
App 枚举增删时这条会立刻红，逼着两边同步。
"""

from __future__ import annotations

import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from kb_build import create_points, pack_io  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parents[2]
ENUM_FILE = REPO / "core" / "model" / "src" / "main" / "kotlin" / "com" / "tingyun" / \
    "smartmistakebook" / "core" / "model" / "ProblemCatalog.kt"


def app_node_kinds() -> set[str]:
    """从 Kotlin 源码里读 KnowledgeNodeKind 的取值（唯一权威源）。"""
    text = ENUM_FILE.read_text(encoding="utf-8")
    match = re.search(r"enum class KnowledgeNodeKind\s*\{(?P<body>[^}]*)\}", text)
    assert match, "找不到 KnowledgeNodeKind 枚举"
    return {tok.strip() for tok in match.group("body").split(",") if tok.strip()}


class NodeKindContractTest(unittest.TestCase):
    def test_tool_kinds_are_subset_of_app_enum(self) -> None:
        """建点工具只能产出 App 认得的 kind；差集只允许 TOPIC（主题层不由本工具建）。"""
        app = app_node_kinds()
        tool = set(create_points.KINDS)
        self.assertEqual(set(), tool - app, "工具允许了 App 不认的 kind")
        self.assertEqual({"TOPIC"}, app - tool, "App 枚举新增了非主题层取值，需同步工具与表")

    def test_shipped_pack_kinds_are_inside_app_enum(self) -> None:
        pack = pack_io.load_json(pack_io.pack_path())
        allowed = app_node_kinds()
        offenders = []
        for subject in pack["subjects"]:
            for topic in subject["topics"]:
                for point in topic.get("knowledgePoints") or []:
                    if point["kind"] not in allowed:
                        offenders.append(f"{subject['subject']}/{point['slug']}={point['kind']}")
        self.assertEqual([], offenders)


if __name__ == "__main__":
    unittest.main()
