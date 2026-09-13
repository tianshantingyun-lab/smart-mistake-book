package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全量重放的**触发判定**（`StudyProjectionDrainer.drain` 的 `requiresReplay`）。
 *
 * 消灭的失败：这条判定是「已投影事件要不要按新版本重算」的唯一开关，
 * `LearningCoreVersions.kt:8-21` 明文记着「版本不匹配不触发全量重放，修复对已投影事件静默无效」，
 * 而在此之前**全仓没有任何测试引用过 `StudyProjectionDrainer` 或 `requiresReplay`**——
 * 把 `!=` 写成 `==`、把整个条件改成 `false`、把 `batch.events.isEmpty() &&` 改成 `||`，
 * 都不会让任何一条测试变红。判定的每个子句在此各有一条断言：
 * 版本不匹配必须重放（即使有新事件）、状态陈旧且无新事件必须重放、两者皆无则原样返回、
 * 有新事件且版本一致走增量提交。
 *
 * 端口替身见 `LedgerProjectionFixture.kt`：它镜像真实 DAO 的分页与停止原因语义，
 * 因此 drain 的循环也会真的收敛。
 */
class StudyProjectionDrainerReplayTriggerTest {

    private val learnerId = "learner:local"

    @Test
    fun projectorVersionMismatchReplaysEvenWhenNewEventsExist() = runBlocking {
        val port = LedgerProjectionPort(
            learnerId = learnerId,
            ledgerEvents = listOf(ledgerEvent(sampleChatEvidence(sequence = 1))),
            current = persistedSnapshot(LearnerSnapshot.empty(learnerId, "unprojected-v6")),
        )

        val drained = drain(port)

        assertEquals(
            "版本不匹配必须触发全量重放，且优先于增量",
            listOf(ProjectionCommitMode.FULL_REPLAY),
            port.commits.map { it.mode },
        )
        assertEquals(
            "重放结果必须带上当前投影版本，否则下一次 drain 会再重放一次",
            LearningProjector.VERSION,
            drained!!.snapshot.checkpoint.projectorVersion,
        )
        assertEquals(
            "重放必须把这一条事件算进去",
            setOf("kc-monotonicity"),
            drained.snapshot.knowledgeMasteryStates.keys,
        )
    }

    @Test
    fun staleFreshnessWithNoNewEventsReplays() = runBlocking {
        val stale = LearnerSnapshot.empty(learnerId, LearningProjector.VERSION)
            .copy(freshness = LearnerSnapshotFreshness.STALE)
        val port = LedgerProjectionPort(
            learnerId = learnerId,
            current = persistedSnapshot(stale),
        )

        drain(port)

        assertEquals(
            "陈旧快照即使没有任何新事件也必须重放，否则陈旧状态会被永久保留",
            listOf(ProjectionCommitMode.FULL_REPLAY),
            port.commits.map { it.mode },
        )
    }

    @Test
    fun nonCurrentProjectionStatusWithNoNewEventsReplays() = runBlocking {
        val catchingUp = LearnerSnapshot.empty(learnerId, LearningProjector.VERSION)
            .copy(projectionStatus = ProjectionStatus.CATCHING_UP)
        val port = LedgerProjectionPort(
            learnerId = learnerId,
            current = persistedSnapshot(catchingUp),
        )

        drain(port)

        assertEquals(
            "非 CURRENT 的投影状态即使没有新事件也必须重放，否则 CATCHING_UP 永远不会回到 CURRENT",
            listOf(ProjectionCommitMode.FULL_REPLAY),
            port.commits.map { it.mode },
        )
    }

    @Test
    fun caughtUpCurrentSnapshotIsReturnedUntouched() = runBlocking {
        val current = persistedSnapshot(LearnerSnapshot.empty(learnerId, LearningProjector.VERSION))
        val port = LedgerProjectionPort(
            learnerId = learnerId,
            current = current,
        )

        val drained = drain(port)

        assertTrue(
            "已追平且版本一致的快照不得触发任何提交，否则每轮 drain 都会改写投影",
            port.commits.isEmpty(),
        )
        assertSame("已追平时应原样返回既有快照", current, drained)
    }

    @Test
    fun newEventsOnACurrentProjectionCommitIncrementally() = runBlocking {
        val port = LedgerProjectionPort(
            learnerId = learnerId,
            ledgerEvents = listOf(ledgerEvent(sampleChatEvidence(sequence = 1))),
            current = persistedSnapshot(LearnerSnapshot.empty(learnerId, LearningProjector.VERSION)),
        )

        val drained = drain(port)

        assertEquals(
            "版本一致且有待投影事件时应走增量提交，不得借道全量重放",
            listOf(ProjectionCommitMode.INCREMENTAL),
            port.commits.map { it.mode },
        )
        assertEquals(
            "增量提交必须回执它消费掉的那条事件",
            listOf("chat-evidence:1"),
            port.commits.single().consumedLedgerEvents.map { it.eventId },
        )
        assertEquals(
            "增量投影必须把证据落到对应知识点",
            setOf("kc-monotonicity"),
            drained!!.snapshot.knowledgeMasteryStates.keys,
        )
    }

    private suspend fun drain(port: LedgerProjectionPort) =
        StudyProjectionDrainer(
            database = port,
            learnerId = learnerId,
            learningProjector = LearningProjector(),
        ).drain()
}
