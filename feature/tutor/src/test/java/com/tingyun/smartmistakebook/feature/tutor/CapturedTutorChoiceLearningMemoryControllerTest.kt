package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictException
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryOperation
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturedTutorChoiceLearningMemoryControllerTest {
    @Test
    fun exactLegacyPlanPreparesOnceAndReplaysTheSameDurableScope() = runTest {
        val fixture = Fixture()
        val first = fixture.controller.ensurePrepared(
            fixture.task,
            fixture.guidance,
            fixture.modeVersion,
        )
        val replay = fixture.controller.ensurePrepared(
            fixture.task,
            fixture.guidance,
            fixture.modeVersion,
        )

        assertTrue(first is CapturedTutorChoiceLearningMemoryState.Prepared)
        assertTrue(replay is CapturedTutorChoiceLearningMemoryState.Prepared)
        assertEquals(1, fixture.repository.allocateCommands.size)
        assertEquals(2, fixture.repository.prepareCommands.size)
        assertEquals(
            fixture.repository.prepareCommands.first().evidenceRequestId,
            fixture.repository.prepareCommands.last().evidenceRequestId,
        )
        with(fixture.repository.allocateCommands.single()) {
            assertEquals(1L, requestVersion)
            assertEquals(fixture.modeVersion, modeVersion)
            assertEquals(TutorExplanationMode.GUIDED, mode)
            assertEquals(SubjectKind.MATH, subject)
            assertTrue(turnReceiptId.startsWith("captured-choice-turn-v1:"))
            assertTrue(clientIdempotencyKey.startsWith("captured-choice-turn-key-v1:"))
        }
        assertEquals(
            fixture.task.request.requestId,
            fixture.repository.prepareCommands.first().evidenceRequestId,
        )
    }

    @Test
    fun nonExactPlansAndUnauthorizedGuidanceAreRejectedWithoutWrites() = runTest {
        val baseline = Fixture()
        val variants = listOf(
            baseline.task.copy(status = ModelTaskStatus.WAITING_FOR_MODEL),
            task(baseline.session, diagnosticItem = null),
            task(
                baseline.session,
                interactionDirective = TutorInteractionDirective.Continue,
            ),
        )
        variants.forEach { candidate ->
            val repository = RecordingMemoryRepository()
            val result = controller(repository, baseline.session).ensurePrepared(
                candidate,
                guidance(candidate, baseline.session),
                baseline.modeVersion,
            )
            assertTrue(result is CapturedTutorChoiceLearningMemoryState.Ineligible)
            assertTrue(repository.allWriteCount == 0)
        }

        val directRepository = RecordingMemoryRepository()
        val direct = controller(directRepository, baseline.session).ensurePrepared(
            baseline.task,
            baseline.guidance.copy(mode = TutorExplanationMode.DIRECT),
            baseline.modeVersion,
        )
        val staleRepository = RecordingMemoryRepository()
        val stale = controller(staleRepository, baseline.session).ensurePrepared(
            baseline.task,
            baseline.guidance.copy(pendingEvidenceRequestId = "request-stale"),
            baseline.modeVersion,
        )
        val generalSession = session(subject = SubjectKind.GENERAL.name)
        val generalTask = task(generalSession)
        val generalRepository = RecordingMemoryRepository()
        val general = controller(generalRepository, generalSession).ensurePrepared(
            generalTask,
            guidance(generalTask, generalSession),
            baseline.modeVersion,
        )

        assertEquals(
            CapturedTutorChoiceIneligibleReason.GUIDANCE_NOT_AUTHORIZED,
            (direct as CapturedTutorChoiceLearningMemoryState.Ineligible).reason,
        )
        assertEquals(
            CapturedTutorChoiceIneligibleReason.GUIDANCE_NOT_AUTHORIZED,
            (stale as CapturedTutorChoiceLearningMemoryState.Ineligible).reason,
        )
        assertEquals(
            CapturedTutorChoiceIneligibleReason.INVALID_SUBJECT,
            (general as CapturedTutorChoiceLearningMemoryState.Ineligible).reason,
        )
        assertEquals(0, directRepository.allWriteCount)
        assertEquals(0, staleRepository.allWriteCount)
        assertEquals(0, generalRepository.allWriteCount)
    }

    @Test
    fun fullQuestionAndSubjectMismatchesFailClosed() = runTest {
        val exactSession = session()
        val controller = controller(RecordingMemoryRepository(), exactSession)
        val changedQuestionSession = session(stem = "另一道题")
        val changedQuestionTask = task(changedQuestionSession)
        val changedSubjectSession = session(subject = SubjectKind.PHYSICS.name)
        val changedSubjectTask = task(changedSubjectSession)

        val questionMismatch = controller.ensurePrepared(
            changedQuestionTask,
            guidance(changedQuestionTask, exactSession),
            MODE_VERSION,
        )
        val subjectMismatch = controller.ensurePrepared(
            changedSubjectTask,
            guidance(changedSubjectTask, exactSession),
            MODE_VERSION,
        )

        assertEquals(
            CapturedTutorChoiceIneligibleReason.PLAN_SCOPE_MISMATCH,
            (questionMismatch as CapturedTutorChoiceLearningMemoryState.Ineligible).reason,
        )
        assertEquals(
            CapturedTutorChoiceIneligibleReason.PLAN_SCOPE_MISMATCH,
            (subjectMismatch as CapturedTutorChoiceLearningMemoryState.Ineligible).reason,
        )
    }

    @Test
    fun modelEvaluatedChoicesRemainDeterministicWeakFacts() = runTest {
        val correct = Fixture()
        correct.controller.ensurePrepared(correct.task, correct.guidance, correct.modeVersion)
        correct.now = 200
        val correctResult = correct.controller.submitPreparedChoice(
            correct.task,
            correct.guidance.copy(hintsUsed = 3, strugglesObserved = 2),
            response(
                correct.session,
                choiceId = "choice-correct",
                occurredAt = 150,
                evidenceRequestId = correct.task.request.requestId,
            ),
            correct.modeVersion,
        )

        val incorrect = Fixture(requestId = "plan-request-incorrect")
        incorrect.controller.ensurePrepared(
            incorrect.task,
            incorrect.guidance,
            incorrect.modeVersion,
        )
        incorrect.now = 200
        val incorrectResult = incorrect.controller.submitPreparedChoice(
            incorrect.task,
            incorrect.guidance,
            response(
                incorrect.session,
                choiceId = "choice-wrong",
                occurredAt = 150,
                evidenceRequestId = incorrect.task.request.requestId,
            ),
            incorrect.modeVersion,
        )

        assertTrue(correctResult is CapturedTutorChoiceLearningMemoryState.Submitted)
        assertTrue(incorrectResult is CapturedTutorChoiceLearningMemoryState.Submitted)
        assertEquals(
            LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE,
            correct.repository.sourceFacts.single().factKind,
        )
        assertEquals(
            LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
            incorrect.repository.sourceFacts.single().factKind,
        )
        assertEquals("先列出已知条件", correct.repository.sourceFacts.single().responseSummary)
        assertEquals("直接猜答案", incorrect.repository.sourceFacts.single().responseSummary)
        assertFalse(
            correct.repository.sourceFacts.single().responseSummary.contains("关键一步"),
        )
        assertFalse(
            correct.repository.sourceFacts.single().responseSummary.contains("判断正确"),
        )
    }

    @Test
    fun sourceSummaryIsTrimmedBoundedAndNeverCopiesFeedbackOrStem() = runTest {
        val longMarkdown = "  " + "甲".repeat(700) + "  "
        val session = session()
        val item = diagnosticItem(correctMarkdown = longMarkdown)
        val task = task(session, diagnosticItem = item)
        val repository = RecordingMemoryRepository()
        var now = 100L
        val controller = controller(repository, session) { now }
        val guidance = guidance(task, session)
        controller.ensurePrepared(task, guidance, MODE_VERSION)
        now = 200

        controller.submitPreparedChoice(
            task,
            guidance,
            response(
                session = session,
                choiceId = "choice-correct",
                occurredAt = 150,
                item = item,
                evidenceRequestId = task.request.requestId,
            ),
            MODE_VERSION,
        )

        val summary = repository.sourceFacts.single().responseSummary
        assertEquals(512, summary.length)
        assertEquals("甲".repeat(512), summary)
        assertFalse(summary.contains("关键一步"))
        assertFalse(summary.contains("判断正确"))
    }

    @Test
    fun persistedResponseWithoutBridgeTurnIsPreBridgeHistoryAndNeverBackfilled() = runTest {
        val fixture = Fixture()
        val response = response(
            fixture.session,
            occurredAt = 150,
            evidenceRequestId = null,
        )

        val ensure = fixture.controller.ensurePrepared(
            fixture.task,
            fixture.guidance,
            fixture.modeVersion,
            persistedResponse = response,
        )
        val submit = fixture.controller.submitPreparedChoice(
            fixture.task,
            fixture.guidance,
            response,
            fixture.modeVersion,
        )

        assertEquals(CapturedTutorChoiceLearningMemoryState.PreBridgeHistory, ensure)
        assertEquals(CapturedTutorChoiceLearningMemoryState.PreBridgeHistory, submit)
        assertEquals(0, fixture.repository.allWriteCount)
        assertTrue(fixture.repository.sourceFacts.isEmpty())
    }

    @Test
    fun pendingPreparationRecoversAndFinalizesThePersistedResponse() = runTest {
        val fixture = Fixture()
        assertTrue(
            fixture.controller.ensurePrepared(
                fixture.task,
                fixture.guidance,
                fixture.modeVersion,
            ) is
                CapturedTutorChoiceLearningMemoryState.Prepared,
        )
        fixture.now = 250

        val recovered = fixture.controller.submitPreparedChoice(
            fixture.task,
            fixture.guidance,
            response(
                fixture.session,
                occurredAt = 200,
                evidenceRequestId = fixture.task.request.requestId,
            ),
            fixture.modeVersion,
        )

        assertTrue(recovered is CapturedTutorChoiceLearningMemoryState.Submitted)
        assertEquals(1, fixture.repository.finalizeCommands.size)
        assertEquals(1, fixture.repository.sourceFacts.size)
    }

    @Test
    fun cancellationWinsAgainstLateSubmissionWithoutCreatingLearningFacts() = runTest {
        val fixture = Fixture()
        fixture.controller.ensurePrepared(fixture.task, fixture.guidance, fixture.modeVersion)
        fixture.now = 150
        val cancelled = fixture.controller.cancelPreparedChoice(
            fixture.task,
            fixture.task.request.requestId,
            fixture.modeVersion + 1,
            TutorLearningEvidenceCancellationReason.GUIDANCE_DISABLED,
        )
        fixture.now = 250
        val late = fixture.controller.submitPreparedChoice(
            fixture.task,
            fixture.guidance,
            response(
                fixture.session,
                occurredAt = 200,
                evidenceRequestId = fixture.task.request.requestId,
            ),
            fixture.modeVersion + 1,
        )

        assertTrue(cancelled is CapturedTutorChoiceLearningMemoryState.Cancelled)
        assertTrue(late is CapturedTutorChoiceLearningMemoryState.Cancelled)
        assertTrue(fixture.repository.sourceFacts.isEmpty())
        assertNull(fixture.repository.evidenceRequests.values.single().terminalSourceFactId)
        assertEquals(1, fixture.repository.finalizeCommands.size)
    }

    @Test
    fun canonicalIdentitiesChangeWithDirectiveDraftAndRevisionButReplayExactly() = runTest {
        val baseline = Fixture()
        baseline.controller.ensurePrepared(
            baseline.task,
            baseline.guidance,
            baseline.modeVersion,
        )
        val baselineTurn = baseline.repository.allocateCommands.single()
        val baselinePrepare = baseline.repository.prepareCommands.single()

        val changedDirective = Fixture(
            diagnosticItem = diagnosticItem(correctMarkdown = "先画关系图"),
        )
        changedDirective.controller.ensurePrepared(
            changedDirective.task,
            changedDirective.guidance,
            changedDirective.modeVersion,
        )
        val changedDraft = Fixture(session = session(draftId = "draft-2"))
        changedDraft.controller.ensurePrepared(
            changedDraft.task,
            changedDraft.guidance,
            changedDraft.modeVersion,
        )
        val changedRevision = Fixture(session = session(stem = "题面增加一个严格条件"))
        changedRevision.controller.ensurePrepared(
            changedRevision.task,
            changedRevision.guidance,
            changedRevision.modeVersion,
        )

        assertNotEquals(
            baselineTurn.directiveFingerprint,
            changedDirective.repository.allocateCommands.single().directiveFingerprint,
        )
        assertNotEquals(
            baselineTurn.turnReceiptId,
            changedDirective.repository.allocateCommands.single().turnReceiptId,
        )
        assertNotEquals(
            baselinePrepare.problemAnchorId,
            changedDraft.repository.prepareCommands.single().problemAnchorId,
        )
        assertNotEquals(
            baselinePrepare.problemAnchorId,
            changedRevision.repository.prepareCommands.single().problemAnchorId,
        )

        baseline.controller.ensurePrepared(
            baseline.task,
            baseline.guidance,
            baseline.modeVersion,
        )
        assertEquals(
            baselineTurn.turnReceiptId,
            baseline.repository.allocateCommands.single().turnReceiptId,
        )
        assertEquals(
            baselinePrepare.payloadFingerprint,
            baseline.repository.prepareCommands.last().payloadFingerprint,
        )
    }

    @Test
    fun postCommitRetryAdoptsSubmittedTerminalWithoutDuplicatingTheFact() = runTest {
        val fixture = Fixture()
        fixture.controller.ensurePrepared(fixture.task, fixture.guidance, fixture.modeVersion)
        fixture.now = 200
        val response = response(
            fixture.session,
            occurredAt = 150,
            evidenceRequestId = fixture.task.request.requestId,
        )

        val first = fixture.controller.submitPreparedChoice(
            fixture.task,
            fixture.guidance,
            response,
            fixture.modeVersion,
        )
        val replay = fixture.controller.submitPreparedChoice(
            fixture.task,
            fixture.guidance,
            response,
            fixture.modeVersion,
        )

        assertTrue(first is CapturedTutorChoiceLearningMemoryState.Submitted)
        assertTrue(replay is CapturedTutorChoiceLearningMemoryState.Submitted)
        assertEquals(1, fixture.repository.sourceFacts.size)
        assertEquals(1, fixture.repository.finalizeCommands.size)
    }

    @Test
    fun foreignConversationAndArchivedPendingRequestFailClosed() = runTest {
        val foreign = Fixture()
        foreign.repository.openConversationOverride = activeConversation(
            id = "foreign-conversation",
            learner = "another-learner",
        )
        val foreignResult = foreign.controller.ensurePrepared(
            foreign.task,
            foreign.guidance,
            foreign.modeVersion,
        )

        assertEquals(
            CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            (foreignResult as CapturedTutorChoiceLearningMemoryState.Ineligible).reason,
        )
        assertEquals(0, foreign.repository.allWriteCount)

        val archived = Fixture(requestId = "plan-request-archived")
        archived.controller.ensurePrepared(
            archived.task,
            archived.guidance,
            archived.modeVersion,
        )
        archived.repository.archiveAndCancelPending(occurredAt = 150)
        archived.now = 250
        val archivedSubmit = archived.controller.submitPreparedChoice(
            archived.task,
            archived.guidance,
            response(
                archived.session,
                occurredAt = 200,
                evidenceRequestId = archived.task.request.requestId,
            ),
            archived.modeVersion,
        )

        assertTrue(archivedSubmit is CapturedTutorChoiceLearningMemoryState.Cancelled)
        assertTrue(archived.repository.sourceFacts.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun coroutineCancellationIsNeverConvertedToRetryableFailure() = runTest {
        val fixture = Fixture()
        fixture.repository.failure = CancellationException("cancelled")
        fixture.controller.ensurePrepared(fixture.task, fixture.guidance, fixture.modeVersion)
    }

    @Test
    fun optimisticOrdinalConflictIsRetryableButScopeConflictIsPermanent() = runTest {
        val retryable = Fixture()
        retryable.repository.failure = TutorLearningMemoryConflictException(
            operation = TutorLearningMemoryOperation.ALLOCATE_TURN,
            reason = TutorLearningMemoryConflictReason.TURN_ORDINAL_MISMATCH,
        )
        val retryableResult = retryable.controller.ensurePrepared(
            retryable.task,
            retryable.guidance,
            retryable.modeVersion,
        )

        val permanent = Fixture(requestId = "plan-request-permanent-conflict")
        permanent.repository.failure = TutorLearningMemoryConflictException(
            operation = TutorLearningMemoryOperation.PREPARE_EVIDENCE,
            reason = TutorLearningMemoryConflictReason.EVIDENCE_SCOPE_MISMATCH,
        )
        val permanentResult = permanent.controller.ensurePrepared(
            permanent.task,
            permanent.guidance,
            permanent.modeVersion,
        )

        assertTrue(retryableResult is CapturedTutorChoiceLearningMemoryState.RetryableFailure)
        assertTrue(permanentResult is CapturedTutorChoiceLearningMemoryState.PermanentConflict)
    }

    private class Fixture(
        val session: ConfirmedTutorSession = session(),
        requestId: String = "plan-request-1",
        diagnosticItem: TutorAssessmentItem = diagnosticItem(),
    ) {
        val repository = RecordingMemoryRepository()
        var now = 100L
        val modeVersion = MODE_VERSION
        val controller = controller(repository, session) { now }
        val task = task(
            target = session,
            requestId = requestId,
            diagnosticItem = diagnosticItem,
        )
        val guidance = guidance(task, session)
    }
}

private class RecordingMemoryRepository : TutorLearningMemoryRepository {
    private val conversations = linkedMapOf<String, TutorConversation>()
    private val turnOwners = linkedMapOf<String, String>()
    private val turns = linkedMapOf<String, TutorTurnReceipt>()
    private val allocationByKey = linkedMapOf<String, Pair<TutorConversation, TutorTurnReceipt>>()
    private val creationByKey = linkedMapOf<String, TutorConversation>()

    val allocateCommands = mutableListOf<AllocateTutorTurnCommand>()
    val prepareCommands = mutableListOf<PrepareTutorEvidenceCommand>()
    val finalizeCommands = mutableListOf<FinalizeTutorEvidenceCommand>()
    val evidenceRequests = linkedMapOf<String, TutorEvidenceRequest>()
    val sourceFacts = mutableListOf<LearningObservationSourceFact>()
    var openConversationOverride: TutorConversation? = null
    var failure: Exception? = null

    val allWriteCount: Int
        get() = creationByKey.size + allocateCommands.size + prepareCommands.size +
            finalizeCommands.size

    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): CreateTutorConversationResult {
        failIfRequested()
        creationByKey[command.clientIdempotencyKey]?.let {
            return CreateTutorConversationResult.Replayed(it)
        }
        val conversation = TutorConversation(
            conversationId = command.conversationId,
            learnerScopeId = command.learnerScopeId,
            generation = command.conversationGeneration,
            status = TutorConversationStatus.ACTIVE,
            createdAtEpochMillis = command.occurredAtEpochMillis,
            archivedAtEpochMillis = null,
            stateVersion = 0,
        )
        conversations[conversation.conversationId] = conversation
        creationByKey[command.clientIdempotencyKey] = conversation
        return CreateTutorConversationResult.Created(conversation)
    }

    override suspend fun openConversation(
        command: OpenTutorConversationCommand,
    ): OpenTutorConversationResult {
        openConversationOverride?.let {
            return OpenTutorConversationResult.Opened(it)
        }
        return conversations[command.conversationId]
            ?.takeIf {
                it.learnerScopeId == command.learnerScopeId &&
                    it.generation == command.conversationGeneration
            }
            ?.let(OpenTutorConversationResult::Opened)
            ?: OpenTutorConversationResult.NotFound
    }

    override suspend fun latestActiveConversation(learnerScopeId: String): TutorConversation? {
        failIfRequested()
        return conversations.values
            .filter {
                it.learnerScopeId == learnerScopeId &&
                    it.status == TutorConversationStatus.ACTIVE
            }
            .maxWithOrNull(
                compareBy<TutorConversation>(TutorConversation::createdAtEpochMillis)
                    .thenBy(TutorConversation::conversationId),
            )
    }

    override suspend fun openTurn(
        learnerScopeId: String,
        turnReceiptId: String,
    ): OpenTutorTurnResult {
        failIfRequested()
        return turns[turnReceiptId]
            ?.takeIf { turnOwners[turnReceiptId] == learnerScopeId }
            ?.let(OpenTutorTurnResult::Found)
            ?: OpenTutorTurnResult.NotFound
    }

    override suspend fun openEvidenceRequest(
        learnerScopeId: String,
        evidenceRequestId: String,
    ): OpenTutorEvidenceResult {
        failIfRequested()
        return evidenceRequests[evidenceRequestId]
            ?.takeIf { request ->
                conversations[request.conversationId]?.learnerScopeId == learnerScopeId
            }
            ?.let(OpenTutorEvidenceResult::Found)
            ?: OpenTutorEvidenceResult.NotFound
    }

    override suspend fun archiveConversation(
        command: ArchiveTutorConversationCommand,
    ): ArchiveTutorConversationResult {
        val current = checkNotNull(conversations[command.conversationId])
        val archived = current.copy(
            status = TutorConversationStatus.ARCHIVED,
            archivedAtEpochMillis = command.occurredAtEpochMillis,
        )
        conversations[current.conversationId] = archived
        return ArchiveTutorConversationResult.Archived(archived)
    }

    override suspend fun allocateTurn(
        command: AllocateTutorTurnCommand,
    ): AllocateTutorTurnResult {
        failIfRequested()
        allocationByKey[command.clientIdempotencyKey]?.let { (conversation, receipt) ->
            return AllocateTutorTurnResult.Replayed(conversation, receipt)
        }
        allocateCommands += command
        val current = checkNotNull(conversations[command.conversationId])
        check(current.learnerScopeId == command.learnerScopeId)
        check(current.status == TutorConversationStatus.ACTIVE)
        check(current.stateVersion == command.expectedConversationStateVersion)
        check(command.expectedTurnOrdinal == current.stateVersion.toInt() + 1)
        val updated = current.copy(stateVersion = current.stateVersion + 1)
        val receipt = TutorTurnReceipt(
            turnReceiptId = command.turnReceiptId,
            conversationId = command.conversationId,
            conversationGeneration = command.conversationGeneration,
            conversationStateVersion = updated.stateVersion,
            turnOrdinal = command.expectedTurnOrdinal,
            subject = command.subject,
            problemAnchorId = command.problemAnchorId,
            requestVersion = command.requestVersion,
            modeVersion = command.modeVersion,
            explanationMode = command.mode,
            directiveFingerprint = command.directiveFingerprint,
            studentMessageFingerprint = command.studentMessageFingerprint,
            studentMessageSummary = command.studentMessageSummary,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        conversations[current.conversationId] = updated
        turns[receipt.turnReceiptId] = receipt
        turnOwners[receipt.turnReceiptId] = command.learnerScopeId
        allocationByKey[command.clientIdempotencyKey] = updated to receipt
        return AllocateTutorTurnResult.Created(updated, receipt)
    }

    override suspend fun prepareEvidenceRequest(
        command: PrepareTutorEvidenceCommand,
    ): PrepareTutorEvidenceResult {
        failIfRequested()
        prepareCommands += command
        evidenceRequests[command.evidenceRequestId]?.let {
            return PrepareTutorEvidenceResult.Replayed(it)
        }
        val request = TutorEvidenceRequest(
            evidenceRequestId = command.evidenceRequestId,
            conversationId = command.conversationId,
            conversationGeneration = command.conversationGeneration,
            conversationStateVersion = command.expectedConversationStateVersion,
            turnReceiptId = command.turnReceiptId,
            turnOrdinal = command.turnOrdinal,
            subject = command.subject,
            problemAnchorId = command.problemAnchorId,
            kind = command.kind,
            requestVersion = command.requestVersion,
            modeVersion = command.modeVersion,
            explanationMode = command.mode,
            directiveFingerprint = command.directiveFingerprint,
            status = TutorEvidenceRequestStatus.PENDING,
            stateVersion = 0,
            createdAtEpochMillis = command.occurredAtEpochMillis,
            resolvedAtEpochMillis = null,
            terminalSourceFactId = null,
        )
        evidenceRequests[request.evidenceRequestId] = request
        return PrepareTutorEvidenceResult.Created(request)
    }

    override suspend fun finalizeEvidence(
        command: FinalizeTutorEvidenceCommand,
    ): FinalizeTutorEvidenceResult {
        failIfRequested()
        val current = checkNotNull(evidenceRequests[command.evidenceRequestId])
        if (current.status.isTerminal) {
            return FinalizeTutorEvidenceResult.Replayed(
                current,
                sourceFacts.firstOrNull { it.sourceFactId == current.terminalSourceFactId },
            )
        }
        finalizeCommands += command
        check(current.stateVersion == command.expectedEvidenceStateVersion)
        return when (val terminal = command.terminal) {
            is TutorLearningEvidenceTerminal.Submitted -> {
                val settled = current.copy(
                    status = TutorEvidenceRequestStatus.SUBMITTED,
                    stateVersion = current.stateVersion + 1,
                    resolvedAtEpochMillis = command.occurredAtEpochMillis,
                    terminalSourceFactId = terminal.sourceFact.sourceFactId,
                )
                evidenceRequests[current.evidenceRequestId] = settled
                sourceFacts += terminal.sourceFact
                FinalizeTutorEvidenceResult.Submitted(settled, terminal.sourceFact)
            }

            is TutorLearningEvidenceTerminal.Cancelled -> {
                val settled = current.copy(
                    status = TutorEvidenceRequestStatus.CANCELLED,
                    stateVersion = current.stateVersion + 1,
                    resolvedAtEpochMillis = command.occurredAtEpochMillis,
                    terminalSourceFactId = null,
                )
                evidenceRequests[current.evidenceRequestId] = settled
                FinalizeTutorEvidenceResult.Cancelled(settled)
            }
        }
    }

    fun archiveAndCancelPending(occurredAt: Long) {
        conversations.replaceAll { _, conversation ->
            conversation.copy(
                status = TutorConversationStatus.ARCHIVED,
                archivedAtEpochMillis = occurredAt,
            )
        }
        evidenceRequests.replaceAll { _, request ->
            if (request.status == TutorEvidenceRequestStatus.PENDING) {
                request.copy(
                    status = TutorEvidenceRequestStatus.CANCELLED,
                    stateVersion = request.stateVersion + 1,
                    resolvedAtEpochMillis = occurredAt,
                )
            } else {
                request
            }
        }
    }

    private fun failIfRequested() {
        failure?.let { throw it }
    }
}

private fun controller(
    repository: RecordingMemoryRepository,
    session: ConfirmedTutorSession,
    clock: () -> Long = { 100 },
) = CapturedTutorChoiceLearningMemoryController(
    repository = repository,
    learnerScopeId = "learner-1",
    session = session,
    clock = clock,
)

private fun task(
    target: ConfirmedTutorSession,
    requestId: String = "plan-request-1",
    diagnosticItem: TutorAssessmentItem? = diagnosticItem(),
    interactionDirective: TutorInteractionDirective? = null,
): ModelTaskSnapshot {
    val input = TutorPlanInput(
        sessionId = target.sessionId,
        draftRevisionNumber = target.draftRevisionNumber,
        subject = target.subject,
        questionDocument = target.questionDocument.document,
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
    )
    val request = ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = 10,
    )
    val output = TutorPlanOutput(
        sessionId = target.sessionId,
        draftRevisionNumber = target.draftRevisionNumber,
        questionDocumentId = target.questionDocument.document.id,
        plan = TutorTurnPlan(
            openingMarkdown = "先看当前题的关键关系。",
            diagnosticItem = diagnosticItem,
            interactionDirective = interactionDirective,
            solutionMarkdown = "完整解答。",
            alternateMethodMarkdown = "另一种方法。",
            difficultyReasonMarkdown = "关键在条件之间的联系。",
            targetedEvidenceLabels = emptyList(),
            inferredKnowledgeLabels = listOf("函数关系"),
        ),
        modelVersion = "model-v1",
    )
    return ModelTaskSnapshot(
        taskId = "task-$requestId",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.SUCCEEDED,
        stateVersion = 1,
        stage = ModelTaskStage.COMPLETE,
        userMessage = "讲解已准备",
        attemptCount = 1,
        output = output,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    )
}

private fun guidance(
    task: ModelTaskSnapshot,
    session: ConfirmedTutorSession,
) = TutorGuidanceState(
    problem = TutorProblemScope(
        problemId = session.questionDocument.document.id,
        revisionNumber = session.draftRevisionNumber,
    ),
    mode = TutorExplanationMode.GUIDED,
    questionsAsked = 1,
    pendingEvidenceRequestId = task.request.requestId,
)

private fun response(
    session: ConfirmedTutorSession,
    choiceId: String = "choice-correct",
    occurredAt: Long,
    item: TutorAssessmentItem = diagnosticItem(),
    evidenceRequestId: String? = "plan-request-1",
): TutorTurnResponse {
    val selected = item.evaluateChoice(choiceId)
    return TutorTurnResponse(
        sessionId = session.sessionId,
        questionDocumentId = session.questionDocument.document.id,
        revisionNumber = session.draftRevisionNumber,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        diagnosticStemMarkdown = item.stemMarkdown,
        selectedChoiceId = selected.choice.id,
        selectedChoiceMarkdown = selected.choice.markdown,
        selectionWasCorrect = selected.isCorrect,
        feedbackMarkdown = checkNotNull(selected.choice.feedbackMarkdown),
        submittedAtEpochMillis = occurredAt,
        updatedAtEpochMillis = occurredAt,
        choiceSubmittedAtEpochMillis = occurredAt,
        evidenceRequestId = evidenceRequestId,
    )
}

private fun diagnosticItem(
    correctMarkdown: String = "先列出已知条件",
) = TutorAssessmentItem(
    id = "diagnostic-1",
    stemMarkdown = "关键一步是什么？",
    choices = listOf(
        TutorChoice(
            id = "choice-correct",
            markdown = correctMarkdown,
            feedbackMarkdown = "判断正确，继续沿这个关系推导。",
        ),
        TutorChoice(
            id = "choice-wrong",
            markdown = "直接猜答案",
            feedbackMarkdown = "先把题目条件连起来。",
        ),
    ),
    correctChoiceId = "choice-correct",
)

private fun session(
    draftId: String = "draft-1",
    subject: String = SubjectKind.MATH.name,
    stem: String = "求函数的单调区间",
    documentId: String = "document-1",
): ConfirmedTutorSession = ConfirmedTutorSession(
    sessionId = "session-1",
    draftId = draftId,
    draftRevisionNumber = 2,
    subject = subject,
    title = "当前题目",
    questionDocument = CapturedQuestionDocument(
        document = QuestionDocument(
            id = documentId,
            blocks = listOf(ContentBlock.Paragraph("stem", stem)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = "asset-1",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = QuestionBlockProvenance.USER_CORRECTION,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            ),
        ),
    ),
    sourceImageUri = "file:///private/source.jpg",
    createdAtEpochMillis = 1,
    isSaved = false,
    errorBookEntryId = null,
)

private fun activeConversation(
    id: String,
    learner: String,
) = TutorConversation(
    conversationId = id,
    learnerScopeId = learner,
    generation = 1,
    status = TutorConversationStatus.ACTIVE,
    createdAtEpochMillis = 1,
    archivedAtEpochMillis = null,
    stateVersion = 0,
)

private const val MODE_VERSION = 7L
