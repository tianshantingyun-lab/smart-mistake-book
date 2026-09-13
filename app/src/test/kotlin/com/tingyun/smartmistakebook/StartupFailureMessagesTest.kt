package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.ProjectionDrainBudgetExhaustedException
import com.tingyun.smartmistakebook.core.database.ProjectionReplayLimitExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动期后台失败的**归类与文案**（审计 N-02／N-04）。
 *
 * 消灭的失败：`BundledKnowledgeBaseInstaller.install` 与 `studyRepository.initialize()`
 * 原先是**同一个 `try` 里的两句**（`SmartMistakeBookApplication.kt:277-304` 旧版），
 * 于是任何投影或账本失败都会走到那句「本地知识包尚未准备好」，
 * 并附带「错题和复习可以继续使用」——而投影失败的含义恰恰是学习进度没有更新。
 *
 * 断言分三层，每层都对应一个具体的错法：
 * 1. 每一类失败**说自己的事**（标题与编号前缀各自不同）——治"说错原因"；
 * 2. 投影类失败**不得出现「可以继续使用」**——治"给出与事实相反的安慰"；
 * 3. 编号前缀**互不相同**——横幅只显示 title／message／编号，
 *    前缀就是用户和排查者唯一能据以区分原因的东西。
 *
 * 这里能用断言而不是靠读代码，是因为映射被抽成了纯函数；留在 `Application.onCreate` 的
 * catch 里就只能靠人眼确认。
 */
class StartupFailureMessagesTest {

    @Test
    fun knowledgeBaseFailureStaysAboutTheKnowledgePack() {
        val message = knowledgeBaseFailure(IllegalStateException("install failed"))

        assertEquals("本地知识包尚未准备好", message.title)
        assertTrue(
            "知识包失败的文案必须点名分类：${message.message}",
            message.message.contains("自动分类"),
        )
        assertTrue(message.diagnosticId.startsWith("startup:knowledge:"))
        // 这一条**可以**说"可以继续使用"——知识包失败确实只影响自动分类。
        assertTrue(message.message.contains("可以继续使用"))
    }

    @Test
    fun ledgerIntegrityFailureNamesTheLedgerAndOffersNoFalseReassurance() {
        val failure = projectionFailure(LearningLedgerIntegrityException("ledger gap at 42"))

        assertTrue(
            "账本损坏必须与知识包失败说不同的话：${failure.title}",
            failure.title != knowledgeBaseFailure(IllegalStateException()).title,
        )
        assertTrue(
            "必须点名是学习记录本身出了问题：${failure.message}",
            failure.message.contains("记录"),
        )
        assertFalse(
            "投影失败时说「可以继续使用」与事实相反——进度正停在最后一次成功的位置：" +
                failure.message,
            failure.message.contains("可以继续使用"),
        )
        assertTrue(failure.diagnosticId.startsWith("startup:ledger:"))
    }

    @Test
    fun replayLimitFailureDoesNotPromiseARetryWillHelp() {
        val failure = projectionFailure(ProjectionReplayLimitExceededException("100001 > 100000"))

        assertTrue(
            "必须点名是记录条数超出范围：${failure.message}",
            failure.message.contains("上限"),
        )
        assertTrue(
            "必须明说重试无用（决策 D-14：这一次升级的出路是重放地平线，不是再试一次）：" +
                failure.message,
            failure.message.contains("重试不会改变结果"),
        )
        assertFalse(failure.message.contains("可以继续使用"))
        assertTrue(failure.diagnosticId.startsWith("startup:replay-limit:"))
    }

    /**
     * 审计 N-06：排空预算用尽与账本超上界**是同一族但不同处**——都关乎"量"，处置却相反。
     *
     * 这条与上一条必须说两样的话，而且是**相反**的两样：上一条说"重试不会改变结果"，
     * 这一条说"重试会从断点继续"。把两者合并成一句（"投影更新失败"），用户就失去了
     * 唯一能据以决定"要不要再点一次重试"的信息。
     */
    @Test
    fun drainBudgetExhaustionSaysTheRetryIsTheWayForward() {
        val failure = projectionFailure(ProjectionDrainBudgetExhaustedException("64 steps"))

        assertTrue(
            "必须点名是积压没排完：${failure.message}",
            failure.message.contains("积压"),
        )
        assertTrue(
            "必须说清重试有通路（与账本超上界那条相反）：${failure.message}",
            failure.message.contains("重试会从断点继续"),
        )
        assertTrue(
            "必须说清已完成的部分没有丢：${failure.message}",
            failure.message.contains("已经保存"),
        )
        assertFalse(
            "投影失败类一律不得说「可以继续使用」：${failure.message}",
            failure.message.contains("可以继续使用"),
        )
        assertTrue(failure.diagnosticId.startsWith("startup:drain-budget:"))
        assertTrue(
            "与账本超上界是同族不同处，文案不得雷同：${failure.title}",
            failure.title != projectionFailure(ProjectionReplayLimitExceededException("x")).title,
        )
    }

    @Test
    fun unknownProjectionFailureStillBlamesTheProjectionNotTheKnowledgePack() {
        val failure = projectionFailure(RuntimeException("scheduler blew up"))

        assertTrue(
            "未知投影失败也必须落在投影这一类：${failure.diagnosticId}",
            failure.diagnosticId.startsWith("startup:projection:"),
        )
        assertFalse(
            "不得把未知失败说成知识包问题（N-02 的原始错法）：${failure.title}",
            failure.title.contains("知识包"),
        )
        assertFalse(failure.message.contains("可以继续使用"))
    }

    @Test
    fun everyFailureClassCarriesItsOwnDiagnosticPrefix() {
        val prefixes = listOf(
            knowledgeBaseFailure(IllegalStateException()),
            projectionFailure(LearningLedgerIntegrityException("x")),
            projectionFailure(ProjectionReplayLimitExceededException("x")),
            projectionFailure(ProjectionDrainBudgetExhaustedException("x")),
            projectionFailure(RuntimeException("x")),
        ).map { it.diagnosticId.substringBeforeLast(':') }

        assertEquals(
            "五类失败的编号前缀必须互不相同，否则用户看到的编号区分不出原因",
            prefixes.size,
            prefixes.distinct().size,
        )
    }

    /**
     * 只有**输入真的会变**的失败才提供「重试」（`StartupStateBanner` 按 `isRetryable`
     * 决定是否渲染按钮）。
     *
     * 消灭的失败：横幅一边说「重试不会改变结果」，一边给出一个按下去只会重放同一条错误的按钮。
     * 前两类的输入在同一版本内不会变——账本只追加、从不裁剪——所以重试无意义；后两类
     * 的重试是有通路的：原因未知的那一类可能只是瞬时故障，而排空预算那一条已经落库的推进
     * 由下一次调用接着走（审计 N-06）。
     *
     * 用例名原先叫 `onlyTheUnknownProjectionFailureOffersARetry`——N-06 之后那句话不再成立
     * （可重试的投影失败变成两类），改的是名字与新增的那条断言，两条 `assertFalse` 逐字未动。
     */
    @Test
    fun onlyFailuresThatCanActuallyChangeOfferARetry() {
        assertFalse(
            "账本损坏是确定性的：重试只会把同一条错误再显示一次",
            projectionFailure(LearningLedgerIntegrityException("x")).isRetryable,
        )
        assertFalse(
            "账本超出重放上界同样确定性：条数只会更多，不会更少",
            projectionFailure(ProjectionReplayLimitExceededException("x")).isRetryable,
        )
        assertTrue(
            "原因未知的投影失败可能是一次瞬时故障，重试是有意义的",
            projectionFailure(RuntimeException("x")).isRetryable,
        )
        assertTrue(
            "排空预算用尽与上面两条相反：已完成的推进已落库，重试就是通路（审计 N-06）",
            projectionFailure(ProjectionDrainBudgetExhaustedException("x")).isRetryable,
        )
        assertTrue(
            "知识包失败与拆分前一样可重试（安装是幂等的）",
            knowledgeBaseFailure(IllegalStateException("x")).isRetryable,
        )
    }
}
