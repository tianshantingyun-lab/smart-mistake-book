package com.tingyun.smartmistakebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题干读取失败的文案与诊断编号（审计 N-12）。
 *
 * 消灭的失败：`produceState` 里 `repository.teachingArtifact(...)` 一旦抛异常，`isLoaded`
 * 永远停在 `false`、界面永远停在「正在读取题目…」。这条文案是用户能看到的**唯一**解释，
 * 所以它必须（1）说这是**读取失败**、（2）说清**没有记录作答**、（3）给出**下一步**。
 *
 * 三条断言各自针对一种错法：
 * - 与「正在读取题目…」逐字不同——否则失败态看起来就是"还在读"；
 * - 与「当前题目暂时不可用…」不同——后者说的是"这道题没有条目/会话"，是**正常**情形，
 *   把它当成读取失败会让用户以为数据坏了（模式 E 的反方向）；
 * - 必须带诊断编号——排查者要能把它与启动期那几条区分开。
 */
class TeachingArtifactFailureMessagesTest {

    @Test
    fun theFailureSaysItFailedRatherThanThatItIsStillLoading() {
        val message = teachingArtifactFailureMessage("review:artifact:42")

        assertFalse(
            "读取失败不得长得像「还在读」：$message",
            message.contains("正在读取"),
        )
        assertTrue("必须说是**读不出来**：$message", message.contains("没能读出来"))
        assertTrue(
            "必须说清这次没有记录作答——否则学员会以为已经答过了：$message",
            message.contains("没有记录作答"),
        )
        assertTrue(
            "必须给出下一步：这条通道没有重试按钮，只能靠文字说清怎么重试：$message",
            message.contains("再试"),
        )
        assertTrue("必须带诊断编号：$message", message.contains("review:artifact:42"))
    }

    @Test
    fun theDiagnosticIdIsIdentifiableAsComingFromTheReviewArtifactRead() {
        val diagnosticId = teachingArtifactFailureDiagnosticId(IllegalStateException("query failed"))

        assertTrue(
            "前缀与启动期那几条（startup:*）必须不同类，排查者才能一眼看出出处",
            diagnosticId.startsWith("review:artifact:"),
        )
        assertTrue(
            "编号必须非空，否则用户看到的括号是空的：$diagnosticId",
            diagnosticId.length > "review:artifact:".length,
        )
    }

    /**
     * 这条断言记的是编号的**限度**，不是它的优点：编号取自 `Throwable.hashCode()`，而
     * Java 的 `Throwable` 不覆盖 `hashCode`，所以它是**身份哈希**——同一个失败实例重复取
     * 得到同一个号，但两次独立发生的同一个故障会得到**不同**的号。启动期那一族
     * （`StartupFailureMessages`）用的是同一个做法，这里保持一致。
     *
     * 为什么值得单独钉：它决定了这个号的用途——它是"这一次故障"的关联钥匙（用户报的号能
     * 对上一条日志），**不是**"这一类故障"的归类依据。归类由**前缀**承担。
     */
    @Test
    fun theDiagnosticIdIsPerFailureInstanceNotPerFailureKind() {
        val first = teachingArtifactFailureDiagnosticId(IllegalStateException("query failed"))
        val second = teachingArtifactFailureDiagnosticId(IllegalStateException("query failed"))

        assertNotEquals(
            "两次独立发生的同一个故障给出不同的号——这正是「归类看前缀、关联看编号」的理由",
            first,
            second,
        )
        assertEquals(
            "同类故障的前缀必须一致，否则就失去了归类能力",
            first.substringBeforeLast(':'),
            second.substringBeforeLast(':'),
        )
    }
}
