package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedTutorProblemIdentity
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
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
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorLearningEvidenceBoundaryInstrumentedTest {
    @Test
    fun exactPersistedChoiceChainFinalizesAndReplaysCanonicalFact() = runBlocking {
        val store = openStore()
        try {
            val fixture = store.exactChoiceFixture("exact")

            val first = store.finalizeTutorEvidenceRequest(fixture.submitCommand)
            val replay = store.finalizeTutorEvidenceRequest(fixture.submitCommand)

            assertFalse(first.replayed)
            assertTrue(replay.replayed)
            assertEquals(TutorEvidenceRequestStatus.SUBMITTED, first.request.status)
            assertEquals(fixture.expectedQuestionFingerprint, first.anchor?.questionFingerprint)
            assertEquals(fixture.revisionFingerprint, first.anchor?.revisionFingerprint)
            assertEquals(CapturedTutorProblemIdentity.fingerprintVersion, first.anchor?.fingerprintVersion)
            assertEquals(fixture.choice.selectedChoiceMarkdown, first.sourceFact?.responseSummary)
            assertEquals(fixture.choice.choiceSubmittedAtEpochMillis, first.sourceFact?.occurredAtEpochMillis)
            assertEquals(first.sourceFact, replay.sourceFact)
        } finally {
            store.close()
        }
    }

    @Test
    fun durableCancellationAfterResponseWinsOverSubmitAndRecovery() = runBlocking {
        val store = openStore()
        try {
            val fixture = store.exactChoiceFixture("cancel-wins")
            store.recordTutorEvidenceCancellation(
                PersistTutorEvidenceCancellationCommand(
                    learnerId = LEARNER_ID,
                    sessionId = fixture.choice.sessionId,
                    questionDocumentId = fixture.choice.questionDocumentId,
                    revisionNumber = fixture.choice.revisionNumber,
                    evidenceRequestId = fixture.prepare.evidenceRequestId,
                    cancelledAtEpochMillis = TRUSTED_NOW,
                ),
            )

            val first = store.finalizeTutorEvidenceRequest(fixture.submitCommand)
            val recovery = store.finalizeTutorEvidenceRequest(fixture.submitCommand)

            assertFalse(first.replayed)
            assertTrue(recovery.replayed)
            assertEquals(TutorEvidenceRequestStatus.CANCELLED, first.request.status)
            assertNull(first.sourceFact)
            assertNull(recovery.sourceFact)
            assertEquals(0, store.database.tutorLearningMemoryDao().countSourceFacts())
            assertEquals(0, store.database.tutorLearningMemoryDao().countAnchors())
        } finally {
            store.close()
        }
    }

    @Test
    fun ambiguousDurableCancellationFailsClosedWithoutCreatingFact() = runBlocking {
        val store = openStore()
        try {
            val fixture = store.exactChoiceFixture("ambiguous-cancellation")
            val cancellation = PersistTutorEvidenceCancellationCommand(
                learnerId = LEARNER_ID,
                sessionId = fixture.choice.sessionId,
                questionDocumentId = fixture.choice.questionDocumentId,
                revisionNumber = fixture.choice.revisionNumber,
                evidenceRequestId = fixture.prepare.evidenceRequestId,
                cancelledAtEpochMillis = TRUSTED_NOW,
            )
            store.recordTutorEvidenceCancellation(cancellation)
            store.recordTutorEvidenceCancellation(
                cancellation.copy(sessionId = "${cancellation.sessionId}-other"),
            )

            assertConflict<TutorEvidenceConflictException> {
                store.finalizeTutorEvidenceRequest(fixture.submitCommand)
            }
            assertEquals(
                TutorEvidenceRequestStatus.PENDING,
                store.openTutorEvidenceRequest(
                    LEARNER_ID,
                    fixture.prepare.evidenceRequestId,
                )?.status,
            )
            assertEquals(0, store.database.tutorLearningMemoryDao().countSourceFacts())
            assertEquals(0, store.database.tutorLearningMemoryDao().countAnchors())
        } finally {
            store.close()
        }
    }

    @Test
    fun callerClaimsCannotOverridePersistedChoiceFact() = runBlocking {
        val store = openStore()
        try {
            val fixture = store.exactChoiceFixture("malicious-claim")
            val maliciousFingerprint = sha256("malicious-response")
            val maliciousClaim = checkNotNull(fixture.submitCommand.submission).copy(
                sourceFactId = "malicious-source-fact",
                source = LearningObservationSource.TUTOR_FREE_RESPONSE,
                factKind = LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
                questionFingerprint = sha256("malicious-question"),
                revisionFingerprint = sha256("malicious-revision"),
                fingerprintVersion = "malicious-fingerprint-v1",
                responseFingerprint = maliciousFingerprint,
                responseSummary = "恶意摘要",
                occurredAtEpochMillis = TRUSTED_NOW + 50_000,
                sourceVersion = "malicious-source-v1",
            )

            val finalized = store.finalizeTutorEvidenceRequest(
                fixture.submitCommand.copy(submission = maliciousClaim),
            )
            val fact = checkNotNull(finalized.sourceFact)

            assertEquals(LearningObservationSource.TUTOR_CHOICE, fact.source)
            assertEquals(
                LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
                fact.factKind,
            )
            assertEquals(fixture.choice.selectedChoiceMarkdown, fact.responseSummary)
            assertEquals(fixture.choice.choiceSubmittedAtEpochMillis, fact.occurredAtEpochMillis)
            assertEquals("captured-choice-source-v1", fact.sourceVersion)
            assertEquals(fixture.expectedQuestionFingerprint, finalized.anchor?.questionFingerprint)
            assertEquals(fixture.revisionFingerprint, finalized.anchor?.revisionFingerprint)
            assertNotEquals(maliciousFingerprint, fact.responseFingerprint)
            assertNotEquals("malicious-source-fact", fact.sourceFactId)
        } finally {
            store.close()
        }
    }

    @Test
    fun missingOrCrossScopeChoiceResponseFailsClosed() = runBlocking {
        val missingStore = openStore()
        try {
            val missing = missingStore.exactChoiceFixture(
                suffix = "missing-response",
                persistResponse = false,
            )
            assertConflict<TutorEvidenceConflictException> {
                missingStore.finalizeTutorEvidenceRequest(missing.submitCommand)
            }
            assertEquals(0, missingStore.database.tutorLearningMemoryDao().countSourceFacts())
        } finally {
            missingStore.close()
        }

        val ambiguousStore = openStore()
        try {
            val fixture = ambiguousStore.exactChoiceFixture("ambiguous-response")
            ambiguousStore.recordTutorChoice(
                fixture.choice.copy(
                    sessionId = "${fixture.choice.sessionId}-other",
                ),
            )
            ambiguousStore.recordTutorChoice(
                fixture.choice.copy(
                    turnOrdinal = fixture.choice.turnOrdinal + 1,
                ),
            )

            assertConflict<TutorEvidenceConflictException> {
                ambiguousStore.finalizeTutorEvidenceRequest(fixture.submitCommand)
            }
            assertEquals(0, ambiguousStore.database.tutorLearningMemoryDao().countSourceFacts())
        } finally {
            ambiguousStore.close()
        }
    }

    @Test
    fun missingTrustedPlanOrSessionDraftChainFailsClosed() = runBlocking {
        val missingPlanStore = openStore()
        try {
            val missingPlan = missingPlanStore.exactChoiceFixture(
                suffix = "missing-plan",
                persistPlan = false,
            )
            assertConflict<TutorEvidenceConflictException> {
                missingPlanStore.finalizeTutorEvidenceRequest(missingPlan.submitCommand)
            }
        } finally {
            missingPlanStore.close()
        }

        val missingSessionStore = openStore()
        try {
            val missingSession = missingSessionStore.exactChoiceFixture(
                suffix = "missing-session-draft",
                persistCapture = false,
            )
            assertConflict<TutorEvidenceConflictException> {
                missingSessionStore.finalizeTutorEvidenceRequest(missingSession.submitCommand)
            }
            assertEquals(0, missingSessionStore.database.tutorLearningMemoryDao().countSourceFacts())
        } finally {
            missingSessionStore.close()
        }
    }

    internal suspend fun RoomStudyDatabase.exactChoiceFixture(
        suffix: String,
        learnerId: String = LEARNER_ID,
        choiceSubmittedAtEpochMillis: Long = TRUSTED_NOW,
        persistCapture: Boolean = true,
        persistPlan: Boolean = true,
        persistResponse: Boolean = true,
        persistEvidenceRequest: Boolean = true,
    ): ExactChoiceFixture {
        val assetId = "asset-$suffix"
        val draftId = "draft-$suffix"
        val sessionId = "session-$suffix"
        val requestId = "plan-$suffix"
        val questionDocumentId = "question-$suffix"
        val assetHash = sha256("asset-content-$suffix")
        val initial = capturedDocument(
            questionDocumentId = questionDocumentId,
            assetId = assetId,
            markdown = "等待确认。",
            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
            reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
            writingLayer = WritingLayer.UNKNOWN,
        )
        val confirmed = capturedDocument(
            questionDocumentId = questionDocumentId,
            assetId = assetId,
            markdown = "已知物体做匀速运动，判断速度是否改变。",
            provenance = QuestionBlockProvenance.USER_CORRECTION,
            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            writingLayer = WritingLayer.PRINTED,
        )
        val revisionFingerprint = CapturedQuestionDocumentFingerprint.of(confirmed)
        if (persistCapture) {
            createProblemDraft(
                CreateProblemDraftCommand(
                    sourceAsset = CanonicalSourceAssetRecord(
                        sourceAssetId = assetId,
                        contentSha256 = assetHash,
                        relativePath = "source-assets/$assetHash.jpg",
                        mimeType = "image/jpeg",
                        byteSize = 1_024,
                        width = 800,
                        height = 600,
                        sourceType = StudyDbValue.SourceAssetType.PHOTO_PICKER,
                        createdAtEpochMillis = 100,
                    ),
                    draftId = draftId,
                    origin = StudyDbValue.CaptureOrigin.TUTOR,
                    initialRevision = ProblemDraftRevisionRecord(
                        draftId = draftId,
                        revisionNumber = 1,
                        basisRevisionNumber = null,
                        subject = null,
                        title = "待确认",
                        questionDocument = initial,
                        documentFingerprint = CapturedQuestionDocumentFingerprint.of(initial),
                        author = StudyDbValue.ProblemDraftAuthor.CAPTURE_IMPORT,
                        createdAtEpochMillis = 100,
                    ),
                ),
            )
            confirmTutorSession(
                ConfirmTutorSessionCommand(
                    sessionId = sessionId,
                    draftId = draftId,
                    expectedRevisionNumber = 1,
                    confirmedRevision = ProblemDraftRevisionRecord(
                        draftId = draftId,
                        revisionNumber = 2,
                        basisRevisionNumber = 1,
                        subject = SubjectKind.PHYSICS.name,
                        title = "运动判断",
                        questionDocument = confirmed,
                        documentFingerprint = revisionFingerprint,
                        author = StudyDbValue.ProblemDraftAuthor.USER,
                        createdAtEpochMillis = 200,
                    ),
                    createdAtEpochMillis = 200,
                ),
            )
        }
        val item = diagnosticItem(suffix)
        val planInput = TutorPlanInput(
            sessionId = sessionId,
            draftRevisionNumber = 2,
            subject = SubjectKind.PHYSICS.name,
            questionDocument = confirmed.document,
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        )
        val planOutput = TutorPlanOutput(
            sessionId = sessionId,
            draftRevisionNumber = 2,
            questionDocumentId = questionDocumentId,
            plan = TutorTurnPlan(
                openingMarkdown = "先看速度的大小和方向。",
                diagnosticItem = item,
                interactionDirective = null,
                solutionMarkdown = "匀速运动中速度保持不变。",
                alternateMethodMarkdown = "也可以从加速度为零判断。",
                difficultyReasonMarkdown = "关键是速度包含大小和方向。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf("匀速运动"),
            ),
            modelVersion = "instrumented-test-model",
            cycleOrdinal = 1,
            turnOrdinal = 1,
        )
        if (persistPlan) {
            persistSucceededModelTask(
                request = ModelTaskRequest(
                    requestId = requestId,
                    input = planInput,
                    occurredAtEpochMillis = 300,
                ),
                output = planOutput,
            )
        }
        val directiveFingerprint = directiveFingerprint(
            requestId = requestId,
            sessionId = sessionId,
            draftId = draftId,
            revisionFingerprint = revisionFingerprint,
            input = planInput,
            output = planOutput,
            item = item,
        )
        val questionFingerprint = CapturedTutorProblemIdentity.questionFingerprint(draftId)
        val conversationId = opaqueId(
            "captured-choice-conversation-v2",
            learnerId,
            sessionId,
            draftId,
            "2",
            questionDocumentId,
        )
        val anchorId = opaqueId(
            "captured-choice-anchor-v1",
            learnerId,
            SubjectKind.PHYSICS.name,
            questionFingerprint,
            revisionFingerprint,
            CapturedTutorProblemIdentity.fingerprintVersion,
        )
        val turnReceiptId = opaqueId(
            "captured-choice-turn-v1",
            learnerId,
            sessionId,
            draftId,
            "2",
            questionDocumentId,
            requestId,
            "1",
            "1",
            directiveFingerprint,
        )
        createTutorConversation(
            CreateTutorConversationCommand(
                conversationId = conversationId,
                learnerId = learnerId,
                idempotencyKey = "create-$suffix",
                payloadFingerprint = sha256("create-$suffix"),
            ),
        )
        val turn = allocateTutorTurn(
            AllocateTutorTurnCommand(
                turnReceiptId = turnReceiptId,
                learnerId = learnerId,
                conversationId = conversationId,
                conversationGeneration = 1,
                expectedConversationStateVersion = 0,
                expectedTurnOrdinal = 1,
                clientTurnId = "client-turn-$suffix",
                payloadFingerprint = sha256("turn-$suffix"),
                subject = SubjectKind.PHYSICS,
                problemAnchorId = anchorId,
                requestVersion = 1,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 1,
                directiveFingerprint = directiveFingerprint,
                studentMessageFingerprint = sha256("student-$suffix"),
                studentMessageSummary = "我选择了一个答案。",
                occurredAtEpochMillis = TRUSTED_NOW,
            ),
        ).receipt
        val prepare = PrepareTutorEvidenceRequestCommand(
            evidenceRequestId = requestId,
            learnerId = learnerId,
            conversationId = conversationId,
            conversationGeneration = 1,
            conversationStateVersion = turn.conversationStateVersion,
            turnReceiptId = turnReceiptId,
            turnOrdinal = turn.turnOrdinal,
            subject = SubjectKind.PHYSICS,
            problemAnchorId = anchorId,
            kind = TutorEvidenceRequestKind.CHOICE,
            requestVersion = 1,
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 1,
            directiveFingerprint = directiveFingerprint,
            idempotencyKey = "prepare-$suffix",
            payloadFingerprint = sha256("prepare-$suffix"),
        )
        if (persistEvidenceRequest) prepareTutorEvidenceRequest(prepare)
        val selected = item.choices.single { it.id != item.correctChoiceId }
        val choice = PersistTutorChoiceCommand(
            sessionId = sessionId,
            questionDocumentId = questionDocumentId,
            revisionNumber = 2,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            diagnosticStemMarkdown = item.stemMarkdown,
            selectedChoiceId = selected.id,
            selectedChoiceMarkdown = selected.markdown,
            selectionWasCorrect = false,
            feedbackMarkdown = checkNotNull(selected.feedbackMarkdown),
            choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
            evidenceRequestId = requestId,
        )
        if (persistResponse) recordTutorChoice(choice)
        val submit = FinalizeTutorEvidenceRequestCommand(
            learnerId = learnerId,
            conversationId = conversationId,
            conversationGeneration = 1,
            conversationStateVersion = turn.conversationStateVersion,
            turnReceiptId = turnReceiptId,
            turnOrdinal = turn.turnOrdinal,
            subject = SubjectKind.PHYSICS,
            problemAnchorId = anchorId,
            evidenceRequestId = requestId,
            expectedEvidenceStateVersion = 0,
            kind = TutorEvidenceRequestKind.CHOICE,
            requestVersion = 1,
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 1,
            directiveFingerprint = directiveFingerprint,
            terminalStatus = TutorEvidenceRequestStatus.SUBMITTED,
            idempotencyKey = "submit-$suffix",
            payloadFingerprint = sha256("submit-$suffix"),
            submission = TutorEvidenceSubmission(
                sourceFactId = "caller-source-$suffix",
                source = LearningObservationSource.TUTOR_CHOICE,
                factKind = LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
                questionFingerprint = questionFingerprint,
                revisionFingerprint = revisionFingerprint,
                fingerprintVersion = CapturedTutorProblemIdentity.fingerprintVersion,
                responseFingerprint = sha256("caller-response-$suffix"),
                responseSummary = "调用方摘要",
                occurredAtEpochMillis = TRUSTED_NOW,
                sourceVersion = "caller-source-v1",
            ),
        )
        return ExactChoiceFixture(
            prepare = prepare,
            submitCommand = submit,
            choice = choice,
            expectedQuestionFingerprint = questionFingerprint,
            revisionFingerprint = revisionFingerprint,
        )
    }

    private suspend fun StudyDatabasePort.persistSucceededModelTask(
        request: ModelTaskRequest,
        output: ModelTaskOutput,
    ) {
        var snapshot = createModelTask(
            CreateModelTaskCommand(
                taskId = "task:${request.requestId}",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                occurredAtEpochMillis = request.occurredAtEpochMillis,
            ),
        ).snapshot
        snapshot = transitionModelTask(
            transition(snapshot, ModelTaskStatus.QUEUED, ModelTaskStage.PREPARING, 301),
        ).snapshot
        snapshot = transitionModelTask(
            transition(snapshot, ModelTaskStatus.RUNNING, ModelTaskStage.VALIDATING_OUTPUT, 302),
        ).snapshot
        snapshot = transitionModelTask(
            transition(
                snapshot,
                ModelTaskStatus.SUCCEEDED,
                ModelTaskStage.COMPLETE,
                303,
                output,
            ),
        ).snapshot
        check(snapshot.status == ModelTaskStatus.SUCCEEDED)
    }

    private fun transition(
        snapshot: ModelTaskSnapshot,
        nextStatus: ModelTaskStatus,
        stage: ModelTaskStage,
        occurredAtEpochMillis: Long,
        output: ModelTaskOutput? = null,
    ) = TransitionModelTaskCommand(
        taskId = snapshot.taskId,
        expectedStateVersion = snapshot.stateVersion,
        expectedStatus = snapshot.status,
        nextStatus = nextStatus,
        stage = stage,
        userMessage = nextStatus.name,
        attemptCount = snapshot.attemptCount,
        output = output,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun capturedDocument(
        questionDocumentId: String,
        assetId: String,
        markdown: String,
        provenance: QuestionBlockProvenance,
        reviewStatus: QuestionBlockReviewStatus,
        writingLayer: WritingLayer,
    ) = CapturedQuestionDocument(
        document = QuestionDocument(
            id = questionDocumentId,
            title = "题目",
            blocks = listOf(ContentBlock.Paragraph("stem", markdown)),
        ),
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = assetId,
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = writingLayer,
                provenance = provenance,
                confidence = null,
                reviewStatus = reviewStatus,
                producerVersion = "instrumented-test-v1",
            ),
        ),
    )

    private fun diagnosticItem(suffix: String) = TutorAssessmentItem(
        id = "diagnostic-$suffix",
        stemMarkdown = "匀速运动中的速度是否改变？",
        choices = listOf(
            TutorChoice(
                id = "stable",
                markdown = "不改变",
                feedbackMarkdown = "正确，速度的大小和方向都不变。",
            ),
            TutorChoice(
                id = "changes",
                markdown = "会改变",
                feedbackMarkdown = "再看“匀速”的含义。",
            ),
        ),
        correctChoiceId = "stable",
    )

    private fun directiveFingerprint(
        requestId: String,
        sessionId: String,
        draftId: String,
        revisionFingerprint: String,
        input: TutorPlanInput,
        output: TutorPlanOutput,
        item: TutorAssessmentItem,
    ): String {
        val digest = CanonicalSha256("captured-choice-directive-v1")
            .field("planRequestId", requestId)
            .field("sessionId", sessionId)
            .field("draftId", draftId)
            .field("draftRevisionNumber", input.draftRevisionNumber)
            .field("questionDocumentId", input.questionDocument.id)
            .field("revisionFingerprint", revisionFingerprint)
            .field("cycleOrdinal", input.cycleOrdinal)
            .field("turnOrdinal", input.turnOrdinal)
            .field("diagnosticItemId", item.id)
            .field("stemMarkdown", item.stemMarkdown)
            .nullableField("promptMarkdown", item.promptMarkdown)
            .field("choiceCount", item.choices.size)
        item.choices.forEachIndexed { index, choice ->
            digest.field("choice[$index].id", choice.id)
                .field("choice[$index].markdown", choice.markdown)
                .nullableField("choice[$index].feedbackMarkdown", choice.feedbackMarkdown)
                .field("choice[$index].followUpCount", choice.followUpIds.size)
            choice.followUpIds.forEachIndexed { followUpIndex, followUpId ->
                digest.field("choice[$index].followUp[$followUpIndex]", followUpId)
            }
        }
        digest.field("correctChoiceId", item.correctChoiceId)
            .field("initialFollowUpCount", item.initialFollowUpIds.size)
        item.initialFollowUpIds.forEachIndexed { index, followUpId ->
            digest.field("initialFollowUp[$index]", followUpId)
        }
        val knowledgeNodes = item.knowledgeNodeIds.sorted()
        digest.field("knowledgeNodeCount", knowledgeNodes.size)
        knowledgeNodes.forEachIndexed { index, node ->
            digest.field("knowledgeNode[$index]", node)
        }
        return digest.field("outputCycleOrdinal", output.cycleOrdinal)
            .field("outputTurnOrdinal", output.turnOrdinal)
            .finish()
    }

    private fun opaqueId(domain: String, vararg values: String): String {
        val digest = CanonicalSha256(domain).field("valueCount", values.size)
        values.forEachIndexed { index, value -> digest.field("value[$index]", value) }
        return "$domain:${digest.finish()}"
    }

    private suspend inline fun <reified T : Throwable> assertConflict(
        crossinline block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
    }

    private fun openStore(): RoomStudyDatabase =
        StudyDatabaseFactory.openInMemory(context()) { TRUSTED_NOW }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    internal data class ExactChoiceFixture(
        val prepare: PrepareTutorEvidenceRequestCommand,
        val submitCommand: FinalizeTutorEvidenceRequestCommand,
        val choice: PersistTutorChoiceCommand,
        val expectedQuestionFingerprint: String,
        val revisionFingerprint: String,
    )

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val TRUSTED_NOW = 10_000L

        fun sha256(value: String): String =
            CanonicalSha256("instrumented-test-sha256-v1")
                .field("value", value)
                .finish()
    }
}

internal suspend fun exactTutorChoiceEvidenceFixture(
    store: RoomStudyDatabase,
    suffix: String,
    learnerId: String = "learner-local",
    choiceSubmittedAtEpochMillis: Long = 10_000L,
    persistCapture: Boolean = true,
    persistPlan: Boolean = true,
    persistResponse: Boolean = true,
    persistEvidenceRequest: Boolean = true,
): TutorLearningEvidenceBoundaryInstrumentedTest.ExactChoiceFixture =
    with(TutorLearningEvidenceBoundaryInstrumentedTest()) {
        store.exactChoiceFixture(
            suffix = suffix,
            learnerId = learnerId,
            choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
            persistCapture = persistCapture,
            persistPlan = persistPlan,
            persistResponse = persistResponse,
            persistEvidenceRequest = persistEvidenceRequest,
        )
    }
