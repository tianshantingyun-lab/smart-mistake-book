package com.tingyun.smartmistakebook.feature.tutor

/**
 * 2D/3D 结构化场景渲染（`visualScene` / `TutorVisualSceneRenderer`）已从产品隔离（2026-09-06）。
 *
 * 决策：该功能需长久打磨、当前仅锦上添花、起不到关键作用，故从用户可见 UI 中移除；模型不再产出、
 * 生成任务不再触发、scene 不再渲染。代码、模型字段、渲染器、视觉任务、测试全部保留，改动本哨兵即可恢复。
 *
 * 权威声明见 `docs/model-first-product-boundaries.md` 的 "2026-09-06 结构化场景渲染隔离"。
 *
 * ⚠️ 边界（未来勿碰）：本隔离只针对**结构化场景渲染**（`TutorVisualScene`/`TutorVisualDocumentScene`/`TutorVisualSceneRenderer`）。
 * **`attachedImages`（位图）是另一功能**，与本隔离无关，不受影响。
 */
object TutorVisualIsolation {
    /** true = 结构化场景已隔离（不生成、不渲染）。改回 false 即恢复整个 2D/3D 渲染链。 */
    const val STRUCTURED_SCENE_ISOLATED = true
}
