package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.domain.ReviewCognitiveLoad
import com.tingyun.smartmistakebook.core.domain.ReviewExamTarget
import com.tingyun.smartmistakebook.core.domain.ReviewKnowledgeSignal
import com.tingyun.smartmistakebook.core.domain.ReviewKnowledgeTrend
import com.tingyun.smartmistakebook.core.domain.ReviewPriorityReason
import com.tingyun.smartmistakebook.core.domain.ReviewRecallState
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewCandidate
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlan
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanner
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanningRequest
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LEARNER_MASTERY_PROJECTION_POLICY_VERSION
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContext
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextItem
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextRequest
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextSelection
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.student.mistake.database.MAX_PAGE_SIZE
import com.tingyun.smartmistakebook.core.student.mistake.database.StoreStudentReviewQueueCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeStore
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateWithKnowledge
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewCandidateWithKnowledgePage
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueItem
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Immutable inputs for one learner's local-day review plan.
 *
 * [planningAtEpochMillis] is supplied by the caller so retries use the same planning snapshot and
 * therefore the same plan fingerprint.
 */
internal data class ThreeAuthorityDailyReviewRequest(
    val learnerId: String,
    val localDayEpochDay: Long,
    val timeZoneId: String,
    val timeBudgetSeconds: Int,
    val maxItemCount: Int = ThreeAuthorityReviewPlanningRequest.DEFAULT_MAX_ITEM_COUNT,
    val examTarget: ReviewExamTarget? = null,
    val planningAtEpochMillis: Long,
    val masteryProjectionVersion: String = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
) {
    init {
        learnerId.requireBoundedReviewValue("Review learner id")
        timeZoneId.requireBoundedReviewValue("Review time-zone id")
        masteryProjectionVersion.requireBoundedReviewValue("Mastery projection version")
        require(
            timeBudgetSeconds in
                0..ThreeAuthorityReviewPlanningRequest.MAX_TIME_BUDGET_SECONDS,
        ) {
            "Review time budget is outside the supported range"
        }
        require(maxItemCount in 1..ThreeAuthorityReviewPlanningRequest.MAX_ITEM_COUNT) {
            "Review item-count budget is outside the supported range"
        }
        require(planningAtEpochMillis >= 0L) {
            "Review planning time must not be negative"
        }
    }
}

internal enum class ReviewCandidateProfileState {
    READY,
    PENDING_KNOWLEDGE_ATTRIBUTION,
}

data class ReviewCandidateProfileSummary(
    val readyCount: Int,
    val pendingKnowledgeAttributionCount: Int,
    val exactProblemIdentityOnlyCount: Int,
) {
    init {
        require(
            readyCount >= 0 &&
                pendingKnowledgeAttributionCount >= 0 &&
                exactProblemIdentityOnlyCount in 0..readyCount,
        ) {
            "Review candidate profile counts are inconsistent"
        }
    }

    val inspectedCount: Int
        get() = readyCount + pendingKnowledgeAttributionCount
}

internal data class ThreeAuthorityDailyReviewPlanningResult(
    val plan: ThreeAuthorityReviewPlan,
    val candidateProfile: ReviewCandidateProfileSummary,
)

/**
 * Coordinates the three local authorities without sharing a connection, DAO, or SQL statement.
 *
 * Candidate reads and queue writes stay inside student-mistakes.db. Qualitative mastery reads stay
 * inside learner-mastery.db. The high-school catalog is intentionally absent because scheduling
 * needs neither display metadata nor another validation pass for source-issued knowledge refs.
 */
internal class ThreeAuthorityReviewPlanCoordinator(
    private val candidatePageReader:
        suspend (StudentReviewCandidateQuery) -> StudentReviewCandidateWithKnowledgePage,
    private val queueWriter: suspend (StoreStudentReviewQueueCommand) -> Unit,
    private val masteryContextReader:
        suspend (LocalMasteryContextRequest) -> LocalMasteryContext,
    private val planner: ThreeAuthorityReviewPlanner = ThreeAuthorityReviewPlanner(),
    private val candidateLimit: Int = ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT,
    private val inspectionLimit: Int = ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT,
) {
    constructor(
        studentMistakes: StudentMistakeStore,
        masteryContext: LocalMasteryContextReader,
        planner: ThreeAuthorityReviewPlanner = ThreeAuthorityReviewPlanner(),
        candidateLimit: Int = ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT,
        inspectionLimit: Int = ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT,
    ) : this(
        candidatePageReader = studentMistakes::readReviewCandidatesWithKnowledge,
        queueWriter = studentMistakes::storeReviewQueue,
        masteryContextReader = masteryContext::queryContext,
        planner = planner,
        candidateLimit = candidateLimit,
        inspectionLimit = inspectionLimit,
    )

    init {
        require(candidateLimit in 1..ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT) {
            "Review candidate limit is outside the supported range"
        }
        require(
            inspectionLimit in
                candidateLimit..ThreeAuthorityReviewPlanningRequest.MAX_CANDIDATE_COUNT,
        ) {
            "Review candidate inspection limit is outside the supported range"
        }
    }

    suspend fun planAndStore(request: ThreeAuthorityDailyReviewRequest): ThreeAuthorityReviewPlan =
        planAndStoreWithProfile(request).plan

    /**
     * Plans only candidates whose exact saved revision already has accepted knowledge attribution.
     *
     * Pending candidates remain in the student-owned candidate pool for a later day; they are not
     * inserted into today's queue and therefore cannot block the next ready item.
     */
    suspend fun planAndStoreWithProfile(
        request: ThreeAuthorityDailyReviewRequest,
    ): ThreeAuthorityDailyReviewPlanningResult {
        currentCoroutineContext().ensureActive()
        val inspectedCandidates = readCandidateSnapshot(request)
        val profiledCandidates =
            inspectedCandidates.groupBy { candidate -> candidate.profileState }
        val sourceCandidates =
            profiledCandidates[ReviewCandidateProfileState.READY]
                .orEmpty()
                .take(candidateLimit)
        val planningCandidates = sourceCandidates.map(::toPlanningCandidate)
        val knowledgeSignals =
            readKnowledgeSignals(
                selectMasteryNodes(sourceCandidates),
            )
        currentCoroutineContext().ensureActive()

        val planningRequest =
            ThreeAuthorityReviewPlanningRequest(
                learnerId = request.learnerId,
                candidates = planningCandidates,
                knowledgeSignals = knowledgeSignals,
                masteryProjectionVersion = request.masteryProjectionVersion,
                localDayEpochDay = request.localDayEpochDay,
                timeZoneId = request.timeZoneId,
                timeBudgetSeconds = request.timeBudgetSeconds,
                maxItemCount = request.maxItemCount,
                examTarget = request.examTarget,
                planningAtEpochMillis = request.planningAtEpochMillis,
            )
        val plan = planWithinQueueCapacity(planningRequest)
        check(plan.estimatedDurationSeconds <= request.timeBudgetSeconds) {
            "Review planner exceeded the strict daily time budget"
        }

        val queueCommand = plan.toStoreCommand(sourceCandidates)
        currentCoroutineContext().ensureActive()
        queueWriter(queueCommand)
        return ThreeAuthorityDailyReviewPlanningResult(
            plan = plan,
            candidateProfile =
                ReviewCandidateProfileSummary(
                    readyCount = sourceCandidates.size,
                    pendingKnowledgeAttributionCount =
                        profiledCandidates[
                            ReviewCandidateProfileState.PENDING_KNOWLEDGE_ATTRIBUTION
                        ].orEmpty().size,
                    // No reviewed structural-family receipt exists in the current student store.
                    // Exact saved problem identity is the only safe de-duplication authority.
                    exactProblemIdentityOnlyCount = sourceCandidates.size,
                ),
        )
    }

    private suspend fun readCandidateSnapshot(
        request: ThreeAuthorityDailyReviewRequest,
    ): List<StudentReviewCandidateWithKnowledge> {
        val collected = ArrayList<StudentReviewCandidateWithKnowledge>()
        val candidateIds = hashSetOf<String>()
        val visitedCursors = hashSetOf<StudentReviewCandidateCursor>()
        var cursor: StudentReviewCandidateCursor? = null
        var readyCount = 0

        while (readyCount < candidateLimit && collected.size < inspectionLimit) {
            currentCoroutineContext().ensureActive()
            val pageLimit =
                minOf(
                    MAX_PAGE_SIZE,
                    candidateLimit - readyCount,
                    inspectionLimit - collected.size,
                )
            val page =
                candidatePageReader(
                    StudentReviewCandidateQuery(
                        learnerId = request.learnerId,
                        nowEpochMillis = request.planningAtEpochMillis,
                        cursor = cursor,
                        limit = pageLimit,
                    ),
                )
            check(page.items.size <= pageLimit) {
                "Student mistake authority exceeded the requested review-candidate page size"
            }
            page.items.forEach { item ->
                check(item.candidate.problemRevision.problem.learnerId == request.learnerId) {
                    "Student mistake authority returned another learner's review candidate"
                }
                check(candidateIds.add(item.candidate.candidateId)) {
                    "Student mistake authority returned a duplicate review candidate"
                }
            }
            collected += page.items
            readyCount += page.items.count { item -> item.profileState == ReviewCandidateProfileState.READY }

            val nextCursor = page.nextCursor ?: break
            check(page.items.isNotEmpty()) {
                "Student mistake authority returned an empty non-terminal candidate page"
            }
            check(visitedCursors.add(nextCursor)) {
                "Student mistake authority repeated a review-candidate cursor"
            }
            cursor = nextCursor
        }
        return collected
    }

    private suspend fun readKnowledgeSignals(
        nodes: List<KnowledgeNodeRef>,
    ): List<ReviewKnowledgeSignal> {
        if (nodes.isEmpty()) return emptyList()

        val signals = ArrayList<ReviewKnowledgeSignal>()
        nodes.groupBy(KnowledgeNodeRef::subject).forEach { (subject, subjectNodes) ->
            subjectNodes
                .chunked(LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT)
                .forEach { batch ->
                    currentCoroutineContext().ensureActive()
                    val query =
                        LocalMasteryContextRequest.fromKnowledgeNodes(
                            subject = subject,
                            exactKnowledgeNodes = batch,
                            fallbackLimit = 0,
                        )
                    val context = masteryContextReader(query)
                    check(context.subject == subject) {
                        "Learner mastery authority crossed the requested subject"
                    }

                    val requestedByCanonicalFingerprint =
                        batch.associateBy(KnowledgeNodeRef::canonicalFingerprint)
                    val stableFingerprintByCanonicalFingerprint =
                        batch.zip(query.exactStableNodeFingerprints).associate { (node, fingerprint) ->
                            node.canonicalFingerprint to fingerprint
                        }
                    context.items.mapNotNullTo(signals) { item ->
                        if (item.selection != LocalMasteryContextSelection.EXACT) {
                            return@mapNotNullTo null
                        }
                        val exactNode =
                            requestedByCanonicalFingerprint[
                                item.knowledgeNode.canonicalFingerprint
                            ] ?: return@mapNotNullTo null
                        if (
                            item.stableNodeIdentityFingerprint !=
                            stableFingerprintByCanonicalFingerprint[exactNode.canonicalFingerprint]
                        ) {
                            return@mapNotNullTo null
                        }
                        item.toReviewSignal(exactNode)
                    }
                }
        }
        return signals
    }

    private fun planWithinQueueCapacity(
        request: ThreeAuthorityReviewPlanningRequest,
    ): ThreeAuthorityReviewPlan {
        val completePlan = planner.plan(request)
        if (completePlan.items.size <= MAX_STORED_REVIEW_ITEMS) return completePlan

        val retainedCandidateIds =
            completePlan.items
                .take(MAX_STORED_REVIEW_ITEMS)
                .mapTo(hashSetOf()) { it.candidateId }
        return planner.plan(
            request.copy(
                candidates =
                    request.candidates.filter { candidate ->
                        candidate.candidateId in retainedCandidateIds
                    },
            ),
        )
    }

    private companion object {
        // StoreStudentReviewQueueCommand enforces the same authority-owned persistence bound.
        const val MAX_STORED_REVIEW_ITEMS = 512
    }
}

private val StudentReviewCandidateWithKnowledge.profileState: ReviewCandidateProfileState
    get() =
        if (acceptedKnowledgeNodes.isEmpty()) {
            ReviewCandidateProfileState.PENDING_KNOWLEDGE_ATTRIBUTION
        } else {
            ReviewCandidateProfileState.READY
        }

private fun selectMasteryNodes(
    candidates: List<StudentReviewCandidateWithKnowledge>,
): List<KnowledgeNodeRef> =
    candidates
        .asSequence()
        .flatMap { it.acceptedKnowledgeNodes.asSequence() }
        .sortedWith(
            compareBy<KnowledgeNodeRef>(
                { it.subject.name },
                KnowledgeNodeRef::knowledgeNodeId,
                KnowledgeNodeRef::taxonomyVersion,
                KnowledgeNodeRef::knowledgePackVersion,
                KnowledgeNodeRef::canonicalFingerprint,
            ),
        )
        .distinctBy {
            StableKnowledgeIdentity(
                subject = it.subject,
                knowledgeNodeId = it.knowledgeNodeId,
                taxonomyVersion = it.taxonomyVersion,
            )
        }
        .take(ThreeAuthorityReviewPlanningRequest.MAX_KNOWLEDGE_SIGNAL_COUNT)
        .toList()

private fun toPlanningCandidate(
    source: StudentReviewCandidateWithKnowledge,
): ThreeAuthorityReviewCandidate {
    val candidate = source.candidate
    return ThreeAuthorityReviewCandidate(
        candidateId = candidate.candidateId,
        problemRevision = candidate.problemRevision,
        knowledgeNodes = source.acceptedKnowledgeNodes,
        problemFamilyId = source.reviewedProblemFamilyId
            ?.takeIf {
                source.reviewedProblemFamilyBindingDocumentFingerprint ==
                    candidate.problemRevision.documentCanonicalFingerprint
            }
            ?: candidate.problemRevision.documentCanonicalFingerprint,
        solutionFamilyId = null,
        presentationFamilyId = null,
        estimatedDurationSeconds = candidate.estimatedDurationSeconds,
        cognitiveLoad = ReviewCognitiveLoad.MODERATE,
        availableAtEpochMillis = candidate.availableAtEpochMillis,
        dueAtEpochMillis = candidate.dueAtEpochMillis,
        waitingSinceEpochMillis =
            minOf(
                candidate.updatedAtEpochMillis,
                candidate.availableAtEpochMillis,
            ),
        priorityReasons = candidate.reasonCodes.toPriorityReasons(),
    )
}

private fun LocalMasteryContextItem.toReviewSignal(
    exactNode: KnowledgeNodeRef,
): ReviewKnowledgeSignal =
    ReviewKnowledgeSignal(
        knowledgeNode = exactNode,
        recallState =
            when (currentRecallState) {
                KnowledgeMasteryState.NEEDS_REINFORCEMENT ->
                    ReviewRecallState.NEEDS_REINFORCEMENT

                KnowledgeMasteryState.FAMILIARIZING -> ReviewRecallState.FAMILIARIZING
                KnowledgeMasteryState.STEADY -> ReviewRecallState.STEADY
            },
        trend =
            when (trend) {
                KnowledgeMasteryTrend.IMPROVING -> ReviewKnowledgeTrend.IMPROVING
                KnowledgeMasteryTrend.STABLE -> ReviewKnowledgeTrend.STABLE
                KnowledgeMasteryTrend.WAVERING -> ReviewKnowledgeTrend.WAVERING
            },
        recallDueAtEpochMillis = null,
    )

private fun Set<String>.toPriorityReasons(): Set<ReviewPriorityReason> =
    mapNotNullTo(linkedSetOf()) { reason ->
        when (val normalized = reason.toReviewReasonKey()) {
            ReviewPriorityReason.NEWLY_SAVED.name -> ReviewPriorityReason.NEWLY_SAVED
            ReviewPriorityReason.DUE_RECALL.name -> ReviewPriorityReason.DUE_RECALL
            ReviewPriorityReason.RECENT_ERROR.name -> ReviewPriorityReason.RECENT_ERROR
            ReviewPriorityReason.ANSWER_REVEALED.name -> ReviewPriorityReason.ANSWER_REVEALED
            ReviewPriorityReason.REPEATED_ERROR.name -> ReviewPriorityReason.REPEATED_ERROR
            ReviewPriorityReason.MASTERY_CONFLICT.name -> ReviewPriorityReason.MASTERY_CONFLICT
            ReviewPriorityReason.LONG_WAITING.name -> ReviewPriorityReason.LONG_WAITING
            else ->
                when {
                    "ANSWER" in normalized || "REVEAL" in normalized ->
                        ReviewPriorityReason.ANSWER_REVEALED

                    "DUE" in normalized -> ReviewPriorityReason.DUE_RECALL
                    "RECENT" in normalized -> ReviewPriorityReason.RECENT_ERROR
                    "REPEAT" in normalized -> ReviewPriorityReason.REPEATED_ERROR
                    "MASTERY" in normalized ||
                        "WEAK" in normalized ||
                        "STUCK" in normalized ->
                        ReviewPriorityReason.MASTERY_CONFLICT

                    "WAIT" in normalized -> ReviewPriorityReason.LONG_WAITING
                    "NEW" in normalized || "SAV" in normalized ->
                        ReviewPriorityReason.NEWLY_SAVED

                    else -> null
                }
        }
    }.ifEmpty { setOf(ReviewPriorityReason.NEWLY_SAVED) }

private fun String.toReviewReasonKey(): String =
    trim()
        .uppercase(Locale.ROOT)
        .map { character ->
            if (character.isLetterOrDigit()) character else '_'
        }.joinToString(separator = "")

private fun ThreeAuthorityReviewPlan.toStoreCommand(
    sourceCandidates: List<StudentReviewCandidateWithKnowledge>,
): StoreStudentReviewQueueCommand {
    val sourceByCandidateId =
        sourceCandidates.associateBy { item ->
            item.candidate.candidateId
        }
    return StoreStudentReviewQueueCommand(
        planId = planId,
        planCanonicalFingerprint = canonicalFingerprint,
        learnerId = learnerId,
        localDayEpochDay = localDayEpochDay,
        timeZoneId = timeZoneId,
        timeBudgetSeconds = timeBudgetSeconds,
        generatedAtEpochMillis = generatedAtEpochMillis,
        plannerVersion = plannerVersion,
        items =
            items.map { planned ->
                val source =
                    checkNotNull(sourceByCandidateId[planned.candidateId]) {
                        "Review planner returned a candidate outside the saved-candidate snapshot"
                    }.candidate
                check(source.problemRevision == planned.problemRevision) {
                    "Review planner changed the saved problem revision"
                }
                StudentReviewQueueItem(
                    queueItemId = "$planId-item-${planned.scheduledOrder}",
                    problemRevision = source.problemRevision,
                    scheduledOrder = planned.scheduledOrder,
                    estimatedDurationSeconds = planned.estimatedDurationSeconds,
                    reasonCodes = source.reasonCodes,
                    sourceEvidence = source.sourceEvidence,
                )
            },
    )
}

private data class StableKnowledgeIdentity(
    val subject: SubjectKind,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
)

private fun String.requireBoundedReviewValue(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= 256 &&
            none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most 256 characters"
    }
}
