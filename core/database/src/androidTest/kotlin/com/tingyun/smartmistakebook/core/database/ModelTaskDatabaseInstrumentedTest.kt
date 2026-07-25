package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelTaskDatabaseInstrumentedTest {
    private lateinit var store: StudyDatabasePort

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun taskCreationAndTransitionsAreIdempotentVersionedAndObservable() = runBlocking {
        val request = request()
        val create = createCommand(request)
        val initial = store.createModelTask(create)
        val replay = store.createModelTask(create)

        assertTrue(initial.applied)
        assertFalse(replay.applied)
        assertEquals(initial.snapshot, replay.snapshot)
        assertEquals(ModelTaskStatus.WAITING_FOR_MODEL, initial.snapshot.status)

        val queued = store.transitionModelTask(
            transition(initial.snapshot, ModelTaskStatus.QUEUED, ModelTaskStage.PREPARING, 200),
        )
        val staleReplay = store.transitionModelTask(
            transition(initial.snapshot, ModelTaskStatus.QUEUED, ModelTaskStage.PREPARING, 200),
        )
        assertTrue(queued.applied)
        assertFalse(staleReplay.applied)
        assertEquals(queued.snapshot, staleReplay.snapshot)

        val running = store.transitionModelTask(
            transition(
                queued.snapshot,
                ModelTaskStatus.RUNNING,
                ModelTaskStage.READING_IMAGE,
                300,
                provider = provider(),
                attemptCount = 1,
            ),
        ).snapshot
        val output = CaptureAssessmentOutput(
            CaptureAssessment(
                decision = CaptureAssessmentDecision.PASS,
                issues = emptyList(),
                suggestedActions = emptyList(),
                modelVersion = "demo/capture-v1",
            ),
        )
        val completed = store.transitionModelTask(
            transition(
                running,
                ModelTaskStatus.SUCCEEDED,
                ModelTaskStage.COMPLETE,
                400,
                provider = running.provider,
                output = output,
                attemptCount = 1,
            ),
        ).snapshot

        assertEquals(ModelTaskStatus.SUCCEEDED, completed.status)
        assertEquals(3L, completed.stateVersion)
        assertEquals(output, completed.output)
        assertEquals(completed, store.observeModelTask(request.requestId).first())
        assertEquals(
            listOf(completed),
            store.observeModelTasks("draft-1", ModelTaskKind.CAPTURE_ASSESS).first(),
        )
        assertTrue(
            store.observeModelTasks("another-draft", ModelTaskKind.CAPTURE_ASSESS).first().isEmpty(),
        )
    }

    @Test
    fun sameRequestIdRejectsDifferentPayload() = runBlocking {
        val request = request()
        store.createModelTask(createCommand(request))
        val changed = request.copy(
            input = (request.input as CaptureAssessmentInput).copy(imageWidth = 720),
        )

        assertTrue(runCatching { store.createModelTask(createCommand(changed)) }.isFailure)
    }

    @Test
    fun sameTutorResponsePayloadAndRequestIdWithDifferentTimestampsJoinsExistingTask() =
        runBlocking {
            val original = tutorRespondRequest(
                occurredAtEpochMillis = 100,
                approvedAtEpochMillis = 110,
            )
            val replay = tutorRespondRequest(
                occurredAtEpochMillis = 200,
                approvedAtEpochMillis = 210,
            )

            val initialWrite = store.createModelTask(
                createCommand(original, taskId = "task-tutor-respond-1"),
            )
            val replayWrite = store.createModelTask(
                createCommand(replay, taskId = "task-tutor-respond-1"),
            )

            assertTrue(initialWrite.applied)
            assertFalse(replayWrite.applied)
            assertEquals(initialWrite.snapshot, replayWrite.snapshot)
            assertEquals(
                listOf(initialWrite.snapshot),
                store.observeModelTasks("tutor-session-1", ModelTaskKind.TUTOR_RESPOND).first(),
            )
        }

    @Test
    fun sameTutorResponseLogicalOperationAllowsANewAuthorizedEnvelopeInTheSameSlot() = runBlocking {
        val original = tutorRespondRequest(
            requestId = "tutor-respond:request-1",
            occurredAtEpochMillis = 100,
            approvedAtEpochMillis = 110,
        )
        val reauthorized = tutorRespondRequest(
            requestId = "tutor-respond:request-2",
            occurredAtEpochMillis = 200,
            approvedAtEpochMillis = 210,
        )

        val first = store.createModelTask(createCommand(original, taskId = "task-tutor-respond-1"))
        val second = store.createModelTask(
            createCommand(reauthorized, taskId = "task-tutor-respond-2"),
        )

        assertTrue(first.applied)
        assertTrue(second.applied)
        assertEquals(original.input, reauthorized.input)
        assertEquals(
            listOf(original.requestId, reauthorized.requestId),
            store.observeModelTasks("tutor-session-1", ModelTaskKind.TUTOR_RESPOND)
                .first()
                .map { it.request.requestId },
        )
    }

    @Test
    fun differentTutorResponsePayloadForSameSlotThrowsImmutableConflict() = runBlocking {
        val accepted = tutorRespondRequest(
            requestId = "tutor-respond:request-1",
            studentMessage = "请解释当前题这一步。",
        )
        val competing = tutorRespondRequest(
            requestId = "tutor-respond:request-2",
            studentMessage = "请直接告诉我答案。",
        )
        store.createModelTask(createCommand(accepted, taskId = "task-tutor-respond-1"))

        val conflict = runCatching {
            store.createModelTask(createCommand(competing, taskId = "task-tutor-respond-2"))
        }.exceptionOrNull()

        assertTrue(conflict is ImmutablePayloadConflictException)
        assertEquals(null, store.readModelTask(competing.requestId))
    }

    @Test
    fun logicalOperationDispatchReservationAtomicallyRejectsTheFourthEnvelope() = runBlocking {
        val queued = (1..4).map { ordinal ->
            val request = request(
                requestId = "capture-assess:logical-$ordinal",
                occurredAtEpochMillis = 100L + ordinal,
            )
            val created = store.createModelTask(
                createCommand(request, taskId = "task-capture-logical-$ordinal"),
            ).snapshot
            store.transitionModelTask(
                transition(
                    created,
                    ModelTaskStatus.QUEUED,
                    ModelTaskStage.PREPARING,
                    occurredAtEpochMillis = 200L + ordinal,
                ),
            ).snapshot
        }

    @Test
    fun recentSubjectObservationIsBoundedAndReturnedOldestToNewest() = runBlocking {
        (1..5).forEach { ordinal ->
            val request = tutorRespondRequest(
                requestId = "tutor-respond:recent-$ordinal",
                responseOrdinal = ordinal,
                studentMessage = "第 $ordinal 条消息",
                occurredAtEpochMillis = 100L + ordinal,
                approvedAtEpochMillis = 200L + ordinal,
            )
            store.createModelTask(
                createCommand(request, taskId = "task-tutor-recent-$ordinal"),
            )
        }

        val recent = store.observeRecentModelTasks(
            "tutor-session-1",
            ModelTaskKind.TUTOR_RESPOND,
            limit = 3,
        ).first()

        assertEquals(
            listOf(
                "tutor-respond:recent-3",
                "tutor-respond:recent-4",
                "tutor-respond:recent-5",
            ),
            recent.map { it.request.requestId },
        )
    }

        val reservations = queued.mapIndexed { index, snapshot ->
            store.reserveModelTaskRemoteDispatch(
                ReserveModelTaskRemoteDispatchCommand(
                    taskId = snapshot.taskId,
                    expectedStateVersion = snapshot.stateVersion,
                    expectedStatus = snapshot.status,
                    provider = provider(),
                    occurredAtEpochMillis = 300L + index,
                ),
            )
        }

        assertEquals(3, reservations.count { it.applied })
        assertEquals(1, reservations.count { it.budgetExhausted })
        assertEquals(listOf(1, 2, 3, 3), reservations.map { it.logicalDispatchCount })
        assertEquals(ModelTaskStatus.QUEUED, reservations.last().snapshot.status)
    }

    @Test
    fun versionThreeDatabaseMigratesWithoutDroppingExistingSchema() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "model-task-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 3)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val created = migrated.createModelTask(createCommand(request()))
            assertTrue(created.applied)
            assertEquals(
                ModelTaskStatus.WAITING_FOR_MODEL,
                migrated.readModelTask(request().requestId)?.status,
            )
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun versionFifteenMigrationAggregatesExistingEnvelopeDispatchCountsByLogicalOperation() =
        runBlocking {
            store.close()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val databaseName = "model-task-operation-migration-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                createDatabaseFromExportedSchema(context, databaseName, version = 15)
                val legacy = SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
                try {
                    insertLegacyModelTask(
                        legacy,
                        taskId = "legacy-task-1",
                        request = request("legacy-request-1", 100),
                        attemptCount = 1,
                    )
                    insertLegacyModelTask(
                        legacy,
                        taskId = "legacy-task-2",
                        request = request("legacy-request-2", 110),
                        attemptCount = 2,
                    )
                } finally {
                    legacy.close()
                }

                val migrated = StudyDatabaseFactory.open(context, databaseName)
                val nextRequest = request("post-migration-request", 200)
                val next = migrated.createModelTask(
                    createCommand(nextRequest, taskId = "post-migration-task"),
                ).snapshot
                val queued = migrated.transitionModelTask(
                    transition(
                        next,
                        ModelTaskStatus.QUEUED,
                        ModelTaskStage.PREPARING,
                        occurredAtEpochMillis = 210,
                    ),
                ).snapshot
                val exhausted = migrated.reserveModelTaskRemoteDispatch(
                    ReserveModelTaskRemoteDispatchCommand(
                        taskId = queued.taskId,
                        expectedStateVersion = queued.stateVersion,
                        expectedStatus = queued.status,
                        provider = provider(),
                        occurredAtEpochMillis = 220,
                    ),
                )

                assertEquals(3, next.attemptCount)
                assertFalse(exhausted.applied)
                assertTrue(exhausted.budgetExhausted)
                assertEquals(3, exhausted.logicalDispatchCount)
                migrated.close()
            } finally {
                context.deleteDatabase(databaseName)
                store = StudyDatabaseFactory.openInMemory(context)
            }
        }

    private fun createCommand(
        request: ModelTaskRequest,
        taskId: String = "task-capture-1",
    ) = CreateModelTaskCommand(
        taskId = taskId,
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        occurredAtEpochMillis = request.occurredAtEpochMillis,
    )

    private fun transition(
        snapshot: com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot,
        nextStatus: ModelTaskStatus,
        stage: ModelTaskStage,
        occurredAtEpochMillis: Long,
        provider: ProviderCapabilitySnapshot? = snapshot.provider,
        output: com.tingyun.smartmistakebook.core.model.ModelTaskOutput? = null,
        attemptCount: Int = snapshot.attemptCount,
    ) = TransitionModelTaskCommand(
        taskId = snapshot.taskId,
        expectedStateVersion = snapshot.stateVersion,
        expectedStatus = snapshot.status,
        nextStatus = nextStatus,
        stage = stage,
        userMessage = nextStatus.name,
        attemptCount = attemptCount,
        provider = provider,
        output = output,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun request(
        requestId: String = "capture-assess:request-1",
        occurredAtEpochMillis: Long = 100,
    ) = ModelTaskRequest(
        requestId = requestId,
        input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 1080,
            imageHeight = 1440,
        ),
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun insertLegacyModelTask(
        database: SQLiteDatabase,
        taskId: String,
        request: ModelTaskRequest,
        attemptCount: Int,
    ) {
        database.execSQL(
            """
            INSERT INTO model_task (
                task_id, request_id, request_fingerprint, request_snapshot, task_kind, subject_id,
                tutor_response_ordinal, status, state_version, stage, user_message, attempt_count,
                provider_snapshot, output_snapshot, failure_code, failure_message,
                failure_retryable, created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, 0, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                taskId,
                request.requestId,
                ModelTaskFingerprint.of(request),
                com.tingyun.smartmistakebook.core.model.ModelTaskCodec.encodeRequest(request),
                request.input.kind.name,
                request.input.subjectId,
                ModelTaskStatus.QUEUED.name,
                ModelTaskStage.PREPARING.name,
                "等待继续",
                attemptCount,
                request.occurredAtEpochMillis,
                request.occurredAtEpochMillis,
            ),
        )
    }

    private fun tutorRespondRequest(
        requestId: String = "tutor-respond:request-1",
        responseOrdinal: Int = 1,
        studentMessage: String = "请解释当前题这一步。",
        occurredAtEpochMillis: Long = 100,
        approvedAtEpochMillis: Long = 110,
    ) = ModelTaskRequest(
        requestId = requestId,
        input = TutorRespondInput(
            sessionId = "tutor-session-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            questionDocument = QuestionDocument(
                id = "question-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = responseOrdinal,
            studentMessage = studentMessage,
        ),
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = ModelEgressManifest(
            authorizationId = "authorization:$requestId",
            subjectId = "tutor-session-1",
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
            providerId = "provider-1",
            modelId = "tutor-model-1",
            providerConfigurationVersion = "provider-config-v1",
            promptPolicyVersion = "tutor-respond-v1",
            approvedAtEpochMillis = approvedAtEpochMillis,
            assets = emptyList(),
            disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
        ),
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "demo",
        providerDisplayName = "演示模型",
        modelId = "capture-v1",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        isDemo = true,
    )
}
