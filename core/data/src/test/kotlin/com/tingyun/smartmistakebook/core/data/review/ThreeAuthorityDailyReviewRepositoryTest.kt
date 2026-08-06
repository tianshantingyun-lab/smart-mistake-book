package com.tingyun.smartmistakebook.core.data.review

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.domain.ReviewPacingLevel
import com.tingyun.smartmistakebook.core.domain.ReviewPriorityReason
import com.tingyun.smartmistakebook.core.domain.ReviewExamTarget
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlan
import com.tingyun.smartmistakebook.core.domain.ThreeAuthorityReviewPlanItem
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayBatch
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayLookup
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogNodeDisplayMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSnapshotMetadata
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContext
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextItem
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextRequest
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextSelection
import com.tingyun.smartmistakebook.core.mastery.database.MasteryEvidenceQuality
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.RemoveUnreadyStudentReviewQueueItemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewHomeAuthoritySnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewPlanSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueMaintenanceResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueRemovalReason
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewQueueState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeAuthorityDailyReviewRepositoryTest {
    @Test
    fun plansFromSavedQuestionsWithDefaultFifteenMinutesAndJoinsThreeStoresInMemory() =
        runBlocking {
            val node = knowledge("quadratic-function")
            val parent = knowledge("functions")
            val revision = revision()
            val source =
                FakeStudentReadPort(
                    problem =
                        SavedDailyReviewProblem(
                            revision = revision,
                            title = "  二次函数  ",
                            stemMarkdown = "求函数的最值。",
                            knowledgeNodes = listOf(node),
                        ),
                )
            val planner = FakeDailyPlanner(source, revision)
            val displays =
                mapOf(
                    node to metadata(node, "二次函数", parent),
                    parent to metadata(parent, "函数"),
                )
            val knowledgeReads = mutableListOf<List<KnowledgeNodeRef>>()
            val masteryRequests = mutableListOf<LocalMasteryContextRequest>()
            val capability =
                DailyReviewRepositoryFactory.assemble(
                    student = source,
                    planner = planner,
                    mastery =
                        masteryReader(
                            node = node,
                            requests = masteryRequests,
                        ),
                    knowledgeDisplay =
                        knowledgeReader(
                            metadata = displays,
                            requests = knowledgeReads,
                        ),
                )

            val state =
                capability.repository.readHome(
                    ReviewHomeRequest(
                        localDayEpochDay = DAY,
                        timeZoneId = ZONE,
                        requestedAtEpochMillis = NOW,
                    ),
                ) as ReviewHomeState.Ready

            assertEquals(
                LearnerBoundDailyReviewPlanPort.DEFAULT_TIME_BUDGET_SECONDS,
                planner.requestedBudgets.single(),
            )
            assertEquals(5, planner.requestedItemCaps.single())
            assertEquals(1, state.plan.scheduledItemCount)
            assertEquals(ITEM_SECONDS, state.plan.remainingEstimatedSeconds)
            assertEquals("二次函数", state.nextProblem?.title)
            assertEquals("二次函数", state.nextProblem?.knowledgePoints?.single()?.displayName)
            assertEquals("函数", state.nextProblem?.knowledgePoints?.single()?.parentDisplayName)
            assertEquals(
                KnowledgeMasteryState.FAMILIARIZING,
                state.nextProblem
                    ?.knowledgePoints
                    ?.single()
                    ?.mastery
                    ?.currentRecallState,
            )
            assertEquals(listOf(listOf(node), listOf(parent)), knowledgeReads)
            assertEquals(1, masteryRequests.size)
            assertEquals(0, masteryRequests.single().fallbackLimit)
            assertEquals(
                setOf(ProductionAdapter.REVIEW_PLANNING),
                capability.availableProductionAdapters,
            )
        }

    @Test
    fun reusesPersistedPlanWithoutReplanningOrInventingAStreak() = runBlocking {
        val node = knowledge("momentum", SubjectKind.PHYSICS)
        val revision = revision(subject = SubjectKind.PHYSICS)
        val source =
            FakeStudentReadPort(
                plan = storedPlan(revision = revision, subjectNode = node),
                problem =
                    SavedDailyReviewProblem(
                        revision = revision,
                        title = null,
                        stemMarkdown = "判断动量变化。",
                        knowledgeNodes = listOf(node),
                    ),
            )
        val planner = FakeDailyPlanner(source, revision)
        val state =
            repository(
                source = source,
                planner = planner,
                displays = mapOf(node to metadata(node, "动量")),
                mastery = masteryReader(node = node),
            ).readHome(request()) as ReviewHomeState.Ready

        assertTrue(planner.requestedBudgets.isEmpty())
        assertEquals(0, state.plan.completedItemCount)
        assertEquals(0, state.plan.skippedItemCount)
        assertNull(state.session)
        assertEquals("判断动量变化。", state.nextProblem?.stemPreview)
    }

    @Test
    fun changedPacingAndExamPreferencesDoNotRewriteAlreadyPersistedTodayPlan() = runBlocking {
        val node = knowledge("quadratic-function")
        val revision = revision()
        val source =
            FakeStudentReadPort(
                plan = storedPlan(revision = revision, subjectNode = node),
                problem =
                    SavedDailyReviewProblem(
                        revision = revision,
                        title = "二次函数",
                        stemMarkdown = "求函数的最值。",
                        knowledgeNodes = listOf(node),
                    ),
            )
        val planner = FakeDailyPlanner(source, revision)
        val state =
            repository(
                source = source,
                planner = planner,
                displays = mapOf(node to metadata(node, "二次函数")),
                mastery = masteryReader(node = node),
            ).readHome(
                request(
                    pacingLevel = ReviewPacingLevel.STRONG,
                    examTarget =
                        ReviewExamTarget(
                            subject = SubjectKind.MATH,
                            examAtEpochMillis = NOW + 3L * DAY_MILLIS,
                        ),
                ),
            ) as ReviewHomeState.Ready

        assertTrue(planner.requestedBudgets.isEmpty())
        assertTrue(planner.requestedItemCaps.isEmpty())
        assertTrue(planner.requestedExamTargets.isEmpty())
        assertEquals(
            LearnerBoundDailyReviewPlanPort.DEFAULT_TIME_BUDGET_SECONDS,
            state.plan.timeBudgetSeconds,
        )
        assertEquals(1, state.plan.scheduledItemCount)
    }

    @Test
    fun removesIneligibleReadyItemBeforeReadingOtherAuthorities() =
        runBlocking {
            val revision = revision()
            val source =
                FakeStudentReadPort(
                    plan = storedPlan(revision = revision),
                    problem = null,
                )
            var masteryRead = false
            var knowledgeRead = false
            val state =
                DailyReviewRepositoryFactory.assemble(
                    student = source,
                    planner = FakeDailyPlanner(source, revision),
                    mastery =
                        localMasteryReader {
                            masteryRead = true
                            LocalMasteryContext(SubjectKind.MATH, emptyList())
                        },
                    knowledgeDisplay =
                        DailyReviewKnowledgeDisplayReader {
                            knowledgeRead = true
                            error("Must not read knowledge for a missing saved problem")
                        },
                ).repository.readHome(request())

            val ready = state as ReviewHomeState.Ready
            assertNull(ready.nextProblem)
            assertEquals(1, ready.plan.skippedItemCount)
            assertEquals(0, ready.plan.remainingItemCount)
            assertEquals(
                StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE,
                source.maintenanceCommands.single().reason,
            )
            assertFalse(masteryRead)
            assertFalse(knowledgeRead)
        }

    @Test
    fun removesConsecutiveUnreadyItemsThenShowsTheNextExactSavedProblem() = runBlocking {
        val pendingRevision = revision(idSuffix = "pending")
        val missingRevision = revision(idSuffix = "missing")
        val readyRevision = revision(idSuffix = "ready")
        val node = knowledge("linear-function")
        val basePlan = storedPlan(revision = readyRevision, subjectNode = node)
        val readyTemplate = basePlan.items.single()
        val source =
            FakeStudentReadPort(
                plan =
                    basePlan.copy(
                        items =
                            listOf(
                                readyTemplate.copy(
                                    queueItemId = "queue-pending",
                                    problemRevision = pendingRevision,
                                    scheduledOrder = 0,
                                ),
                                readyTemplate.copy(
                                    queueItemId = "queue-missing",
                                    problemRevision = missingRevision,
                                    scheduledOrder = 1,
                                ),
                                readyTemplate.copy(
                                    queueItemId = "queue-ready",
                                    problemRevision = readyRevision,
                                    scheduledOrder = 2,
                                ),
                            ),
                    ),
                problem =
                    SavedDailyReviewProblem(
                        revision = pendingRevision,
                        title = "待归类",
                        stemMarkdown = "待归类题目",
                        knowledgeNodes = emptyList(),
                    ),
                additionalProblems =
                    listOf(
                        SavedDailyReviewProblem(
                            revision = readyRevision,
                            title = "一次函数",
                            stemMarkdown = "求一次函数解析式。",
                            knowledgeNodes = listOf(node),
                        ),
                    ),
            )

        val state =
            repository(
                source = source,
                planner = FakeDailyPlanner(source, readyRevision),
                displays = mapOf(node to metadata(node, "一次函数")),
                mastery = masteryReader(node),
            ).readHome(request()) as ReviewHomeState.Ready

        assertEquals(
            listOf(
                StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION,
                StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE,
            ),
            source.maintenanceCommands.map { it.reason },
        )
        assertEquals(3, state.plan.scheduledItemCount)
        assertEquals(2, state.plan.skippedItemCount)
        assertEquals(1, state.plan.remainingItemCount)
        assertEquals("queue-ready", state.nextProblem?.queueItemId)
        assertEquals(readyRevision, state.nextProblem?.problemRevision)
    }

    @Test
    fun maintenanceReloadSeesConcurrentSessionStartWithoutFalseConflict() = runBlocking {
        val revision = revision(idSuffix = "interleaving")
        val node = knowledge("linear-equations")
        val source =
            FakeStudentReadPort(
                plan = storedPlan(revision = revision, subjectNode = node),
                problem =
                    SavedDailyReviewProblem(
                        revision = revision,
                        title = "方程",
                        stemMarkdown = "解方程。",
                        knowledgeNodes = emptyList(),
                    ),
            )
        source.maintenanceOverride = {
            val currentPlan = checkNotNull(source.plan)
            val presented = currentPlan.items.single().copy(
                state = StudentReviewQueueState.PRESENTED,
            )
            source.plan = currentPlan.copy(items = listOf(presented))
            source.problem = checkNotNull(source.problem).copy(knowledgeNodes = listOf(node))
            source.activeSession = activeSession(currentPlan, presented)
            source.changeVersion += 1L
            StudentReviewQueueMaintenanceResult.ReloadRequired
        }

        val state =
            repository(
                source = source,
                planner = FakeDailyPlanner(source, revision),
                displays = mapOf(node to metadata(node, "一元一次方程")),
                mastery = masteryReader(node),
            ).readHome(request()) as ReviewHomeState.Ready

        assertEquals("review-session-interleaving", state.session?.sessionId)
        assertEquals("queue-1", state.nextProblem?.queueItemId)
        assertEquals(StudentReviewQueueState.PRESENTED, source.plan?.items?.single()?.state)
    }

    @Test
    fun maintenanceRereadIoFailureIsPlanUnavailable() = runBlocking {
        val revision = revision(idSuffix = "reread-failure")
        val source =
            FakeStudentReadPort(
                plan = storedPlan(revision = revision),
                problem = null,
            )
        source.failSnapshotReadsAfterMaintenance = true

        val state =
            repository(
                source = source,
                planner = FakeDailyPlanner(source, revision),
                displays = emptyMap(),
                mastery =
                    localMasteryReader { request ->
                        LocalMasteryContext(request.subject, emptyList())
                    },
            ).readHome(request()) as ReviewHomeState.Unavailable

        assertEquals(ReviewHomeUnavailableReason.PLAN_UNAVAILABLE, state.reason)
    }

    @Test
    fun failsClosedWhenActiveKnowledgePackCannotNameAnExactReference() = runBlocking {
        val node = knowledge("chemical-equilibrium", SubjectKind.CHEMISTRY)
        val revision = revision(subject = SubjectKind.CHEMISTRY)
        val source =
            FakeStudentReadPort(
                plan = storedPlan(revision = revision, subjectNode = node),
                problem =
                    SavedDailyReviewProblem(
                        revision = revision,
                        title = "化学平衡",
                        stemMarkdown = "判断平衡移动方向。",
                        knowledgeNodes = listOf(node),
                    ),
            )
        val state =
            repository(
                source = source,
                planner = FakeDailyPlanner(source, revision),
                displays = emptyMap(),
                mastery = masteryReader(node = node),
            ).readHome(request())

        assertEquals(
            ReviewHomeState.Unavailable(
                ReviewHomeUnavailableReason.KNOWLEDGE_DISPLAY_UNAVAILABLE,
            ),
            state,
        )
    }

    @Test
    fun absenceOfMasteryHistoryDoesNotBecomeAnInventedMasteryState() = runBlocking {
        val node = knowledge("probability")
        val revision = revision()
        val source =
            FakeStudentReadPort(
                plan = storedPlan(revision = revision, subjectNode = node),
                problem =
                    SavedDailyReviewProblem(
                        revision = revision,
                        title = "概率",
                        stemMarkdown = "计算事件概率。",
                        knowledgeNodes = listOf(node),
                    ),
            )
        val state =
            repository(
                source = source,
                planner = FakeDailyPlanner(source, revision),
                displays = mapOf(node to metadata(node, "概率")),
                mastery =
                    localMasteryReader { request ->
                        LocalMasteryContext(request.subject, emptyList())
                    },
            ).readHome(request()) as ReviewHomeState.Ready

        assertNull(state.nextProblem?.knowledgePoints?.single()?.mastery)
    }

    private fun repository(
        source: FakeStudentReadPort,
        planner: LearnerBoundDailyReviewPlanPort,
        displays: Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>,
        mastery: LocalMasteryContextReader,
    ): DailyReviewRepository =
        DailyReviewRepositoryFactory.assemble(
            student = source,
            planner = planner,
            mastery = mastery,
            knowledgeDisplay = knowledgeReader(displays),
        ).repository

    private fun request(
        pacingLevel: ReviewPacingLevel = ReviewPacingLevel.STANDARD,
        examTarget: ReviewExamTarget? = null,
    ): ReviewHomeRequest =
        ReviewHomeRequest(
            localDayEpochDay = DAY,
            timeZoneId = ZONE,
            requestedAtEpochMillis = NOW,
            pacingLevel = pacingLevel,
            examTarget = examTarget,
        )

    private class FakeStudentReadPort(
        var plan: StudentReviewPlanSnapshot? = null,
        var problem: SavedDailyReviewProblem?,
        private val additionalProblems: List<SavedDailyReviewProblem> = emptyList(),
        var activeSession: StudentReviewSessionSnapshot? = null,
    ) : DailyReviewStudentReadPort {
        val maintenanceCommands = mutableListOf<RemoveUnreadyStudentReviewQueueItemCommand>()
        var changeVersion: Long = 0L
        var snapshotReadFailure: Exception? = null
        var beforeSnapshotRead: (() -> Unit)? = null
        var failSnapshotReadsAfterMaintenance: Boolean = false
        var maintenanceOverride:
            ((RemoveUnreadyStudentReviewQueueItemCommand) -> StudentReviewQueueMaintenanceResult)? =
            null

        override suspend fun readHomeSnapshot(
            localDayEpochDay: Long,
        ): StudentReviewHomeAuthoritySnapshot {
            snapshotReadFailure?.let { throw it }
            beforeSnapshotRead?.let { hook ->
                beforeSnapshotRead = null
                hook()
            }
            return StudentReviewHomeAuthoritySnapshot(
                changeVersion = changeVersion,
                plan = plan,
                activeSession = activeSession,
            )
        }

        override suspend fun readSavedProblem(
            revision: StudentProblemRevisionRef,
        ): SavedDailyReviewProblem? =
            (listOfNotNull(problem) + additionalProblems)
                .singleOrNull { it.revision == revision }

        override suspend fun removeUnreadyQueueItem(
            command: RemoveUnreadyStudentReviewQueueItemCommand,
        ): StudentReviewQueueMaintenanceResult {
            maintenanceCommands += command
            maintenanceOverride?.let { override -> return override(command) }
            val currentPlan = plan ?: return StudentReviewQueueMaintenanceResult.ReloadRequired
            val item =
                currentPlan.items.singleOrNull { it.queueItemId == command.queueItemId }
                    ?: return StudentReviewQueueMaintenanceResult.ReloadRequired
            if (
                currentPlan.planId != command.planId ||
                currentPlan.planCanonicalFingerprint !=
                command.expectedPlanCanonicalFingerprint ||
                item.state != StudentReviewQueueState.READY ||
                item.problemRevision != command.expectedProblemRevision
            ) {
                return StudentReviewQueueMaintenanceResult.ReloadRequired
            }
            val saved = readSavedProblem(item.problemRevision)
            val reasonIsCurrent =
                when (command.reason) {
                    StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE -> saved == null
                    StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION ->
                        saved?.knowledgeNodes?.isEmpty() == true
                }
            if (!reasonIsCurrent) return StudentReviewQueueMaintenanceResult.ReloadRequired
            plan =
                currentPlan.copy(
                    items =
                        currentPlan.items.map { queued ->
                            if (queued.queueItemId == item.queueItemId) {
                                queued.copy(
                                    reasonCodes = setOf("maintenance-audit"),
                                    state = StudentReviewQueueState.REMOVED,
                                )
                            } else {
                                queued
                            }
                        },
                )
            changeVersion += 1L
            if (failSnapshotReadsAfterMaintenance) {
                snapshotReadFailure =
                    IllegalStateException("Injected student authority reread failure")
            }
            return StudentReviewQueueMaintenanceResult.Applied
        }
    }

    private class FakeDailyPlanner(
        private val source: FakeStudentReadPort,
        private val revision: StudentProblemRevisionRef,
    ) : LearnerBoundDailyReviewPlanPort {
        val requestedBudgets = mutableListOf<Int>()
        val requestedItemCaps = mutableListOf<Int>()
        val requestedExamTargets = mutableListOf<ReviewExamTarget?>()

        override suspend fun planDailyReview(
            localDayEpochDay: Long,
            timeZoneId: String,
            planningAtEpochMillis: Long,
            timeBudgetSeconds: Int,
            maxItemCount: Int,
            examTarget: ReviewExamTarget?,
        ): DailyReviewPlanningResult {
            requestedBudgets += timeBudgetSeconds
            requestedItemCaps += maxItemCount
            requestedExamTargets += examTarget
            val plan =
                ThreeAuthorityReviewPlan(
                    planId = PLAN_ID,
                    canonicalFingerprint = PLAN_FINGERPRINT,
                    learnerId = LEARNER,
                    localDayEpochDay = localDayEpochDay,
                    timeZoneId = timeZoneId,
                    timeBudgetSeconds = timeBudgetSeconds,
                    maxItemCount = maxItemCount,
                    generatedAtEpochMillis = planningAtEpochMillis,
                    plannerVersion = "review-planner-v1",
                    masteryProjectionVersion = "mastery-v1",
                    items =
                        listOf(
                            ThreeAuthorityReviewPlanItem(
                                candidateId = "candidate-1",
                                problemRevision = revision,
                                scheduledOrder = 0,
                                estimatedDurationSeconds = ITEM_SECONDS,
                                priorityReasons =
                                    setOf(ReviewPriorityReason.NEWLY_SAVED),
                            ),
                        ),
                )
            source.plan =
                storedPlan(
                    revision = revision,
                    timeBudgetSeconds = timeBudgetSeconds,
                )
            return DailyReviewPlanningResult(
                plan = plan,
                candidateProfile =
                    ReviewCandidateProfileSummary(
                        readyCount = 1,
                        pendingKnowledgeAttributionCount = 0,
                        exactProblemIdentityOnlyCount = 1,
                    ),
            )
        }
    }

    private companion object {
        const val LEARNER = "local-learner"
        const val DAY = 20_000L
        const val ZONE = "Asia/Shanghai"
        const val NOW = 10_000L
        const val DAY_MILLIS = 86_400_000L
        const val ITEM_SECONDS = 180
        const val PLAN_ID = "review-plan"
        val PLAN_FINGERPRINT = "a".repeat(64)
        val DOCUMENT_FINGERPRINT = "b".repeat(64)
        val MANIFEST_FINGERPRINT = "c".repeat(64)
    }
}

private fun storedPlan(
    revision: StudentProblemRevisionRef,
    subjectNode: KnowledgeNodeRef? = null,
    timeBudgetSeconds: Int = LearnerBoundDailyReviewPlanPort.DEFAULT_TIME_BUDGET_SECONDS,
): StudentReviewPlanSnapshot {
    check(subjectNode == null || subjectNode.subject == revision.problem.subject)
    return StudentReviewPlanSnapshot(
        planId = "review-plan",
        planCanonicalFingerprint = "a".repeat(64),
        learnerId = "local-learner",
        localDayEpochDay = 20_000L,
        timeZoneId = "Asia/Shanghai",
        timeBudgetSeconds = timeBudgetSeconds,
        generatedAtEpochMillis = 10_000L,
        plannerVersion = "review-planner-v1",
        items =
            listOf(
                StudentReviewQueueItem(
                    queueItemId = "queue-1",
                    problemRevision = revision,
                    scheduledOrder = 0,
                    estimatedDurationSeconds = 180,
                    reasonCodes = setOf("NEWLY_SAVED"),
                    state = StudentReviewQueueState.READY,
                ),
            ),
    )
}

private fun activeSession(
    plan: StudentReviewPlanSnapshot,
    item: StudentReviewQueueItem,
): StudentReviewSessionSnapshot =
    StudentReviewSessionSnapshot(
        sessionId = "review-session-interleaving",
        planId = plan.planId,
        status = StudentReviewSessionStatus.ACTIVE,
        sessionVersion = 1L,
        currentItem = item,
        currentPresentationId = "presentation-interleaving",
        startedAtEpochMillis = plan.generatedAtEpochMillis,
        updatedAtEpochMillis = plan.generatedAtEpochMillis,
        completedAtEpochMillis = null,
    )

private fun revision(
    subject: SubjectKind = SubjectKind.MATH,
    idSuffix: String? = null,
): StudentProblemRevisionRef =
    StudentProblemRevisionRef(
        problem =
            StudentProblemRef(
                learnerId = "local-learner",
                subject = subject,
                problemId =
                    idSuffix?.let { "problem-$it" } ?: "problem-${subject.name.lowercase()}",
                practiceUnitId =
                    idSuffix?.let { "practice-$it" } ?: "practice-${subject.name.lowercase()}",
            ),
        revisionId = idSuffix?.let { "revision-$it" } ?: "revision-1",
        revisionNumber = 1,
        documentCanonicalFingerprint = "b".repeat(64),
    )

private fun knowledge(
    id: String,
    subject: SubjectKind = SubjectKind.MATH,
): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject = subject,
        knowledgeNodeId = id,
        taxonomyVersion = "taxonomy-v1",
        knowledgePackVersion = "pack-v1",
    )

private fun metadata(
    ref: KnowledgeNodeRef,
    name: String,
    parent: KnowledgeNodeRef? = null,
): KnowledgeCatalogNodeDisplayMetadata =
    KnowledgeCatalogNodeDisplayMetadata(
        ref = ref,
        displayName = name,
        kind = KnowledgeNodeKind.CONCEPT,
        granularity = KnowledgeNodeGranularity.ATOMIC,
        parentRef = parent,
    )

private fun knowledgeReader(
    metadata: Map<KnowledgeNodeRef, KnowledgeCatalogNodeDisplayMetadata>,
    requests: MutableList<List<KnowledgeNodeRef>> = mutableListOf(),
): DailyReviewKnowledgeDisplayReader =
    DailyReviewKnowledgeDisplayReader { refs ->
        requests += refs
        KnowledgeCatalogNodeDisplayBatch(
            snapshot =
                KnowledgeCatalogSnapshotMetadata(
                    knowledgePackVersion = "pack-v1",
                    taxonomyVersion = "taxonomy-v1",
                    manifestFingerprint = "c".repeat(64),
                    activationGeneration = 1L,
                ),
            lookups =
                refs.map { ref ->
                    KnowledgeCatalogNodeDisplayLookup(
                        requestedRef = ref,
                        metadata = metadata[ref],
                    )
                },
        )
    }

private fun masteryReader(
    node: KnowledgeNodeRef,
    requests: MutableList<LocalMasteryContextRequest> = mutableListOf(),
): LocalMasteryContextReader {
    val stable =
        LocalMasteryContextRequest.fromKnowledgeNodes(
            subject = node.subject,
            exactKnowledgeNodes = listOf(node),
            fallbackLimit = 0,
        ).exactStableNodeFingerprints.single()
    return localMasteryReader { request ->
        requests += request
        val items =
            if (stable in request.exactStableNodeFingerprints) {
                listOf(
                    LocalMasteryContextItem(
                        selection = LocalMasteryContextSelection.EXACT,
                        stableNodeIdentityFingerprint = stable,
                        knowledgeNode = node,
                        historicalState = KnowledgeMasteryState.FAMILIARIZING,
                        currentRecallState = KnowledgeMasteryState.FAMILIARIZING,
                        trend = KnowledgeMasteryTrend.STABLE,
                        evidenceQuality = MasteryEvidenceQuality.MEDIUM,
                    ),
                )
            } else {
                emptyList()
            }
        LocalMasteryContext(request.subject, items)
    }
}

private fun localMasteryReader(
    block: suspend (LocalMasteryContextRequest) -> LocalMasteryContext,
): LocalMasteryContextReader =
    object : LocalMasteryContextReader {
        override suspend fun queryContext(
            request: LocalMasteryContextRequest,
        ): LocalMasteryContext = block(request)
    }
