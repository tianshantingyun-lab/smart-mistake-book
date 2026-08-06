package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlan
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSnapshotMetadata
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContext
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextItem
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextRequest
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextSelection
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentReviewSessionPort
import com.tingyun.smartmistakebook.core.student.mistake.database.RemoveUnreadyStudentReviewQueueItemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeStore
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemClassificationDimension
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemClassificationStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemLifecycleState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewPlanSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewHomeAuthoritySnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueMaintenanceResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueRemovalReason
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionSnapshot
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SavedDailyReviewProblem(
    val revision: StudentProblemRevisionRef,
    val title: String?,
    val stemMarkdown: String,
    val knowledgeNodes: List<KnowledgeNodeRef>,
)

/**
 * Learner-bound student-mistakes.db read surface used by the review landing adapter.
 *
 * It exposes neither a store nor a learner selector. The other two databases never receive a
 * student problem document.
 */
internal interface DailyReviewStudentReadPort {
    suspend fun readHomeSnapshot(
        localDayEpochDay: Long,
    ): StudentReviewHomeAuthoritySnapshot

    suspend fun readSavedProblem(
        revision: StudentProblemRevisionRef,
    ): SavedDailyReviewProblem?

    suspend fun removeUnreadyQueueItem(
        command: RemoveUnreadyStudentReviewQueueItemCommand,
    ): StudentReviewQueueMaintenanceResult
}

internal class StudentMistakeDailyReviewReadPort(
    private val learnerId: String,
    private val store: StudentMistakeStore,
    private val sessions: LearnerBoundStudentReviewSessionPort,
) : DailyReviewStudentReadPort {
    override suspend fun readHomeSnapshot(
        localDayEpochDay: Long,
    ): StudentReviewHomeAuthoritySnapshot =
        sessions.readHomeSnapshot(localDayEpochDay).also { snapshot ->
            snapshot.plan?.let { plan ->
                check(plan.learnerId == learnerId) {
                    "Student mistake authority returned another learner's review plan"
                }
            }
        }

    override suspend fun readSavedProblem(
        revision: StudentProblemRevisionRef,
    ): SavedDailyReviewProblem? {
        if (revision.problem.learnerId != learnerId) return null
        val document = store.findProblem(revision.problem) ?: return null
        if (
            document.revision != revision ||
            document.lifecycleState != StudentProblemLifecycleState.ACTIVE ||
            document.mistakeState != StudentMistakeEntryState.ACTIVE
        ) {
            return null
        }
        val knowledgeNodes =
            store.readCurrentClassifications(revision)
                .asSequence()
                .filter { classification ->
                    classification.problemRevision == revision &&
                        classification.dimension ==
                        StudentProblemClassificationDimension.KNOWLEDGE &&
                        classification.status ==
                        StudentProblemClassificationStatus.ACCEPTED
                }
                .mapNotNull { classification -> classification.knowledgeNode }
                .filter { node -> node.subject == revision.problem.subject }
                .distinctBy(KnowledgeNodeRef::canonicalFingerprint)
                .sortedWith(KNOWLEDGE_NODE_ORDER)
                .toList()
        return SavedDailyReviewProblem(
            revision = document.revision,
            title = document.title?.trim(),
            stemMarkdown = document.stemMarkdown,
            knowledgeNodes = Collections.unmodifiableList(knowledgeNodes),
        )
    }

    override suspend fun removeUnreadyQueueItem(
        command: RemoveUnreadyStudentReviewQueueItemCommand,
    ): StudentReviewQueueMaintenanceResult =
        sessions.removeUnreadyQueueItem(command)
}

internal fun interface DailyReviewKnowledgeDisplayReader {
    suspend fun read(
        refs: List<KnowledgeNodeRef>,
    ): KnowledgeCatalogNodeDisplayBatch
}

/**
 * Review landing composition across the three independent databases.
 *
 * - student-mistakes.db owns the persisted plan and exact saved question;
 * - learner-mastery.db supplies a bounded categorical state;
 * - high-school-knowledge.db supplies reviewed display names and parent relations.
 *
 * The join exists only for the duration of [readHome] and is keyed by exact
 * [KnowledgeNodeRef] values. No cross-database transaction, table, foreign key, or cache is made.
 */
internal class ThreeAuthorityDailyReviewRepository(
    private val student: DailyReviewStudentReadPort,
    private val planner: LearnerBoundDailyReviewPlanPort,
    private val mastery: LocalMasteryContextReader,
    private val knowledgeDisplay: DailyReviewKnowledgeDisplayReader,
) : DailyReviewRepository {
    override suspend fun readHome(
        request: ReviewHomeRequest,
    ): ReviewHomeState {
        currentCoroutineContext().ensureActive()
        var authoritySnapshot =
            try {
                readOrCreateHomeSnapshot(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ReviewHomeFailure) {
                return ReviewHomeState.Unavailable(ReviewHomeUnavailableReason.PLAN_CONFLICT)
            } catch (_: Exception) {
                return ReviewHomeState.Unavailable(ReviewHomeUnavailableReason.PLAN_UNAVAILABLE)
            }
        var plan =
            authoritySnapshot.plan
                ?: return ReviewHomeState.Unavailable(
                    ReviewHomeUnavailableReason.PLAN_CONFLICT,
                )
        var session = authoritySnapshot.activeSession
        var nextItem =
            try {
                plan.resolveCurrentReviewItem(session)
            } catch (_: ActiveReviewSessionFailure) {
                return ReviewHomeState.Unavailable(
                    ReviewHomeUnavailableReason.ACTIVE_SESSION_CONFLICT,
                )
            }

        var savedProblem: SavedDailyReviewProblem? = null
        if (session == null) {
            var maintenanceAttempts = 0
            while (true) {
                val candidateItem = nextItem ?: break
                currentCoroutineContext().ensureActive()
                val candidateProblem =
                    try {
                        student.readSavedProblem(candidateItem.problemRevision)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return ReviewHomeState.Unavailable(
                            ReviewHomeUnavailableReason.SAVED_PROBLEM_UNAVAILABLE,
                        )
                    }
                val removalReason =
                    when {
                        candidateProblem == null ->
                            StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE
                        candidateProblem.knowledgeNodes.isEmpty() ->
                            StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION
                        else -> null
                    }
                if (removalReason == null) {
                    savedProblem = candidateProblem
                    break
                }
                if (maintenanceAttempts >= plan.items.size) {
                    return ReviewHomeState.Unavailable(
                        ReviewHomeUnavailableReason.PLAN_CONFLICT,
                    )
                }
                try {
                    student.removeUnreadyQueueItem(
                        request.toUnreadyQueueMaintenanceCommand(
                            plan = plan,
                            item = candidateItem,
                            reason = removalReason,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return ReviewHomeState.Unavailable(
                        ReviewHomeUnavailableReason.PLAN_UNAVAILABLE,
                    )
                }
                maintenanceAttempts += 1
                authoritySnapshot =
                    try {
                        readStudentHomeSnapshot(request.localDayEpochDay)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ReviewHomeAuthorityReadFailure) {
                        return ReviewHomeState.Unavailable(
                            ReviewHomeUnavailableReason.PLAN_UNAVAILABLE,
                        )
                    }
                val reloadedPlan =
                    authoritySnapshot.plan
                        ?: return ReviewHomeState.Unavailable(
                            ReviewHomeUnavailableReason.PLAN_CONFLICT,
                        )
                try {
                    reloadedPlan.requireSamePersistedPlanIdentity(plan)
                    reloadedPlan.requireSameDay(request)
                } catch (_: ReviewHomeFailure) {
                    return ReviewHomeState.Unavailable(
                        ReviewHomeUnavailableReason.PLAN_CONFLICT,
                    )
                }
                plan = reloadedPlan
                session = authoritySnapshot.activeSession
                nextItem =
                    try {
                        plan.resolveCurrentReviewItem(session)
                    } catch (_: ActiveReviewSessionFailure) {
                        return ReviewHomeState.Unavailable(
                            ReviewHomeUnavailableReason.ACTIVE_SESSION_CONFLICT,
                        )
                    }
                if (session != null) {
                    savedProblem = null
                    break
                }
                if (maintenanceAttempts >= plan.items.size && nextItem != null) {
                    return ReviewHomeState.Unavailable(
                        ReviewHomeUnavailableReason.PLAN_CONFLICT,
                    )
                }
            }
        }

        val summary = plan.toHomeSummary()
        val reviewItem =
            nextItem
                ?: return ReviewHomeState.Ready(
                    plan = summary,
                    session = session?.toReviewHomeSession(),
                    nextProblem = null,
                )

        val resolvedSavedProblem =
            savedProblem
                ?: try {
                    student.readSavedProblem(reviewItem.problemRevision)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                ?: return ReviewHomeState.Unavailable(
                    ReviewHomeUnavailableReason.SAVED_PROBLEM_UNAVAILABLE,
                )
        if (
            resolvedSavedProblem.revision != reviewItem.problemRevision ||
            resolvedSavedProblem.knowledgeNodes.isEmpty() ||
            resolvedSavedProblem.knowledgeNodes.size >
            ReviewHomeProblemPreview.MAX_REVIEW_HOME_KNOWLEDGE_POINTS
        ) {
            return ReviewHomeState.Unavailable(
                ReviewHomeUnavailableReason.KNOWLEDGE_CLASSIFICATION_UNAVAILABLE,
            )
        }

        val displayByRef =
            try {
                readReviewedDisplay(resolvedSavedProblem.knowledgeNodes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ReviewHomeState.Unavailable(
                    ReviewHomeUnavailableReason.KNOWLEDGE_DISPLAY_UNAVAILABLE,
                )
            }
        val masteryByRef =
            try {
                readMastery(resolvedSavedProblem.knowledgeNodes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ReviewHomeState.Unavailable(
                    ReviewHomeUnavailableReason.MASTERY_CONTEXT_UNAVAILABLE,
                )
            }

        val knowledgePoints =
            resolvedSavedProblem.knowledgeNodes.map { ref ->
                val display =
                    displayByRef[ref]
                        ?: return ReviewHomeState.Unavailable(
                            ReviewHomeUnavailableReason.KNOWLEDGE_DISPLAY_UNAVAILABLE,
                        )
                val parent = display.parentRef?.let(displayByRef::get)
                if (display.parentRef != null && parent == null) {
                    return ReviewHomeState.Unavailable(
                        ReviewHomeUnavailableReason.KNOWLEDGE_DISPLAY_UNAVAILABLE,
                    )
                }
                ReviewKnowledgePoint(
                    ref = ref,
                    displayName = display.displayName.trim(),
                    parentRef = display.parentRef,
                    parentDisplayName = parent?.displayName?.trim(),
                    mastery = masteryByRef[ref]?.toReviewKnowledgeMastery(),
                )
            }
        return ReviewHomeState.Ready(
            plan = summary,
            session = session?.toReviewHomeSession(),
            nextProblem =
                ReviewHomeProblemPreview(
                    queueItemId = reviewItem.queueItemId,
                    problemRevision = resolvedSavedProblem.revision,
                    title = resolvedSavedProblem.title?.trim(),
                    problemMarkdown = resolvedSavedProblem.stemMarkdown.trim(),
                    estimatedDurationSeconds = reviewItem.estimatedDurationSeconds,
                    knowledgePoints = Collections.unmodifiableList(knowledgePoints),
                ),
        )
    }

    private suspend fun readOrCreateHomeSnapshot(
        request: ReviewHomeRequest,
    ): StudentReviewHomeAuthoritySnapshot {
        val existing = readStudentHomeSnapshot(request.localDayEpochDay)
        val existingPlan = existing.plan
        if (existingPlan != null) {
            existingPlan.requireSameDay(request)
            return existing
        }

        val planned =
            planner.planDailyReview(
                localDayEpochDay = request.localDayEpochDay,
                timeZoneId = request.timeZoneId,
                planningAtEpochMillis = request.requestedAtEpochMillis,
                timeBudgetSeconds = request.timeBudgetSeconds,
                maxItemCount = request.pacingLevel.maxItemCount,
                examTarget = request.examTarget,
            )
        val persistedSnapshot = readStudentHomeSnapshot(request.localDayEpochDay)
        val persisted =
            persistedSnapshot.plan
                ?: throw ReviewHomeFailure("Daily review plan was not persisted")
        persisted.requireSamePlan(planned.plan)
        return persistedSnapshot
    }

    private suspend fun readStudentHomeSnapshot(
        localDayEpochDay: Long,
    ): StudentReviewHomeAuthoritySnapshot =
        try {
            student.readHomeSnapshot(localDayEpochDay)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ReviewHomeAuthorityReadFailure(
                message = "Student review-home authority read failed",
                cause = failure,
            )
        }

    private suspend fun readReviewedDisplay(
        exactRefs: List<KnowledgeNodeRef>,
    ): Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata> {
        val exact = readDisplayBatches(exactRefs)
        val parents =
            exact.values
                .mapNotNull(KnowledgeCatalogNodeDisplayMetadata::parentRef)
                .filterNot(exact::containsKey)
                .distinctBy(KnowledgeNodeRef::canonicalFingerprint)
                .sortedWith(KNOWLEDGE_NODE_ORDER)
        if (parents.isEmpty()) return exact
        return exact + readDisplayBatches(parents, exact.snapshot)
    }

    private suspend fun readDisplayBatches(
        refs: List<KnowledgeNodeRef>,
        requiredSnapshot: KnowledgeCatalogSnapshotMetadata? = null,
    ): DisplayMetadataMap {
        var snapshot = requiredSnapshot
        val result = LinkedHashMap<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>()
        refs.chunked(HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS).forEach { batchRefs ->
            currentCoroutineContext().ensureActive()
            val batch = knowledgeDisplay.read(batchRefs)
            check(batch.lookups.map { it.requestedRef } == batchRefs) {
                "Knowledge display authority changed the requested reference positions"
            }
            val acceptedSnapshot = snapshot
            if (acceptedSnapshot == null) {
                snapshot = batch.snapshot
            } else {
                check(batch.snapshot == acceptedSnapshot) {
                    "Knowledge display authority crossed activated catalog generations"
                }
            }
            batch.lookups.forEach { lookup ->
                val metadata =
                    lookup.metadata
                        ?: throw ReviewHomeFailure(
                            "Knowledge display authority did not resolve an exact reference",
                        )
                check(metadata.ref == lookup.requestedRef) {
                    "Knowledge display authority returned different node metadata"
                }
                result[lookup.requestedRef] = metadata
            }
        }
        return DisplayMetadataMap(
            snapshot =
                requireNotNull(snapshot) {
                    "Knowledge display snapshot is unavailable"
                },
            metadataByRef = result,
        )
    }

    private suspend fun readMastery(
        refs: List<KnowledgeNodeRef>,
    ): Map<KnowledgeNodeRef, LocalMasteryContextItem> {
        val result = LinkedHashMap<KnowledgeNodeRef, LocalMasteryContextItem>()
        refs.groupBy(KnowledgeNodeRef::subject).forEach { (subject, subjectRefs) ->
            subjectRefs.chunked(LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT)
                .forEach { batch ->
                    currentCoroutineContext().ensureActive()
                    val request =
                        LocalMasteryContextRequest.fromKnowledgeNodes(
                            subject = subject,
                            exactKnowledgeNodes = batch,
                            fallbackLimit = 0,
                        )
                    val context = mastery.queryContext(request)
                    context.requireExactSubject(subject)
                    val refByStableFingerprint =
                        batch.zip(request.exactStableNodeFingerprints).associate { (ref, stable) ->
                            stable to ref
                        }
                    context.items.forEach { item ->
                        if (item.selection != LocalMasteryContextSelection.EXACT) return@forEach
                        val requestedRef =
                            refByStableFingerprint[item.stableNodeIdentityFingerprint]
                                ?: throw ReviewHomeFailure(
                                    "Mastery authority returned an unrequested exact knowledge node",
                                )
                        if (item.knowledgeNode == requestedRef) {
                            result[requestedRef] = item
                        }
                    }
                }
        }
        return result
    }
}

/**
 * A production review-planning capability contributes only REVIEW_PLANNING.
 */
class DailyReviewProductionCapability internal constructor(
    val repository: DailyReviewRepository,
) {
    val availableProductionAdapters: Set<ProductionAdapter> =
        setOf(ProductionAdapter.REVIEW_PLANNING)
}

internal object DailyReviewRepositoryFactory {
    fun create(
        learnerId: String,
        studentMistakes: StudentMistakeStore,
        reviewSessions: LearnerBoundStudentReviewSessionPort,
        planner: LearnerBoundDailyReviewPlanPort,
        mastery: LocalMasteryContextReader,
        highSchoolKnowledge: HighSchoolKnowledgeCatalog,
    ): DailyReviewProductionCapability =
        DailyReviewProductionCapability(
            repository =
                ThreeAuthorityDailyReviewRepository(
                    student =
                        StudentMistakeDailyReviewReadPort(
                            learnerId = learnerId,
                            store = studentMistakes,
                            sessions = reviewSessions,
                        ),
                    planner = planner,
                    mastery = mastery,
                    knowledgeDisplay =
                        DailyReviewKnowledgeDisplayReader(highSchoolKnowledge::findNodes),
                ),
        )

    fun assemble(
        student: DailyReviewStudentReadPort,
        planner: LearnerBoundDailyReviewPlanPort,
        mastery: LocalMasteryContextReader,
        knowledgeDisplay: DailyReviewKnowledgeDisplayReader,
    ): DailyReviewProductionCapability =
        DailyReviewProductionCapability(
            ThreeAuthorityDailyReviewRepository(
                student = student,
                planner = planner,
                mastery = mastery,
                knowledgeDisplay = knowledgeDisplay,
            ),
        )
}

private data class DisplayMetadataMap(
    val snapshot: KnowledgeCatalogSnapshotMetadata,
    val metadataByRef: Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>,
) : Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata> by metadataByRef

private class ReviewHomeFailure(
    message: String,
) : IllegalStateException(message)

private class ReviewHomeAuthorityReadFailure(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

private class ActiveReviewSessionFailure(
    message: String,
) : IllegalStateException(message)

private fun StudentReviewPlanSnapshot.resolveCurrentReviewItem(
    session: StudentReviewSessionSnapshot?,
): StudentReviewQueueItem? {
    if (session == null) {
        if (items.any { item -> item.state == StudentReviewQueueState.PRESENTED }) {
            throw ActiveReviewSessionFailure(
                "Review plan exposes a presented item without its active session",
            )
        }
        return items.firstOrNull { item -> item.state == StudentReviewQueueState.READY }
    }
    if (session.planId != planId) {
        throw ActiveReviewSessionFailure("Active review session belongs to another plan")
    }
    val activeItem =
        session.currentItem
            ?: throw ActiveReviewSessionFailure("Active review session has no current item")
    val fromPlan =
        items.singleOrNull { item -> item.queueItemId == activeItem.queueItemId }
            ?: throw ActiveReviewSessionFailure(
                "Active review item is missing from its persisted plan",
            )
    if (fromPlan != activeItem) {
        throw ActiveReviewSessionFailure(
            "Active review item differs from its persisted plan snapshot",
        )
    }
    return activeItem
}

private fun StudentReviewPlanSnapshot.requireSameDay(
    request: ReviewHomeRequest,
) {
    if (
        localDayEpochDay != request.localDayEpochDay ||
        timeZoneId != request.timeZoneId
    ) {
        throw ReviewHomeFailure("Persisted daily review plan belongs to another local day")
    }
}

private fun StudentReviewPlanSnapshot.requireSamePlan(
    planned: ThreeAuthorityReviewPlan,
) {
    if (
        planId != planned.planId ||
        planCanonicalFingerprint != planned.canonicalFingerprint ||
        learnerId != planned.learnerId ||
        localDayEpochDay != planned.localDayEpochDay ||
        timeZoneId != planned.timeZoneId ||
        timeBudgetSeconds != planned.timeBudgetSeconds ||
        items.map(StudentReviewQueueItem::problemRevision) !=
        planned.items.map { item -> item.problemRevision }
    ) {
        throw ReviewHomeFailure("Persisted daily review plan does not match the planned result")
    }
}

private fun StudentReviewPlanSnapshot.requireSamePersistedPlanIdentity(
    expected: StudentReviewPlanSnapshot,
) {
    val itemIdentities =
        items.map { item ->
            listOf(
                item.queueItemId,
                item.problemRevision.canonicalFingerprint,
                item.scheduledOrder.toString(),
                item.estimatedDurationSeconds.toString(),
            )
        }
    val expectedItemIdentities =
        expected.items.map { item ->
            listOf(
                item.queueItemId,
                item.problemRevision.canonicalFingerprint,
                item.scheduledOrder.toString(),
                item.estimatedDurationSeconds.toString(),
            )
        }
    if (
        planId != expected.planId ||
        planCanonicalFingerprint != expected.planCanonicalFingerprint ||
        learnerId != expected.learnerId ||
        localDayEpochDay != expected.localDayEpochDay ||
        timeZoneId != expected.timeZoneId ||
        timeBudgetSeconds != expected.timeBudgetSeconds ||
        generatedAtEpochMillis != expected.generatedAtEpochMillis ||
        plannerVersion != expected.plannerVersion ||
        itemIdentities != expectedItemIdentities
    ) {
        throw ReviewHomeFailure("Review queue maintenance reloaded another persisted plan")
    }
}

private fun ReviewHomeRequest.toUnreadyQueueMaintenanceCommand(
    plan: StudentReviewPlanSnapshot,
    item: StudentReviewQueueItem,
    reason: StudentReviewQueueRemovalReason,
): RemoveUnreadyStudentReviewQueueItemCommand {
    val changedAtEpochMillis = maxOf(requestedAtEpochMillis, plan.generatedAtEpochMillis)
    val maintenanceFingerprint =
        CanonicalSha256("daily-review-unready-maintenance-v1")
            .field("planId", plan.planId)
            .field("planCanonicalFingerprint", plan.planCanonicalFingerprint)
            .field("queueItemId", item.queueItemId)
            .field("problemRevision", item.problemRevision.canonicalFingerprint)
            .field("reason", reason.name)
            .field("changedAtEpochMillis", changedAtEpochMillis)
            .finish()
    return RemoveUnreadyStudentReviewQueueItemCommand(
        maintenanceId = "review-unready-$maintenanceFingerprint",
        planId = plan.planId,
        expectedPlanCanonicalFingerprint = plan.planCanonicalFingerprint,
        queueItemId = item.queueItemId,
        expectedProblemRevision = item.problemRevision,
        reason = reason,
        changedAtEpochMillis = changedAtEpochMillis,
    )
}

private fun StudentReviewPlanSnapshot.toHomeSummary(): ReviewHomePlanSummary {
    val completed = items.count { item -> item.state == StudentReviewQueueState.COMPLETED }
    val skipped =
        items.count { item ->
            item.state == StudentReviewQueueState.SKIPPED ||
                item.state == StudentReviewQueueState.REMOVED
        }
    val remaining =
        items.filter { item ->
            item.state == StudentReviewQueueState.READY ||
                item.state == StudentReviewQueueState.PRESENTED
        }
    return ReviewHomePlanSummary(
        planId = planId,
        canonicalFingerprint = planCanonicalFingerprint,
        localDayEpochDay = localDayEpochDay,
        timeZoneId = timeZoneId,
        timeBudgetSeconds = timeBudgetSeconds,
        scheduledItemCount = items.size,
        completedItemCount = completed,
        skippedItemCount = skipped,
        remainingItemCount = remaining.size,
        remainingEstimatedSeconds =
            remaining.sumOf(StudentReviewQueueItem::estimatedDurationSeconds),
    )
}

private fun LocalMasteryContext.requireExactSubject(
    expectedSubject: com.tingyun.smartmistakebook.core.model.SubjectKind,
) {
    if (
        subject != expectedSubject ||
        items.any { item ->
            item.selection != LocalMasteryContextSelection.EXACT ||
                item.knowledgeNode.subject != expectedSubject
        }
    ) {
        throw ReviewHomeFailure("Mastery authority crossed the exact subject request")
    }
}

private fun LocalMasteryContextItem.toReviewKnowledgeMastery(): ReviewKnowledgeMastery =
    ReviewKnowledgeMastery(
        historicalState = historicalState,
        currentRecallState = currentRecallState,
        trend = trend,
    )

private val KNOWLEDGE_NODE_ORDER =
    compareBy<KnowledgeNodeRef>(
        { it.subject.name },
        KnowledgeNodeRef::knowledgeNodeId,
        KnowledgeNodeRef::taxonomyVersion,
        KnowledgeNodeRef::knowledgePackVersion,
        KnowledgeNodeRef::canonicalFingerprint,
    )
