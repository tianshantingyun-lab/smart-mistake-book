package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseContextRequest
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentOpenResponseLearningScope
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorOpenResponseAuthorizationConsumeDisposition
import com.tingyun.smartmistakebook.core.data.tutor.TutorLearningEvidenceCandidate
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationProductionOwnerPorts
import com.tingyun.smartmistakebook.core.database.ActivateCurrentTutorInteractionCommand
import com.tingyun.smartmistakebook.core.database.AppendCurrentTutorInteractionCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionActivationDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionActivationResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionAppendDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionAppendResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionBundle
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionEventRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionHeadRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionEventKind
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionScopeRecord
import com.tingyun.smartmistakebook.core.database.ConsumeCurrentTutorOpenResponseAuthorizationCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorOpenResponseAuthorizationConsumeResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorOpenResponseAuthorizationConsumeDisposition as DatabaseOpenResponseConsumeDisposition
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAssistance
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceReceipt
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerEvaluationReceipt
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTutorSessionProductionOwnerTest {
    @Test
    fun `current generation publishes narrow tutor ports without exposing the owner`() {
        val publicMethods =
            CurrentGenerationProductionOwnerPorts::class.java.declaredMethods
                .filter { method -> Modifier.isPublic(method.modifiers) }

        assertEquals(
            1,
            publicMethods.count { it.returnType == TutorCurrentSessionHostPort::class.java },
        )
        assertEquals(
            1,
            publicMethods.count { it.returnType == TutorConversationLobbyPort::class.java },
        )
        assertFalse(publicMethods.any { it.returnType == TutorInteractionRepository::class.java })
        assertFalse(publicMethods.any { it.returnType == TutorLearningMemoryRepository::class.java })
        assertFalse(publicMethods.any { it.returnType == CurrentTutorSessionProductionOwner::class.java })
    }

    @Test
    fun `choice and visual correctness writes never receive a session authorization`() = runBlocking {
        val authority = FakeCurrentTutorSessionAuthority()
        val owner = owner(authority)
        val activation = activation(revision = 1, seed = "answer-owner-boundary")
        assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))

        listOf(
            TutorCurrentInteractionAuthorizationPurpose.RECORD_CHOICE,
            TutorCurrentInteractionAuthorizationPurpose.RECORD_VISUAL_SELECTION,
        ).forEach { purpose ->
            assertNull(
                owner.authorizeCurrent(
                    TutorCurrentInteractionAuthorizationQuery(
                        scope = SessionScope(LEARNER),
                        conversationId = activation.conversationId,
                        questionDocumentId = activation.questionDocument.id,
                        revisionNumber = activation.questionRevisionNumber,
                        cycleOrdinal = activation.cycleOrdinal,
                        turnOrdinal = activation.turnOrdinal,
                        requestId = activation.turnReferenceId,
                        purpose = purpose,
                    ),
                ),
            )
        }

        assertTrue(authority.appendCommands.isEmpty())
        owner.close()
    }

    @Test
    fun `direct current context is owner issued and close revokes it`() = runBlocking {
        val authority = FakeCurrentTutorSessionAuthority()
        val owner = owner(authority)
        val activation = activation(revision = 1, seed = "first")
        assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))

        val authorization = owner.authorizeCurrent(activation.toContextRequest(), NOW + 1_000)

        assertNotNull(authorization)
        assertTrue(owner.isCurrent(checkNotNull(authorization)))
        owner.close()
        assertFalse(owner.isCurrent(authorization))
        assertNull(owner.authorizeCurrent(activation.toContextRequest(), NOW + 1_000))
    }

    @Test
    fun `persisted answer exposure revokes old and newly requested learning authority`() =
        runBlocking {
            val authority = FakeCurrentTutorSessionAuthority()
            val owner = owner(authority)
            val activation = activation(revision = 1, seed = "answer-exposure")
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))
            val authorization = checkNotNull(
                owner.authorizeCurrent(activation.toContextRequest(), NOW + 1_000L),
            )

            authority.persistAnswerExposure()

            assertTrue(checkNotNull(owner.currentEvidenceState(CONVERSATION)).answerWasRevealed)
            assertFalse(owner.isCurrent(authorization))
            assertNull(owner.authorizeCurrent(activation.toContextRequest(), NOW + 1_000L))
            assertNull(owner.currentLearningEvidenceReference(activation))
            assertEquals(
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT,
                owner.consumeCurrentAuthorization(
                    authorization = authorization,
                    scope = activation.toLearningScope(),
                    candidateIdempotencyKey = sha256("exposed-candidate"),
                ),
            )
            assertTrue(authority.consumeCommands.isEmpty())
            owner.close()
        }

    @Test
    fun `host hint commit is one shot exact scoped and does not create an attempt`() = runBlocking {
        val authority = FakeCurrentTutorSessionAuthority().apply {
            applyTrustedAnswerCommands = true
        }
        val owner = owner(authority)
        val activation = activation(
            revision = 1,
            seed = "host-hint",
            explanationMode = TutorExplanationMode.GUIDED,
            attemptOrdinal = 1,
        )
        assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))
        val commit = CurrentTutorHintShownCommit(
            sessionId = activation.conversationId,
            expectedScopeId = activation.scopeId,
            expectedQuestionDocumentId = activation.questionDocument.id,
            expectedQuestionRevisionNumber = activation.questionRevisionNumber,
            expectedCycleOrdinal = activation.cycleOrdinal,
            expectedTurnOrdinal = activation.turnOrdinal,
            expectedPresentationFingerprint = activation.presentationFingerprint,
            modeVersion = activation.modeVersion,
            slotToken = sha256("host-issued-hint-slot"),
            occurredAtEpochMillis = NOW,
        )

        assertEquals(CurrentTutorHintShownCommitResult.RECORDED, owner.recordHintShown(commit))
        val afterHint = checkNotNull(owner.currentEvidenceState(CONVERSATION))
        assertEquals(1, afterHint.attemptOrdinal)
        assertEquals(1, afterHint.hintCount)
        assertEquals(CurrentTutorHintShownCommitResult.DUPLICATE, owner.recordHintShown(commit))
        assertEquals(
            CurrentTutorHintShownCommitResult.REJECTED,
            owner.recordHintShown(commit.copy(slotToken = sha256("forged-second-slot"))),
        )
        assertEquals(
            CurrentTutorHintShownCommitResult.REJECTED,
            owner.recordHintShown(commit.copy(expectedTurnOrdinal = 2)),
        )
        assertEquals(1, authority.currentEvents.count { it.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN })
        owner.close()
    }

    @Test
    fun `new revision revokes old grant and cross learner activation never reaches authority`() =
        runBlocking {
            val authority = FakeCurrentTutorSessionAuthority()
            val owner = owner(authority)
            val first = activation(revision = 1, seed = "first")
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(first))
            val oldGrant = checkNotNull(owner.authorizeCurrent(first.toContextRequest(), NOW + 1_000))

            val next = activation(revision = 2, seed = "next")
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(next))
            assertFalse(owner.isCurrent(oldGrant))
            assertNull(owner.authorizeCurrent(first.toContextRequest(), NOW + 1_000))
            assertNotNull(owner.authorizeCurrent(next.toContextRequest(), NOW + 1_000))

            val callsBeforeCrossLearner = authority.activationCalls
            assertEquals(
                CurrentTutorSessionActivationResult.AUTHORITY_MISMATCH,
                owner.activate(activation(learnerId = "another-learner", revision = 3, seed = "cross")),
            )
            assertEquals(callsBeforeCrossLearner, authority.activationCalls)
            owner.close()
        }

    @Test
    fun `duplicate activation rehydrates process local authority after owner restart`() = runBlocking {
        val authority = FakeCurrentTutorSessionAuthority()
        val activation = activation(revision = 1, seed = "restart")
        owner(authority).also { first ->
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, first.activate(activation))
            first.close()
        }

        owner(authority).also { reopened ->
            assertEquals(CurrentTutorSessionActivationResult.DUPLICATE, reopened.activate(activation))
            assertNotNull(
                reopened.authorizeCurrent(activation.toContextRequest(), NOW + 1_000),
            )
            reopened.close()
        }
        Unit
    }

    @Test
    fun `persisted consume rejects stale grant while collector still reports it current`() =
        runBlocking {
            val authority = FakeCurrentTutorSessionAuthority()
            val owner = owner(authority)
            val activation = activation(revision = 1, seed = "paused-collector")
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))
            val authorization = checkNotNull(
                owner.authorizeCurrent(activation.toContextRequest(), NOW + 1_000),
            )
            assertTrue(owner.isCurrent(authorization))
            authority.consumeDisposition = DatabaseOpenResponseConsumeDisposition.NOT_CURRENT

            val result = owner.consumeCurrentAuthorization(
                authorization = authorization,
                scope = activation.toLearningScope(),
                candidateIdempotencyKey = sha256("paused-collector-candidate"),
            )

            assertEquals(CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT, result)
            assertTrue(owner.isCurrent(authorization))
            assertEquals(activation.learningWritePermissionVersion, authority.consumeCommands.single().learningWritePermissionVersion)
            owner.close()
        }

    @Test
    fun `claimed authorization keeps exact owner scope after issuance deadline`() = runBlocking {
        val now = AtomicLong(NOW)
        val authority = FakeCurrentTutorSessionAuthority()
        val owner = owner(authority, LongSupplier(now::get))
        val activation = activation(revision = 1, seed = "claimed-deadline")
        assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))
        val authorization = checkNotNull(
            owner.authorizeCurrent(activation.toContextRequest(), NOW + 1_000L),
        )

        now.set(NOW + 1_001L)

        assertFalse(owner.isCurrent(authorization))
        assertTrue(owner.isClaimedCurrent(authorization))
        assertEquals(
            CurrentTutorOpenResponseAuthorizationConsumeDisposition.CONSUMED,
            owner.consumeClaimedAuthorization(
                authorization = authorization,
                scope = activation.toLearningScope(),
                candidateIdempotencyKey = sha256("claimed-deadline-candidate"),
            ),
        )
        assertEquals(1, authority.consumeCommands.size)
        owner.close()
        assertFalse(owner.isClaimedCurrent(authorization))
    }

    @Test
    fun `learning proof source requires exact current authority scope and closes fail closed`() =
        runBlocking {
            val authority = FakeCurrentTutorSessionAuthority()
            val owner = owner(authority)
            val proof = knowledgeProof()
            val activation = activation(revision = 1, seed = "learning", proofs = listOf(proof))
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))
            val reference = checkNotNull(owner.currentLearningEvidenceReference(activation))
            val candidate = activation.toLearningCandidate(reference.authorizationFingerprint)

            assertEquals(listOf(proof), owner.resolve(candidate))
            assertNull(
                owner.resolve(
                    candidate.copy(conversationGeneration = candidate.conversationGeneration + 1),
                ),
            )
            assertNull(
                owner.resolve(
                    candidate.copy(learnerId = "another-learner"),
                ),
            )
            assertNull(
                owner.resolve(
                    candidate.copy(subject = SubjectKind.CHEMISTRY),
                ),
            )
            assertNull(
                owner.resolve(
                    candidate.copy(
                        currentSessionReference =
                            candidate.currentSessionReference.copy(
                                learningWritePermissionVersion =
                                    candidate.currentSessionReference.learningWritePermissionVersion + 1,
                            ),
                    ),
                ),
            )
            assertNull(
                owner.resolve(
                    candidate.copy(
                        currentSessionReference =
                            candidate.currentSessionReference.copy(
                                authorizationFingerprint = sha256("forged-authorization"),
                            ),
                    ),
                ),
            )

            owner.close()
            assertNull(owner.resolve(candidate))
            assertNull(owner.currentLearningEvidenceReference(activation))
        }

    @Test
    fun `trusted answer commit resumes append then finalization without duplicating the fact`() =
        runBlocking {
            val authority = FakeCurrentTutorSessionAuthority().apply {
                applyTrustedAnswerCommands = true
            }
            val owner = owner(authority)
            val activation = activation(
                revision = 1,
                seed = "trusted-answer-saga",
                explanationMode = TutorExplanationMode.GUIDED,
                attemptOrdinal = 0,
            )
            assertEquals(CurrentTutorSessionActivationResult.ACTIVE, owner.activate(activation))
            val request = activation.toEvidenceRequest("evidence-choice")
            authority.storedEvidenceRequest = request
            val evaluation = StudentTrustedSavedAnswerEvaluationReceipt(
                responseForm = ReviewResponseForm.CHOICE,
                responseBinding = sha256("trusted-response"),
                selectionWasCorrect = false,
                canonicalFingerprint = sha256("trusted-evaluation"),
            )
            val firstCommand = CurrentTutorTrustedAnswerCommitCommand(
                learnerId = LEARNER,
                sessionId = CONVERSATION,
                presentationToken = sha256("presentation-token"),
                exactContentBinding = "current-tutor-visible-presentation-v2:${sha256("content")}",
                evidenceRequestId = request.evidenceRequestId,
                expectedEvidenceState = checkNotNull(owner.currentEvidenceState(CONVERSATION)),
                evaluation = evaluation,
                event = CurrentTutorTrustedAnswerEvent.Choice(
                    diagnosticStemMarkdown = "判断电流方向",
                    selectedChoiceId = "b",
                    selectedChoiceMarkdown = "从 B 到 A",
                    feedbackMarkdown = "已记录",
                ),
                occurredAtEpochMillis = NOW + 1L,
            )

            val interrupted = owner.commitTrustedSavedAnswer(
                firstCommand,
                CurrentTutorTrustedEvidenceFinalizer {
                    throw IllegalStateException("simulated crash after current-session append")
                },
            )

            assertEquals(CurrentTutorTrustedAnswerSubmissionResult.Rejected, interrupted)
            val stateAfterAppend = checkNotNull(owner.currentEvidenceState(CONVERSATION))
            assertEquals(firstCommand.expectedEvidenceState.attemptOrdinal + 1, stateAfterAppend.attemptOrdinal)
            assertEquals(1, authority.currentEvents.size)

            val committed = owner.commitTrustedSavedAnswer(
                firstCommand.copy(
                    expectedEvidenceState = stateAfterAppend,
                    occurredAtEpochMillis = NOW + 2L,
                ),
                CurrentTutorTrustedEvidenceFinalizer { finalize ->
                    val receipt = TutorLearningEvidenceReceipt(
                        receiptId = "mastery-receipt",
                        receiptFingerprint = sha256("mastery-receipt"),
                    )
                    val submitted = checkNotNull(authority.storedEvidenceRequest).copy(
                        status = TutorEvidenceRequestStatus.SUBMITTED,
                        stateVersion = finalize.expectedEvidenceStateVersion + 1L,
                        resolvedAtEpochMillis = finalize.occurredAtEpochMillis,
                        terminalReceiptId = receipt.receiptId,
                    )
                    authority.storedEvidenceRequest = submitted
                    FinalizeTutorEvidenceResult.Submitted(submitted, receipt)
                },
            )

            val receipt = (committed as CurrentTutorTrustedAnswerSubmissionResult.Committed).receipt
            assertTrue(receipt.duplicate)
            assertEquals(stateAfterAppend, receipt.resultingEvidenceState)
            assertEquals(1, authority.currentEvents.size)
            assertEquals(2, authority.appendCommands.size)
            owner.close()
        }

    private fun owner(
        authority: CurrentTutorSessionAuthority,
        nowEpochMillis: LongSupplier = LongSupplier { NOW },
    ) =
        CurrentTutorInteractionSessionOwner(
            scope = SessionScope(LEARNER),
            authority = authority,
            nowEpochMillis = nowEpochMillis,
        )

    private fun activation(
        learnerId: String = LEARNER,
        revision: Int,
        seed: String,
        proofs: List<VerifiedKnowledgeReferenceProof> = emptyList(),
        explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
        attemptOrdinal: Int = 1,
    ) = CurrentTutorSessionActivation(
        learnerId = learnerId,
        conversationId = CONVERSATION,
        conversationGeneration = 1,
        conversationStateVersion = revision.toLong(),
        authorityConversationId = AUTHORITY_CONVERSATION,
        authorityConversationGeneration = 1,
        authorityConversationStateVersion = 1,
        authorityTurnReceiptId = AUTHORITY_TURN,
        authorityTurnOrdinal = 1,
        authorityRequestVersion = 1,
        questionRevisionNumber = revision,
        questionDocument = questionDocument(revision),
        subject = SubjectKind.PHYSICS,
        problemAnchorId = PROBLEM_ANCHOR,
        explanationMode = explanationMode,
        modeVersion = 1,
        turnReferenceId = "model-task-$revision",
        turnOrdinal = 1,
        turnGeneration = revision.toLong(),
        cycleOrdinal = 1,
        attemptOrdinal = attemptOrdinal,
        hintCount = 0,
        answerWasRevealed = false,
        requestVersion = revision.toLong(),
        learningWritesAllowed = true,
        learningWritePermissionVersion = revision.toLong(),
        presentationFingerprint = sha256("presentation-$seed"),
        problemFingerprint = sha256("problem"),
        problemFamilyFingerprint = sha256("family"),
        attributionPolicyVersion = "attribution-v1",
        responsePolicyVersion = "response-v1",
        rubricCanonicalFingerprint = sha256("rubric"),
        verifiedKnowledgeProofs = proofs,
        teachingReferences = emptyList(),
        evaluator = OpenResponseEvaluatorKind.RUBRIC,
        evaluatorPolicyFingerprint = sha256("evaluator"),
        occurredAtEpochMillis = NOW,
    )

    private fun CurrentTutorSessionActivation.toLearningCandidate(
        authorizationFingerprint: String,
    ) = TutorLearningEvidenceCandidate(
        learnerId = learnerId,
        evidenceRequestId = turnReferenceId,
        conversationId = authorityConversationId,
        conversationGeneration = authorityConversationGeneration,
        conversationStateVersion = authorityConversationStateVersion,
        turnReceiptId = authorityTurnReceiptId,
        turnOrdinal = authorityTurnOrdinal,
        subject = subject,
        sessionAnchorId = problemAnchorId,
        kind = TutorEvidenceRequestKind.SPECIFIC_STUCK,
        requestVersion = authorityRequestVersion,
        modeVersion = modeVersion,
        directiveFingerprint = sha256("directive"),
        idempotencyKey = "candidate-learning",
        payloadFingerprint = sha256("payload"),
        questionFingerprint = questionFingerprint,
        problemRevisionFingerprint = sha256("revision"),
        problemFingerprintVersion = "problem-v1",
        turnFingerprint = sha256("turn"),
        responseFingerprint = sha256("response"),
        outcome = TutorLearningEvidenceOutcome.SPECIFIC_STUCK,
        occurredAtEpochMillis = occurredAtEpochMillis,
        attestedAtEpochMillis = occurredAtEpochMillis,
        producerVersion = "tutor-test-v1",
        currentSessionReference =
            com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCurrentSessionReference(
                authorizationFingerprint = authorizationFingerprint,
                learningWritePermissionVersion = learningWritePermissionVersion,
            ),
        attemptOrdinal = attemptOrdinal,
        retryCount = attemptOrdinal - 1,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        independentlyAnswered = false,
        assistance = TutorLearningEvidenceAssistance.UNKNOWN,
    )

    private fun CurrentTutorSessionActivation.toEvidenceRequest(
        evidenceRequestId: String,
    ) = TutorEvidenceRequest(
        evidenceRequestId = evidenceRequestId,
        conversationId = authorityConversationId,
        conversationGeneration = authorityConversationGeneration,
        conversationStateVersion = authorityConversationStateVersion,
        turnReceiptId = authorityTurnReceiptId,
        turnOrdinal = authorityTurnOrdinal,
        subject = subject,
        problemAnchorId = problemAnchorId,
        kind = TutorEvidenceRequestKind.CHOICE,
        requestVersion = authorityRequestVersion,
        modeVersion = modeVersion,
        explanationMode = explanationMode,
        directiveFingerprint = sha256("trusted-choice-directive"),
        status = TutorEvidenceRequestStatus.PENDING,
        stateVersion = 0L,
        createdAtEpochMillis = occurredAtEpochMillis,
        resolvedAtEpochMillis = null,
        terminalReceiptId = null,
    )

    private fun CurrentTutorSessionActivation.toContextRequest() =
        CoreDataTutorOpenResponseContextRequest(
            conversationId = conversationId,
            conversationGeneration = conversationGeneration,
            conversationStateVersion = conversationStateVersion,
            questionDocumentId = questionDocument.id,
            questionRevisionNumber = questionRevisionNumber,
            subject = subject,
            questionDocument = questionDocument,
            explanationMode = explanationMode,
            modeVersion = modeVersion,
            turnReferenceId = turnReferenceId,
            turnOrdinal = turnOrdinal,
            turnGeneration = turnGeneration,
            evidenceRequestId = turnReferenceId,
            attemptOrdinal = attemptOrdinal,
            hintCount = hintCount,
            answerWasRevealed = answerWasRevealed,
            requestVersion = requestVersion,
        )

    private fun CurrentTutorSessionActivation.toLearningScope() =
        CurrentOpenResponseLearningScope(
            learnerId = learnerId,
            conversationId = conversationId,
            conversationGeneration = conversationGeneration,
            conversationStateVersion = conversationStateVersion,
            questionDocumentId = questionDocument.id,
            questionRevisionNumber = questionRevisionNumber,
            subject = subject,
            questionFingerprint = questionFingerprint,
            responseBinding = sha256("paused-collector-answer-binding"),
            evidenceRequestId = turnReferenceId,
            modeVersion = modeVersion,
            turnReferenceId = turnReferenceId,
            turnOrdinal = turnOrdinal,
            turnGeneration = turnGeneration,
            attemptOrdinal = attemptOrdinal,
            hintCount = hintCount,
            answerWasRevealed = answerWasRevealed,
            requestVersion = requestVersion,
        )

    private fun questionDocument(revision: Int) =
        QuestionDocument(
            id = "question-$revision",
            blocks = listOf(ContentBlock.Paragraph("stem-$revision", "当前题目")),
        )

    private companion object {
        const val LEARNER = "learner-current"
        const val CONVERSATION = "visible-conversation"
        const val AUTHORITY_CONVERSATION = "authority-conversation"
        const val AUTHORITY_TURN = "authority-turn"
        const val PROBLEM_ANCHOR = "problem-anchor"
        const val NOW = 10_000L
        val PROOF_AUTHORITY: KnowledgeReferenceProofAuthority =
            KnowledgeReferenceProofAuthority.create()

        fun knowledgeProof(): VerifiedKnowledgeReferenceProof =
            PROOF_AUTHORITY.issuer.issue(
                KnowledgeNodeRef(
                    subject = SubjectKind.PHYSICS,
                    knowledgeNodeId = "physics.electromagnetic.induction",
                    taxonomyVersion = "taxonomy-v1",
                    knowledgePackVersion = "pack-v1",
                ),
                sha256("manifest"),
                1,
            )

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private class FakeCurrentTutorSessionAuthority : CurrentTutorSessionAuthority {
    private val current = MutableStateFlow<CurrentTutorInteractionBundle?>(null)
    private val history = MutableStateFlow<List<CurrentTutorInteractionEventRecord>>(emptyList())
    var activationCalls: Int = 0
        private set
    var consumeDisposition: DatabaseOpenResponseConsumeDisposition =
        DatabaseOpenResponseConsumeDisposition.CONSUMED
    var applyTrustedAnswerCommands: Boolean = false
    var storedEvidenceRequest: TutorEvidenceRequest? = null
    val currentEvents: List<CurrentTutorInteractionEventRecord>
        get() = current.value?.events.orEmpty()
    val appendCommands = mutableListOf<AppendCurrentTutorInteractionCommand>()
    val consumeCommands = mutableListOf<ConsumeCurrentTutorOpenResponseAuthorizationCommand>()

    fun persistAnswerExposure() {
        val existing = checkNotNull(current.value)
        val nextVersion = existing.head.stateVersion + 1L
        val nextFingerprint = MessageDigest.getInstance("SHA-256")
            .digest("answer-exposure:$nextVersion".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val next = existing.copy(
            scope = existing.scope.copy(answerWasRevealed = true),
            head = existing.head.copy(
                stateVersion = nextVersion,
                stateFingerprint = nextFingerprint,
                updatedAtEpochMillis = existing.head.updatedAtEpochMillis + 1L,
            ),
        )
        current.value = next
    }

    override suspend fun activateCurrentTutorInteraction(
        command: ActivateCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionActivationResult {
        activationCalls += 1
        val existing = current.value
        if (existing?.scope?.activationFingerprint == command.activationFingerprint) {
            return CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.DUPLICATE,
                existing,
            )
        }
        if (existing != null && command.questionRevisionNumber <= existing.scope.questionRevisionNumber) {
            return CurrentTutorInteractionActivationResult(
                CurrentTutorInteractionActivationDisposition.STALE,
                existing,
            )
        }
        val next = command.toBundle((existing?.head?.stateVersion ?: -1L) + 1L)
        current.value = next
        return CurrentTutorInteractionActivationResult(
            CurrentTutorInteractionActivationDisposition.ACTIVATED,
            next,
        )
    }

    override suspend fun readCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): CurrentTutorInteractionBundle? =
        current.value?.takeIf {
            it.scope.learnerId == learnerId && it.scope.conversationId == conversationId
        }

    override fun observeCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): Flow<CurrentTutorInteractionBundle?> = current

    override fun observeTutorInteractionHistory(
        learnerId: String,
        conversationId: String,
    ): Flow<List<CurrentTutorInteractionEventRecord>> = history

    override suspend fun readTutorAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ): List<CurrentTutorInteractionEventRecord> = emptyList()

    override suspend fun appendCurrentTutorInteraction(
        command: AppendCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionAppendResult {
        appendCommands += command
        if (applyTrustedAnswerCommands) {
            val existing = checkNotNull(current.value)
            existing.events.singleOrNull { it.eventId == command.eventId }?.let { replay ->
                return CurrentTutorInteractionAppendResult(
                    disposition = CurrentTutorInteractionAppendDisposition.DUPLICATE,
                    head = existing.head,
                    event = replay,
                    recordedAtEpochMillis = command.occurredAtEpochMillis,
                )
            }
            if (
                existing.head.stateVersion != command.expectedStateVersion ||
                existing.head.stateFingerprint != command.expectedStateFingerprint
            ) {
                return CurrentTutorInteractionAppendResult(
                    disposition = CurrentTutorInteractionAppendDisposition.RELOAD_REQUIRED,
                    head = existing.head,
                    event = null,
                    recordedAtEpochMillis = command.occurredAtEpochMillis,
                )
            }
            val nextVersion = existing.head.stateVersion + 1L
            val nextFingerprint = sha256ForFake(
                "${existing.head.stateFingerprint}:${command.payloadFingerprint}:$nextVersion",
            )
            val nextAttemptOrdinal = existing.scope.attemptOrdinal + if (
                command.eventKind == CurrentTutorInteractionEventKind.CHOICE ||
                command.eventKind == CurrentTutorInteractionEventKind.VISUAL_SELECTION ||
                command.eventKind == CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM
            ) 1 else 0
            val nextHintCount = existing.scope.hintCount + if (
                command.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN
            ) 1 else 0
            val event = command.toFakeRecord(
                eventSequence = nextVersion,
                committedStateFingerprint = nextFingerprint,
                attemptOrdinal = nextAttemptOrdinal,
                hintCount = nextHintCount,
            )
            val nextHead = existing.head.copy(
                stateVersion = nextVersion,
                stateFingerprint = nextFingerprint,
                updatedAtEpochMillis = command.occurredAtEpochMillis,
            )
            val next = existing.copy(
                scope = existing.scope.copy(
                    attemptOrdinal = event.attemptOrdinal,
                    hintCount = event.hintCount,
                ),
                head = nextHead,
                events = existing.events + event,
            )
            current.value = next
            history.value = next.events
            return CurrentTutorInteractionAppendResult(
                disposition = CurrentTutorInteractionAppendDisposition.APPLIED,
                head = nextHead,
                event = event,
                recordedAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
        return CurrentTutorInteractionAppendResult(
            disposition = CurrentTutorInteractionAppendDisposition.REJECTED,
            head = current.value?.head,
            event = null,
            recordedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun consumeCurrentTutorOpenResponseAuthorization(
        command: ConsumeCurrentTutorOpenResponseAuthorizationCommand,
    ): CurrentTutorOpenResponseAuthorizationConsumeResult {
        consumeCommands += command
        return CurrentTutorOpenResponseAuthorizationConsumeResult(
            disposition = consumeDisposition,
            recordedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun openTutorEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorEvidenceRequest? = storedEvidenceRequest?.takeIf {
        learnerId == LEARNER_FOR_FAKE && it.evidenceRequestId == evidenceRequestId
    }

    private companion object {
        const val LEARNER_FOR_FAKE = "learner-current"
    }
}

private fun AppendCurrentTutorInteractionCommand.toFakeRecord(
    eventSequence: Long,
    committedStateFingerprint: String,
    attemptOrdinal: Int,
    hintCount: Int,
) = CurrentTutorInteractionEventRecord(
    eventId = eventId,
    scopeId = scopeId,
    learnerId = learnerId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    questionDocumentId = questionDocumentId,
    questionRevisionNumber = questionRevisionNumber,
    questionFingerprint = questionFingerprint,
    subject = subject,
    problemAnchorId = problemAnchorId,
    explanationMode = explanationMode,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    modeVersion = modeVersion,
    learningWritePermissionVersion = learningWritePermissionVersion,
    turnReferenceId = turnReferenceId,
    turnGeneration = turnGeneration,
    attemptOrdinal = attemptOrdinal,
    hintCount = hintCount,
    answerWasRevealed = answerWasRevealed,
    eventSequence = eventSequence,
    committedStateFingerprint = committedStateFingerprint,
    eventKind = eventKind,
    authorizationPurpose = authorizationPurpose,
    authorizationRequestId = authorizationRequestId,
    idempotencyKey = idempotencyKey,
    requestVersion = requestVersion,
    payloadFingerprint = payloadFingerprint,
    occurredAtEpochMillis = occurredAtEpochMillis,
    recordedAtEpochMillis = occurredAtEpochMillis,
    diagnosticStemMarkdown = diagnosticStemMarkdown,
    selectedChoiceId = selectedChoiceId,
    selectedChoiceMarkdown = selectedChoiceMarkdown,
    selectionWasCorrect = selectionWasCorrect,
    feedbackMarkdown = feedbackMarkdown,
    evidenceRequestId = evidenceRequestId,
    requestedMove = requestedMove,
    solutionRevealed = solutionRevealed,
    surfaceKind = surfaceKind,
    modelTaskRequestId = modelTaskRequestId,
    responseOrdinal = responseOrdinal,
    sceneSourceKind = sceneSourceKind,
    sceneTaskRequestId = sceneTaskRequestId,
    sceneId = sceneId,
    sceneFingerprint = sceneFingerprint,
    hitProofId = hitProofId,
    panelId = panelId,
    frameFingerprint = frameFingerprint,
    stepIndex = stepIndex,
    selectedTargetId = selectedTargetId,
    targetRevisionRef = targetRevisionRef,
    targetPracticeRef = targetPracticeRef,
    sourceKind = sourceKind,
)

private fun sha256ForFake(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ActivateCurrentTutorInteractionCommand.toBundle(
    stateVersion: Long,
) = CurrentTutorInteractionBundle(
    scope =
        CurrentTutorInteractionScopeRecord(
            scopeId = scopeId,
            learnerId = learnerId,
            conversationId = conversationId,
            conversationGeneration = conversationGeneration,
            conversationStateVersion = conversationStateVersion,
            authorityConversationId = authorityConversationId,
            authorityConversationGeneration = authorityConversationGeneration,
            authorityConversationStateVersion = authorityConversationStateVersion,
            authorityTurnReceiptId = authorityTurnReceiptId,
            authorityTurnOrdinal = authorityTurnOrdinal,
            authorityRequestVersion = authorityRequestVersion,
            questionDocumentId = questionDocumentId,
            questionRevisionNumber = questionRevisionNumber,
            questionDocumentSnapshot = questionDocumentSnapshot,
            questionFingerprint = questionFingerprint,
            subject = subject,
            problemAnchorId = problemAnchorId,
            explanationMode = explanationMode,
            modeVersion = modeVersion,
            turnReferenceId = turnReferenceId,
            turnOrdinal = turnOrdinal,
            turnGeneration = turnGeneration,
            cycleOrdinal = cycleOrdinal,
            attemptOrdinal = attemptOrdinal,
            hintCount = hintCount,
            answerWasRevealed = answerWasRevealed,
            requestVersion = requestVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
            presentationFingerprint = presentationFingerprint,
            problemFingerprint = problemFingerprint,
            problemFamilyFingerprint = problemFamilyFingerprint,
            attributionPolicyVersion = attributionPolicyVersion,
            responsePolicyVersion = responsePolicyVersion,
            rubricCanonicalFingerprint = rubricCanonicalFingerprint,
            knowledgeAuthorityFingerprint = knowledgeAuthorityFingerprint,
            evaluator = evaluator,
            evaluatorPolicyFingerprint = evaluatorPolicyFingerprint,
            activationFingerprint = activationFingerprint,
            createdAtEpochMillis = occurredAtEpochMillis,
        ),
    head =
        CurrentTutorInteractionHeadRecord(
            learnerId = learnerId,
            conversationId = conversationId,
            currentScopeId = scopeId,
            stateVersion = stateVersion,
            stateFingerprint = activationFingerprint,
            updatedAtEpochMillis = occurredAtEpochMillis,
        ),
    events = emptyList(),
)
