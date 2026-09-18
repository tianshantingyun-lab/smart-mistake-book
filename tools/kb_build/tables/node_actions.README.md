# node_actions.csv 的现状：作废（2026-09-19）

本表（871 行：rename 439 / delete 324 / merge 108）的 slug **与成品包不是同一坐标系**
（实测 324 个 delete 的 slug 一个都不在成品里），跑 build.py 全流程会产出比成品更差的
staging（章层挂点 167→352）。裁定见 `docs/kb-problem-register-2026-09-15.md` 的 C-09 条目。

**权威动作日志**（都经过逐条校验 + 幂等 + 无损 + 门，且与成品坐标系一致）：

| 动作 | 表 |
|---|---|
| 改名 | `point_rename.csv` |
| 删除（含材料随删） | `point_delete.csv` |
| 合并 | `point_merge.csv` |
| 材料改绑 | `material_rebind.csv` |
| 归位 / 主题跨章 | `chapter_point_relocation.csv` |
| 新建节点 | `new_points_manual.csv` |
| 单元级章表 | `chapter_by_source.csv` |

本文件保留仅为溯源（它记录的是候选层时代的操作意图），不再被执行。
