package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ConsumedLedgerEventReceipt
import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.ProjectionDrainBudgetExhaustedException
import com.tingyun.smartmistakebook.core.database.ProjectionReplayLimitExceededException
import com.tingyun.smartmistakebook.core.database.port.LearningProjectionPort
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome

/**
 * 一次全量重放能接受的账本条数上界，**按"防 OOM"量级取，不是容量规划**（决策 D-14）。
 *
 * 它是**对一条硬限制的承认**：`LearningProjector.replay` 必须把整份账本一次持在内存里
 * （先 `validPrefix.map { it.event }`，两遍结构还必须持有全部作答、全部记忆与掌握度状态），
 * 而 ADR-0002 说明这条约束不能靠分块绕开。账本无界、永不裁剪、单学习者（审计 §12.6），
 * 所以"大得装不进内存的账本"不是假想。
 *
 * **明确接受它的后果**：重度长期用户（每天 20 题 ≈ 一年 7_300 条）几年内会撞上，
 * 撞上即该次投影版本升级**永久无法完成**。出路是 `docs/adr/0003-replay-horizon.md`
 * 的重放地平线（从版本标注的基线快照开始重放），**不是把这个数调大**。
 *
 * 量级依据是"每条账本事件的对象图约 1KB"这一**未实测的假设**，`UNVERIFIED`。
 * 真正立得住的判据是"上界必须在 OOM 之前触发"；要收紧或放宽，先实测一次大账本重放的峰值内存。
 * **不要**照抄同族常量：4_096（[com.tingyun.smartmistakebook.core.domain.LearningProjector] 的记录表封顶）
 * 与 6_400（一次排空能处理的量）在账本几年内就会超过，取它们等于把正常使用的安装直接打成一等故障。
 */
internal const val MAX_FULL_REPLAY_EVENTS = 100_000

/**
 * Drains the immutable learning ledger into the learner projection (spec §6):
 * incremental batches with CAS retries, and a bounded full replay when the
 * ledger demands one. A pass that runs out of steps before reaching the head
 * stops with a named failure that the next call resumes from — never by
 * claiming a checkpoint conflict that did not happen. Extracted from the study
 * repository so the ledger/CAS mechanics stay readable on their own; every
 * database and projector access is an explicit constructor dependency.
 *
 * Depends on [LearningProjectionPort] rather than the whole persistence contract
 * because those four methods are all it uses — which is also what lets the
 * replay trigger below be tested without a stand-in for the other 28 ports.
 */
internal class StudyProjectionDrainer(
    private val database: LearningProjectionPort,
    private val learnerId: String,
    private val learningProjector: LearningProjector,
    /**
     * 上界本身。[MAX_FULL_REPLAY_EVENTS] 是产线值，改它只为了在测试里用一个小上界
     * 验证边界行为——否则要验证"超限时保留旧检查点"就得造十万条事件的夹具，
     * 这条上界就会带着未验证的边界行为上线。
     */
    private val maxFullReplayEvents: Int = MAX_FULL_REPLAY_EVENTS,
    /**
     * 一次排空的步数上界。[MAX_PROJECTION_DRAIN_STEPS] 是产线值；开口子的理由与
     * [maxFullReplayEvents] 相同——不注入的话，要验证"预算用尽时已推进的部分仍在库里、
     * 下次接着走"就得造 6_400 条事件的夹具，这条失败出口就会带着未验证的边界行为上线。
     */
    private val maxDrainSteps: Int = MAX_PROJECTION_DRAIN_STEPS,
) {

    suspend fun drain(): PersistedLearnerSnapshot? {
        var consecutiveCasConflicts = 0
        repeat(maxDrainSteps) {
            val current = database.readCurrentLearnerSnapshot(PROJECTION_NAME, learnerId)
            val batch = database.loadProjectionBatch(
                projectionName = PROJECTION_NAME,
                learnerId = learnerId,
                limit = PROJECTION_BATCH_SIZE,
            )
            val expectedCheckpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
            if (batch.previousCheckpoint != expectedCheckpoint) {
                consecutiveCasConflicts++
                if (consecutiveCasConflicts >= MAX_CAS_RETRIES) {
                    throw ProjectionCasConflictException("Projection checkpoint changed during drain")
                }
                return@repeat
            }
            when (batch.stopReason) {
                ProjectionBatchStopReason.GAP,
                ProjectionBatchStopReason.CONFLICT,
                -> throw LearningLedgerIntegrityException(
                    batch.detail ?: "Learning ledger stopped at ${batch.blockedAtSequence}",
                )

                ProjectionBatchStopReason.FULL_REPLAY_REQUIRED -> {
                    try {
                        commitFullReplay(current)
                        consecutiveCasConflicts = 0
                    } catch (conflict: ProjectionCasConflictException) {
                        consecutiveCasConflicts++
                        if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                    }
                }

                ProjectionBatchStopReason.END_OF_LEDGER,
                ProjectionBatchStopReason.LIMIT_REACHED,
                -> {
                    val previous = current?.snapshot ?: LearnerSnapshot.empty(
                        learnerId = learnerId,
                        projectorVersion = LearningProjector.VERSION,
                    )
                    val requiresReplay = previous.checkpoint.projectorVersion != LearningProjector.VERSION ||
                        (
                            batch.events.isEmpty() &&
                                (
                                    previous.freshness != LearnerSnapshotFreshness.CURRENT ||
                                        previous.projectionStatus != ProjectionStatus.CURRENT
                                    )
                            )
                    if (requiresReplay) {
                        try {
                            commitFullReplay(current)
                            consecutiveCasConflicts = 0
                        } catch (conflict: ProjectionCasConflictException) {
                            consecutiveCasConflicts++
                            if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                        }
                    } else if (batch.events.isEmpty()) {
                        return current
                    } else {
                        val result = learningProjector.project(
                            previous = previous,
                            events = batch.events.map { it.event },
                            knownLedgerHeadSequence = batch.ledgerHeadSequence,
                            authoritativePresentationStates = batch.authoritativePresentationStates,
                        )
                        check(
                            result.missingSequence == null &&
                            result.conflictedAttemptIds.isEmpty() &&
                                result.conflictedAnswerRevealOutcomeIds.isEmpty() &&
                                result.conflictedTutorAnswerExposureOutcomeIds.isEmpty() &&
                                result.deferredAttemptIds.isEmpty() &&
                                result.deferredAnswerRevealOutcomeIds.isEmpty() &&
                                result.deferredTutorAnswerExposureOutcomeIds.isEmpty(),
                        ) { "Projector rejected a database-validated incremental prefix" }
                        val commit = ProjectionCommit(
                            projectionName = PROJECTION_NAME,
                            learnerId = learnerId,
                            expectedPreviousCheckpoint = expectedCheckpoint,
                            expectedPreviousStateVersion = current?.stateVersion ?: 0L,
                            mode = ProjectionCommitMode.INCREMENTAL,
                            knownLedgerHeadSequence = batch.ledgerHeadSequence,
                            consumedLedgerEvents = batch.events.map { persisted ->
                                ConsumedLedgerEventReceipt(
                                    eventKind = persisted.outbox.eventKind,
                                    eventId = persisted.outbox.eventId,
                                    eventSequence = persisted.outbox.outboxSequence,
                                    canonicalFingerprint = persisted.canonicalFingerprint,
                                )
                            },
                            presentationProjectionStates = result.presentationProjectionStates,
                            snapshot = result.snapshot,
                        )
                        try {
                            database.commitProjection(commit)
                            consecutiveCasConflicts = 0
                        } catch (conflict: ProjectionCasConflictException) {
                            consecutiveCasConflicts++
                            if (consecutiveCasConflicts >= MAX_CAS_RETRIES) throw conflict
                        }
                    }
                }
            }
        }
        // 走到这里是"预算用完"，不是"检查点被抢"：本次调用里已提交的每一页都独立落库，
        // 下一次 drain 从最新检查点接着走（审计 N-06）。
        //
        // 文案**只说"没看到空批次"，不说"没追平"**：这两件事在预算恰好被真实工作吃光时是
        // 分开的——64 步正好提交完 64 页之后，循环就结束了，它没有机会读到那个空批次来确认
        // 追平。此时"没追平"是假话（检查点已经是账本头），而"没确认"始终是真的。
        throw ProjectionDrainBudgetExhaustedException(
            "Projection used the whole $maxDrainSteps-step drain budget without observing " +
                "an empty batch; the advances already committed are durable and the next " +
                "drain resumes from the newest checkpoint",
        )
    }

    private suspend fun commitFullReplay(
        current: PersistedLearnerSnapshot?,
    ): PersistedLearnerSnapshot {
        val ledger = database.loadLearningLedger(learnerId)
        if (ledger.status != LearningLedgerReadStatus.COMPLETE) {
            throw LearningLedgerIntegrityException(
                ledger.detail ?: "Full replay blocked at ${ledger.blockedAtSequence}",
            )
        }
        // 检查点选在读取之后、`replay` 之前：`replay` 在内存里持住整份账本，
        // 进去之后就没有"提前失败"的机会了。这里抛出的异常不继承
        // ProjectionCasConflictException，因此 drain 的重试分支不会接住它。
        if (ledger.validPrefix.size > maxFullReplayEvents) {
            throw ProjectionReplayLimitExceededException(
                "Full replay needs ${ledger.validPrefix.size} ledger events, over the " +
                    "$maxFullReplayEvents limit; the projection keeps its previous checkpoint",
            )
        }
        val result = learningProjector.replay(
            learnerId = learnerId,
            ledger = ledger.validPrefix.map { it.event },
        )
        val expectedCheckpoint = current?.snapshot?.checkpoint?.lastSequence ?: 0L
        val consumed = ledger.validPrefix
            .filter { it.event.eventSequence > expectedCheckpoint }
            .map { persisted -> persisted.toReceipt() }
        return database.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION_NAME,
                learnerId = learnerId,
                expectedPreviousCheckpoint = expectedCheckpoint,
                expectedPreviousStateVersion = current?.stateVersion ?: 0L,
                mode = ProjectionCommitMode.FULL_REPLAY,
                knownLedgerHeadSequence = result.snapshot.knownLedgerHeadSequence,
                consumedLedgerEvents = consumed,
                presentationProjectionStates = result.presentationProjectionStates,
                snapshot = result.snapshot,
            ),
        )
    }

    private fun com.tingyun.smartmistakebook.core.database.PersistedLearningLedgerEvent.toReceipt() =
        ConsumedLedgerEventReceipt(
            eventKind = when (event) {
                is Attempt -> EVENT_KIND_ATTEMPT
                is AttemptCorrection -> EVENT_KIND_CORRECTION
                is com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome -> EVENT_KIND_ANSWER_REVEAL
                is TutorAnswerExposureOutcome -> EVENT_KIND_TUTOR_ANSWER_EXPOSURE
                is ChatEvidenceSubmitted -> EVENT_KIND_CHAT_EVIDENCE
            },
            eventId = event.ledgerEventId,
            eventSequence = event.eventSequence,
            canonicalFingerprint = canonicalFingerprint,
        )

    private companion object {
        const val PROJECTION_NAME = "study-experience-v1"
        const val PROJECTION_BATCH_SIZE = 100
        const val MAX_CAS_RETRIES = 4

        /**
         * 一次 `drain()` 最多走多少步（一步 = 一页 = 至多 [PROJECTION_BATCH_SIZE] 条）。
         *
         * 它限的是**这一次走多远**，不是**系统能装多少**：走到头就抛
         * [com.tingyun.smartmistakebook.core.database.ProjectionDrainBudgetExhaustedException]，
         * 已提交的推进留在库里，下一次 `drain()` 从那里接着走。
         *
         * **边界要说清楚，因为它比看上去低一页**：循环靠"读到一批空批次"来确认追平，而最后一页
         * 的停止原因仍然是 `LIMIT_REACHED`（页满，账本后面还有没有不知道），所以走完 k 步能
         * **确认**追平的最大积压是 `(k − 1) × 100 = 6_300` 条。积压落在 6_301 与 6_400 之间时，
         * 工作其实已经全部提交，只是那一次多读没有预算了——这正是异常文案说"没看到空批次"
         * 而不说"没追平"的原因，也是重试总能立刻返回的原因（见
         * `runningOutOfDrainStepsReportsItselfAndKeepsTheCommittedProgress` 的边界断言）。
         *
         * 6_300 这个量级对**正常使用的重度用户是可达的**（每天 20 题 ≈ 一年 7_300 条），
         * 所以那条失败必须是可重试的、且必须说自己是"没走完"而不是别的什么事。
         */
        const val MAX_PROJECTION_DRAIN_STEPS = 64

        const val EVENT_KIND_ATTEMPT = "ATTEMPT"
        const val EVENT_KIND_CORRECTION = "ATTEMPT_CORRECTION"
        const val EVENT_KIND_ANSWER_REVEAL = "ANSWER_REVEAL_OUTCOME"
        const val EVENT_KIND_TUTOR_ANSWER_EXPOSURE = "TUTOR_ANSWER_EXPOSURE_OUTCOME"
        const val EVENT_KIND_CHAT_EVIDENCE = "CHAT_EVIDENCE_SUBMITTED"
    }
}
