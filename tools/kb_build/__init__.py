# -*- coding: utf-8 -*-
"""知识库构建工具：把可审校的权威表生成成品知识包。

设计前提（来自 2026-09-12 全量审计）：
- 成品包此前只在仓库外一次性生成，仓库内无法重放，因此缺陷无法被系统性修正。
  本包把"成品"变成"权威表 + 生成器"的可重放产物。
- 管线的忠实性由 round-trip 保证：空表生成必须与现行成品逐字段等价。

权威表位于 tools/kb_build/tables/，均为 CSV，一行一个知识点/章节/材料：
- node_actions.csv  知识点改名/合并/删除
- chapter_map.csv   知识点 -> 教材册·章·主题（章节归属唯一权威）
- alias_map.csv     知识点 -> 别名（只允许同专题来源）
- boundary_map.csv  知识点 -> 边界（适用范围/前提/易错；禁止原文摘录）
- prereq_map.csv    知识点 -> 前置（空即无前置）
- material_bindings.csv  材料 slug -> 知识点 slug（同专题内绑定）

生成物写回 core/data/src/main/resources/knowledge/。
"""
