package com.tingyun.smartmistakebook

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动期两段初始化的**归属**（审计 N-02）。
 *
 * 消灭的失败：两段合用一个 `try` 时，任何投影或账本失败都被说成「本地知识包尚未准备好」，
 * 并附一句「错题和复习可以继续使用」。文案断言钉不住这个——文案本身没变，
 * 变的是「哪个 catch 用哪句」。所以这里断的是归属本身。
 */
class StartupInitializationTest {

    private val projectionOk = "projection"

    @Test
    fun aHealthyStartupPublishesNothing() = runBlocking {
        val logged = mutableListOf<String>()
        var projectionRan = false

        val published = runStartupInitialization(
            installKnowledgeBase = { },
            initializeProjection = { projectionRan = true },
            logFailure = { what, _ -> logged += what },
        )

        assertNull("全部成功时不写任何状态：此时启动态已是 Ready，重写是空操作", published)
        assertTrue(projectionRan)
        assertTrue("成功不该产生失败日志：$logged", logged.isEmpty())
    }

    @Test
    fun aKnowledgeInstallFailureSaysSoAndDoesNotStopProjection() = runBlocking {
        var projectionRan = false

        val published = runStartupInitialization(
            installKnowledgeBase = { error("knowledge pack missing") },
            initializeProjection = { projectionRan = true },
            logFailure = { _, _ -> },
        )

        assertEquals("本地知识包尚未准备好", published?.title)
        assertTrue(published!!.diagnosticId.startsWith("startup:knowledge:"))
        // 刻意行为改动（见 `StartupInitialization` 的 KDoc）：HEAD 上两句共用一个 try，
        // 知识包失败会让投影初始化永远不被执行，学习进度被连带冻住。现在继续尝试。
        assertTrue("知识包失败后仍要尝试投影", projectionRan)
    }

    @Test
    fun aProjectionFailureIsNeverBlamedOnTheKnowledgePack() = runBlocking {
        val published = runStartupInitialization(
            installKnowledgeBase = { },
            initializeProjection = { error("projection drain failed") },
            logFailure = { _, _ -> },
        )

        assertTrue(
            "投影失败必须说投影的事，这是 N-02 的全部要点：${published?.title}",
            published?.title != "本地知识包尚未准备好",
        )
        assertTrue(published!!.diagnosticId.startsWith("startup:projection:"))
        assertTrue(published.retryable)
    }

    @Test
    fun bothFailuresAreLoggedSeparatelyAndProjectionIsWhatTheUserSees() = runBlocking {
        val logged = mutableListOf<String>()

        val published = runStartupInitialization(
            installKnowledgeBase = { error("knowledge pack missing") },
            initializeProjection = { error(projectionOk) },
            logFailure = { what, _ -> logged += what },
        )

        assertEquals(
            "两次失败必须各自记一条，合并成一条就说明又被并回了同一个 catch",
            listOf("Bundled knowledge install failed", "Study projection initialize failed"),
            logged,
        )
        // 两段都失败时以投影失败为准：学习进度停滞比自动分类暂缓更严重。
        assertTrue(published!!.diagnosticId.startsWith("startup:projection:"))
    }

    @Test
    fun cancellationIsNeverConvertedIntoAFailureState() {
        val logged = mutableListOf<String>()

        val thrown = runBlocking {
            try {
                runStartupInitialization(
                    installKnowledgeBase = { throw CancellationException("process shutting down") },
                    initializeProjection = { error("取消之后不得继续下一段") },
                    logFailure = { what, _ -> logged += what },
                )
                null
            } catch (failure: Throwable) {
                failure
            }
        }

        assertTrue(
            "取消必须原样上抛；转成失败态会在退出路径上弹一条用户看不懂的横幅：$thrown",
            thrown is CancellationException,
        )
        assertTrue("取消不是失败，不该记失败日志：$logged", logged.isEmpty())
    }
}
