package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.domain.LogDurationModel
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlement
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlementResult
import com.tingyun.smartmistakebook.core.domain.TutorJudgedReviewSettlementStatus
import com.tingyun.smartmistakebook.core.domain.tutorSessionObjectiveRecord
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LocalModelJudgedContract
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.TutorConversationIds
import kotlinx.coroutines.flow.first

/**
 * 讲题判定的题目级结算（第2条：复习作答不再由学生自评）。
 *
 * 判定合成顺序（行为证据胜出，`f506ff7a` 的既有原则）：
 * 1. 本轮有本地核对的检查题作答 → **本地判定就是判定**：一条答错即判错（模型口头说对不作数）；
 * 2. 没有检查题作答 → 取该会话最近一条**通过门控的**模型判词方向（开放作答只能靠它）；
 * 3. 两样都没有 → 不写任何证据、不推进队列（`NO_VERDICT`）。产品裁定：无判定时保持阻塞，
 *    既不伪造"本次未作答"记录，也不给假的记忆更新。
 *
 * 定价是本地的、非独立的：题目级只落 HARD（对）/ AGAIN（错），权重 0.5；快照故意不带
 * 知识归属，知识点掌握度由模型的 chat-evidence 通道单独写。依据见
 * `docs/research/model-judged-verdict-pricing.md`。
 *
 * 幂等：attempt/submission 由 (复习会话, 队列项) 派生，重复结算只生效一次（第二次是回放）。
 */
internal class TutorJudgedReviewSettler(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val durationModel: LogDurationModel,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
    private val learnerSnapshot: suspend () -> LearnerSnapshot,
) {
    suspend fun settle(
        settlement: TutorJudgedReviewSettlement,
    ): TutorJudgedReviewSettlementResult {
        val reviewPlan = requireNotNull(
            database.observeReviewPlanForSession(settlement.sessionId).first(),
        ) { "No persisted review plan owns session ${settlement.sessionId}" }
        val session = requireNotNull(
            (reviewPlan.activeSession ?: reviewPlan.latestSession)?.takeIf {
                it.reviewSessionId == settlement.sessionId
            },
        ) { "Review session ${settlement.sessionId} does not belong to its persisted plan" }
        val orderedQueue = reviewPlan.queue.sortedBy { it.ordinal }
        val queueItem = requireNotNull(
            orderedQueue.singleOrNull { it.ordinal.toLong() == settlement.expectedStateVersion },
        ) { "Expected review-session version does not identify one planned queue item" }
        require(queueItem.practiceUnitId == settlement.practiceUnitId) {
            "Settlement belongs to another planned practice unit"
        }
        val mistake = requireNotNull(
            database.observeMistakes().first()
                .singleOrNull { it.practiceUnitId == settlement.practiceUnitId },
        ) { "The planned saved question is no longer active" }

        val tutorAnchor = database.readLatestTutorSessionAnchor(
            practiceUnitId = settlement.practiceUnitId,
            learnerId = learnerId,
        )
        val isHeadItem = session.currentOrdinal.toLong() == settlement.expectedStateVersion
        val verdict = tutorAnchor?.let { anchor ->
            resolveVerdict(anchor.sessionId)
        }?.takeIf { candidate ->
            // 只认**本次复习这一项**期间的讲题：上次推进之前的判词早已被上一次结算消费
            // （或本就不属于这次复习），否则一进复习页就会被旧判词直接判定通过。
            // 只对"当前队首项"要求新鲜度——已结算项的重复结算交给写入幂等处理，
            // 而完成态的 lastActiveAt 必然晚于当初那条判词，拿它当门槛会把回放误判成无判词。
            !isHeadItem || candidate.atEpochMillis >= session.lastActiveAtEpochMillis
        }
        if (verdict == null) {
            // 没有任何可核查的判定：不写、不推进。队列项保持到期，下次进入复习还会出现。
            return TutorJudgedReviewSettlementResult(
                status = TutorJudgedReviewSettlementStatus.NO_VERDICT,
                progress = session.toProgress(orderedQueue.size),
                nextPracticeUnitId = orderedQueue
                    .getOrNull(session.currentOrdinal)
                    ?.practiceUnitId,
            )
        }

        val isCorrect = verdict.isCorrect
        val evidence = LearningEvidence(
            direction = if (isCorrect) {
                LearningEvidenceDirection.POSITIVE
            } else {
                LearningEvidenceDirection.NEGATIVE
            },
            weight = MODEL_JUDGED_EVIDENCE_WEIGHT,
            reason = if (isCorrect) {
                LearningEvidenceReason.MODEL_JUDGED_CORRECT
            } else {
                LearningEvidenceReason.MODEL_JUDGED_INCORRECT
            },
        )
        val outcome = if (isCorrect) {
            ProblemMemoryOutcome.ASSISTED_RECALL
        } else {
            ProblemMemoryOutcome.RETRIEVAL_FAILURE
        }
        val revisionKey = "${mistake.practiceUnitId}\n${mistake.problemRevisionId}"
        val snapshot = AssessmentEvidenceSnapshot(
            snapshotId = writeContext.stableId("tutor-judged-snapshot", settlement.requestId),
            assessmentItemId = LocalModelJudgedContract.ASSESSMENT_ITEM_ID_PREFIX +
                writeContext.stableId("item", revisionKey),
            practiceUnitId = mistake.practiceUnitId,
            problemRevisionId = mistake.problemRevisionId,
            answerSpecId = LocalModelJudgedContract.ANSWER_SPEC_ID,
            itemFamilyId = LocalModelJudgedContract.ITEM_FAMILY_ID,
            sourceBundleId = null,
            taxonomyVersion = LocalModelJudgedContract.TAXONOMY_VERSION,
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot.unknown(),
            // 空归属：这条通道只驱动题目级排期，不碰知识点掌握度（避免与模型通道双写）。
            attributions = emptyList(),
            capturedAtEpochMillis = settlement.occurredAtEpochMillis,
        )
        // 0 表示由结算自己推导（避免调用方凭空给一个时长）：本轮判词发生时间 − 上次推进时间。
        val durationSeconds = if (settlement.durationSeconds > 0) {
            settlement.durationSeconds
        } else {
            ((verdict.atEpochMillis - session.lastActiveAtEpochMillis) / 1_000L)
                .coerceAtLeast(1L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
        val priorMemory = learnerSnapshot().problemMemoryStates[settlement.practiceUnitId]
        database.saveAssessmentEvidenceSnapshot(snapshot)
        val writeResult = database.recordReviewAttempt(
            ReviewAttemptWriteCommand(
                attempt = AttemptWriteCommand(
                    learnerId = learnerId,
                    submissionId = writeContext.stableId(
                        "submission",
                        "tutor-judged:${settlement.sessionId}:${settlement.expectedStateVersion}",
                    ),
                    attemptId = writeContext.stableId(
                        "attempt",
                        "tutor-judged:${settlement.sessionId}:${settlement.expectedStateVersion}",
                    ),
                    presentationId = settlement.presentationId,
                    assessmentSnapshotId = snapshot.snapshotId,
                    submittedResponse = AttemptSubmittedResponse.Choice(
                        choiceId = if (isCorrect) "tutor-judged:correct" else "tutor-judged:incorrect",
                        choiceMarkdown = if (isCorrect) {
                            "讲题判定：这次做对了"
                        } else {
                            "讲题判定：这次还没做对"
                        },
                        submittedAtEpochMillis = settlement.occurredAtEpochMillis,
                    ),
                    evidence = evidence,
                    problemMemoryOutcome = outcome,
                    occurredAtEpochMillis = settlement.occurredAtEpochMillis,
                    durationSeconds = durationSeconds,
                    studyDay = writeContext.studyDayAt(settlement.occurredAtEpochMillis),
                ),
                sessionId = settlement.sessionId,
                expectedStateVersion = settlement.expectedStateVersion,
                reviewQueueItemId = queueItem.reviewQueueItemId,
                practiceUnitId = queueItem.practiceUnitId,
            ),
        )
        if (writeResult.attempt.created) {
            reviewLogSink.record(
                practiceUnitId = settlement.practiceUnitId,
                evidence = evidence,
                occurredAtEpochMillis = settlement.occurredAtEpochMillis,
                durationSeconds = durationSeconds,
                studyDay = writeContext.studyDayAt(settlement.occurredAtEpochMillis),
                sourceKind = ReviewLogSink.SOURCE_KIND_MODEL_JUDGED,
                sourceId = writeResult.attempt.attempt.attemptId,
                previousReviewedAtEpochMillis = priorMemory?.lastReviewedAtEpochMillis,
                plannedReason = queueItem.reasonSnapshot.takeIf(String::isNotBlank),
            )
        }
        if (writeResult.attempt.created) {
            durationModel.record(
                learnerId = learnerId,
                subjectId = mistake.subject,
                itemType = null,
                difficulty = 5.0, // unused dimension; kept for API stability
                durationSeconds = durationSeconds.toDouble(),
            )
        }
        val progress = writeResult.advance.session.toProgress(orderedQueue.size)
        return TutorJudgedReviewSettlementResult(
            status = TutorJudgedReviewSettlementStatus.RECORDED,
            isCorrect = isCorrect,
            attemptId = writeResult.attempt.attempt.attemptId,
            created = writeResult.attempt.created,
            progress = progress,
            nextPracticeUnitId = orderedQueue
                .getOrNull(progress.currentOrdinal)
                ?.practiceUnitId,
        )
    }

    /**
     * 本会话的判定：本地核对优先，其次模型判词，都没有返回 null。
     * 只统计**最新一轮**（cycle）的检查题：`restartCycle` 重教之后，上一轮的答错
     * 正是重教的理由，不该把它永久算在头上（与 `tutorSessionObjectiveRecord` 同一原因）。
     */
    private suspend fun resolveVerdict(tutorSessionId: String): Verdict? {
        val responses = database.observeTutorTurnResponses(tutorSessionId).first()
        val latestCycle = responses.maxOfOrNull(TutorTurnResponseRecord::cycleOrdinal)
        if (latestCycle != null) {
            val currentCycle = responses.filter { it.cycleOrdinal == latestCycle }
            val record = tutorSessionObjectiveRecord(
                currentCycle.mapNotNull(TutorTurnResponseRecord::selectionWasCorrect),
            )
            if (record.answeredCount > 0) {
                val judgedAt = currentCycle.mapNotNull(TutorTurnResponseRecord::choiceSubmittedAtEpochMillis)
                    .maxOrNull()
                    ?: currentCycle.maxOf(TutorTurnResponseRecord::updatedAtEpochMillis)
                return Verdict(
                    isCorrect = !record.contradictsPositiveClaim,
                    atEpochMillis = judgedAt,
                )
            }
        }
        val evidence = database
            .readChatEvidenceByConversation(TutorConversationIds.captured(tutorSessionId))
            .filter { it.rejected_reason == null && it.weight > 0.0 }
            .maxByOrNull { it.created_at_epoch_millis }
            ?: return null
        val isCorrect = when (evidence.direction) {
            LearningEvidenceDirection.POSITIVE.name -> true
            LearningEvidenceDirection.NEGATIVE.name -> false
            else -> return null
        }
        return Verdict(isCorrect = isCorrect, atEpochMillis = evidence.created_at_epoch_millis)
    }

    private data class Verdict(val isCorrect: Boolean, val atEpochMillis: Long)

    internal companion object {
        /**
         * 题目级证据权重【I 先验】：区间 0.5–0.6 的保守端（等于既有 assisted 上限的下沿），
         * 校准达标前不动；依据见 `docs/research/model-judged-verdict-pricing.md` §4(i)。
         */
        const val MODEL_JUDGED_EVIDENCE_WEIGHT = 0.5
    }
}
