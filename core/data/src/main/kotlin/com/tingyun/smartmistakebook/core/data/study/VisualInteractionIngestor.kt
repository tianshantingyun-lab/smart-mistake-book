package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome

/**
 * Turns judged visual interactions into ledger evidence (audit §12): only
 * actions with a decisive local verdict, anchored to one active saved question
 * that already has accepted knowledge bindings, may enter the mastery ledger.
 * Extracted from the study repository so the conservative admission rules and
 * their cooldown stay readable on their own.
 */
internal class VisualInteractionIngestor(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val reviewLogSink: ReviewLogSink,
    private val writeContext: StudyWriteContext,
) {

    suspend fun ingestPending(
        mistakes: List<MistakeRecord>,
        /**
         * 每个被复习题**上一次复习发生的时间戳**（没有该单元就缺席）。`ReviewLogSink.record`
         * 用它算 `delta_t`："距上一次复习过了几天"，而 `review_log` 正是 FSRS 参数优化器的
         * 训练数据。传空表（或不传）会让每一条视觉证据都记下 `delta_t = 0`——优化器就此看到
         * 一个"所有复习都挤在同一天"的世界，且不会有任何断言变红（审计 §8「视觉通道
         * priorMemory 恒 null」）。给一个"空表"默认值，就是给下一个调用点一次静默复现它的
         * 机会（模式 E：缺省值冒充真实信号）。
         *
         * 收**时间戳**而不是 `ProblemMemoryState`：排空内部要把"上一次复习"推进到刚刚写下的
         * 那一行，而完整的状态对象要求 `nextReviewAt ≥ lastReviewedAt`，于是推进时只能给
         * 无关字段编值。只收时间戳就没有可编的字段。
         */
        previousReviewedAtByUnit: Map<String, Long>,
    ): Int {
        var createdCount = 0
        // 一轮里会**推进**的副本：同一次排空可能要补录同一个单元的多次视觉交互
        // （相隔超过一小时的冷却、且都还没入过库），后一条的 `delta_t` 必须以**紧邻的前一条**
        // 为界，而不是跨过它去读上一次投影——否则优化器会把"隔了两天"的第二次读成"隔了五天"。
        // DAO 按 `attempted_at_epoch_millis ASC` 返回，所以顺序推进是对的。
        val previousByUnit = previousReviewedAtByUnit.toMutableMap()
        mistakes
            .distinctBy(MistakeRecord::practiceUnitId)
            .forEach { mistake ->
                database.readVisualInteractionAttempts(mistake.problemRevisionId)
                    .forEach { attempt ->
                        if (ingestOne(attempt, mistake, previousByUnit)) {
                            createdCount += 1
                        }
                    }
            }
        return createdCount
    }

    /**
     * Converts one judged visual interaction into ledger evidence. Stays
     * conservative on purpose (audit §12): only actions with a decisive
     * local verdict, anchored to one active saved question that already has
     * accepted knowledge bindings, may enter the mastery ledger.
     */
    private suspend fun ingestOne(
        attempt: VisualInteractionAttemptRecord,
        mistake: MistakeRecord,
        previousReviewedAtByUnit: MutableMap<String, Long>,
    ): Boolean {
        // Exploratory selects and UNDECIDABLE tool actions (draw/measure/reset)
        // carry no answer semantics and stay audit-only.
        if (attempt.actionKind !in DECISIVE_VISUAL_ACTION_KINDS) return false
        if (attempt.problemRevisionId != mistake.problemRevisionId) return false
        // Spec 2.7: visual interactions cool down for one hour per unit.
        if (writeContext.isWithinCooldown(
                practiceUnitId = mistake.practiceUnitId,
                sourceKind = ReviewLogSink.SOURCE_KIND_VISUAL,
                cooldownMillis = VISUAL_COOLDOWN_MILLIS,
                atEpochMillis = attempt.attemptedAtEpochMillis,
            )
        ) {
            return false
        }
        val bindings = database.readPracticeUnitKnowledgeBindings(mistake.practiceUnitId)
            .filter { binding -> binding.basisRevisionId == mistake.problemRevisionId }
            .sortedWith(compareBy({ it.acceptedAtEpochMillis }, { it.bindingId }))
        if (bindings.isEmpty()) return false
        // Audit §5.3.6 / S-9 — two rules on the same pool:
        //
        // (1) Accepted classifications win outright. The placeholder binding is
        //     the OLDEST row for any question that was reviewed before it was
        //     classified, so leaving it in the pool hands every later visual
        //     interaction to the subject-wide placeholder and the real knowledge
        //     nodes never see it. It is used only while it is all the question
        //     has. (1.4 stops NEW placeholder rows being written for a classified
        //     question, but it cannot and should not remove the one that recorded
        //     the question as unclassified at the time.)
        // (2) The chosen group is the most recently accepted one, not the
        //     earliest: a user correction writes a NEW group ("user-corrected-v1")
        //     with a later accepted_at, while the earlier group survives as long
        //     as attributions reference it (deleteUnreferencedKnowledgeBindings
        //     retains exactly those), and §2.13 says the old knowledge node stops
        //     receiving new evidence. Taking the earliest group would keep feeding
        //     the node the user just corrected away from. Spec:
        //     three-store-linkage-design.md §3.2「取 accepted_at 最新的
        //     taxonomy_version 组」.
        val classified = bindings.filterNot { it.isPseudoFallback }
        val pool = classified.ifEmpty { bindings }
        val taxonomyVersion = pool.last().taxonomyVersion
        val attributed = pool.filter { it.taxonomyVersion == taxonomyVersion }
        val secondaryWeight = SECONDARY_VISUAL_ATTRIBUTION_WEIGHT_POOL /
            (attributed.size - 1).coerceAtLeast(1)
        val attributions = attributed.mapIndexed { index, binding ->
            KnowledgeEvidenceAttribution(
                bindingId = binding.bindingId,
                knowledgeNodeId = binding.knowledgeNodeId,
                weight = if (index == 0) {
                    PRIMARY_VISUAL_ATTRIBUTION_WEIGHT
                } else {
                    secondaryWeight
                },
                basisRevisionId = mistake.problemRevisionId,
                taxonomyVersion = taxonomyVersion,
                role = if (index == 0) {
                    EvidenceAttributionRole.PRIMARY
                } else {
                    EvidenceAttributionRole.SECONDARY
                },
                certainty = EvidenceAttributionCertainty.DIRECT,
            )
        }
        val evidence = if (attempt.feasible) {
            LearningEvidence(
                direction = LearningEvidenceDirection.POSITIVE,
                weight = VISUAL_SATISFIED_WEIGHT,
                reason = LearningEvidenceReason.VISUAL_INTERACTION_SATISFIED,
            )
        } else {
            LearningEvidence(
                direction = LearningEvidenceDirection.NEGATIVE,
                weight = VISUAL_VIOLATED_WEIGHT,
                reason = LearningEvidenceReason.VISUAL_INTERACTION_VIOLATED,
            )
        }
        val snapshot = AssessmentEvidenceSnapshot(
            snapshotId = writeContext.stableId("visual-snapshot", attempt.attemptId),
            assessmentItemId = VISUAL_ASSESSMENT_ITEM_ID_PREFIX + writeContext.stableId(
                namespace = "item",
                requestId = "${mistake.practiceUnitId}\n${attempt.attemptId}",
            ),
            practiceUnitId = mistake.practiceUnitId,
            problemRevisionId = mistake.problemRevisionId,
            answerSpecId = VISUAL_ANSWER_SPEC_ID,
            itemFamilyId = VISUAL_ITEM_FAMILY_ID,
            sourceBundleId = null,
            taxonomyVersion = taxonomyVersion,
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot.unknown(),
            attributions = attributions,
            capturedAtEpochMillis = attempt.attemptedAtEpochMillis,
        )
        database.saveAssessmentEvidenceSnapshot(snapshot)
        val writeResult = database.recordAttempt(
            AttemptWriteCommand(
                learnerId = learnerId,
                submissionId = writeContext.stableId("submission", "visual-attempt:${attempt.attemptId}"),
                attemptId = writeContext.stableId("attempt", "visual-attempt:${attempt.attemptId}"),
                presentationId = writeContext.stableId("visual-presentation", attempt.attemptId),
                assessmentSnapshotId = snapshot.snapshotId,
                submittedResponse = AttemptSubmittedResponse.Choice(
                    choiceId = if (attempt.feasible) "visual:SATISFIED" else "visual:VIOLATED",
                    choiceMarkdown = attempt.feedback.ifBlank {
                        if (attempt.feasible) {
                            "操作满足题目条件"
                        } else {
                            "操作不满足题目条件"
                        }
                    },
                    submittedAtEpochMillis = attempt.attemptedAtEpochMillis,
                ),
                evidence = evidence,
                problemMemoryOutcome = if (attempt.feasible) {
                    ProblemMemoryOutcome.ASSISTED_RECALL
                } else {
                    ProblemMemoryOutcome.RETRIEVAL_FAILURE
                },
                occurredAtEpochMillis = attempt.attemptedAtEpochMillis,
                durationSeconds = 0,
                studyDay = writeContext.studyDayAt(attempt.attemptedAtEpochMillis),
            ),
        )
        if (writeResult.created) {
            reviewLogSink.record(
                practiceUnitId = mistake.practiceUnitId,
                evidence = evidence,
                occurredAtEpochMillis = attempt.attemptedAtEpochMillis,
                durationSeconds = 0,
                studyDay = writeContext.studyDayAt(attempt.attemptedAtEpochMillis),
                sourceKind = ReviewLogSink.SOURCE_KIND_VISUAL,
                sourceId = writeResult.attempt.attemptId,
                previousReviewedAtEpochMillis = previousReviewedAtByUnit[mistake.practiceUnitId],
            )
            // 这一条刚刚成为该单元"最近的一次复习"：同一次排空里排在它后面的视觉证据
            // 必须以它为界算 `delta_t`（否则会跨过它、读到上一次投影去，见 ingestPending 的注释）。
            //
            // **无条件推进**（2026-09-13 修正）：此前这里带一个 `containsKey` 守卫，理由是
            // "map 里没有它 ⇒ 这个单元还没被投影过 ⇒ `delta_t = 0` 是首次复习的约定"。
            // 那个理由对**第一条**成立，对**第二条**不成立——第一条刚刚创建了账本行，
            // 此时它就已经是该单元最近的一次复习了；守卫让第二条也读到 null，
            // 于是同日/跨日的第二条视觉证据被当成"首次复习"喂给 FSRS，
            // **把跨天间隔写成 0 送进优化器的训练数据**（与 F-01 同一类）。
            // 顺序保证了第一条仍然拿到 null：`record` 在上面读 map，这一行才写。
            previousReviewedAtByUnit[mistake.practiceUnitId] = attempt.attemptedAtEpochMillis
            return true
        }
        // A replay after the first successful sweep must not claim a new
        // creation; the ledger already has this attempt exactly once.
        return false
    }

    private companion object {
        const val VISUAL_SATISFIED_WEIGHT = 0.25
        const val VISUAL_VIOLATED_WEIGHT = 0.5
        const val PRIMARY_VISUAL_ATTRIBUTION_WEIGHT = 0.6
        const val SECONDARY_VISUAL_ATTRIBUTION_WEIGHT_POOL = 0.4
        const val VISUAL_ASSESSMENT_ITEM_ID_PREFIX = "local-visual-interaction:"
        const val VISUAL_ANSWER_SPEC_ID = "local-visual-interaction-v1"
        const val VISUAL_ITEM_FAMILY_ID = "local-visual-interaction"
        val DECISIVE_VISUAL_ACTION_KINDS = setOf(
            "DragPoint",
            "AdjustParameter",
            "Connect",
            "OrderItems",
            "SubmitHypothesis",
        )
        /** Spec 2.7: visual interactions cool down for one hour per unit. */
        const val VISUAL_COOLDOWN_MILLIS = 1L * 60 * 60 * 1000
    }
}
