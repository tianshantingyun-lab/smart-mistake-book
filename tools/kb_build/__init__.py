# -*- coding: utf-8 -*-
"""知识库构建工具：把可审校的权威表生成成品知识包。

设计前提（来自 2026-09-12 全量审计）：
- 成品包此前只在仓库外一次性生成，仓库内无法重放，因此缺陷无法被系统性修正。
  本包把"成品"变成"权威表 + 生成器"的可重放产物。
- 管线的忠实性由 round-trip 保证：空表生成必须与现行成品逐字段等价。

权威表位于 tools/kb_build/tables/，均为 CSV，一行一个知识点/章节/材料：
- 知识点改名/合并/删除/材料改绑/归位：五张权威动作表
  （point_rename / point_delete / point_merge / material_rebind /
  chapter_point_relocation，见 node_actions.README.md）
- chapter_map.csv   知识点 -> 教材册·章·主题（章节归属唯一权威）
- alias_map.csv     知识点 -> 别名（只允许同专题来源）
- boundary_map.csv  知识点 -> 边界（适用范围/前提/易错；禁止原文摘录）
- prereq_map.csv    知识点 -> 前置（空即无前置）
- material_bindings.csv  材料 slug -> 知识点 slug（同专题内绑定）

写盘拓扑（2026-09-22 单一晋升路径）：所有手术工具默认读写 build/kb-staging/
（staging，不存在时从成品复制基线）；**唯一**能把内容写进
core/data/src/main/resources/knowledge/ 的是 `kb_build.promote`
（22 门 + 表↔包一致性 + roundtrip 全绿才落盘，并原子刷新内容戳与单调 version）。
"""
