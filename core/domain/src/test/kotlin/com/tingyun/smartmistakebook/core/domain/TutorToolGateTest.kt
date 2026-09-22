package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能体页工具面常量（同一页口径，`docs/tutor-surface-unification.md` §5.6）。
 *
 * **本文件此前的断言对象已经不存在了**：`tutorRoundToolAvailable`（按"本轮有没有题"
 * 放行写工具 / 按"披露面装不装得下"放行读工具的场景门）被 2026-09-21 裁定（ADR 0001 /
 * D6/D7）废除——无题轮不再结构性拒写、MASTERY_READ 无场景分支。逐次准入现在只剩两条，
 * 都取自模型自己这一轮的语义输出，不取自场景：
 * - 意图授权矩阵（core:model `tutorToolAuthorization`：意图 × 置信度 × 声明集，
 *   NOTEBOOK_WRITE 另要求学生明确命令）；
 * - MASTERY_UPDATE 的代号白名单（本会话已披露集合，core:data 仓库轮次判定 +
 *   runner 解析，编造代号结构性拒）。
 *
 * 那两张表的钉住分别在 `TutorToolAuthorizationTest`（core:model）与
 * `TutorToolRoundGateTest`（core:data）；这里钉住的是声明面本身——同一页、全量五个、
 * 写口概念不漂移。
 */
class TutorToolGateTest {

    @Test
    fun `the page declares the full five tool surface for every entry`() {
        // D7/D8：Plan / Respond / 大厅同一 5 工具面，不随场景分叉。
        assertEquals(
            setOf(
                TutorToolName.KNOWLEDGE_READ,
                TutorToolName.NOTEBOOK_READ,
                TutorToolName.MASTERY_READ,
                TutorToolName.MASTERY_UPDATE,
                TutorToolName.NOTEBOOK_WRITE,
            ),
            TUTOR_TOOL_DECLARATIONS,
        )
    }

    @Test
    fun `the write tools are the two that persist`() {
        // 写口概念保留（落库的两个），但准入不再看"本轮有没有题"。
        assertEquals(
            setOf(TutorToolName.MASTERY_UPDATE, TutorToolName.NOTEBOOK_WRITE),
            TUTOR_WRITE_TOOLS,
        )
        assertTrue("写工具必须都在声明面内", TUTOR_WRITE_TOOLS.all { it in TUTOR_TOOL_DECLARATIONS })
    }

    @Test
    fun `mastery read has no scene branch`() {
        // D7：MASTERY_READ 与 KNOWLEDGE_READ 在声明面内、不再被任何场景常量挑出来；
        // "没有科目上下文"的边界由 runner 失败关闭（no_subject），不是轮次层拒发。
        assertTrue(TutorToolName.MASTERY_READ in TUTOR_TOOL_DECLARATIONS)
        assertTrue(TutorToolName.KNOWLEDGE_READ in TUTOR_TOOL_DECLARATIONS)
    }
}
