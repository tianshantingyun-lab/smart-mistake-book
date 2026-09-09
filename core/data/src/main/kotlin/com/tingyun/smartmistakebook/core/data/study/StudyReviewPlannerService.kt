package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ReviewPlanBundle
import com.tingyun.smartmistakebook.core.database.ReviewPlanRecord
import com.tingyun.smartmistakebook.core.database.ReviewQueueItemRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewCandidate
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewQueueEntry
import com.tingyun.smartmistakebook.core.domain.KnowledgeReviewSessionPlan
import com.tingyun.smartmistakebook.core.domain.extractReviewKnowledgeScope
import com.tingyun.smartmistakebook.core.domain.knowledgeRecallRiskByNode
import com.tingyun.smartmistakebook.core.domain.selectKnowledgeReviewQueue
import com.tingyun.smartmistakebook.core.domain.ReviewScopeQuestion
import com.tingyun.smartmistakebook.core.domain.HLRPredictionAuditService
import com.tingyun.smartmistakebook.core.domain.LogDurationModel
import com.tingyun.smartmistakebook.core.domain.NewIntroductionPolicy
import com.tingyun.smartmistakebook.core.domain.PredictionAuditSink
import com.tingyun.smartmistakebook.core.domain.ReviewCandidate
import com.tingyun.smartmistakebook.core.domain.ReviewPlanningRequest
import com.tingyun.smartmistakebook.core.domain.ReviewPlanner
import com.tingyun.smartmistakebook.core.domain.ReviewPlannerV2
import com.tingyun.smartmistakebook.core.domain.SchedulingSettingsStore
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.ReviewPlan
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Produces the day's review plan and resolves knowledge context for the study
 * repository: candidate assembly, intake introduction, the V1/V2 planner
 * choice, shadow-prediction persistence and the knowledge-topic lookups the
 * knowledge review plan needs. Extracted so planning stays auditable without
 * the repository's snapshot orchestration around it.
 */
internal class StudyReviewPlannerService(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val studyZoneId: ZoneId,
    private val clock: Clock,
    private val reviewTimeBudgetSeconds: Int,
    private val useReviewPlannerV2: Boolean,
    private val fixtureSource: StudyFixtureSource,
    private val reviewPlanner: ReviewPlanner,
    private val reviewPlannerV2: ReviewPlannerV2,
    private val durationModel: LogDurationModel,
    private val reviewLogSink: ReviewLogSink,
    private val schedulingSettingsStore: SchedulingSettingsStore?,
    private val predictionAuditService: HLRPredictionAuditService,
    private val predictionAuditSink: PredictionAuditSink,
    private val learnerSnapshot: suspend () -> LearnerSnapshot,
) {

    /**
     * Best-effort shadow-prediction persistence (audit §6.3): planning must
     * never fail because the audit loop failed.
     */
    private suspend fun persistShadowPredictions(
        request: ReviewPlanningRequest,
        plan: ReviewPlan,
    ) {
        try {
            val latenciesSeconds = plan.queueItems.associate { item ->
                val latencyMs = runCatching {
                    database.findLastPredictionLatencyMs(item.practiceUnitId)
                }.getOrNull()
                val seconds = latencyMs?.let { (it / 1000L).toInt().coerceIn(0, 300) }
                item.practiceUnitId to seconds
            }
            predictionAuditService.planPredictions(
                request = request,
                scoredPracticeUnitIds = plan.queueItems.map { it.practiceUnitId },
                lastResponseLatenciesSeconds = latenciesSeconds,
            ).forEach { audit -> predictionAuditSink.record(audit) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Shadow audit degradation must never surface to the user.
        }
    }


    /**
     * Exam-mode ramp (spec 2.17): during the fourteen days before a declared
     * exam, matching candidates gain priority so they enter the queue before
     * their regular due date. The ramp peaks at the exam day and falls back
     * to zero automatically afterwards.
     */
    suspend fun examPriorityFor(subject: String, localDayEpochDay: Long): Double {
        val store = schedulingSettingsStore ?: return 0.0
        val exams = store.exams.first().filter { it.subject == subject }
        var best = 0.0
        for (exam in exams) {
            val daysUntil = exam.examEpochDay - localDayEpochDay
            if (daysUntil in 0..EXAM_RAMP_DAYS) {
                best = maxOf(best, 1.0 - daysUntil.toDouble() / EXAM_RAMP_DAYS)
            }
        }
        return best.coerceIn(0.0, 1.0)
    }


    /**
     * Days until the nearest declared exam (any subject), or null when no
     * exam is ahead — the catch-up input of [NewIntroductionPolicy] (spec
     * `batch-intake-spec.md` §2): near an exam, intake converts by
     * ceil(remaining backlog / days) instead of the fixed time share.
     */
    /**
     * L2 tier baseline (spec §2): map the FSRS difficulty (1..10) onto the
     * same EASY/MEDIUM/HARD bands the planner uses (ceilings 4/7) and return
     * the model-tier baseline seconds for never-attempted questions.
     */
    fun tierBaselineSecondsFor(difficulty: Double): Int = when {
        difficulty < 4.0 -> LogDurationModel.TIER_BASELINE_EASY_SECONDS
        difficulty < 7.0 -> LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS
        else -> LogDurationModel.TIER_BASELINE_HARD_SECONDS
    }


    suspend fun daysUntilNearestExam(localDayEpochDay: Long): Int? {
        val store = schedulingSettingsStore ?: return null
        return store.exams.first()
            .map { (it.examEpochDay - localDayEpochDay).toInt() }
            .filter { it >= 0 }
            .minOrNull()
    }


    suspend fun currentReviewPlan(
        planningContext: PlanningContext,
    ): ReviewPlanBundle? = database.observeCurrentReviewPlan(
        learnerId = learnerId,
        localDayEpochDay = planningContext.localDate.toEpochDay(),
        timeZoneId = studyZoneId.id,
    ).first()


    suspend fun createReviewPlan(
        mistakes: List<MistakeRecord>,
        learnerSnapshot: LearnerSnapshot,
        planningContext: PlanningContext,
    ): ReviewPlanBundle {
        val avoidanceUnits = reviewLogSink.avoidancePracticeUnitIds()
        // Spec 3.4: the pseudo knowledge node must exist in knowledge_node
        // BEFORE the plan persists its queue (review_queue_knowledge_node is
        // FK-restricted), so unbound questions materialize their pseudo KC
        // here rather than at first submission.
        val pseudoNodeIds = mutableMapOf<String, String>()
        mistakes
            .sortedBy(MistakeRecord::practiceUnitId)
            .distinctBy(MistakeRecord::practiceUnitId)
            .filter { it.knowledgeNodeIds.isEmpty() }
            .forEach { mistake ->
                val binding = database.ensurePseudoKnowledgeBinding(
                    practiceUnitId = mistake.practiceUnitId,
                    problemRevisionId = mistake.problemRevisionId,
                    taxonomyVersion = "pseudo-plan-v1",
                    subject = mistake.subject,
                    acceptedAtEpochMillis = planningContext.planningAtEpochMillis,
                )
                if (binding != null) {
                    pseudoNodeIds[mistake.practiceUnitId] = binding.knowledgeNodeId
                }
            }
        val candidates = mistakes
            .sortedBy(MistakeRecord::practiceUnitId)
            .distinctBy(MistakeRecord::practiceUnitId)
            .map { mistake ->
                val curatedEvidence = fixtureSource
                    .teachingArtifactForPracticeUnit(mistake.practiceUnitId)
                    ?.assessmentItems
                    ?.singleOrNull()
                    ?.let { assessment ->
                        fixtureSource.evidenceSnapshotForAssessment(assessment.id)
                    }
                ReviewCandidate(
                    practiceUnitId = mistake.practiceUnitId,
                    leech = learnerSnapshot.problemMemoryStates[mistake.practiceUnitId]?.isLeeched == true,
                    avoidance = mistake.practiceUnitId in avoidanceUnits,
                    knowledgeNodeIds = mistake.knowledgeNodeIds.ifEmpty {
                        curatedEvidence?.attributions
                            ?.mapTo(linkedSetOf()) { it.knowledgeNodeId }
                            .orEmpty()
                            .ifEmpty {
                                // Spec §3.4: unbound questions fall back to the
                                // subject-scoped pseudo KC (materialized above)
                                // so their mastery evidence stays visible to
                                // the planner.
                                setOf(
                                    pseudoNodeIds[mistake.practiceUnitId]
                                        ?: "pseudo:${mistake.subject.uppercase()}",
                                )
                            }
                    },
                    itemFamilyId = curatedEvidence?.itemFamilyId
                        ?: "saved-question:${mistake.practiceUnitId}",
                    sourceBundleId = curatedEvidence?.sourceBundleId,
                    subjectId = mistake.subject,
                    // Mistake records do not carry an item-type dimension yet; the
                    // duration model buckets on (learner, subject, itemType=null,
                    // difficulty) until the data layer exposes item types.
                    difficulty = learnerSnapshot.problemMemoryStates[mistake.practiceUnitId]
                        ?.difficulty ?: DEFAULT_CANDIDATE_DIFFICULTY,
                    estimatedDurationSeconds = mistake.estimatedSeconds,
                    repeatMistakePriority = (mistake.captureOccurrenceCount - 1)
                        .coerceIn(0, MAX_REPEAT_CAPTURE_BONUS_COUNT).toDouble() /
                        MAX_REPEAT_CAPTURE_BONUS_COUNT,
                    eligibleSinceEpochMillis = mistake.createdAtEpochMillis,
                    examPriority = examPriorityFor(
                        subject = mistake.subject,
                        localDayEpochDay = planningContext.localDate.toEpochDay(),
                    ),
                )
            }

        // Spec `batch-intake-spec.md` §1-I1/I2/I3: intake only adds inventory.
        // Zero-evidence NEW questions (no memory state) enter today's plan
        // only through NewIntroductionPolicy — the time-share slice (with
        // exam catch-up) decides which of them are introduced today, and the
        // rest stay in the backlog with NO learning pressure. Introduced
        // questions accrue waiting from TODAY (eligibleSince = planningAt),
        // never from their creation date, so an old backlog cannot outrank
        // due reviews the day it finally gets opened.
        val finalCandidates = run {
            val freshIds = candidates
                .filter { learnerSnapshot.problemMemoryStates[it.practiceUnitId] == null }
                .mapTo(hashSetOf()) { it.practiceUnitId }
            if (freshIds.isEmpty()) {
                candidates
            } else {
                val fresh = candidates.filter { it.practiceUnitId in freshIds }
                val seasoned = candidates.filter { it.practiceUnitId !in freshIds }
                // Hypercorrection ordering input (spec §4): the multi-source
                // confidence level of each card's most recent wrong attempt,
                // judged from signals already stored in review_log.
                val confidenceAtError = reviewLogSink.confidenceAtErrorByPracticeUnit()
                val decision = NewIntroductionPolicy.decide(
                    candidates = fresh.map { candidate ->
                        NewIntroductionPolicy.IntakeCandidate(
                            practiceUnitId = candidate.practiceUnitId,
                            estimatedDurationSeconds = durationModel.expectedSecondsForNew(
                                learnerId = learnerId,
                                subjectId = candidate.subjectId,
                                itemType = candidate.itemType,
                                difficulty = candidate.difficulty,
                                tierBaselineSeconds = tierBaselineSecondsFor(candidate.difficulty),
                            ).toInt().coerceAtLeast(1),
                            examPriority = candidate.examPriority,
                            confidenceAtError = confidenceAtError[candidate.practiceUnitId],
                            createdAtEpochMillis = candidate.eligibleSinceEpochMillis
                                ?: planningContext.planningAtEpochMillis,
                        )
                    },
                    timeBudgetSeconds = reviewTimeBudgetSeconds,
                    daysLeftToExam = daysUntilNearestExam(planningContext.localDate.toEpochDay()),
                )
                val introducedIds = decision.introduced.mapTo(hashSetOf()) { it.practiceUnitId }
                // Introduced-today questions start accruing waiting pressure
                // now; everything else keeps its original eligibility.
                val adjustedFresh = fresh.map { candidate ->
                    if (candidate.practiceUnitId in introducedIds) {
                        candidate.copy(eligibleSinceEpochMillis = planningContext.planningAtEpochMillis)
                    } else {
                        candidate
                    }
                }
                seasoned + adjustedFresh.filter { it.practiceUnitId in introducedIds }
            }
        }
        val request = ReviewPlanningRequest(
            learnerSnapshot = learnerSnapshot,
            candidates = finalCandidates,
            localDayEpochDay = planningContext.localDate.toEpochDay(),
            timeZoneId = studyZoneId.id,
            timeBudgetSeconds = reviewTimeBudgetSeconds,
            planningAtEpochMillis = planningContext.planningAtEpochMillis,
        )
        val plan = if (useReviewPlannerV2) {
            reviewPlannerV2.plan(request)
        } else {
            // Rollback path (audit §3.4): the audited V1 greedy planner.
            reviewPlanner.plan(request)
        }
        persistShadowPredictions(request = request, plan = plan)
        return ReviewPlanBundle(
            plan = ReviewPlanRecord(
                reviewPlanId = plan.planId,
                learnerId = learnerId,
                localDate = planningContext.localDate.toString(),
                localDayEpochDay = planningContext.localDate.toEpochDay(),
                timeZoneId = studyZoneId.id,
                timeBudgetSeconds = plan.timeBudgetSeconds,
                planningAtEpochMillis = plan.generatedAtEpochMillis,
                status = StudyDbValue.ReviewStatus.PLANNED,
                plannerVersion = plan.plannerVersion,
                projectionCheckpoint = plan.projectionCheckpoint.lastSequence,
                inputFingerprint = plan.planFingerprint,
                planFingerprint = plan.planFingerprint,
                planRevision = 1,
                createdAtEpochMillis = plan.generatedAtEpochMillis,
            ),
            queue = plan.queueItems.map { queueItem ->
                ReviewQueueItemRecord(
                    reviewQueueItemId = queueItem.queueItemId,
                    reviewPlanId = plan.planId,
                    practiceUnitId = queueItem.practiceUnitId,
                    knowledgeNodeIds = queueItem.knowledgeNodeIds,
                    itemFamilyId = queueItem.itemFamilyId,
                    sourceBundleId = queueItem.sourceBundleId,
                    reasons = queueItem.reasons.mapTo(linkedSetOf()) { it.name },
                    ordinal = queueItem.scheduledOrder,
                    priorityScore = queueItem.priorityScore,
                    difficultyBand = queueItem.difficultyBand.name,
                    dueAtEpochMillis = queueItem.dueAtEpochMillis,
                    estimatedSeconds = queueItem.estimatedDurationSeconds,
                    reasonSnapshot = queueItem.reasons.map { it.name }.sorted().joinToString(","),
                )
            },
            activeSession = null,
            isCurrent = true,
        )
    }


    suspend fun resolveKnowledgeContexts(
        knowledgeNodeIds: Set<String>,
    ): Map<String, ResolvedKnowledgeContext> {
        if (knowledgeNodeIds.isEmpty()) return emptyMap()
        val nodesById = database.readKnowledgeNodesByIds(knowledgeNodeIds)
            .associateByTo(linkedMapOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        var pendingParentIds = nodesById.values
            .mapNotNullTo(linkedSetOf(), KnowledgeNodeSeedRecord::parentKnowledgeNodeId)
            .filterNotTo(linkedSetOf(), nodesById::containsKey)
        var remainingDepth = MAX_KNOWLEDGE_TOPIC_DEPTH
        while (pendingParentIds.isNotEmpty() && remainingDepth > 0) {
            val parents = database.readKnowledgeNodesByIds(pendingParentIds)
            parents.forEach { parent -> nodesById[parent.knowledgeNodeId] = parent }
            pendingParentIds = parents
                .mapNotNullTo(linkedSetOf(), KnowledgeNodeSeedRecord::parentKnowledgeNodeId)
                .filterNotTo(linkedSetOf(), nodesById::containsKey)
            remainingDepth -= 1
        }
        return knowledgeNodeIds.mapNotNull { knowledgeNodeId ->
            val node = nodesById[knowledgeNodeId] ?: return@mapNotNull null
            val path = ArrayDeque<String>()
            val visited = hashSetOf<String>()
            var parentId = node.parentKnowledgeNodeId
            while (parentId != null && path.size < MAX_KNOWLEDGE_TOPIC_DEPTH) {
                if (!visited.add(parentId)) break
                val parent = nodesById[parentId] ?: break
                path.addFirst(parent.displayName)
                parentId = parent.parentKnowledgeNodeId
            }
            val subject = runCatching { SubjectKind.valueOf(node.subject) }
                .getOrDefault(SubjectKind.GENERAL)
            knowledgeNodeId to ResolvedKnowledgeContext(
                displayName = node.displayName,
                subject = subject,
                topicPath = path.toList(),
            )
        }.toMap()
    }


    /**
     * 只保留可出题的知识点（spec dual-review-entry §3.3）：KNOWLEDGE_QUIZ 把讲解材料的
     * boundaryMarkdown 当防臆造锚，无材料的伪节点（pseudo:*）/未装配材料节点无法出题。
     * 逐科目读该组节点可用的讲解材料，再据 material↔node 绑定交集得出真正有材料覆盖的
     * 节点——与派发时 TutorTeachingReferenceRepository 的解析口径一致，避免计划里出现
     * "排了却出不了题"的死节点。
     *
     * 返回 节点 → 该节点的**讲解材料组**（多份材料时取 materialId 字典序最小者，确定性），
     * 供队列做"同材料不连续出题"的交错（spec §3.2 多样性）。
     */
    suspend fun quizAbleScopeNodes(
        scope: Set<String>,
        subjectByNode: Map<String, SubjectKind>,
    ): Map<String, String> {
        if (scope.isEmpty()) return emptyMap()
        val materialGroupByNode = linkedMapOf<String, String>()
        scope
            .mapNotNull { knowledgeNodeId ->
                subjectByNode[knowledgeNodeId]?.let { subject -> knowledgeNodeId to subject }
            }
            .groupBy({ (_, subject) -> subject }, { (knowledgeNodeId, _) -> knowledgeNodeId })
            .forEach { (subject, nodeIds) ->
                val materials = database.readKnowledgeTeachingMaterialsForNodes(
                    subject = subject.name,
                    knowledgeNodeIds = nodeIds.toSet(),
                    limit = MAX_KNOWLEDGE_QUIZ_SCOPE_MATERIALS,
                )
                if (materials.isEmpty()) return@forEach
                val materialIds = materials.mapTo(linkedSetOf()) { it.materialId }
                database.readKnowledgeTeachingMaterialNodeBindings(materialIds).forEach { binding ->
                    if (binding.knowledgeNodeId !in nodeIds) return@forEach
                    val existing = materialGroupByNode[binding.knowledgeNodeId]
                    if (existing == null || binding.materialId < existing) {
                        materialGroupByNode[binding.knowledgeNodeId] = binding.materialId
                    }
                }
            }
        return materialGroupByNode
    }


    fun planningContext(learnerSnapshot: LearnerSnapshot): PlanningContext {
        val referenceAt = maxOf(clock.millis(), learnerSnapshot.decisionWatermarkEpochMillis)
        val localDate = Instant.ofEpochMilli(referenceAt).atZone(studyZoneId).toLocalDate()
        val startOfDay = localDate.atStartOfDay(studyZoneId).toInstant().toEpochMilli()
        return PlanningContext(
            localDate = localDate,
            planningAtEpochMillis = maxOf(startOfDay, learnerSnapshot.decisionWatermarkEpochMillis),
        )
    }


    internal data class PlanningContext(
        val localDate: LocalDate,
        val planningAtEpochMillis: Long,
    )


    suspend fun currentKnowledgeReviewPlan(
        requestId: String,
        occurredAtEpochMillis: Long,
        mistakes: List<MistakeRecord>,
        knowledgeNames: Map<String, String>,
    ): KnowledgeReviewSessionPlan {
        require(requestId.isNotBlank()) { "Knowledge-review request id must not be blank" }
        require(occurredAtEpochMillis >= 0) { "Knowledge-review request time must not be negative" }
        val planningContext = planningContext(learnerSnapshot())
        val currentPlan = requireNotNull(
            database.observeActiveReviewPlan(learnerId).first() ?: currentReviewPlan(planningContext),
        ) {
            "No current review plan is available"
        }
        // 知识点复习不要求错题会话已启动：今日有 current 计划（含已完成）即可据此排知识点。
        val planQueue = currentPlan.queue
        if (planQueue.isEmpty()) return KnowledgeReviewSessionPlan()

        // 范围 = 今天错题复习队列的题绑定知识点并集（T1），只取今天队列实际覆盖的点，
        // 不把整个知识库拖进来（spec §1.3：范围 = 今天错题里涉及的知识点）。
        val queueScope = extractReviewKnowledgeScope(
            planQueue.map { queueItem ->
                ReviewScopeQuestion(
                    practiceUnitId = queueItem.practiceUnitId,
                    knowledgeNodeIds = queueItem.knowledgeNodeIds,
                )
            },
        )
        if (queueScope.isEmpty()) return KnowledgeReviewSessionPlan()

        val learnerSnapshot = learnerSnapshot()
        val resolvedContexts = resolveKnowledgeContexts(queueScope)
        // 科目权威来源：知识点节点自身的 subject（resolveKnowledgeContexts 解析自 knowledge_node），
        // 兜底取今天队列中绑定该点的错题的 subject——两者都是知识库真值，不猜前缀。
        val subjectByNode = resolvedContexts.mapValues { (_, context) -> context.subject } +
            mistakes.asSequence()
                .flatMap { mistake -> mistake.knowledgeNodeIds.map { it to mistake.subject } }
                .filter { (knowledgeNodeId, _) -> knowledgeNodeId in queueScope }
                .associate { (knowledgeNodeId, subject) ->
                    knowledgeNodeId to runCatching { SubjectKind.valueOf(subject) }
                        .getOrDefault(SubjectKind.GENERAL)
                }
        // 只排可出题的知识点：KNOWLEDGE_QUIZ 以讲解材料 boundary 为防臆造锚（spec §3.3），
        // 无材料的伪节点（pseudo:*）或未装配材料的真节点无法出题，若进计划会让会话卡死。
        val materialGroupByNode = quizAbleScopeNodes(queueScope, subjectByNode)
        if (materialGroupByNode.isEmpty()) return KnowledgeReviewSessionPlan()
        val candidateIds = queueScope.filterTo(linkedSetOf()) { it in materialGroupByNode }
        // 知识点没有自己的记忆痕迹：到期风险由今天队列中承载它的题目的 FSRS 检索概率聚合
        // （取最小 R），而不是掌握度 EMA + 45 天悬崖（研究 2026-09-09 §1）。
        val boundUnitsByNode = planQueue
            .flatMap { item -> item.knowledgeNodeIds.map { nodeId -> nodeId to item.practiceUnitId } }
            .groupBy({ it.first }, { it.second })
        val recallRiskByNode = knowledgeRecallRiskByNode(
            boundPracticeUnitIdsByNode = boundUnitsByNode,
            memoryStates = learnerSnapshot.problemMemoryStates,
            nowEpochMillis = planningContext.planningAtEpochMillis,
        )
        val selected = selectKnowledgeReviewQueue(
            planner = reviewPlanner,
            candidates = candidateIds.map { knowledgeNodeId ->
                KnowledgeReviewCandidate(
                    knowledgeNodeId = knowledgeNodeId,
                    subjectId = subjectByNode[knowledgeNodeId]?.name ?: SubjectKind.GENERAL.name,
                    materialGroupId = materialGroupByNode[knowledgeNodeId],
                    state = learnerSnapshot.knowledgeMasteryStates[knowledgeNodeId],
                    estimatedDurationSeconds = LogDurationModel.TIER_BASELINE_MEDIUM_SECONDS,
                    recallRisk = recallRiskByNode[knowledgeNodeId],
                )
            },
            now = planningContext.planningAtEpochMillis,
            timeBudgetSeconds = reviewTimeBudgetSeconds,
        )
        if (selected.isEmpty()) return KnowledgeReviewSessionPlan()
        return KnowledgeReviewSessionPlan(
            queue = selected.map { scored ->
                val context = resolvedContexts[scored.knowledgeNodeId]
                val state = learnerSnapshot.knowledgeMasteryStates[scored.knowledgeNodeId]
                KnowledgeReviewQueueEntry(
                    knowledgeNodeId = scored.knowledgeNodeId,
                    subject = subjectByNode[scored.knowledgeNodeId]?.name
                        ?: SubjectKind.GENERAL.name,
                    displayName = context?.displayName
                        ?: knowledgeNames[scored.knowledgeNodeId]
                        ?: scored.knowledgeNodeId,
                    masteryScore = state?.masteryScore,
                    lastEvidenceAtEpochMillis = state?.lastEvidenceAtEpochMillis,
                )
            },
        )
    }

    private companion object {
        /** Difficulty mid-point on the FSRS 1..10 domain (spec 3.2). */
        const val DEFAULT_CANDIDATE_DIFFICULTY = 5.5
        const val MAX_REPEAT_CAPTURE_BONUS_COUNT = 4
        const val MAX_KNOWLEDGE_TOPIC_DEPTH = 6
        /** 知识点复习范围材料读取上限（对齐 KnowledgeTeachingMaterialDao 的 1..64 约束）。 */
        const val MAX_KNOWLEDGE_QUIZ_SCOPE_MATERIALS = 64
        const val EXAM_RAMP_DAYS = 14
    }
}
