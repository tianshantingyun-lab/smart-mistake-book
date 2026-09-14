package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lobby 与讲题会话之间的读取边界。
 *
 * 两个库（错题库 / 掌握情况）各有自己的入口，边界划在**有没有当前题**上：掌握情况是
 * "某个知识点你掌握得怎样"，没有题就没有锚点，因此它只保留在讲题会话的 `MASTERY_READ`
 * 工具里，Lobby 一律不提供。
 *
 * 这些用例存在的理由是一处真实的历史矛盾：`ALLOWED_LOCAL_CAPABILITIES` 曾经允许模型在
 * Lobby 申请掌握情况读取，而 Lobby 提示词同时写着"本地不提供该查询"——两者互相否认，
 * 却没有任何测试会因此变红。
 *
 * 边界的另一半（工具声明集）由 `feature:tutor` 的
 * `TutorModelTaskPolicyTest.lobbyRequestCarriesLookupToolDeclarations` 锁定：它断言
 * Lobby 的声明集不含 `MASTERY_READ`。声明集由 feature 层按 kind 计算，core:model 看不到，
 * 所以那半只能在那里锁——两半合起来才是这条边界。
 */
class TutorLobbyCapabilityBoundaryTest {

    @Test
    fun lobbyOffersOnlyTheMistakeNotebookRead() {
        assertEquals(
            setOf(
                TutorRequestedLocalCapability.NONE,
                TutorRequestedLocalCapability.READ_MISTAKE_NOTEBOOK,
            ),
            TutorLobbyOutput.ALLOWED_LOCAL_CAPABILITIES,
        )
    }

    @Test
    fun lobbyNeverOffersTheMasteryRead() {
        // 这条与提示词那句"不得申请读取学习/掌握情况"是同一条边界的两半：
        // 若哪天有人把枚举加回来，提示词仍在否认，二者必须一起改。
        assertTrue(
            "Lobby 不得开放掌握情况读取：没有当前题就没有锚点",
            TutorRequestedLocalCapability.READ_LEARNING_PROGRESS !in
                TutorLobbyOutput.ALLOWED_LOCAL_CAPABILITIES,
        )
    }
}
