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

    // ------------------------------------------------------------------ 重试（N-21）

    /**
     * 审计 N-21：横幅上那个「重试」。
     *
     * 消灭的失败有两半，这一条钉住第一半——**它必须重跑失败的那一步**。
     * 旧接线调的是 `refreshStudyExperience()` → `studyRepository.refresh()`（默认实现就是
     * `initialize()`），知识包安装**从来没有被重跑过**：用户按了重试，看到的是同一句话。
     */
    @Test
    fun aRetryRunsBothStepsAgain() = runBlocking {
        var installs = 0
        var initializations = 0

        runStartupRetry(
            installKnowledgeBase = { installs += 1 },
            initializeProjection = { initializations += 1 },
            publish = { },
        )

        assertEquals("重试必须重跑知识包安装——失败的那一步正是它", 1, installs)
        assertEquals(1, initializations)
    }

    /**
     * 第二半：**成功必须把横幅收回去**。这是与启动路径唯一的差别——
     * 启动成功时不写状态（那时启动态本来就是 Ready，而"投影成功不得抹掉知识包失败"是对的），
     * 但用户按过重试之后再成功，那条失败就是真的过去了。
     */
    @Test
    fun aSuccessfulRetryPublishesReadySoTheBannerCanGoAway() = runBlocking {
        val published = mutableListOf<StartupState>()

        runStartupRetry(
            installKnowledgeBase = { },
            initializeProjection = { },
            publish = { published += it },
        )

        assertEquals(
            "成功必须发布 Ready：不发布就等于给用户一个永远不会消失的横幅",
            listOf<StartupState>(StartupState.Ready),
            published,
        )
    }

    @Test
    fun aFailingRetryPublishesThisAttemptsFailureNotTheOldOne() = runBlocking {
        val published = mutableListOf<StartupState>()

        runStartupRetry(
            installKnowledgeBase = { error("knowledge pack missing") },
            initializeProjection = { },
            publish = { published += it },
        )

        val failure = published.single()
        assertTrue("这一次失败的是知识包，就该说知识包：$published", failure is StartupState.RecoverableFailure)
        assertTrue(
            (failure as StartupState.RecoverableFailure).diagnosticId.startsWith("startup:knowledge:"),
        )
    }

    @Test
    fun aCancelledRetryPublishesNothing() {
        val published = mutableListOf<StartupState>()

        val thrown = runBlocking {
            try {
                runStartupRetry(
                    installKnowledgeBase = { throw CancellationException("process shutting down") },
                    initializeProjection = { error("取消之后不得继续下一段") },
                    publish = { published += it },
                )
                null
            } catch (failure: Throwable) {
                failure
            }
        }

        assertTrue(thrown is CancellationException)
        assertTrue(
            "被取消的那次尝试不是一次结论，不该发布任何状态：$published",
            published.isEmpty(),
        )
    }
}
