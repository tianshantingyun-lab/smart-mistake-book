package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `TutorToolCall` 的构造约束里，凡是"只对某个工具合法"的字段都必须 fail closed：
 * 模型可以在 JSON 里塞任何键，但解析后构造失败即整轮契约拒，不会静默降级。
 */
class TutorToolCallTest {

    @Test
    fun onlyTheMasteryReadToolMayAskForTheExtendedBudget() {
        // 扩展预算放大的是出网体量，只有那个会随学情增长的读工具配用它。
        TutorToolCall(
            tool = TutorToolName.MASTERY_READ,
            rationale = "需要看本科目完整清单",
            extendedResult = true,
        )

        assertThrows(IllegalArgumentException::class.java) {
            TutorToolCall(
                tool = TutorToolName.NOTEBOOK_READ,
                rationale = "检索错题本",
                terms = listOf("函数"),
                extendedResult = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TutorToolCall(
                tool = TutorToolName.MASTERY_UPDATE,
                rationale = "学生答对了",
                terms = listOf("kc-1"),
                direction = TutorEvidenceDirection.POSITIVE,
                understanding = TutorUnderstandingTier.CONFIDENT,
                extendedResult = true,
            )
        }
    }

    @Test
    fun theExtendedBudgetDefaultsOff() {
        val call = TutorToolCall(
            tool = TutorToolName.MASTERY_READ,
            rationale = "看看掌握情况",
        )

        assertEquals(false, call.extendedResult)
    }

    @Test
    fun masteryReadIsTheOnlyToolThatMayOmitTerms() {
        // 留空 = "本科目清单"模式，这正是本次扩出来的能力；其它工具没有这种语义。
        TutorToolCall(tool = TutorToolName.MASTERY_READ, rationale = "本科目清单")

        assertThrows(IllegalArgumentException::class.java) {
            TutorToolCall(tool = TutorToolName.KNOWLEDGE_READ, rationale = "查材料")
        }
    }
}
