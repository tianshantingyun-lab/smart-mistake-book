package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorRecoveryAuthorityCoordinatorTest {
    @Test
    fun exactRuntimeAndOperationRemainAuthorized() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val operation = operation()

        val token = coordinator.issue(runtime, operation)

        assertTrue(coordinator.isCurrent(token, runtime, operation))
    }

    @Test
    fun everyRuntimeAuthorityDimensionInvalidatesTheSuspendedRecovery() {
        val variants = listOf<(TutorRecoveryRuntimeAuthority) -> TutorRecoveryRuntimeAuthority>(
            { it.copy(question = it.question.copy(revisionNumber = it.question.revisionNumber + 1)) },
            { it.copy(explanationMode = TutorExplanationMode.GUIDED) },
            { it.copy(effectiveExplanationMode = TutorExplanationMode.GUIDED) },
            { it.copy(modeVersion = it.modeVersion + 1) },
            {
                it.copy(
                    learningWritePermissionVersion =
                        it.learningWritePermissionVersion + 1,
                )
            },
            { it.copy(learningWritesAllowed = false) },
            { it.copy(cycleOrdinal = it.cycleOrdinal + 1) },
            { it.copy(turnOrdinal = it.turnOrdinal + 1) },
            { it.copy(conversationGeneration = it.conversationGeneration + 1) },
            { it.copy(questionDocumentFingerprint = "b".repeat(64)) },
            { it.copy(providerId = "provider-next") },
            { it.copy(modelId = "model-next") },
            { it.copy(providerConfigurationVersion = "configuration-next") },
            { it.copy(providerAuthorityGeneration = it.providerAuthorityGeneration + 1) },
            { it.copy(executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER) },
            { it.copy(taskKind = ModelTaskKind.TUTOR_RESPOND) },
            { it.copy(approvedAtEpochMillis = it.approvedAtEpochMillis + 1) },
            { it.copy(activeOwnerEpoch = it.activeOwnerEpoch + 1) },
        )

        variants.forEach { mutate ->
            val coordinator = TutorRecoveryAuthorityCoordinator()
            val runtime = runtime()
            val operation = operation()
            val token = coordinator.issue(runtime, operation)

            assertFalse(
                mutate(runtime).toString(),
                coordinator.isCurrent(token, mutate(runtime), operation),
            )
        }
    }

    @Test
    fun taskStateAndLatestIssuePreventAnOldRequestFromReturning() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val firstOperation = operation()
        val first = coordinator.issue(runtime, firstOperation)

        assertFalse(
            coordinator.isCurrent(
                first,
                runtime,
                firstOperation.copy(taskStateVersion = firstOperation.taskStateVersion + 1),
            ),
        )
        assertFalse(
            coordinator.isCurrent(
                first,
                runtime,
                firstOperation.copy(
                    sourceRequest = firstOperation.sourceRequest.copy(
                        occurredAtEpochMillis =
                            firstOperation.sourceRequest.occurredAtEpochMillis + 1,
                    ),
                ),
            ),
        )

        val second = coordinator.issue(runtime, firstOperation)
        assertFalse(coordinator.isCurrent(first, runtime, firstOperation))
        assertTrue(coordinator.isCurrent(second, runtime, firstOperation))
        assertTrue(
            coordinator.isRuntimeCurrent(
                second,
                runtime,
            ),
        )
        assertFalse(
            coordinator.isRuntimeCurrent(
                second,
                runtime.copy(modeVersion = runtime.modeVersion + 1),
            ),
        )
    }

    @Test
    fun revokeAndRouteCloseAreTerminalForIssuedTokens() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val operation = operation()
        val beforeRevoke = coordinator.issue(runtime, operation)

        coordinator.revoke()
        assertFalse(coordinator.isCurrent(beforeRevoke, runtime, operation))

        val beforeClose = coordinator.issue(runtime, operation)
        coordinator.close()
        assertFalse(coordinator.isCurrent(beforeClose, runtime, operation))
    }

    @Test
    fun acceptedCompletionRemainsBoundToItsExactRequestAndLiveGeneration() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val operation = operation()
        val token = coordinator.issue(runtime, operation)

        assertTrue(coordinator.admitLocalRequest(recoveredRequest("rebuilt-request"), token))
        val credential = coordinator.acceptCompletion(
            token,
            runtime,
            operation,
            "rebuilt-request",
        )
        assertNotNull(credential)
        assertEquals("rebuilt-request", credential?.recoveredRequestId)
        assertEquals(0, coordinator.pendingLocalAdmissionCount())

        val supersededBeforeAcceptance = coordinator.issue(runtime, operation)
        assertTrue(
            coordinator.admitLocalRequest(
                recoveredRequest("superseded-request"),
                supersededBeforeAcceptance,
            ),
        )
        coordinator.issue(runtime, operation)
        assertNull(
            coordinator.acceptCompletion(
                supersededBeforeAcceptance,
                runtime,
                operation,
                "superseded-request",
            ),
        )
        coordinator.revoke()

        assertEquals(
            credential,
            TutorLocalRecoveryPresentationCredential.decode(credential!!.encode()),
        )
        val revokedBeforeAcceptance = coordinator.issue(runtime, operation)
        assertTrue(
            coordinator.admitLocalRequest(
                recoveredRequest("late-request"),
                revokedBeforeAcceptance,
            ),
        )
        coordinator.revoke()
        assertNull(
            coordinator.acceptCompletion(
                revokedBeforeAcceptance,
                runtime,
                operation,
                "late-request",
            ),
        )
        coordinator.close()
        assertEquals(
            credential,
            TutorLocalRecoveryPresentationCredential.decode(credential.encode()),
        )
    }

    @Test
    fun moreThanPendingBudgetSequentialCompletionsSucceedAndReleasePendingState() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val operation = operation()
        val credentials = (1..300).map { index ->
            val token = coordinator.issue(runtime, operation)
            val request = recoveredRequest("rebuilt-request-$index")
            assertTrue(coordinator.admitLocalRequest(request, token))
            coordinator.acceptCompletion(token, runtime, operation, request.requestId)
                ?: error("Accepted credential disappeared")
        }

        assertEquals(0, coordinator.pendingLocalAdmissionCount())
        credentials.forEachIndexed { index, credential ->
            assertEquals("rebuilt-request-${index + 1}", credential.recoveredRequestId)
            assertEquals(
                credential,
                TutorLocalRecoveryPresentationCredential.decode(credential.encode()),
            )
        }
    }

    @Test
    fun fullPendingBudgetRejectsNewWorkWithoutDroppingExistingPendingAuthority() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val operation = operation()
        val token = coordinator.issue(runtime, operation)
        repeat(256) { offset ->
            val requestId = "bounded-request-${offset + 1}"
            assertTrue(coordinator.admitLocalRequest(recoveredRequest(requestId), token))
        }

        assertFalse(
            coordinator.admitLocalRequest(
                recoveredRequest("bounded-request-overflow"),
                token,
            ),
        )
        assertEquals(256, coordinator.pendingLocalAdmissionCount())
        assertNotNull(
            coordinator.acceptCompletion(token, runtime, operation, "bounded-request-1"),
        )
        assertEquals(255, coordinator.pendingLocalAdmissionCount())
    }

    @Test
    fun tokenFromAnotherCoordinatorNeverPassesEvenWhenRuntimeAndGenerationMatch() {
        val first = TutorRecoveryAuthorityCoordinator("authority-first")
        val second = TutorRecoveryAuthorityCoordinator("authority-second")
        val runtime = runtime()
        val operation = operation()
        val firstToken = first.issue(runtime, operation)
        second.issue(runtime, operation)

        assertFalse(second.isCurrent(firstToken, runtime, operation))
        assertFalse(second.isRuntimeCurrent(firstToken, runtime))
        assertFalse(
            second.admitLocalRequest(recoveredRequest("cross-coordinator"), firstToken),
        )
    }

    @Test
    fun sourceStateRaceCannotMintPresentationCredential() {
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val runtime = runtime()
        val operation = operation()
        val token = coordinator.issue(runtime, operation)
        assertTrue(coordinator.admitLocalRequest(recoveredRequest("raced-request"), token))

        assertNull(
            coordinator.acceptCompletion(
                token = token,
                runtime = runtime,
                operation = operation.copy(taskStateVersion = operation.taskStateVersion + 1),
                recoveredRequestId = "raced-request",
            ),
        )
    }

    @Test
    fun lifecycleIdentityChangesWhenCanonicalQuestionContentChangesUnderTheSameIds() {
        val first = question("求解 x")
        val replaced = question("求解 y")

        assertNotEquals(
            first.toCapturedTutorQuestionLifecycleIdentity(),
            replaced.toCapturedTutorQuestionLifecycleIdentity(),
        )
    }

    private fun runtime() = TutorRecoveryRuntimeAuthority(
        question = TutorMasteryRecoveryReadKey(
            sessionId = "session",
            revisionNumber = 3,
            questionDocumentId = "question",
            request = null,
        ),
        questionDocumentFingerprint = "a".repeat(64),
        explanationMode = TutorExplanationMode.DIRECT,
        effectiveExplanationMode = TutorExplanationMode.DIRECT,
        modeVersion = 5,
        learningWritePermissionVersion = 7,
        learningWritesAllowed = true,
        cycleOrdinal = 2,
        turnOrdinal = 4,
        conversationGeneration = 11,
        activeOwnerEpoch = 12,
        providerId = "provider",
        modelId = "model",
        providerConfigurationVersion = "configuration",
        providerAuthorityGeneration = 13,
        executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        taskKind = ModelTaskKind.TUTOR_PLAN,
        approvedAtEpochMillis = 0,
    )

    private fun operation() = TutorRecoveryOperation(
        taskKind = ModelTaskKind.TUTOR_PLAN,
        sourceRequest = ModelTaskRequest(
            requestId = "request",
            input = TutorPlanInput(
                sessionId = "session",
                draftRevisionNumber = 3,
                subject = "MATH",
                questionDocument = QuestionDocument(
                    id = "question",
                    blocks = listOf(ContentBlock.Paragraph("stem", "求解")),
                ),
            ),
            occurredAtEpochMillis = 17,
        ),
        taskStateVersion = 19,
    )

    private fun recoveredRequest(requestId: String) = operation().sourceRequest.copy(
        requestId = requestId,
    )

    private fun question(markdown: String) = TutorQuestionContext(
        sessionId = "session",
        revisionNumber = 3,
        subject = "MATH",
        title = "同一题目",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "question",
                blocks = listOf(ContentBlock.Paragraph("stem", markdown)),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset",
                    provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
    )
}
