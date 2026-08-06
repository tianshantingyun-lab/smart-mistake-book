package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseLearningReceipt
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseCandidateCommitReceipt
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseSubmissionResult
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorFreeResponseSubmission
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorFreeResponseSubmissionPort
import com.tingyun.smartmistakebook.core.database.ClaimCurrentTutorFreeResponseActionCommand
import com.tingyun.smartmistakebook.core.database.AcquireCurrentTutorFreeResponseDispatchCommand
import com.tingyun.smartmistakebook.core.database.CompleteCurrentTutorFreeResponseDispatchCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseActionClaimDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseActionClaimQuery
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseActionClaimResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchAcquireResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchLease
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchMutationResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchState
import com.tingyun.smartmistakebook.core.database.FailCurrentTutorFreeResponseDispatchClosedCommand
import com.tingyun.smartmistakebook.core.database.ReleaseCurrentTutorFreeResponseDispatchCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkDatabasePort
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkRevocationReason
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkStatus
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkWriteDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkWriteResult
import com.tingyun.smartmistakebook.core.database.MarkCurrentTutorSessionHostWorkActiveCommand
import com.tingyun.smartmistakebook.core.database.PersistCurrentTutorSessionPolicyCommand
import com.tingyun.smartmistakebook.core.database.RevokeCurrentTutorSessionHostWorkCommand
import com.tingyun.smartmistakebook.core.database.StageCurrentTutorSessionHostWorkCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionPolicyRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionPolicyWriteResult
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintShownAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintShownResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseRetryAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionInteraction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyUpdate
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionRevocationReason
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetPreparation
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualTargetEvidence
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionChoice
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorResponseIntent
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProofRegistry
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.locallyConstrainedFor
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentTrustedSavedAnswerRulePort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewResponse
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerLease
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerPresentationRequest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerSubmissionResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.function.LongSupplier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTutorSessionHostCoordinatorTest {
    @Test
    fun `direct and guided sessions bind only executable constrained content before activation`() =
        runBlocking {
            TutorExplanationMode.entries.forEach { mode ->
                val fixture = HostFixture(mode)
                val owner = FakeActivationOwner()

                val result = fixture.coordinator(owner).activate(SESSION, REQUEST)

                assertTrue(result is TutorCurrentSessionHostResult.Ready)
                val work = checkNotNull(fixture.host.current())
                val activation = checkNotNull(owner.lastActivation)
                assertEquals(CurrentTutorSessionHostWorkStatus.ACTIVE, work.status)
                assertEquals(activation.presentationFingerprint, work.constrainedTutorContentFingerprint)
                assertEquals(0, fixture.learning.calls)
                assertNull(work.pendingInteractionKind)
                assertTrue(activation.verifiedKnowledgeProofs.isEmpty())
                assertTrue(activation.teachingReferences.isEmpty())
                assertFalse(work.payloadFingerprint.contains("answer", ignoreCase = true))
            }
        }

    @Test
    fun `prepare survives owner crash and exact resume is idempotent`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.DIRECT)
        val crashingOwner = FakeActivationOwner(crashNext = true)

        assertTrue(fixture.coordinator(crashingOwner).activate(SESSION, REQUEST) is
            TutorCurrentSessionHostResult.Unavailable)
        assertEquals(CurrentTutorSessionHostWorkStatus.STAGED, fixture.host.current()?.status)

        val recoveredOwner = FakeActivationOwner()
        val recovered = fixture.coordinator(recoveredOwner)
        val firstResume = recovered.resume(SESSION)
        val secondResume = recovered.resume(SESSION)

        assertTrue(firstResume is TutorCurrentSessionHostResult.Ready && firstResume.restored)
        assertTrue(secondResume is TutorCurrentSessionHostResult.Ready && secondResume.restored)
        assertEquals(2, recoveredOwner.calls)
        assertEquals(
            recoveredOwner.activationFingerprints.single(),
            fixture.host.current()?.targetActivationFingerprint,
        )
        assertEquals(CurrentTutorSessionHostWorkStatus.ACTIVE, fixture.host.current()?.status)
    }

    @Test
    fun `same request version cannot replace directive or presentation after prepare`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.DIRECT)
            fixture.coordinator(FakeActivationOwner(crashNext = true)).activate(SESSION, REQUEST)
            val prepared = checkNotNull(fixture.host.current())
            val originalContent = prepared.constrainedTutorContentFingerprint
            fixture.modelTask = fixture.modelTask(outputSeed = "replacement")
            val owner = FakeActivationOwner()

            val replaced = fixture.coordinator(owner).resume(SESSION)

            assertTrue(replaced is TutorCurrentSessionHostResult.Unavailable)
            assertEquals(0, owner.calls)
            assertEquals(originalContent, fixture.host.current()?.constrainedTutorContentFingerprint)
            assertNotEquals(
                originalContent,
                fixture.expectedConstrainedContentFingerprint(outputSeed = "replacement"),
            )
        }

    @Test
    fun `forged learner revision mode and presentation token fail closed`() = runBlocking {
        val revisionFixture = HostFixture(TutorExplanationMode.DIRECT)
        revisionFixture.session = revisionFixture.session.copy(draftRevisionNumber = 2)
        assertBlockedBeforeOwner(revisionFixture)

        val modeFixture = HostFixture(TutorExplanationMode.DIRECT)
        modeFixture.currentMode = TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 1)
        assertBlockedBeforeOwner(modeFixture)

        val learnerFixture = HostFixture(TutorExplanationMode.DIRECT)
        learnerFixture.coordinator(FakeActivationOwner(crashNext = true)).activate(SESSION, REQUEST)
        learnerFixture.host.mutate { record -> record.copy(learnerId = "forged-learner") }
        assertBlockedBeforeOwner(learnerFixture, resume = true)

        val tokenFixture = HostFixture(TutorExplanationMode.DIRECT)
        tokenFixture.coordinator(FakeActivationOwner(crashNext = true)).activate(SESSION, REQUEST)
        tokenFixture.host.mutate { record ->
            record.copy(presentationToken = sha256("forged-presentation-token"))
        }
        assertBlockedBeforeOwner(tokenFixture, resume = true)
    }

    @Test
    fun `revoke is terminal for late resume and duplicate revoke`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        val owner = FakeActivationOwner()
        val coordinator = fixture.coordinator(owner)
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val callsBeforeRevoke = owner.calls

        val revoked = coordinator.revoke(
            SESSION,
            TutorCurrentSessionRevocationReason.LEARNING_WRITES_DISABLED,
        )
        val duplicate = coordinator.revoke(
            SESSION,
            TutorCurrentSessionRevocationReason.LEARNING_WRITES_DISABLED,
        )
        val lateResume = coordinator.resume(SESSION)

        assertTrue(revoked is TutorCurrentSessionHostResult.Revoked)
        assertTrue(duplicate is TutorCurrentSessionHostResult.Revoked)
        assertTrue(lateResume is TutorCurrentSessionHostResult.Revoked)
        assertEquals(callsBeforeRevoke, owner.calls)
        assertEquals(1, owner.revokedScopes.size)
        assertEquals(false, fixture.host.current()?.learningWritesAllowed)
    }

    @Test
    fun `write-disabled session presents the solution without creating learning records`() =
        runBlocking {
            val fixture = HostFixture(
                mode = TutorExplanationMode.GUIDED,
                learningWritesAllowed = false,
            )
            val owner = FakeActivationOwner()
            val coordinator = fixture.coordinator(owner)

            val result = coordinator.activate(SESSION, REQUEST)
            val presentation = coordinator.observePresentation(SESSION).first()

            assertTrue(result is TutorCurrentSessionHostResult.Ready)
            assertEquals(0, fixture.learning.calls)
            assertEquals(false, owner.lastActivation?.learningWritesAllowed)
            assertNull(fixture.host.current()?.pendingInteractionKind)
            assertEquals(false, presentation?.learningWritesAllowed)
            assertNull(presentation?.interaction)
            assertTrue(
                presentation?.text.orEmpty().any { text ->
                    text.kind == com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionTextKind.SOLUTION
                },
            )
        }

    @Test
    fun `choice action accepts only opaque current-presentation identifiers`() {
        val fields = TutorCurrentSessionChoiceAction::class.java.declaredFields
            .filterNot { field -> field.isSynthetic }
            .mapTo(sortedSetOf()) { field -> field.name }

        assertEquals(
            sortedSetOf("choiceId", "presentationToken", "sessionId"),
            fields,
        )
    }

    @Test
    fun `untrusted model authored choice stays a local checkpoint without creating learning evidence`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.modelTask = with(fixture) {
            modelTaskWithChoiceDirective().withGuidedEvidenceLabel()
        }
        val owner = FakeActivationOwner()
        val coordinator = fixture.coordinator(owner)

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())

        assertNull(presentation.interaction)
        assertTrue(presentation.text.all { text ->
            text.kind != com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionTextKind.SOLUTION
        })
        assertEquals(0, fixture.learning.calls)
        assertNull(fixture.host.current()?.evidenceRequestId)
        assertNull(fixture.host.current()?.pendingInteractionKind)
        assertTrue(checkNotNull(owner.lastActivation).verifiedKnowledgeProofs.isEmpty())
    }

    @Test
    fun `v2 choice directive is shown only with exact trusted answer authority`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeChoiceDirectiveEvidence()
        val coordinator = fixture.coordinator(FakeActivationOwner())

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
        val interaction = presentation.interaction as TutorCurrentSessionInteraction.Choices
        assertEquals(listOf("choice-a", "choice-b"), interaction.choices.map { it.choiceId })

        val result = coordinator.submitChoice(
            TutorCurrentSessionChoiceAction(
                sessionId = SESSION,
                presentationToken = presentation.presentationToken,
                choiceId = "choice-b",
            ),
        )

        assertTrue(result is TutorCurrentSessionChoiceActionResult.Answered)
        assertEquals(
            listOf(StudentTrustedReviewResponse.Choice("choice-b")),
            fixture.submittedTrustedAnswers,
        )
    }

    @Test
    fun `v2 choice directive without trusted answer authority remains presentation only`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.GUIDED)
            fixture.modelTask = with(fixture) {
                modelTaskWithChoiceDirective().withGuidedEvidenceLabel()
            }
            val coordinator = fixture.coordinator(FakeActivationOwner())

            assertTrue(
                coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready,
            )
            val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())

            assertNull(presentation.interaction)
            assertTrue(presentation.text.all { text ->
                text.kind != com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionTextKind.SOLUTION
            })
            assertNull(fixture.host.current()?.pendingInteractionKind)
        }

    @Test
    fun `free response claim survives dispatch crash and exact retry dispatches once`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(
                listOf(
                    CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
                    CoreDataTutorOpenResponseLearningReceipt.PENDING,
                ),
            ),
        )
        val first = fixture.coordinator(FakeActivationOwner(), freeResponseSubmission = sink)

        assertTrue(first.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val firstInteraction = checkNotNull(first.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse
        val firstResult = first.submitFreeResponse(
            TutorCurrentSessionFreeResponseAction(
                sessionId = SESSION,
                actionToken = firstInteraction.actionToken,
                answer = "E = nBSω",
            ),
        )
        assertTrue(firstResult is TutorCurrentSessionFreeResponseActionResult.Rejected)
        fixture.clockEpochMillis += FREE_RESPONSE_TTL_MILLIS + 1L

        val recovered = fixture.coordinator(FakeActivationOwner(), freeResponseSubmission = sink)
        assertTrue(recovered.resume(SESSION) is TutorCurrentSessionHostResult.Ready)
        val recoveredInteraction =
            checkNotNull(recovered.observePresentation(SESSION).first()).interaction
                as TutorCurrentSessionInteraction.FreeResponse
        assertEquals(firstInteraction.actionToken, recoveredInteraction.actionToken)
        assertEquals(
            TutorCurrentSessionFreeResponseStatus.COMPLETED,
            recoveredInteraction.submissionStatus,
        )
        assertEquals(2, sink.calls)
        assertEquals(1, sink.uniqueEvidenceKeys.size)
    }

    @Test
    fun `free response submission uses fresh persisted hint and reveal facts`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        fixture.addGuidedHint("先只写出磁通量随时间变化的关系。")
        val owner = FakeActivationOwner()
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
        )
        val coordinator = fixture.coordinator(owner, freeResponseSubmission = sink)
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
        val interaction = presentation.interaction as TutorCurrentSessionInteraction.FreeResponse
        val hint = checkNotNull(presentation.hint)
        assertEquals(TutorCurrentSessionHintStatus.AVAILABLE, hint.status)

        val hintAction = TutorCurrentSessionHintShownAction(
            sessionId = SESSION,
            presentationToken = presentation.presentationToken,
            slotToken = hint.slotToken,
        )
        assertEquals(
            TutorCurrentSessionHintShownResult.Recorded,
            coordinator.recordHintShown(hintAction),
        )
        assertEquals(
            TutorCurrentSessionHintShownResult.Duplicate,
            coordinator.recordHintShown(hintAction),
        )
        assertEquals(
            TutorCurrentSessionHintStatus.SHOWN,
            checkNotNull(coordinator.observePresentation(SESSION).first()).hint?.status,
        )
        assertEquals(0, checkNotNull(owner.currentEvidenceState(SESSION)).attemptOrdinal)
        assertEquals(
            TutorCurrentSessionFreeResponseActionResult.Accepted,
            coordinator.submitFreeResponse(
                TutorCurrentSessionFreeResponseAction(
                    sessionId = SESSION,
                    actionToken = interaction.actionToken,
                    answer = "E = nBSω",
                ),
            ),
        )

        val persisted = sink.submissions.single().contextRequest
        assertEquals(1, persisted.attemptOrdinal)
        assertEquals(1, persisted.hintCount)
        assertFalse(persisted.answerWasRevealed)
    }

    @Test
    fun `hint slot rejects forged second and stale actions without changing attempts`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        fixture.addGuidedHint("先判断磁通量变化最快的位置。")
        val owner = FakeActivationOwner()
        val coordinator = fixture.coordinator(
            owner = owner,
            freeResponseSubmission = FakeFreeResponseSubmissionPort(
                receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
            ),
        )
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
        val hint = checkNotNull(presentation.hint)

        assertTrue(
            coordinator.recordHintShown(
                TutorCurrentSessionHintShownAction(
                    sessionId = SESSION,
                    presentationToken = presentation.presentationToken,
                    slotToken = sha256("forged-hint-slot"),
                ),
            ) is TutorCurrentSessionHintShownResult.Rejected,
        )
        assertEquals(0, checkNotNull(owner.currentEvidenceState(SESSION)).hintCount)

        val valid = TutorCurrentSessionHintShownAction(
            sessionId = SESSION,
            presentationToken = presentation.presentationToken,
            slotToken = hint.slotToken,
        )
        assertEquals(TutorCurrentSessionHintShownResult.Recorded, coordinator.recordHintShown(valid))
        assertEquals(0, checkNotNull(owner.currentEvidenceState(SESSION)).attemptOrdinal)
        assertTrue(
            coordinator.recordHintShown(
                valid.copy(slotToken = sha256("different-second-hint")),
            ) is TutorCurrentSessionHintShownResult.Rejected,
        )
        owner.recordAnswerExposure()
        assertNull(coordinator.observePresentation(SESSION).first()?.hint)
        assertTrue(coordinator.recordHintShown(valid) is TutorCurrentSessionHintShownResult.Rejected)
        assertEquals(1, checkNotNull(owner.currentEvidenceState(SESSION)).hintCount)
    }

    @Test
    fun `one current-step hint remains available after an earlier wrong attempt`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        fixture.addGuidedHint("先回到磁通量的定义，再检查变化率。")
        val owner = FakeActivationOwner()
        val coordinator = fixture.coordinator(
            owner = owner,
            freeResponseSubmission = FakeFreeResponseSubmissionPort(
                receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
            ),
        )
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        owner.recordStudentSubmission()

        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
        val hint = checkNotNull(presentation.hint)
        assertEquals(TutorCurrentSessionHintStatus.AVAILABLE, hint.status)
        assertEquals(
            TutorCurrentSessionHintShownResult.Recorded,
            coordinator.recordHintShown(
                TutorCurrentSessionHintShownAction(
                    sessionId = SESSION,
                    presentationToken = presentation.presentationToken,
                    slotToken = hint.slotToken,
                ),
            ),
        )
        val evidenceState = checkNotNull(owner.currentEvidenceState(SESSION))
        assertEquals(1, evidenceState.attemptOrdinal)
        assertEquals(1, evidenceState.hintCount)
    }

    @Test
    fun `more than one knowledge authority cannot multiply current-step evidence`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.GUIDED)
            fixture.authorizeFreeResponseEvidence(
                knowledgeNodeIds = listOf(
                    "physics.electromagnetic.induction",
                    "physics.electromagnetic.flux",
                ),
            )
            val owner = FakeActivationOwner()
            val coordinator = fixture.coordinator(
                owner = owner,
                freeResponseSubmission = FakeFreeResponseSubmissionPort(
                    receipts = ArrayDeque(
                        listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING),
                    ),
                ),
            )

            assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
            val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())

            assertNull(presentation.interaction)
            assertNull(fixture.host.current()?.pendingInteractionKind)
            assertTrue(checkNotNull(owner.lastActivation).verifiedKnowledgeProofs.isEmpty())
        }

    @Test
    fun `answer exposure racing a free response submission fails the outbox closed`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        val owner = FakeActivationOwner()
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
            beforeCurrentCheck = { owner.recordAnswerExposure() },
        )
        val coordinator = fixture.coordinator(owner, freeResponseSubmission = sink)
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val interaction = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse

        val result = coordinator.submitFreeResponse(
            TutorCurrentSessionFreeResponseAction(
                sessionId = SESSION,
                actionToken = interaction.actionToken,
                answer = "E = nBSω",
            ),
        )

        assertTrue(result is TutorCurrentSessionFreeResponseActionResult.Rejected)
        assertEquals(listOf(true), sink.currentChecks)
        assertEquals(CurrentTutorFreeResponseDispatchState.FAILED_CLOSED, fixture.host.outboxState(
            interaction.actionToken,
        ))
    }

    @Test
    fun `process restart reclaims an abandoned dispatch lease and completes exactly once`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
        )
        val first = fixture.coordinator(
            owner = FakeActivationOwner(),
            freeResponseSubmission = sink,
            freeResponseDispatchGenerationId = "process-generation-one",
        )
        assertTrue(first.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val interaction = checkNotNull(first.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse
        fixture.host.crashAfterNextFreeResponseAcquire = true

        assertThrows(FakeProcessDeath::class.java) {
            runBlocking {
                first.submitFreeResponse(
                    TutorCurrentSessionFreeResponseAction(
                        sessionId = SESSION,
                        actionToken = interaction.actionToken,
                        answer = "E = nBSω",
                    ),
                )
            }
        }
        assertEquals(0, sink.calls)
        fixture.clockEpochMillis += 1L

        val recovered = fixture.coordinator(
            owner = FakeActivationOwner(),
            freeResponseSubmission = sink,
            freeResponseDispatchGenerationId = "process-generation-two",
        )
        assertTrue(recovered.resume(SESSION) is TutorCurrentSessionHostResult.Ready)
        val recoveredInteraction =
            checkNotNull(recovered.observePresentation(SESSION).first()).interaction
                as TutorCurrentSessionInteraction.FreeResponse

        assertEquals(TutorCurrentSessionFreeResponseStatus.COMPLETED, recoveredInteraction.submissionStatus)
        assertEquals(1, sink.calls)
        assertEquals(1, sink.uniqueEvidenceKeys.size)
    }

    @Test
    fun `retry capability dispatches encrypted pending answer without answer resubmission`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(
                listOf(
                    CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
                    CoreDataTutorOpenResponseLearningReceipt.PENDING,
                ),
            ),
        )
        val coordinator = fixture.coordinator(FakeActivationOwner(), freeResponseSubmission = sink)
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val initial = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse

        assertTrue(
            coordinator.submitFreeResponse(
                TutorCurrentSessionFreeResponseAction(
                    sessionId = SESSION,
                    actionToken = initial.actionToken,
                    answer = "E = nBSω",
                ),
            ) is TutorCurrentSessionFreeResponseActionResult.Rejected,
        )
        val retryable = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse
        assertEquals(TutorCurrentSessionFreeResponseStatus.RETRY_AVAILABLE, retryable.submissionStatus)

        assertEquals(
            TutorCurrentSessionFreeResponseActionResult.Duplicate,
            coordinator.retryFreeResponse(
                TutorCurrentSessionFreeResponseRetryAction(
                    sessionId = SESSION,
                    actionToken = retryable.actionToken,
                ),
            ),
        )
        assertEquals(2, sink.calls)
        assertEquals(1, sink.uniqueEvidenceKeys.size)
    }

    @Test
    fun `first free response claim rejects exact expiry without durable outbox`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        val coordinator = fixture.coordinator(
            FakeActivationOwner(),
            freeResponseSubmission = FakeFreeResponseSubmissionPort(ArrayDeque()),
        )
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val interaction = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse
        fixture.clockEpochMillis = NOW + FREE_RESPONSE_TTL_MILLIS

        assertTrue(
            coordinator.submitFreeResponse(
                TutorCurrentSessionFreeResponseAction(
                    sessionId = SESSION,
                    actionToken = interaction.actionToken,
                    answer = "E = nBSω",
                ),
            ) is TutorCurrentSessionFreeResponseActionResult.Rejected,
        )
        assertEquals(0, fixture.host.freeResponseClaimCount)
    }

    @Test
    fun `free response claimed before ttl remains current after slow evaluation`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        lateinit var coordinator: CurrentTutorSessionHostCoordinator
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
            beforeCurrentCheck = {
                fixture.clockEpochMillis = NOW + FREE_RESPONSE_TTL_MILLIS + 1L
                val claimedInteraction =
                    checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
                        as TutorCurrentSessionInteraction.FreeResponse
                assertEquals(
                    TutorCurrentSessionFreeResponseStatus.SENDING,
                    claimedInteraction.submissionStatus,
                )
            },
        )
        coordinator = fixture.coordinator(
            FakeActivationOwner(),
            freeResponseSubmission = sink,
        )

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val interaction = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse
        fixture.clockEpochMillis = NOW + FREE_RESPONSE_TTL_MILLIS - 1L

        assertEquals(
            TutorCurrentSessionFreeResponseActionResult.Accepted,
            coordinator.submitFreeResponse(
                TutorCurrentSessionFreeResponseAction(
                    sessionId = SESSION,
                    actionToken = interaction.actionToken,
                    answer = "E = nBSω",
                ),
            ),
        )
        assertEquals(listOf(true), sink.currentChecks)
    }

    @Test
    fun `mode change after free response claim invalidates slow evaluation`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        val policy = FakeHostPolicyController(
            TutorCurrentSessionPolicySnapshot(
                sessionId = SESSION,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 1L,
                learningWritesAllowed = true,
                learningWritePermissionVersion = 1L,
            ),
        )
        lateinit var coordinator: CurrentTutorSessionHostCoordinator
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
            beforeCurrentCheck = {
                assertTrue(
                    coordinator.updatePolicy(
                        TutorCurrentSessionPolicyUpdate(
                            sessionId = SESSION,
                            explanationMode = TutorExplanationMode.DIRECT,
                            learningWritesAllowed = true,
                        ),
                    ) is TutorCurrentSessionPolicyResult.Current,
                )
            },
        )
        coordinator = fixture.coordinator(
            FakeActivationOwner(),
            policyController = policy,
            freeResponseSubmission = sink,
        )

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val interaction = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse

        assertTrue(
            coordinator.submitFreeResponse(
                TutorCurrentSessionFreeResponseAction(
                    sessionId = SESSION,
                    actionToken = interaction.actionToken,
                    answer = "E = nBSω",
                ),
            ) is TutorCurrentSessionFreeResponseActionResult.Rejected,
        )
        assertEquals(listOf(false), sink.currentChecks)
    }

    @Test
    fun `revocation after free response claim invalidates slow evaluation`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeFreeResponseEvidence()
        lateinit var coordinator: CurrentTutorSessionHostCoordinator
        val sink = FakeFreeResponseSubmissionPort(
            receipts = ArrayDeque(listOf(CoreDataTutorOpenResponseLearningReceipt.PENDING)),
            beforeCurrentCheck = {
                assertTrue(
                    coordinator.revoke(
                        sessionId = SESSION,
                        reason = TutorCurrentSessionRevocationReason.NEW_QUESTION,
                    ) is TutorCurrentSessionHostResult.Revoked,
                )
            },
        )
        coordinator = fixture.coordinator(
            FakeActivationOwner(),
            freeResponseSubmission = sink,
        )

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val interaction = checkNotNull(coordinator.observePresentation(SESSION).first()).interaction
            as TutorCurrentSessionInteraction.FreeResponse

        assertTrue(
            coordinator.submitFreeResponse(
                TutorCurrentSessionFreeResponseAction(
                    sessionId = SESSION,
                    actionToken = interaction.actionToken,
                    answer = "E = nBSω",
                ),
            ) is TutorCurrentSessionFreeResponseActionResult.Rejected,
        )
        assertEquals(listOf(false), sink.currentChecks)
    }

    @Test
    fun `model visual target remains a local checkpoint without a host issued target proof`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.modelTask = fixture.modelTaskWithVisualTarget()
        val coordinator = fixture.coordinator(FakeActivationOwner())

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())

        assertNull(presentation.interaction)
        assertTrue(presentation.text.all { text ->
            text.kind != com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionTextKind.SOLUTION
        })
        assertNull(fixture.host.current()?.pendingInteractionKind)
        assertEquals(0, fixture.learning.calls)
    }

    @Test
    fun `activate and policy update serialize without leaving stale active work`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.authorizeChoiceEvidence()
        val owner = BlockingActivationOwner()
        val policy = FakeHostPolicyController(
            TutorCurrentSessionPolicySnapshot(
                sessionId = SESSION,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 1,
                learningWritesAllowed = true,
                learningWritePermissionVersion = 1,
            ),
        ) { snapshot ->
            fixture.currentMode = TutorExplanationModeSnapshot(
                mode = snapshot.explanationMode,
                modeVersion = snapshot.modeVersion,
            )
        }
        val coordinator = fixture.coordinator(owner, policy)
        val activation = async { coordinator.activate(SESSION, REQUEST) }
        withTimeout(5_000) { owner.activationEntered.await() }

        val updateStarted = CompletableDeferred<Unit>()
        val update = async {
            updateStarted.complete(Unit)
            coordinator.updatePolicy(
                TutorCurrentSessionPolicyUpdate(
                    sessionId = SESSION,
                    explanationMode = TutorExplanationMode.DIRECT,
                    learningWritesAllowed = true,
                ),
            )
        }
        withTimeout(5_000) { updateStarted.await() }
        yield()

        assertFalse(update.isCompleted)
        owner.releaseActivation.complete(Unit)
        assertTrue(withTimeout(5_000) { activation.await() } is TutorCurrentSessionHostResult.Ready)
        val policyResult = withTimeout(5_000) { update.await() }

        assertTrue(policyResult is TutorCurrentSessionPolicyResult.Current)
        assertEquals(TutorExplanationMode.DIRECT, policy.currentPolicy(SESSION)?.explanationMode)
        assertEquals(CurrentTutorSessionHostWorkStatus.REVOKED, fixture.host.current()?.status)
        assertNull(coordinator.observePresentation(SESSION).first())
        assertEquals(1, owner.revokedScopes.size)
        assertEquals(
            setOf(checkNotNull(fixture.host.current()?.evidenceRequestId)),
            fixture.interactions.cancelledEvidenceIds,
        )
        assertTrue(fixture.interactions.choiceCommands.isEmpty())
        assertTrue(fixture.interactions.visualCommands.isEmpty())
    }

    @Test
    fun `policy update fails closed when active-work revocation is rejected`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.DIRECT)
        val owner = FakeActivationOwner()
        val policy = FakeHostPolicyController(
            TutorCurrentSessionPolicySnapshot(
                sessionId = SESSION,
                explanationMode = TutorExplanationMode.DIRECT,
                modeVersion = 1L,
                learningWritesAllowed = true,
                learningWritePermissionVersion = 1L,
            ),
        )
        val coordinator = fixture.coordinator(owner, policy)
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        fixture.host.rejectNextRevoke = true

        val result = coordinator.updatePolicy(
            TutorCurrentSessionPolicyUpdate(
                sessionId = SESSION,
                explanationMode = TutorExplanationMode.DIRECT,
                learningWritesAllowed = true,
                visualIntent = TutorCurrentSessionVisualIntent.USER_EXPLICIT,
            ),
        )

        assertEquals(TutorCurrentSessionPolicyResult.Unavailable, result)
        assertEquals(
            TutorCurrentSessionVisualIntent.NONE,
            policy.currentPolicy(SESSION)?.visualIntent,
        )
        assertEquals(CurrentTutorSessionHostWorkStatus.ACTIVE, fixture.host.current()?.status)
        assertTrue(owner.revokedScopes.isEmpty())
    }

    @Test
    fun `choice submission and policy revocation share one session linearization point`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.GUIDED)
            fixture.authorizeChoiceEvidence()
            val submissionEntered = CompletableDeferred<Unit>()
            val releaseSubmission = CompletableDeferred<Unit>()
            fixture.beforeTrustedAnswerCommit = {
                submissionEntered.complete(Unit)
                releaseSubmission.await()
            }
            val policy = FakeHostPolicyController(
                TutorCurrentSessionPolicySnapshot(
                    sessionId = SESSION,
                    explanationMode = TutorExplanationMode.GUIDED,
                    modeVersion = 1L,
                    learningWritesAllowed = true,
                    learningWritePermissionVersion = 1L,
                ),
            )
            val coordinator = fixture.coordinator(
                owner = FakeActivationOwner(),
                policyController = policy,
            )
            assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
            val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
            val submitted = async {
                coordinator.submitChoice(
                    TutorCurrentSessionChoiceAction(
                        sessionId = SESSION,
                        presentationToken = presentation.presentationToken,
                        choiceId = "choice-a",
                    ),
                )
            }
            withTimeout(5_000L) { submissionEntered.await() }
            val changed = async {
                coordinator.updatePolicy(
                    TutorCurrentSessionPolicyUpdate(
                        sessionId = SESSION,
                        explanationMode = TutorExplanationMode.DIRECT,
                        learningWritesAllowed = true,
                    ),
                )
            }
            yield()
            assertFalse(changed.isCompleted)

            releaseSubmission.complete(Unit)
            assertTrue(
                withTimeout(5_000L) { submitted.await() } is
                    TutorCurrentSessionChoiceActionResult.Answered,
            )
            assertTrue(
                withTimeout(5_000L) { changed.await() } is TutorCurrentSessionPolicyResult.Current,
            )
            assertEquals(CurrentTutorSessionHostWorkStatus.REVOKED, fixture.host.current()?.status)
            assertEquals(
                listOf(StudentTrustedReviewResponse.Choice("choice-a")),
                fixture.submittedTrustedAnswers,
            )
            assertTrue(fixture.interactions.cancelledEvidenceIds.isEmpty())
        }

    @Test
    fun `trusted choice submission never writes correctness into the session repository`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.GUIDED)
            fixture.authorizeChoiceEvidence()
            val coordinator = fixture.coordinator(FakeActivationOwner())

            assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
            val work = checkNotNull(fixture.host.current())
            val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())

            assertEquals(TutorEvidenceRequestKind.CHOICE, work.pendingInteractionKind)
            assertTrue(presentation.interaction is TutorCurrentSessionInteraction.Choices)
            val result = coordinator.submitChoice(
                TutorCurrentSessionChoiceAction(
                    sessionId = SESSION,
                    presentationToken = presentation.presentationToken,
                    choiceId = "choice-a",
                ),
            )

            assertTrue(result is TutorCurrentSessionChoiceActionResult.Answered)
            assertEquals(
                "已记录。",
                (result as TutorCurrentSessionChoiceActionResult.Answered)
                    .feedback.feedbackMarkdown,
            )
            assertTrue(fixture.interactions.choiceCommands.isEmpty())
            assertEquals(
                listOf(StudentTrustedReviewResponse.Choice("choice-a")),
                fixture.submittedTrustedAnswers,
            )
            assertEquals(
                listOf("choice-a", "choice-b"),
                (((fixture.modelTask.output as TutorPlanOutput).plan.guidedInteractionProposal
                    ?.directive) as TutorInteractionDirective.Choices)
                    .choices.map(TutorInteractionChoice::id),
            )
        }

    @Test
    fun `answer exposure during trusted choice submission rejects without cancelling evidence`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.GUIDED)
            fixture.authorizeChoiceEvidence()
            val owner = FakeActivationOwner()
            fixture.beforeTrustedAnswerSubmission = owner::recordAnswerExposure
            val coordinator = fixture.coordinator(owner)
            assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
            val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
            val evidenceRequestId = checkNotNull(fixture.host.current()?.evidenceRequestId)

            val result = coordinator.submitChoice(
                TutorCurrentSessionChoiceAction(
                    sessionId = SESSION,
                    presentationToken = presentation.presentationToken,
                    choiceId = "choice-a",
                ),
            )

            assertTrue(result is TutorCurrentSessionChoiceActionResult.Rejected)
            assertFalse(evidenceRequestId in fixture.interactions.cancelledEvidenceIds)
            assertTrue(fixture.submittedTrustedAnswers.isEmpty())
        }

    @Test
    fun `trusted visual target submission never writes correctness into the session repository`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        val scene = fixture.authorizeVisualTargetEvidence()
        val coordinator = fixture.coordinator(FakeActivationOwner())

        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val work = checkNotNull(fixture.host.current())
        val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
        val identity = TutorVisualPresentationIdentity(
            ownerModelTaskRequestId = REQUEST,
            sourceKind = TutorVisualSceneSourceKind.GENERATED,
            sceneTaskRequestId = fixture.visualTaskRequestId,
            sceneId = scene.sceneId,
            sceneFingerprint = TutorVisualSceneFingerprint.of(scene),
        )
        val hitProof = TutorVisualHitProofRegistry.issue(
            presentation = identity,
            panelId = "panel",
            frameFingerprint = sha256("visual-frame"),
            stepIndex = 0,
            selectedTargetId = "target-a",
            eligibleTargetIds = setOf("target-a", "target-b"),
        )

        assertEquals(TutorEvidenceRequestKind.VISUAL_TARGET, work.pendingInteractionKind)
        assertTrue(presentation.interaction is TutorCurrentSessionInteraction.VisualTarget)
        val prepared = coordinator.prepareVisualTarget(
            TutorCurrentSessionVisualTargetPreparation(
                sessionId = SESSION,
                presentationToken = presentation.presentationToken,
                hitProof = hitProof,
            ),
        )
        assertTrue(prepared is TutorCurrentSessionVisualTargetPreparationResult.Ready)
        val actionToken =
            (prepared as TutorCurrentSessionVisualTargetPreparationResult.Ready).prepared.actionToken
        val result = coordinator.submitVisualTarget(
            TutorCurrentSessionVisualTargetAction(
                sessionId = SESSION,
                actionToken = actionToken,
                targetId = "target-a",
            ),
        )

        assertTrue(result is TutorCurrentSessionVisualTargetActionResult.Answered)
        assertTrue(fixture.interactions.visualCommands.isEmpty())
        assertEquals(
            listOf(StudentTrustedReviewResponse.VisualTarget("target-a")),
            fixture.submittedTrustedAnswers,
        )
    }

    @Test
    fun `answer exposure during trusted visual submission rejects without cancelling evidence`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.GUIDED)
            val scene = fixture.authorizeVisualTargetEvidence()
            val owner = FakeActivationOwner()
            fixture.beforeTrustedAnswerSubmission = owner::recordAnswerExposure
            val coordinator = fixture.coordinator(owner)
            assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
            val presentation = checkNotNull(coordinator.observePresentation(SESSION).first())
            val evidenceRequestId = checkNotNull(fixture.host.current()?.evidenceRequestId)
            val identity = TutorVisualPresentationIdentity(
                ownerModelTaskRequestId = REQUEST,
                sourceKind = TutorVisualSceneSourceKind.GENERATED,
                sceneTaskRequestId = fixture.visualTaskRequestId,
                sceneId = scene.sceneId,
                sceneFingerprint = TutorVisualSceneFingerprint.of(scene),
            )
            val proof = TutorVisualHitProofRegistry.issue(
                presentation = identity,
                panelId = "panel",
                frameFingerprint = sha256("visual-exposure-frame"),
                stepIndex = 0,
                selectedTargetId = "target-a",
                eligibleTargetIds = setOf("target-a", "target-b"),
            )
            val prepared = coordinator.prepareVisualTarget(
                TutorCurrentSessionVisualTargetPreparation(
                    sessionId = SESSION,
                    presentationToken = presentation.presentationToken,
                    hitProof = proof,
                ),
            ) as TutorCurrentSessionVisualTargetPreparationResult.Ready

            val result = coordinator.submitVisualTarget(
                TutorCurrentSessionVisualTargetAction(
                    sessionId = SESSION,
                    actionToken = prepared.prepared.actionToken,
                    targetId = "target-a",
                ),
            )

            assertTrue(result is TutorCurrentSessionVisualTargetActionResult.Rejected)
            assertFalse(evidenceRequestId in fixture.interactions.cancelledEvidenceIds)
            assertTrue(fixture.submittedTrustedAnswers.isEmpty())
        }

    @Test
    fun `production policy owner restores the durable current-session epochs`() = runBlocking {
        val persisted = TutorCurrentSessionPolicySnapshot(
            sessionId = SESSION,
            explanationMode = TutorExplanationMode.GUIDED,
            modeVersion = 7L,
            learningWritesAllowed = true,
            learningWritePermissionVersion = 4L,
            visualIntent = TutorCurrentSessionVisualIntent.USER_EXPLICIT,
            visualIntentVersion = 3L,
        )
        val owner = ProductionCurrentTutorPolicyOwner(
            questions = CurrentTutorQuestionSource { confirmedSession() },
            persistedPolicies = CurrentTutorPersistedPolicySource { persisted },
        )

        assertEquals(persisted.explanationMode, owner.currentMode(SESSION).mode)
        assertEquals(persisted.modeVersion, owner.currentMode(SESSION).modeVersion)
        assertEquals(persisted.learningWritesAllowed, owner.current(SESSION)?.allowed)
        assertEquals(
            persisted.learningWritePermissionVersion,
            owner.current(SESSION)?.permissionVersion,
        )
        assertEquals(persisted, owner.currentPolicy(SESSION))
    }

    @Test
    fun `presentation rejects a visual epoch that differs from durable policy`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.DIRECT)
            val owner = FakeActivationOwner()
            val mismatchedPolicy = FakeHostPolicyController(
                TutorCurrentSessionPolicySnapshot(
                    sessionId = SESSION,
                    explanationMode = TutorExplanationMode.DIRECT,
                    modeVersion = 1L,
                    learningWritesAllowed = true,
                    learningWritePermissionVersion = 1L,
                    visualIntent = TutorCurrentSessionVisualIntent.USER_EXPLICIT,
                    visualIntentVersion = 2L,
                ),
            )

            val result = fixture.coordinator(owner, mismatchedPolicy).activate(SESSION, REQUEST)

            assertTrue(result is TutorCurrentSessionHostResult.Ready)
            assertEquals(
                TutorCurrentSessionVisualIntent.USER_EXPLICIT,
                checkNotNull(fixture.host.current()).visualIntent,
            )
            fixture.host.mutate { work ->
                work.copy(
                    visualIntent = TutorCurrentSessionVisualIntent.NONE,
                    visualIntentVersion = 0L,
                )
            }
            assertNull(
                fixture.coordinator(owner, mismatchedPolicy)
                    .observePresentation(SESSION)
                    .first(),
            )
        }


    @Test
    fun `activation rejects a durable mode epoch instead of synthesizing a fallback`() =
        runBlocking {
            val fixture = HostFixture(TutorExplanationMode.DIRECT)
            val owner = FakeActivationOwner()
            val mismatchedPolicy = FakeHostPolicyController(
                TutorCurrentSessionPolicySnapshot(
                    sessionId = SESSION,
                    explanationMode = TutorExplanationMode.DIRECT,
                    modeVersion = 2L,
                    learningWritesAllowed = true,
                    learningWritePermissionVersion = 1L,
                ),
            )

            val result = fixture.coordinator(owner, mismatchedPolicy).activate(SESSION, REQUEST)

            assertTrue(result is TutorCurrentSessionHostResult.Unavailable)
            assertEquals(0, owner.calls)
            assertNull(fixture.host.current())
        }

    @Test
    fun `trusted answer authority binds leases to exact visible content and fails closed without one`() = runBlocking {
        val savedSession = confirmedSession().copy(
            isSaved = true,
            errorBookEntryId = "entry-current-host",
            savedProblemRevisionId = "revision-current-host",
        )
        val task = HostFixture(TutorExplanationMode.GUIDED).modelTaskWithChoiceDirective()
        val input = task.request.input as TutorPlanInput
        val output = task.output as TutorPlanOutput
        val query = CurrentTutorVerifiedMaterialQuery(
            learnerId = LEARNER,
            session = savedSession,
            subject = SubjectKind.PHYSICS,
            task = task,
            input = input,
            output = output,
            currentStepKnowledgeAuthorityRequired = true,
        )
        val requests = mutableListOf<StudentTrustedSavedAnswerPresentationRequest>()
        val authority = ProductionCurrentTutorVerifiedAnswerAuthority(
            object : LearnerBoundStudentTrustedSavedAnswerRulePort {
                override val learnerId: String = LEARNER

                override suspend fun issueExactLease(
                    request: StudentTrustedSavedAnswerPresentationRequest,
                ): StudentTrustedSavedAnswerLease? {
                    requests += request
                    return null
                }

                override suspend fun submitResponse(
                    lease: StudentTrustedSavedAnswerLease,
                    response: StudentTrustedReviewResponse,
                ): StudentTrustedSavedAnswerSubmissionResult =
                    StudentTrustedSavedAnswerSubmissionResult.Rejected
            },
            CurrentTutorTrustedAnswerCommitter {
                CurrentTutorTrustedAnswerSubmissionResult.Rejected
            },
        )

        assertNull(authority.resolve(query))

        val proposal = checkNotNull(output.plan.guidedInteractionProposal)
        val choices = proposal.directive as TutorInteractionDirective.Choices
        val changedOutput = output.copy(
            plan = output.plan.copy(
                guidedInteractionProposal = proposal.copy(
                    directive = choices.copy(
                        choices = choices.choices.map { choice ->
                            if (choice.id == "choice-a") {
                                choice.copy(labelMarkdown = "同一个 id，但内容已经变化")
                            } else {
                                choice
                            }
                        },
                    ),
                ),
            ),
        )
        assertNull(authority.resolve(query.copy(output = changedOutput)))
        val reorderedOutput = output.copy(
            plan = output.plan.copy(
                guidedInteractionProposal = proposal.copy(
                    directive = choices.copy(choices = choices.choices.reversed()),
                ),
            ),
        )
        assertNull(authority.resolve(query.copy(output = reorderedOutput)))
        val changedPromptOutput = output.copy(
            plan = output.plan.copy(
                guidedInteractionProposal = proposal.copy(
                    directive = choices.copy(promptMarkdown = "先判断哪一个关系？"),
                ),
            ),
        )
        assertNull(authority.resolve(query.copy(output = changedPromptOutput)))
        assertEquals(4, requests.size)
        assertEquals(ReviewResponseForm.CHOICE, requests.first().responseForm)
        assertTrue(requests.all { it.questionVersion.startsWith("current-tutor-visible-presentation-v2:") })
        assertEquals(4, requests.map { it.questionVersion }.distinct().size)
    }

    @Test
    fun `visual answer binding changes with target or scene and rejects a missing activation scene`() {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        val scene = visualTargetScene()
        val sourceTask = fixture.modelTaskWithVisualTarget(scene, targetId = "target-a")
        val sourceOutput = sourceTask.output as TutorPlanOutput
        val visual = checkNotNull(sourceOutput.plan.guidedInteractionProposal).directive as
            TutorInteractionDirective.VisualTarget
        val task = with(fixture) {
            sourceTask.withGuidedEvidenceLabel()
        }
        val input = task.request.input as TutorPlanInput
        val output = (task.output as TutorPlanOutput).locallyConstrainedFor(input)
        fun binding(candidate: TutorPlanOutput): String? = CurrentTutorTrustedAnswerContentBinding.forQuery(
            query = CurrentTutorVerifiedMaterialQuery(
                learnerId = LEARNER,
                session = fixture.session,
                subject = SubjectKind.PHYSICS,
                task = task,
                input = input,
                output = candidate,
                currentStepKnowledgeAuthorityRequired = true,
            ),
            responseForm = ReviewResponseForm.VISUAL_TARGET,
        )

        val original = checkNotNull(binding(output))
        val proposal = checkNotNull(output.plan.guidedInteractionProposal)
        val changedTarget = output.copy(
            plan = output.plan.copy(
                guidedInteractionProposal = proposal.copy(
                    directive = visual.copy(targetId = "target-b"),
                ),
            ),
        )
        val changedScene = output.copy(
            plan = output.plan.copy(
                guidedInteractionProposal = proposal.copy(
                    visualScene = scene.copy(title = "被篡改的场景"),
                ),
            ),
        )
        val missingScene = output.copy(
            plan = output.plan.copy(
                guidedInteractionProposal = proposal.copy(visualScene = null),
            ),
        )

        assertNotEquals(original, binding(changedTarget))
        assertNotEquals(original, binding(changedScene))
        assertNull(binding(missingScene))
    }

    @Test
    fun `answer capability is useful only for its exact presentation token`() {
        val source = CurrentTutorPreparedAnswerInteraction(
            responseForm = ReviewResponseForm.CHOICE,
            canonicalFingerprint = sha256("source-answer-capability"),
            exactContentBinding = "current-tutor-visible-presentation-v2:${sha256("content")}",
            submitter = { _, _ -> CurrentTutorTrustedAnswerSubmissionResult.Rejected },
        )
        val firstToken = sha256("presentation-one")
        val bound = source.bindToPresentation(firstToken)

        assertTrue(bound.matchesSource(source, firstToken))
        assertFalse(bound.matchesSource(source, sha256("presentation-two")))
        assertFalse(source.matchesSource(source, firstToken))
        assertThrows(IllegalStateException::class.java) {
            bound.bindToPresentation(firstToken)
        }
    }

    @Test
    fun `late answer certification cannot retrofit evidence onto an existing presentation`() = runBlocking {
        val fixture = HostFixture(TutorExplanationMode.GUIDED)
        fixture.modelTask = with(fixture) {
            modelTaskWithChoiceDirective().withGuidedEvidenceLabel()
        }
        val coordinator = fixture.coordinator(FakeActivationOwner())
        assertTrue(coordinator.activate(SESSION, REQUEST) is TutorCurrentSessionHostResult.Ready)
        val before = checkNotNull(fixture.host.current())
        assertNull(before.evidenceRequestId)

        fixture.authorizeChoiceDirectiveEvidence()
        val resumed = coordinator.resume(SESSION)
        val after = checkNotNull(fixture.host.current())

        assertTrue(resumed is TutorCurrentSessionHostResult.Ready)
        assertEquals(before.presentationToken, after.presentationToken)
        assertNull(after.evidenceRequestId)
        assertNull(after.pendingInteractionKind)
        assertEquals(0, fixture.learning.calls)
        assertNull(checkNotNull(coordinator.observePresentation(SESSION).first()).interaction)
    }

    private suspend fun assertBlockedBeforeOwner(
        fixture: HostFixture,
        resume: Boolean = false,
    ) {
        val owner = FakeActivationOwner()
        val coordinator = fixture.coordinator(owner)
        val result = if (resume) coordinator.resume(SESSION) else coordinator.activate(SESSION, REQUEST)
        assertTrue(result is TutorCurrentSessionHostResult.Unavailable)
        assertEquals(0, owner.calls)
    }

    private companion object {
        const val LEARNER = "learner-current-host"
        const val SESSION = "session-current-host"
        const val REQUEST = "request-current-host"
        const val NOW = 50_000L
        const val FREE_RESPONSE_TTL_MILLIS = 2L * 60L * 1_000L
    }
}

private class HostFixture(
    private val mode: TutorExplanationMode,
    private val learningWritesAllowed: Boolean = true,
) {
    var clockEpochMillis: Long = NOW
    var session: ConfirmedTutorSession = confirmedSession()
    var modelTask: ModelTaskSnapshot = modelTask()
    var visualTask: ModelTaskSnapshot? = null
    val visualTaskRequestId = "visual-request-current-host"
    var currentMode: TutorExplanationModeSnapshot = TutorExplanationModeSnapshot(mode, 1)
    val host = FakeHostWorkStore()
    val learning = FakeLearningAuthorityStore()
    val interactions = FakeTutorInteractionRepository()
    val submittedTrustedAnswers = mutableListOf<StudentTrustedReviewResponse>()
    var beforeTrustedAnswerSubmission: () -> Unit = {}
    var beforeTrustedAnswerCommit: suspend () -> Unit = {}
    private var activeActivationOwner: FakeActivationOwner? = null
    private var previousActivationOwner: FakeActivationOwner? = null
    private var material = verifiedMaterial()

    fun coordinator(
        owner: CurrentTutorSessionActivationOwner,
        policyController: CurrentTutorHostPolicyController? = null,
        interactionWriter: TutorInteractionRepository = interactions,
        freeResponseSubmission: CurrentTutorFreeResponseSubmissionPort? = null,
        freeResponseDispatchGenerationId: String = "test-process-generation",
    ): CurrentTutorSessionHostCoordinator {
        if (owner is FakeActivationOwner) {
            previousActivationOwner
                ?.takeUnless { previous -> previous === owner }
                ?.let(owner::restorePersistedStateFrom)
            previousActivationOwner = owner
        }
        activeActivationOwner = owner as? FakeActivationOwner
        host.onNewFreeResponseClaim = {
            if (owner is FakeActivationOwner) owner.recordStudentSubmission()
        }
        return CurrentTutorSessionHostCoordinator(
            learnerId = LEARNER,
            questions = CurrentTutorQuestionSource { requested ->
                session.takeIf { requested == SESSION }
            },
            modelTasks = CurrentTutorModelTaskSource { requested ->
                modelTask.takeIf { requested == modelTask.request.requestId }
                    ?: visualTask?.takeIf { requested == it.request.requestId }
            },
            modes = CurrentTutorModeSource { _ -> currentMode },
            learningAuthorityStore = learning,
            hostWork = host,
            learningWriteAuthority = CurrentTutorLearningWriteAuthority { requested ->
                CurrentTutorLearningWriteAuthoritySnapshot(
                    sessionId = requested,
                    allowed = learningWritesAllowed,
                    permissionVersion = 1,
                )
            },
            verifiedMaterialAuthority = CurrentTutorVerifiedMaterialAuthority { material },
            activationOwner = owner,
            interactionWriter = interactionWriter,
            freeResponseSubmission = freeResponseSubmission,
            freeResponseDispatchGenerationId = freeResponseDispatchGenerationId,
            policyController = policyController,
            nowEpochMillis = LongSupplier { clockEpochMillis },
        )
    }

    fun modelTask(outputSeed: String = "original"): ModelTaskSnapshot =
        tutorModelTask(session, mode, outputSeed, learningWritesAllowed)

    fun modelTaskWithChoiceDirective(): ModelTaskSnapshot {
        val task = modelTask()
        val input = task.request.input as TutorPlanInput
        val output = task.output as TutorPlanOutput
        return task.copy(
            output =
                output.copy(
                    plan = output.plan.copy(
                        responseIntent = TutorResponseIntent.ASK,
                        solutionRevealed = false,
                        diagnosticItem = null,
                        interactionDirective = TutorInteractionDirective.Choices(
                            promptMarkdown = "先判断哪一项？",
                            choices = listOf(
                                TutorInteractionChoice("choice-a", "线框面积"),
                                TutorInteractionChoice("choice-b", "磁通量随时间的变化"),
                            ),
                        ),
                    ),
                ).locallyConstrainedFor(input),
        )
    }

    fun modelTaskWithVisualTarget(
        proposedScene: TutorVisualDocumentScene? = null,
        targetId: String = "model-authored-target",
    ): ModelTaskSnapshot {
        val task = modelTask()
        val input = task.request.input as TutorPlanInput
        val output = task.output as TutorPlanOutput
        return task.copy(
            output =
                output.copy(
                    plan = output.plan.copy(
                        responseIntent = TutorResponseIntent.ASK,
                        solutionRevealed = false,
                        diagnosticItem = null,
                        interactionDirective = TutorInteractionDirective.VisualTarget(
                            promptMarkdown = "请点出磁通量变化最快的位置。",
                            targetId = targetId,
                        ),
                        visualScene = proposedScene,
                    ),
                ).locallyConstrainedFor(input),
        )
    }

    fun authorizeChoiceEvidence() {
        modelTask = modelTaskWithChoiceDirective().withGuidedEvidenceLabel()
        material = verifiedMaterial(
            teachingConstraint = TutorTeachingConstraint.MAY_GUIDE,
            trustedAnswerInteraction = CurrentTutorPreparedAnswerInteraction(
                responseForm = ReviewResponseForm.CHOICE,
                canonicalFingerprint = sha256("trusted-choice-rule"),
                exactContentBinding = exactAnswerContentBinding(
                    modelTask,
                    ReviewResponseForm.CHOICE,
                ),
                submitter = { response, fence ->
                    commitTestTrustedAnswer(response, fence, ReviewResponseForm.CHOICE)
                },
            ),
        )
    }

    fun authorizeChoiceDirectiveEvidence() {
        modelTask = modelTaskWithChoiceDirective().withGuidedEvidenceLabel()
        material = verifiedMaterial(
            teachingConstraint = TutorTeachingConstraint.MAY_GUIDE,
            trustedAnswerInteraction = CurrentTutorPreparedAnswerInteraction(
                responseForm = ReviewResponseForm.CHOICE,
                canonicalFingerprint = sha256("trusted-v2-choice-rule"),
                exactContentBinding = exactAnswerContentBinding(
                    modelTask,
                    ReviewResponseForm.CHOICE,
                ),
                submitter = { response, fence ->
                    commitTestTrustedAnswer(response, fence, ReviewResponseForm.CHOICE)
                },
            ),
        )
    }

    fun authorizeVisualTargetEvidence(): TutorVisualDocumentScene {
        val scene = visualTargetScene()
        modelTask = modelTaskWithVisualTarget(scene, targetId = "target-a")
            .withGuidedEvidenceLabel()
        visualTask = generatedVisualTask(modelTask, scene)
        material = verifiedMaterial(
            teachingConstraint = TutorTeachingConstraint.MAY_GUIDE,
            trustedAnswerInteraction = CurrentTutorPreparedAnswerInteraction(
                responseForm = ReviewResponseForm.VISUAL_TARGET,
                canonicalFingerprint = sha256("trusted-visual-target-rule"),
                exactContentBinding = exactAnswerContentBinding(
                    modelTask,
                    ReviewResponseForm.VISUAL_TARGET,
                ),
                submitter = { response, fence ->
                    commitTestTrustedAnswer(response, fence, ReviewResponseForm.VISUAL_TARGET)
                },
            ),
        )
        return scene
    }

    private suspend fun commitTestTrustedAnswer(
        response: StudentTrustedReviewResponse,
        fence: CurrentTutorTrustedAnswerSubmissionFence,
        responseForm: ReviewResponseForm,
    ): CurrentTutorTrustedAnswerSubmissionResult {
        val owner = activeActivationOwner
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        beforeTrustedAnswerCommit()
        beforeTrustedAnswerSubmission()
        if (owner.currentEvidenceState(fence.sessionId) != fence.expectedEvidenceState) {
            return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        }
        submittedTrustedAnswers += response
        owner.recordStudentSubmission()
        val resultingState = owner.currentEvidenceState(fence.sessionId)
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val responseBinding = sha256("trusted-test-response:$response")
        val contentBinding = exactAnswerContentBinding(modelTask, responseForm)
        val eventId = "trusted-test-event-${responseBinding.take(32)}"
        val eventPayloadFingerprint = sha256("trusted-test-event:$responseBinding")
        val learningReceiptFingerprint = sha256("trusted-test-learning:$responseBinding")
        val commitFingerprint = CanonicalSha256(
            "current-tutor-trusted-answer-commit-receipt-v1",
        )
            .field("sessionId", fence.sessionId)
            .field("presentationToken", fence.presentationToken)
            .field("evidenceRequestId", fence.evidenceRequestId)
            .field("exactContentBinding", contentBinding)
            .field("responseForm", responseForm.name)
            .field("responseBinding", responseBinding)
            .field("eventId", eventId)
            .field("eventPayloadFingerprint", eventPayloadFingerprint)
            .field("duplicate", false)
            .field("scopeId", resultingState.scopeId)
            .field("activationFingerprint", resultingState.activationFingerprint)
            .field("presentationFingerprint", resultingState.presentationFingerprint)
            .field("stateVersion", resultingState.stateVersion)
            .field("stateFingerprint", resultingState.stateFingerprint)
            .field("attemptOrdinal", resultingState.attemptOrdinal)
            .field("hintCount", resultingState.hintCount)
            .field("answerWasRevealed", resultingState.answerWasRevealed)
            .field("learningReceiptFingerprint", learningReceiptFingerprint)
            .finish()
        return CurrentTutorTrustedAnswerSubmissionResult.Committed(
            CurrentTutorTrustedAnswerCommitReceipt(
                sessionId = fence.sessionId,
                presentationToken = fence.presentationToken,
                evidenceRequestId = fence.evidenceRequestId,
                exactContentBinding = contentBinding,
                responseForm = responseForm,
                responseBinding = responseBinding,
                eventId = eventId,
                eventPayloadFingerprint = eventPayloadFingerprint,
                duplicate = false,
                resultingEvidenceState = resultingState,
                learningReceiptFingerprint = learningReceiptFingerprint,
                canonicalFingerprint = commitFingerprint,
            ),
        )
    }

    private fun exactAnswerContentBinding(
        task: ModelTaskSnapshot,
        responseForm: ReviewResponseForm,
    ): String {
        val input = task.request.input as TutorPlanInput
        val output = (task.output as TutorPlanOutput).locallyConstrainedFor(input)
        return checkNotNull(
            CurrentTutorTrustedAnswerContentBinding.forQuery(
                query = CurrentTutorVerifiedMaterialQuery(
                    learnerId = LEARNER,
                    session = session,
                    subject = SubjectKind.PHYSICS,
                    task = task,
                    input = input,
                    output = output,
                    currentStepKnowledgeAuthorityRequired = true,
                ),
                responseForm = responseForm,
            ),
        )
    }

    private fun generatedVisualTask(
        ownerTask: ModelTaskSnapshot,
        scene: TutorVisualDocumentScene,
    ): ModelTaskSnapshot {
        val ownerInput = ownerTask.request.input as TutorPlanInput
        val ownerOutput = ownerTask.output as TutorPlanOutput
        val anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = ownerOutput.cycleOrdinal,
            turnOrdinal = ownerOutput.turnOrdinal,
        )
        val input = TutorVisualGenerateInput(
            sessionId = ownerInput.sessionId,
            draftRevisionNumber = ownerInput.draftRevisionNumber,
            subject = ownerInput.subject,
            questionDocument = ownerInput.questionDocument,
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = "source-current-host",
                    sha256 = sha256("source-current-host"),
                    width = 100,
                    height = 100,
                    pageIndex = 0,
                ),
            ),
            anchor = anchor,
            focusMarkdown = "聚焦磁通量变化最快的位置。",
            explanationMarkdown = ownerOutput.plan.solutionMarkdown,
        )
        val request = ModelTaskRequest(
            requestId = visualTaskRequestId,
            input = input,
            occurredAtEpochMillis = 49_600,
        )
        return ModelTaskSnapshot(
            taskId = "visual-task-current-host",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 2,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "",
            attemptCount = 1,
            output = TutorVisualGenerateOutput(
                sessionId = input.sessionId,
                draftRevisionNumber = input.draftRevisionNumber,
                questionDocumentId = input.questionDocument.id,
                anchor = input.anchor,
                decision = TutorVisualGenerationDecision.GENERATED,
                confidence = 0.9,
                scene = scene,
                modelVersion = "visual-model-current-host",
            ),
            createdAtEpochMillis = 49_600,
            updatedAtEpochMillis = 49_700,
        )
    }

    fun authorizeFreeResponseEvidence(
        knowledgeNodeIds: List<String> = listOf("physics.electromagnetic.induction"),
    ) {
        modelTask = modelTask().withGuidedEvidenceLabel()
        material = verifiedMaterial(
            teachingConstraint = TutorTeachingConstraint.MAY_GUIDE,
            trustedAnswerInteraction = null,
            prohibitedEvaluatorExecutionFingerprint = sha256("tutor-model-execution"),
            knowledgeNodeIds = knowledgeNodeIds,
        )
    }

    fun addGuidedHint(markdown: String) {
        val task = modelTask
        val input = task.request.input as TutorPlanInput
        val output = task.output as TutorPlanOutput
        modelTask = task.copy(
            output = output.copy(
                plan = output.plan.copy(hintMarkdown = markdown),
            ).locallyConstrainedFor(input),
        )
    }

    fun ModelTaskSnapshot.withGuidedEvidenceLabel(): ModelTaskSnapshot {
        val input = request.input as TutorPlanInput
        val nextRequest = request.copy(
            input = input.copy(
                teachingConstraints = listOf(
                    TutorKnowledgeGuidance(
                        ref = "current-question-point-1",
                        label = TRUSTED_KNOWLEDGE_LABEL,
                        constraint = TutorTeachingConstraint.MAY_GUIDE,
                    ),
                ),
            ),
        )
        val output = output as TutorPlanOutput
        return copy(
            request = nextRequest,
            requestFingerprint = ModelTaskFingerprint.of(nextRequest),
            output = output.copy(
                plan = output.plan.copy(
                    targetedEvidenceLabels = listOf(TRUSTED_KNOWLEDGE_LABEL),
                ),
            ),
        )
    }

    fun expectedConstrainedContentFingerprint(outputSeed: String): String {
        val task = modelTask(outputSeed)
        val input = task.request.input as TutorPlanInput
        val rawOutput = task.output as TutorPlanOutput
        val output = rawOutput.locallyConstrainedFor(input)
            .constrainedForCurrentHostInteraction(authorizedEvidenceKind = null)
        return sha256CanonicalPresentation(
            requestFingerprint = task.requestFingerprint,
            modeVersion = input.modeVersion,
            learningWritesAllowed = input.allowLongTermLearningWrites,
            learningWritePermissionVersion = input.learningWritePermissionVersion,
            output = output,
        )
    }

    private companion object {
        const val LEARNER = "learner-current-host"
        const val SESSION = "session-current-host"
        const val NOW = 50_000L
        const val TRUSTED_KNOWLEDGE_LABEL = "法拉第电磁感应定律"
    }
}

private class FakeActivationOwner(
    var crashNext: Boolean = false,
) : CurrentTutorSessionActivationOwner {
    var calls: Int = 0
        private set
    var lastActivation: CurrentTutorSessionActivation? = null
        private set
    val activationFingerprints = linkedSetOf<String>()
    val revokedScopes = linkedSetOf<String>()
    private var evidenceStateVersion = 0L
    private var evidenceStateFingerprint: String? = null
    private var persistedAttemptOrdinal = 0
    private var persistedHintCount = 0
    private var persistedAnswerWasRevealed = false
    private val shownHintTokens = linkedSetOf<String>()

    override suspend fun activate(
        activation: CurrentTutorSessionActivation,
    ): CurrentTutorSessionActivationResult {
        calls += 1
        if (crashNext) {
            crashNext = false
            throw IllegalStateException("simulated process loss after durable prepare")
        }
        lastActivation = activation
        val isNewActivation = activationFingerprints.add(activation.activationFingerprint)
        if (isNewActivation) {
            evidenceStateVersion = 0L
            evidenceStateFingerprint = activation.activationFingerprint
            persistedAttemptOrdinal = activation.attemptOrdinal
            persistedHintCount = activation.hintCount
            persistedAnswerWasRevealed = activation.answerWasRevealed
            shownHintTokens.clear()
        }
        return if (isNewActivation) {
            CurrentTutorSessionActivationResult.ACTIVE
        } else {
            CurrentTutorSessionActivationResult.DUPLICATE
        }
    }

    override suspend fun revokeLearningWrites(scopeId: String) {
        revokedScopes += scopeId
    }

    override suspend fun currentEvidenceState(
        sessionId: String,
    ): CurrentTutorSessionEvidenceState? = lastActivation
        ?.takeIf { activation -> activation.conversationId == sessionId }
        ?.let { activation ->
            CurrentTutorSessionEvidenceState(
                scopeId = activation.scopeId,
                activationFingerprint = activation.activationFingerprint,
                presentationFingerprint = activation.presentationFingerprint,
                stateVersion = evidenceStateVersion,
                stateFingerprint = checkNotNull(evidenceStateFingerprint),
                attemptOrdinal = persistedAttemptOrdinal,
                hintCount = persistedHintCount,
                answerWasRevealed = persistedAnswerWasRevealed,
            )
        }

    override suspend fun recordHintShown(
        commit: CurrentTutorHintShownCommit,
    ): CurrentTutorHintShownCommitResult {
        val activation = lastActivation
        if (
            activation == null ||
            activation.conversationId != commit.sessionId ||
            activation.scopeId != commit.expectedScopeId ||
            activation.questionDocument.id != commit.expectedQuestionDocumentId ||
            activation.questionRevisionNumber != commit.expectedQuestionRevisionNumber ||
            activation.cycleOrdinal != commit.expectedCycleOrdinal ||
            activation.turnOrdinal != commit.expectedTurnOrdinal ||
            activation.presentationFingerprint != commit.expectedPresentationFingerprint ||
            activation.explanationMode != TutorExplanationMode.GUIDED ||
            activation.modeVersion != commit.modeVersion ||
            persistedAnswerWasRevealed
        ) {
            return CurrentTutorHintShownCommitResult.REJECTED
        }
        if (persistedHintCount != 0) {
            return if (
                persistedHintCount == 1 && commit.slotToken in shownHintTokens
            ) {
                CurrentTutorHintShownCommitResult.DUPLICATE
            } else {
                CurrentTutorHintShownCommitResult.REJECTED
            }
        }
        shownHintTokens += commit.slotToken
        persistedHintCount += 1
        advanceEvidenceState("hint")
        return CurrentTutorHintShownCommitResult.RECORDED
    }

    fun recordStudentSubmission() {
        persistedAttemptOrdinal += 1
        advanceEvidenceState("student-submission")
    }

    fun restorePersistedStateFrom(previous: FakeActivationOwner) {
        lastActivation = previous.lastActivation
        activationFingerprints += previous.activationFingerprints
        evidenceStateVersion = previous.evidenceStateVersion
        evidenceStateFingerprint = previous.evidenceStateFingerprint
        persistedAttemptOrdinal = previous.persistedAttemptOrdinal
        persistedHintCount = previous.persistedHintCount
        persistedAnswerWasRevealed = previous.persistedAnswerWasRevealed
        shownHintTokens += previous.shownHintTokens
    }

    fun recordAnswerExposure() {
        persistedAnswerWasRevealed = true
        advanceEvidenceState("answer-exposure")
    }

    private fun advanceEvidenceState(reason: String) {
        evidenceStateVersion += 1L
        evidenceStateFingerprint = sha256("$reason:$evidenceStateVersion")
    }
}

private class BlockingActivationOwner : CurrentTutorSessionActivationOwner {
    val activationEntered = CompletableDeferred<Unit>()
    val releaseActivation = CompletableDeferred<Unit>()
    val revokedScopes = linkedSetOf<String>()
    private var activation: CurrentTutorSessionActivation? = null

    override suspend fun activate(
        activation: CurrentTutorSessionActivation,
    ): CurrentTutorSessionActivationResult {
        this.activation = activation
        activationEntered.complete(Unit)
        releaseActivation.await()
        return CurrentTutorSessionActivationResult.ACTIVE
    }

    override suspend fun revokeLearningWrites(scopeId: String) {
        revokedScopes += scopeId
    }

    override suspend fun currentEvidenceState(
        sessionId: String,
    ): CurrentTutorSessionEvidenceState? = activation
        ?.takeIf { current -> current.conversationId == sessionId }
        ?.let { current ->
            CurrentTutorSessionEvidenceState(
                scopeId = current.scopeId,
                activationFingerprint = current.activationFingerprint,
                presentationFingerprint = current.presentationFingerprint,
                stateVersion = 0L,
                stateFingerprint = current.activationFingerprint,
                attemptOrdinal = current.attemptOrdinal,
                hintCount = current.hintCount,
                answerWasRevealed = current.answerWasRevealed,
            )
        }
}

private class FakeHostPolicyController(
    initial: TutorCurrentSessionPolicySnapshot,
    private val onUpdated: (TutorCurrentSessionPolicySnapshot) -> Unit = {},
) : CurrentTutorHostPolicyController {
    private var snapshot = initial

    override suspend fun update(
        command: TutorCurrentSessionPolicyUpdate,
    ): TutorCurrentSessionPolicySnapshot {
        snapshot = snapshot.copy(
            explanationMode = command.explanationMode,
            modeVersion = snapshot.modeVersion +
                if (snapshot.explanationMode == command.explanationMode) 0 else 1,
            learningWritesAllowed = command.learningWritesAllowed,
            learningWritePermissionVersion = snapshot.learningWritePermissionVersion +
                if (snapshot.learningWritesAllowed == command.learningWritesAllowed) 0 else 1,
            visualIntent = command.visualIntent,
            visualIntentVersion = snapshot.visualIntentVersion +
                if (snapshot.visualIntent == command.visualIntent) 0 else 1,
        )
        onUpdated(snapshot)
        return snapshot
    }

    override suspend fun currentPolicy(sessionId: String): TutorCurrentSessionPolicySnapshot? =
        snapshot.takeIf { value -> value.sessionId == sessionId }
}

private class FakeTutorInteractionRepository : TutorInteractionRepository {
    val choiceCommands = mutableListOf<RecordTutorChoiceCommand>()
    val visualCommands = mutableListOf<RecordTutorVisualTargetEvidenceCommand>()
    val cancelledEvidenceIds = mutableSetOf<String>()

    override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> = flowOf(emptyList())

    override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse {
        choiceCommands += command
        return TutorTurnResponse(
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            diagnosticStemMarkdown = command.diagnosticStemMarkdown,
            selectedChoiceId = command.selectedChoiceId,
            selectedChoiceMarkdown = command.selectedChoiceMarkdown,
            selectionWasCorrect = command.selectionWasCorrect,
            feedbackMarkdown = command.feedbackMarkdown,
            submittedAtEpochMillis = command.occurredAtEpochMillis,
            updatedAtEpochMillis = command.occurredAtEpochMillis,
            evidenceRequestId = command.evidenceRequestId,
        )
    }

    override suspend fun recordVisualTargetEvidence(
        command: RecordTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidence {
        visualCommands += command
        val proof = command.hitProof
        return TutorVisualTargetEvidence(
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            anchor = command.anchor,
            modelTaskRequestId = command.modelTaskRequestId,
            sceneSourceKind = proof.presentation.sourceKind,
            sceneTaskRequestId = proof.presentation.sceneTaskRequestId,
            sceneId = proof.presentation.sceneId,
            sceneFingerprint = proof.presentation.sceneFingerprint,
            hitProofId = proof.proofId,
            panelId = proof.panelId,
            frameFingerprint = proof.frameFingerprint,
            stepIndex = proof.stepIndex,
            selectedTargetId = proof.selectedTargetId,
            selectionWasCorrect = command.selectionWasCorrect,
            submittedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun cancelEvidence(command: CancelTutorEvidenceCommand) {
        cancelledEvidenceIds += command.evidenceRequestId
    }

    override suspend fun isEvidenceCancelled(command: CancelTutorEvidenceCommand): Boolean =
        command.evidenceRequestId in cancelledEvidenceIds

    override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
        error("recordMove is not used by the current-session Host tests")

    override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
        error("revealSolution is not used by the current-session Host tests")

    override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) {
        error("recordSolutionExposure is not used by the current-session Host tests")
    }
}

private class FakeLearningAuthorityStore : CurrentTutorLearningAuthorityStore {
    var calls: Int = 0
        private set
    private var conversation: TutorConversation? = null
    var turn: TutorTurnReceipt? = null
        private set
    private var evidence: TutorEvidenceRequest? = null

    override suspend fun openConversation(
        learnerId: String,
        conversationId: String,
        generation: Long,
    ): TutorConversation? {
        calls += 1
        return conversation?.takeIf { current ->
            current.learnerScopeId == learnerId &&
                current.conversationId == conversationId &&
                current.generation == generation
        }
    }

    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversation {
        calls += 1
        return conversation ?: TutorConversation(
            conversationId = command.conversationId,
            learnerScopeId = command.learnerScopeId,
            generation = command.conversationGeneration,
            status = TutorConversationStatus.ACTIVE,
            createdAtEpochMillis = command.occurredAtEpochMillis,
            archivedAtEpochMillis = null,
            stateVersion = 0,
        ).also { created -> conversation = created }
    }

    override suspend fun openTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReceipt? {
        calls += 1
        return turn?.takeIf { current ->
            current.turnReceiptId == turnReceiptId && conversation?.learnerScopeId == learnerId
        }
    }

    override suspend fun allocateTurn(command: AllocateTutorTurnCommand): TutorTurnReceipt {
        calls += 1
        return turn ?: TutorTurnReceipt(
            turnReceiptId = command.turnReceiptId,
            conversationId = command.conversationId,
            conversationGeneration = command.conversationGeneration,
            conversationStateVersion = command.expectedConversationStateVersion + 1,
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
        ).also { created ->
            turn = created
            conversation = checkNotNull(conversation).copy(
                stateVersion = created.conversationStateVersion,
            )
        }
    }

    override suspend fun prepareEvidenceRequest(
        command: PrepareTutorEvidenceCommand,
    ): TutorEvidenceRequest {
        calls += 1
        return evidence ?: TutorEvidenceRequest(
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
            terminalReceiptId = null,
        ).also { created -> evidence = created }
    }
}

private class FakeFreeResponseSubmissionPort(
    private val receipts: ArrayDeque<CoreDataTutorOpenResponseLearningReceipt>,
    private val beforeCurrentCheck: suspend (CurrentTutorFreeResponseSubmission) -> Unit = {},
) : CurrentTutorFreeResponseSubmissionPort {
    var calls: Int = 0
        private set
    val uniqueEvidenceKeys = linkedSetOf<String>()
    val currentChecks = mutableListOf<Boolean>()
    val submissions = mutableListOf<CurrentTutorFreeResponseSubmission>()

    override suspend fun submit(
        submission: CurrentTutorFreeResponseSubmission,
    ): CoreDataTutorOpenResponseSubmissionResult {
        calls += 1
        submissions += submission
        beforeCurrentCheck(submission)
        val isCurrent = submission.isCurrent()
        currentChecks += isCurrent
        if (!isCurrent) {
            return CoreDataTutorOpenResponseSubmissionResult(
                CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
                null,
            )
        }
        uniqueEvidenceKeys += "${submission.actionToken}:${sha256(submission.answer)}"
        val disposition =
            receipts.removeFirstOrNull() ?: CoreDataTutorOpenResponseLearningReceipt.DUPLICATE
        val commitReceipt = if (
            disposition == CoreDataTutorOpenResponseLearningReceipt.PENDING ||
            disposition == CoreDataTutorOpenResponseLearningReceipt.DUPLICATE
        ) {
            CoreDataTutorOpenResponseCandidateCommitReceipt(
                candidateIdempotencyKey = sha256("candidate:${submission.actionToken}"),
                receiptFingerprint = sha256("receipt:${submission.actionToken}"),
            )
        } else {
            null
        }
        return CoreDataTutorOpenResponseSubmissionResult(disposition, commitReceipt)
    }
}

private class FakeHostWorkStore : CurrentTutorSessionHostWorkDatabasePort {
    private val state = MutableStateFlow<CurrentTutorSessionHostWorkRecord?>(null)
    private var policy: CurrentTutorSessionPolicyRecord? = null
    private val freeResponseOutboxes = mutableMapOf<String, FakeFreeResponseOutbox>()
    val freeResponseClaimCount: Int
        get() = freeResponseOutboxes.size
    var rejectNextRevoke: Boolean = false
    var crashAfterNextFreeResponseAcquire: Boolean = false
    var onNewFreeResponseClaim: () -> Unit = {}

    fun current(): CurrentTutorSessionHostWorkRecord? = state.value

    fun outboxState(actionToken: String): CurrentTutorFreeResponseDispatchState? =
        freeResponseOutboxes[actionToken]?.state

    fun mutate(transform: (CurrentTutorSessionHostWorkRecord) -> CurrentTutorSessionHostWorkRecord) {
        state.value = transform(checkNotNull(state.value))
    }

    override suspend fun persistCurrentTutorSessionPolicy(
        command: PersistCurrentTutorSessionPolicyCommand,
    ): CurrentTutorSessionPolicyWriteResult {
        val candidate = CurrentTutorSessionPolicyRecord(
            learnerId = command.learnerId,
            sessionId = command.sessionId,
            explanationMode = command.explanationMode,
            modeVersion = command.modeVersion,
            learningWritesAllowed = command.learningWritesAllowed,
            learningWritePermissionVersion = command.learningWritePermissionVersion,
            visualIntent = command.visualIntent,
            visualIntentVersion = command.visualIntentVersion,
            stateFingerprint = sha256(
                "policy:${command.modeVersion}:${command.learningWritePermissionVersion}:" +
                    command.visualIntentVersion,
            ),
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
        val duplicate = policy?.copy(updatedAtEpochMillis = candidate.updatedAtEpochMillis) == candidate
        if (!duplicate) policy = candidate
        return CurrentTutorSessionPolicyWriteResult(
            disposition = if (duplicate) {
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE
            } else {
                CurrentTutorSessionHostWorkWriteDisposition.APPLIED
            },
            record = policy,
            recordedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun readCurrentTutorSessionPolicy(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionPolicyRecord? = policy?.takeIf {
        it.learnerId == learnerId && it.sessionId == sessionId
    }

    override suspend fun stageCurrentTutorSessionHostWork(
        command: StageCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult {
        val existing = state.value
        if (existing != null) {
            val duplicate = existing.status != CurrentTutorSessionHostWorkStatus.REVOKED &&
                existing.matches(command)
            return result(
                if (duplicate) CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE
                else CurrentTutorSessionHostWorkWriteDisposition.STALE,
                existing,
                command.occurredAtEpochMillis,
            )
        }
        val record = command.toRecord()
        state.value = record
        return result(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, record, command.occurredAtEpochMillis)
    }

    override suspend fun readCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionHostWorkRecord? = state.value

    override suspend fun claimCurrentTutorFreeResponseAction(
        command: ClaimCurrentTutorFreeResponseActionCommand,
    ): CurrentTutorFreeResponseActionClaimResult {
        val current = state.value
        if (
            current == null ||
            current.status != CurrentTutorSessionHostWorkStatus.ACTIVE ||
            current.workId != command.expectedWorkId ||
            current.stateVersion != command.expectedWorkStateVersion ||
            current.stateFingerprint != command.expectedWorkStateFingerprint ||
            current.presentationToken != command.presentationToken ||
            current.evidenceRequestId != command.evidenceRequestId ||
            current.pendingInteractionKind != TutorEvidenceRequestKind.FREE_RESPONSE
        ) {
            return CurrentTutorFreeResponseActionClaimResult(
                disposition = CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT,
                canonicalOccurredAtEpochMillis = null,
                recordedAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
        val previous = freeResponseOutboxes[command.actionToken]
        if (previous == null && command.occurredAtEpochMillis >= command.actionExpiresAtEpochMillis) {
            return CurrentTutorFreeResponseActionClaimResult(
                disposition = CurrentTutorFreeResponseActionClaimDisposition.REJECTED,
                canonicalOccurredAtEpochMillis = null,
                recordedAtEpochMillis = command.occurredAtEpochMillis,
            )
        }
        val binding = sha256("test-response-binding:${command.actionToken}:${command.answer}")
        val disposition = when {
            previous == null -> {
                freeResponseOutboxes[command.actionToken] = FakeFreeResponseOutbox(
                    answer = command.answer,
                    answerBinding = binding,
                    canonicalOccurredAtEpochMillis = command.occurredAtEpochMillis,
                )
                onNewFreeResponseClaim()
                CurrentTutorFreeResponseActionClaimDisposition.CLAIMED
            }
            previous.answerBinding == binding ->
                CurrentTutorFreeResponseActionClaimDisposition.DUPLICATE
            else -> CurrentTutorFreeResponseActionClaimDisposition.REJECTED
        }
        val canonical = freeResponseOutboxes[command.actionToken]?.canonicalOccurredAtEpochMillis
        return CurrentTutorFreeResponseActionClaimResult(
            disposition = disposition,
            canonicalOccurredAtEpochMillis = canonical.takeIf {
                disposition == CurrentTutorFreeResponseActionClaimDisposition.CLAIMED ||
                    disposition == CurrentTutorFreeResponseActionClaimDisposition.DUPLICATE
            },
            recordedAtEpochMillis = command.occurredAtEpochMillis,
        )
    }

    override suspend fun readCurrentTutorFreeResponseDispatchState(
        query: CurrentTutorFreeResponseActionClaimQuery,
    ): CurrentTutorFreeResponseDispatchState {
        val current = state.value ?: return CurrentTutorFreeResponseDispatchState.NOT_CLAIMED
        if (
            current.status != CurrentTutorSessionHostWorkStatus.ACTIVE ||
            current.workId != query.expectedWorkId ||
            current.stateVersion != query.expectedWorkStateVersion ||
            current.stateFingerprint != query.expectedWorkStateFingerprint ||
            current.presentationToken != query.presentationToken ||
            current.evidenceRequestId != query.evidenceRequestId
        ) return CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        val outbox = freeResponseOutboxes[query.actionToken]
            ?: return CurrentTutorFreeResponseDispatchState.NOT_CLAIMED
        return outbox.state
    }

    override suspend fun acquireCurrentTutorFreeResponseDispatch(
        command: AcquireCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchAcquireResult {
        val current = state.value
            ?.takeIf { it.status == CurrentTutorSessionHostWorkStatus.ACTIVE }
            ?: return CurrentTutorFreeResponseDispatchAcquireResult.NotAvailable
        val entry = if (command.actionToken != null) {
            freeResponseOutboxes[command.actionToken]
        } else {
            freeResponseOutboxes.values.firstOrNull {
                it.state == CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH ||
                    (it.state == CurrentTutorFreeResponseDispatchState.IN_FLIGHT &&
                        (it.leaseGenerationId != command.leaseGenerationId ||
                            (it.leaseExpiresAtEpochMillis ?: Long.MIN_VALUE) <=
                            command.occurredAtEpochMillis))
            }
        } ?: return CurrentTutorFreeResponseDispatchAcquireResult.NotAvailable
        when (entry.state) {
            CurrentTutorFreeResponseDispatchState.COMPLETED ->
                return CurrentTutorFreeResponseDispatchAcquireResult.Completed
            CurrentTutorFreeResponseDispatchState.FAILED_CLOSED ->
                return CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed
            CurrentTutorFreeResponseDispatchState.IN_FLIGHT -> if (
                entry.leaseGenerationId == command.leaseGenerationId &&
                (entry.leaseExpiresAtEpochMillis ?: Long.MAX_VALUE) > command.occurredAtEpochMillis
            ) {
                return CurrentTutorFreeResponseDispatchAcquireResult.Busy
            }
            CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH -> Unit
            CurrentTutorFreeResponseDispatchState.NOT_CLAIMED ->
                return CurrentTutorFreeResponseDispatchAcquireResult.NotAvailable
        }
        val actionToken = command.actionToken ?: freeResponseOutboxes.entries
            .first { it.value === entry }.key
        val leaseToken = sha256(
            "lease:$actionToken:${command.leaseOwnerId}:${command.leaseGenerationId}:" +
                command.occurredAtEpochMillis,
        )
        entry.state = CurrentTutorFreeResponseDispatchState.IN_FLIGHT
        entry.leaseToken = leaseToken
        entry.leaseGenerationId = command.leaseGenerationId
        entry.leaseExpiresAtEpochMillis = command.occurredAtEpochMillis + command.leaseDurationMillis
        val acquired = CurrentTutorFreeResponseDispatchAcquireResult.Acquired(
            FakeFreeResponseDispatchLease(
                learnerId = current.learnerId,
                sessionId = current.sessionId,
                actionToken = actionToken,
                workId = current.workId,
                workStateVersion = current.stateVersion,
                workStateFingerprint = current.stateFingerprint,
                presentationToken = current.presentationToken,
                evidenceRequestId = checkNotNull(current.evidenceRequestId),
                answerBinding = entry.answerBinding,
                answer = entry.answer,
                canonicalOccurredAtEpochMillis = entry.canonicalOccurredAtEpochMillis,
                leaseToken = leaseToken,
                leaseExpiresAtEpochMillis = command.occurredAtEpochMillis + command.leaseDurationMillis,
            ),
        )
        if (crashAfterNextFreeResponseAcquire) {
            crashAfterNextFreeResponseAcquire = false
            throw FakeProcessDeath()
        }
        return acquired
    }

    override suspend fun completeCurrentTutorFreeResponseDispatch(
        command: CompleteCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult = mutateFreeResponseLease(
        command.actionToken,
        command.leaseToken,
        CurrentTutorFreeResponseDispatchState.COMPLETED,
    )

    override suspend fun releaseCurrentTutorFreeResponseDispatch(
        command: ReleaseCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult = mutateFreeResponseLease(
        command.actionToken,
        command.leaseToken,
        CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH,
    )

    override suspend fun failCurrentTutorFreeResponseDispatchClosed(
        command: FailCurrentTutorFreeResponseDispatchClosedCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult = mutateFreeResponseLease(
        command.actionToken,
        command.leaseToken,
        CurrentTutorFreeResponseDispatchState.FAILED_CLOSED,
    ).also {
        if (it == CurrentTutorFreeResponseDispatchMutationResult.APPLIED) {
            freeResponseOutboxes[command.actionToken]?.answer = ""
        }
    }

    private fun mutateFreeResponseLease(
        actionToken: String,
        leaseToken: String,
        next: CurrentTutorFreeResponseDispatchState,
    ): CurrentTutorFreeResponseDispatchMutationResult {
        val entry = freeResponseOutboxes[actionToken]
            ?: return CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        if (entry.state == next) return CurrentTutorFreeResponseDispatchMutationResult.DUPLICATE
        if (
            entry.state != CurrentTutorFreeResponseDispatchState.IN_FLIGHT ||
            entry.leaseToken != leaseToken
        ) return CurrentTutorFreeResponseDispatchMutationResult.NOT_CURRENT
        entry.state = next
        entry.leaseToken = null
        entry.leaseGenerationId = null
        entry.leaseExpiresAtEpochMillis = null
        if (
            next == CurrentTutorFreeResponseDispatchState.COMPLETED ||
            next == CurrentTutorFreeResponseDispatchState.FAILED_CLOSED
        ) entry.answer = ""
        return CurrentTutorFreeResponseDispatchMutationResult.APPLIED
    }

    override fun observeCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): Flow<CurrentTutorSessionHostWorkRecord?> = state

    override suspend fun markCurrentTutorSessionHostWorkActive(
        command: MarkCurrentTutorSessionHostWorkActiveCommand,
    ): CurrentTutorSessionHostWorkWriteResult {
        val current = state.value ?: return result(
            CurrentTutorSessionHostWorkWriteDisposition.NOT_FOUND,
            null,
            command.occurredAtEpochMillis,
        )
        if (current.status == CurrentTutorSessionHostWorkStatus.ACTIVE) {
            return result(
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
                current,
                command.occurredAtEpochMillis,
            )
        }
        if (
            current.status != CurrentTutorSessionHostWorkStatus.STAGED ||
            current.workId != command.expectedWorkId ||
            current.stateVersion != command.expectedStateVersion ||
            current.stateFingerprint != command.expectedStateFingerprint ||
            current.targetScopeId != command.expectedTargetScopeId ||
            current.targetActivationFingerprint != command.expectedTargetActivationFingerprint
        ) return result(CurrentTutorSessionHostWorkWriteDisposition.STALE, current, command.occurredAtEpochMillis)
        val next = current.copy(
            status = CurrentTutorSessionHostWorkStatus.ACTIVE,
            activeScopeId = current.targetScopeId,
            stateVersion = current.stateVersion + 1,
            stateFingerprint = sha256("active:${current.stateFingerprint}"),
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
        state.value = next
        return result(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, next, command.occurredAtEpochMillis)
    }

    override suspend fun revokeCurrentTutorSessionHostWork(
        command: RevokeCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult {
        val current = state.value ?: return result(
            CurrentTutorSessionHostWorkWriteDisposition.NOT_FOUND,
            null,
            command.occurredAtEpochMillis,
        )
        if (current.status == CurrentTutorSessionHostWorkStatus.REVOKED) {
            return result(
                CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
                current,
                command.occurredAtEpochMillis,
            )
        }
        if (rejectNextRevoke) {
            rejectNextRevoke = false
            return result(
                CurrentTutorSessionHostWorkWriteDisposition.STALE,
                current,
                command.occurredAtEpochMillis,
            )
        }
        val next = current.copy(
            learningWritesAllowed = false,
            status = CurrentTutorSessionHostWorkStatus.REVOKED,
            revocationReason = command.reason,
            stateVersion = current.stateVersion + 1,
            stateFingerprint = sha256("revoked:${current.stateFingerprint}:${command.reason}"),
            updatedAtEpochMillis = command.occurredAtEpochMillis,
        )
        state.value = next
        return result(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, next, command.occurredAtEpochMillis)
    }

    private fun result(
        disposition: CurrentTutorSessionHostWorkWriteDisposition,
        record: CurrentTutorSessionHostWorkRecord?,
        at: Long,
    ) = CurrentTutorSessionHostWorkWriteResult(disposition, record, at)
}

private fun StageCurrentTutorSessionHostWorkCommand.toRecord() =
    CurrentTutorSessionHostWorkRecord(
        workId = workId,
        learnerId = learnerId,
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        subject = subject,
        authorityConversationId = authorityConversationId,
        authorityConversationGeneration = authorityConversationGeneration,
        authorityConversationStateVersion = authorityConversationStateVersion,
        authorityTurnReceiptId = authorityTurnReceiptId,
        authorityTurnOrdinal = authorityTurnOrdinal,
        authorityRequestVersion = authorityRequestVersion,
        authorityDirectiveFingerprint = authorityDirectiveFingerprint,
        modelTaskRequestId = modelTaskRequestId,
        modelTaskRequestFingerprint = modelTaskRequestFingerprint,
        problemAnchorId = problemAnchorId,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritesAllowed = learningWritesAllowed,
        learningWritePermissionVersion = learningWritePermissionVersion,
        visualIntent = visualIntent,
        visualIntentVersion = visualIntentVersion,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        attemptOrdinal = attemptOrdinal,
        requestVersion = requestVersion,
        evidenceRequestId = evidenceRequestId,
        pendingInteractionKind = pendingInteractionKind,
        targetScopeId = targetScopeId,
        targetActivationFingerprint = targetActivationFingerprint,
        constrainedTutorContentFingerprint = constrainedTutorContentFingerprint,
        activeScopeId = null,
        presentationToken = presentationToken,
        payloadFingerprint = payloadFingerprint,
        status = CurrentTutorSessionHostWorkStatus.STAGED,
        revocationReason = null,
        stateVersion = 0,
        stateFingerprint = sha256("staged:$payloadFingerprint"),
        createdAtEpochMillis = occurredAtEpochMillis,
        updatedAtEpochMillis = occurredAtEpochMillis,
    )

private fun CurrentTutorSessionHostWorkRecord.matches(
    command: StageCurrentTutorSessionHostWorkCommand,
): Boolean =
    learnerId == command.learnerId &&
        sessionId == command.sessionId &&
        modelTaskRequestId == command.modelTaskRequestId &&
        modelTaskRequestFingerprint == command.modelTaskRequestFingerprint &&
        authorityDirectiveFingerprint == command.authorityDirectiveFingerprint &&
        constrainedTutorContentFingerprint == command.constrainedTutorContentFingerprint &&
        targetActivationFingerprint == command.targetActivationFingerprint &&
        presentationToken == command.presentationToken &&
        payloadFingerprint == command.payloadFingerprint

private fun confirmedSession() = ConfirmedTutorSession(
    sessionId = "session-current-host",
    draftId = "draft-current-host",
    draftRevisionNumber = 1,
    subject = SubjectKind.PHYSICS.name,
    title = "当前题",
    questionDocument = capturedQuestion(),
    sourceImageUri = "content://current-host/question",
    createdAtEpochMillis = 49_000,
    isSaved = false,
    errorBookEntryId = null,
)

private fun capturedQuestion(): CapturedQuestionDocument {
    val document = QuestionDocument(
        id = "question-current-host",
        blocks = listOf(ContentBlock.Paragraph("stem", "线框转动时感应电动势如何变化？")),
    )
    return CapturedQuestionDocument(
        document = document,
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = "source-current-host",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = QuestionBlockProvenance.USER_CORRECTION,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            ),
        ),
    )
}

private fun tutorModelTask(
    session: ConfirmedTutorSession,
    mode: TutorExplanationMode,
    outputSeed: String,
    learningWritesAllowed: Boolean = true,
): ModelTaskSnapshot {
    val input = TutorPlanInput(
        sessionId = session.sessionId,
        draftRevisionNumber = 1,
        subject = SubjectKind.PHYSICS.name,
        questionDocument = session.questionDocument.document,
        explanationMode = mode,
        modeVersion = 1,
        learningWritePermissionVersion = 1,
        allowLongTermLearningWrites = learningWritesAllowed,
    )
    val directive = if (mode == TutorExplanationMode.GUIDED) {
        TutorInteractionDirective.FreeResponse("请写下下一步的关系式。")
    } else {
        null
    }
    val output = TutorPlanOutput(
        sessionId = session.sessionId,
        draftRevisionNumber = 1,
        questionDocumentId = input.questionDocument.id,
        plan = TutorTurnPlan(
            openingMarkdown = "先看磁通量。$outputSeed",
            responseIntent = if (directive == null) TutorResponseIntent.EXPLAIN else TutorResponseIntent.ASK,
            solutionRevealed = directive == null,
            interactionDirective = directive,
            solutionMarkdown = "根据磁通量随时间的变化分析。$outputSeed",
            alternateMethodMarkdown = "也可以比较关键位置。",
            difficultyReasonMarkdown = "方向与变化率需要同时判断。",
            targetedEvidenceLabels = emptyList(),
            inferredKnowledgeLabels = listOf("电磁感应"),
        ),
        modelVersion = "model-current-host",
    ).locallyConstrainedFor(input)
    val request = ModelTaskRequest(
        requestId = "request-current-host",
        input = input,
        occurredAtEpochMillis = 49_500,
    )
    return ModelTaskSnapshot(
        taskId = "task-current-host",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.SUCCEEDED,
        stateVersion = 2,
        stage = ModelTaskStage.COMPLETE,
        userMessage = "",
        attemptCount = 1,
        output = output,
        createdAtEpochMillis = 49_500,
        updatedAtEpochMillis = 49_900,
    )
}

private fun verifiedMaterial(
    teachingConstraint: TutorTeachingConstraint = TutorTeachingConstraint.EXPLAIN_DIRECTLY,
    trustedAnswerInteraction: CurrentTutorPreparedAnswerInteraction? = null,
    prohibitedEvaluatorExecutionFingerprint: String? = null,
    knowledgeNodeIds: List<String> = listOf("physics.electromagnetic.induction"),
): CurrentTutorVerifiedMaterial {
    val authority = KnowledgeReferenceProofAuthority.create()
    val proofs = knowledgeNodeIds.mapIndexed { index, knowledgeNodeId ->
        authority.issuer.issue(
            KnowledgeNodeRef(
                subject = SubjectKind.PHYSICS,
                knowledgeNodeId = knowledgeNodeId,
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            ),
            sha256(if (index == 0) "manifest" else "manifest-$index"),
            1,
        )
    }
    return CurrentTutorVerifiedMaterial(
        problemAnchorId = "anchor-current-host",
        problemFingerprint = sha256("problem"),
        problemFamilyFingerprint = sha256("family"),
        attributionPolicyVersion = "attribution-v1",
        responsePolicyVersion = "response-v1",
        rubricCanonicalFingerprint = sha256("rubric"),
        verifiedKnowledgeProofs = proofs,
        teachingReferences = proofs.map { proof ->
            VerifiedOpenResponseEvaluationTeachingReference(
                proof = proof,
                label = "法拉第电磁感应定律",
                constraint = teachingConstraint,
            )
        },
        evaluator = OpenResponseEvaluatorKind.RUBRIC,
        evaluatorPolicyFingerprint = sha256("evaluator-policy"),
        prohibitedEvaluatorExecutionFingerprint = prohibitedEvaluatorExecutionFingerprint,
        trustedAnswerInteraction = trustedAnswerInteraction,
    )
}

private fun visualTargetScene() = TutorVisualDocumentScene(
    sceneId = "scene-current-host",
    title = "线框位置",
    panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
    elements = listOf(
        TutorVisual2DNodeElement(
            elementId = "target-a",
            panelId = "panel",
            kind = TutorVisual2DNodeKind.RECTANGLE,
            label = "位置 A",
        ),
        TutorVisual2DNodeElement(
            elementId = "target-b",
            panelId = "panel",
            kind = TutorVisual2DNodeKind.RECTANGLE,
            label = "位置 B",
        ),
    ),
    steps = listOf(
        TutorVisualStep(
            stepId = "locate",
            label = "判断变化最快的位置",
            focusElementIds = listOf("target-a", "target-b"),
            primaryRelationElementId = "target-a",
        ),
    ),
    fallbackMarkdown = "比较两个位置的磁通量变化率。",
    accessibilitySummary = "线框的两个候选位置。",
)

private fun sha256CanonicalPresentation(
    requestFingerprint: String,
    modeVersion: Long,
    learningWritesAllowed: Boolean,
    learningWritePermissionVersion: Long,
    output: TutorPlanOutput,
): String = com.tingyun.smartmistakebook.core.model.CanonicalSha256(
    "current-tutor-presentation-v1",
)
    .field("taskRequestFingerprint", requestFingerprint)
    .field(
        "constrainedOutput",
        com.tingyun.smartmistakebook.core.model.ModelTaskCodec.encodeOutput(output),
    )
    .field("modeVersion", modeVersion)
    .field("learningWritesAllowed", learningWritesAllowed)
    .field("learningWritePermissionVersion", learningWritePermissionVersion)
    .finish()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private data class FakeFreeResponseOutbox(
    var answer: String,
    val answerBinding: String,
    val canonicalOccurredAtEpochMillis: Long,
    var state: CurrentTutorFreeResponseDispatchState =
        CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH,
    var leaseToken: String? = null,
    var leaseGenerationId: String? = null,
    var leaseExpiresAtEpochMillis: Long? = null,
)

private class FakeProcessDeath : Error("simulated process death")

private data class FakeFreeResponseDispatchLease(
    override val learnerId: String,
    override val sessionId: String,
    override val actionToken: String,
    override val workId: String,
    override val workStateVersion: Long,
    override val workStateFingerprint: String,
    override val presentationToken: String,
    override val evidenceRequestId: String,
    override val answerBinding: String,
    override val answer: String,
    override val canonicalOccurredAtEpochMillis: Long,
    override val leaseToken: String,
    override val leaseExpiresAtEpochMillis: Long,
) : CurrentTutorFreeResponseDispatchLease {
    override fun toString(): String = "FakeFreeResponseDispatchLease(<redacted>)"
}
