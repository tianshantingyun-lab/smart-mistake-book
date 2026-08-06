package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorResponseIntent
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production admission predicate used between Room snapshots and the timeline. */
class TutorLocalRecoveryAdmissionProjectionTest {
    @Test
    fun sixtyFiveSucceededRoomSnapshotsRemainAdmitted() {
        val fixture = Fixture()
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val recovered = (1..65).map { index ->
            fixture.acceptedRecovery(coordinator, index)
        }

        recovered.forEach { snapshot ->
            assertTrue(
                snapshot.hasCurrentLocalRecoveryAdmission(
                    allTasks = listOf(fixture.sourceSnapshot) + recovered,
                    question = fixture.question,
                    questionDocumentFingerprint = fixture.documentFingerprint,
                    presentationCredentials = fixture.credentials,
                ),
            )
        }
    }

    @Test
    fun savedCredentialSurvivesRouteReconstructionAndRejectsCorruption() {
        val fixture = Fixture()
        val originalCoordinator = TutorRecoveryAuthorityCoordinator()
        val recovered = fixture.acceptedRecovery(originalCoordinator, 1)
        val encoded = fixture.credentials.single().encode()
        val restored = TutorLocalRecoveryPresentationCredential.decode(encoded)
        val corrupted = encoded.dropLast(1) + if (encoded.last() == '0') "1" else "0"

        assertTrue(
            recovered.hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(fixture.sourceSnapshot, recovered),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = listOfNotNull(restored),
            ),
        )
        assertFalse(
            recovered.hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(fixture.sourceSnapshot, recovered),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = listOfNotNull(
                    TutorLocalRecoveryPresentationCredential.decode(corrupted),
                ),
            ),
        )
    }

    @Test
    fun coldStartAndNewNavigationOwnerReadAcceptedAuthorityFromDurableStore() = runTest {
        val fixture = Fixture()
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val recovered = fixture.acceptedRecovery(coordinator, 1)
        val credential = fixture.credentials.single()
        val storage = InMemoryPresentationStorage()
        val scopeKey = localRecoveryPresentationScopeKey(
            sessionId = fixture.question.sessionId,
            revisionNumber = fixture.question.revisionNumber,
            questionDocumentFingerprint = fixture.documentFingerprint,
        )
        val firstOwner = BoundedTutorLocalRecoveryPresentationStore(
            storage = storage,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 100L },
        )
        assertTrue(firstOwner.persist(scopeKey, credential))

        val coldStartOwner = BoundedTutorLocalRecoveryPresentationStore(
            storage = storage,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 200L },
        )
        val restored = coldStartOwner.read(scopeKey)

        assertEquals(listOf(credential), restored)
        assertTrue(
            recovered.hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(fixture.sourceSnapshot, recovered),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = restored,
            ),
        )
    }

    @Test
    fun durableStoreRejectsCredentialReplayIntoAnotherQuestionScope() = runTest {
        val fixture = Fixture()
        fixture.acceptedRecovery(TutorRecoveryAuthorityCoordinator(), 1)
        val storage = InMemoryPresentationStorage()
        val store = BoundedTutorLocalRecoveryPresentationStore(
            storage = storage,
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        val anotherScope = localRecoveryPresentationScopeKey(
            sessionId = "another-session",
            revisionNumber = fixture.question.revisionNumber,
            questionDocumentFingerprint = fixture.documentFingerprint,
        )

        assertFalse(store.persist(anotherScope, fixture.credentials.single()))
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun durableStoreBoundsCompletedRowsScopesAndBytes() = runTest {
        val fixture = Fixture()
        val coordinator = TutorRecoveryAuthorityCoordinator()
        repeat(160) { index -> fixture.acceptedRecovery(coordinator, index + 1) }
        val storage = InMemoryPresentationStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = BoundedTutorLocalRecoveryPresentationStore(
            storage = storage,
            workerDispatcher = dispatcher,
            clock = { 300L },
        )
        val primaryScope = localRecoveryPresentationScopeKey(
            fixture.question.sessionId,
            fixture.question.revisionNumber,
            fixture.documentFingerprint,
        )
        fixture.credentials.forEach { credential ->
            assertTrue(store.persist(primaryScope, credential))
        }

        val retained = store.read(primaryScope)
        assertTrue(retained.size in 1..128)
        assertEquals(
            "tutor-plan:local-recovery:request-160",
            retained.last().recoveredRequestId,
        )
        assertTrue(
            storage.values.getValue(primaryScope).toByteArray().size <= 64 * 1_024,
        )

        repeat(40) { index ->
            val scopedFixture = Fixture(sessionId = "session-$index")
            val scopedCoordinator = TutorRecoveryAuthorityCoordinator()
            scopedFixture.acceptedRecovery(scopedCoordinator, index + 1)
            val scope = localRecoveryPresentationScopeKey(
                sessionId = scopedFixture.question.sessionId,
                revisionNumber = scopedFixture.question.revisionNumber,
                questionDocumentFingerprint = scopedFixture.documentFingerprint,
            )
            assertTrue(store.persist(scope, scopedFixture.credentials.single()))
        }
        assertTrue(storage.values.size <= 32)
        assertTrue(
            storage.values.entries.sumOf { (key, value) ->
                key.toByteArray().size + value.toByteArray().size
            } <= 256 * 1_024,
        )
    }

    @Test
    fun changedSourceStateInvalidatesSucceededRoomSnapshot() {
        val fixture = Fixture()
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val recovered = fixture.acceptedRecovery(coordinator, 1)
        val changedSource = fixture.sourceSnapshot.copy(
            status = ModelTaskStatus.STREAMING,
            stateVersion = fixture.sourceSnapshot.stateVersion + 1,
        )

        assertFalse(
            recovered.hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(changedSource, recovered),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = fixture.credentials,
            ),
        )
    }

    @Test
    fun revokeAndCompletionRaceHasOneAtomicWinner() {
        val fixture = Fixture()

        val revokeFirst = TutorRecoveryAuthorityCoordinator()
        val revokeFirstToken = fixture.admitRecovery(revokeFirst, 1)
        revokeFirst.revoke()
        assertFalse(
            revokeFirst.acceptCompletion(
                revokeFirstToken,
                fixture.runtime,
                fixture.operation,
                fixture.recoveredRequest(1).requestId,
            ) != null,
        )
        assertFalse(
            fixture.succeededSnapshot(1).hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(fixture.sourceSnapshot),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = emptyList(),
            ),
        )

        val completionFirst = TutorRecoveryAuthorityCoordinator()
        val completionFirstToken = fixture.admitRecovery(completionFirst, 2)
        val completionCredential = completionFirst.acceptCompletion(
                completionFirstToken,
                fixture.runtime,
                fixture.operation,
                fixture.recoveredRequest(2).requestId,
            )
        assertTrue(completionCredential != null)
        completionFirst.revoke()
        assertTrue(
            fixture.succeededSnapshot(2).hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(fixture.sourceSnapshot),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = listOfNotNull(completionCredential),
            ),
        )
    }

    @Test
    fun revokeWhileCompletionCallbackIsSuspendedCannotPublishRoomResult() = runTest {
        val fixture = Fixture()
        val coordinator = TutorRecoveryAuthorityCoordinator()
        val token = fixture.admitRecovery(coordinator, 1)
        val request = fixture.recoveredRequest(1)
        val callbackEntered = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = TutorActiveStreamOwner(
            scope = this,
            workerDispatcher = dispatcher,
            initialMode = TutorExplanationMode.DIRECT,
            ownerVersion = fixture.runtime.activeOwnerEpoch,
        )

        owner.submit(
            studentMessage = "恢复当前讲解",
            startsNewTurn = false,
            executionAuthorized = {
                coordinator.isRuntimeCurrent(token, fixture.runtime)
            },
            resultAuthorized = {
                coordinator.isRuntimeCurrent(token, fixture.runtime)
            },
            onAuthorizedCompletion = { requestId ->
                callbackEntered.complete(Unit)
                releaseCallback.await()
                coordinator.acceptCompletion(
                    token,
                    fixture.runtime,
                    fixture.operation,
                    requestId,
                ) != null
            },
        ) {
            TutorPreparedStream(request.requestId) { identity ->
                flowOf(
                    TutorStreamEvent.Completed(
                        identity,
                        TutorMarkdownSnapshot("这条迟到讲解不能展示。", ""),
                    ),
                )
            }
        }
        runCurrent()
        callbackEntered.await()

        coordinator.revoke()
        releaseCallback.complete(Unit)
        runCurrent()

        assertTrue(owner.state.value.active == null)
        assertFalse(
            fixture.succeededSnapshot(1).hasCurrentLocalRecoveryAdmission(
                allTasks = listOf(fixture.sourceSnapshot),
                question = fixture.question,
                questionDocumentFingerprint = fixture.documentFingerprint,
                presentationCredentials = emptyList(),
            ),
        )
        owner.close()
    }

    private class Fixture(
        sessionId: String = "session",
    ) {
        val credentials = mutableListOf<TutorLocalRecoveryPresentationCredential>()
        val question = question(sessionId)
        val documentFingerprint = CapturedQuestionDocumentFingerprint.of(question.questionDocument)
        private val sourceRequest = ModelTaskRequest(
            requestId = "source-request",
            input = TutorPlanInput(
                sessionId = question.sessionId,
                draftRevisionNumber = question.revisionNumber,
                subject = question.subject,
                questionDocument = question.questionDocument.document,
                explanationMode = TutorExplanationMode.DIRECT,
                modeVersion = 5,
                learningWritePermissionVersion = 7,
            ),
            occurredAtEpochMillis = 17,
        )
        val sourceSnapshot = snapshot(
            taskId = "source-task",
            request = sourceRequest,
            status = ModelTaskStatus.RUNNING,
            stateVersion = 19,
        )
        val runtime = TutorRecoveryRuntimeAuthority(
            question = question.toTutorMasteryRecoveryReadKey(),
            questionDocumentFingerprint = documentFingerprint,
            explanationMode = TutorExplanationMode.DIRECT,
            effectiveExplanationMode = TutorExplanationMode.DIRECT,
            modeVersion = 5,
            learningWritePermissionVersion = 7,
            learningWritesAllowed = true,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            conversationGeneration = 11,
            activeOwnerEpoch = 12,
            providerId = "local-provider",
            modelId = "local-model",
            providerConfigurationVersion = "configuration",
            providerAuthorityGeneration = 13,
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
            taskKind = ModelTaskKind.TUTOR_PLAN,
            approvedAtEpochMillis = 0,
        )
        val operation = TutorRecoveryOperation(
            taskKind = ModelTaskKind.TUTOR_PLAN,
            sourceRequest = sourceRequest,
            taskStateVersion = sourceSnapshot.stateVersion,
        )

        fun recoveredRequest(index: Int) = sourceRequest.copy(
            requestId = "tutor-plan:local-recovery:request-$index",
        )

        fun admitRecovery(
            coordinator: TutorRecoveryAuthorityCoordinator,
            index: Int,
        ): TutorRecoveryAuthorityToken {
            val token = coordinator.issue(runtime, operation)
            assertTrue(coordinator.admitLocalRequest(recoveredRequest(index), token))
            return token
        }

        fun acceptedRecovery(
            coordinator: TutorRecoveryAuthorityCoordinator,
            index: Int,
        ): ModelTaskSnapshot {
            val token = admitRecovery(coordinator, index)
            val request = recoveredRequest(index)
            val credential = coordinator.acceptCompletion(
                token,
                runtime,
                operation,
                request.requestId,
            ) ?: error("Recovery credential was not accepted")
            credentials += credential
            return succeededSnapshot(index)
        }

        fun succeededSnapshot(index: Int) = snapshot(
            taskId = "recovered-task-$index",
            request = recoveredRequest(index),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 3,
            output = TutorPlanOutput(
                sessionId = question.sessionId,
                draftRevisionNumber = question.revisionNumber,
                questionDocumentId = question.questionDocument.document.id,
                plan = TutorTurnPlan(
                    openingMarkdown = "先看题目条件。",
                    responseIntent = TutorResponseIntent.EXPLAIN,
                    solutionRevealed = true,
                    solutionMarkdown = "由条件整理并完成计算。",
                    alternateMethodMarkdown = "也可以利用图像关系判断。",
                    difficultyReasonMarkdown = "关键是保持条件一致。",
                    targetedEvidenceLabels = emptyList(),
                    inferredKnowledgeLabels = listOf("函数关系"),
                ),
                modelVersion = "local-model-v1",
            ),
        )

        private fun snapshot(
            taskId: String,
            request: ModelTaskRequest,
            status: ModelTaskStatus,
            stateVersion: Long,
            output: TutorPlanOutput? = null,
        ) = ModelTaskSnapshot(
            taskId = taskId,
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = stateVersion,
            stage = if (status == ModelTaskStatus.SUCCEEDED) {
                ModelTaskStage.COMPLETE
            } else {
                ModelTaskStage.PREPARING
            },
            userMessage = "",
            attemptCount = 1,
            output = output,
            createdAtEpochMillis = 17,
            updatedAtEpochMillis = 18,
        )

        private companion object {
            fun question(sessionId: String) = TutorQuestionContext(
                sessionId = sessionId,
                revisionNumber = 3,
                subject = "MATH",
                title = "函数关系",
                questionDocument = CapturedQuestionDocument(
                    document = QuestionDocument(
                        id = "question",
                        blocks = listOf(ContentBlock.Paragraph("stem", "求解 x")),
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
    }

    private class InMemoryPresentationStorage : TutorLocalRecoveryPresentationStorage {
        val values = linkedMapOf<String, String>()

        override fun readAll(): Map<String, String> = values.toMap()

        override fun commit(
            upserts: Map<String, String>,
            removals: Set<String>,
        ): Boolean {
            removals.forEach(values::remove)
            values.putAll(upserts)
            return true
        }
    }
}
