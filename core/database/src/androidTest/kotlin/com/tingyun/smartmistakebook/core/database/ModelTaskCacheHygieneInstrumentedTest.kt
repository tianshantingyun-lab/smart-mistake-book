package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelTaskCacheHygieneInstrumentedTest {
    @Test
    fun firstAccessAtomicallyPurgesOnlyRebuildableLegacyTutorTaskFamilies() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "model-task-cache-hygiene-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, STUDY_DATABASE_VERSION)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw ->
                listOf(
                    "legacy-running" to ModelTaskStatus.RUNNING.name,
                    "legacy-complete" to ModelTaskStatus.SUCCEEDED.name,
                    "legacy-failed" to ModelTaskStatus.PERMANENT_FAILURE.name,
                ).forEachIndexed { index, (requestId, status) ->
                    insertLegacyTutorFamily(
                        database = raw,
                        taskId = "task-$requestId",
                        requestId = requestId,
                        operationFingerprint = "a".repeat(64),
                        status = status,
                        evidenceMass = index + 0.25,
                    )
                }
                insertLegacyTutorFamily(
                    database = raw,
                    taskId = "task-protected",
                    requestId = "legacy-protected",
                    operationFingerprint = "b".repeat(64),
                    status = ModelTaskStatus.SUCCEEDED.name,
                    evidenceMass = 9.75,
                )
                insertVisualEvidenceDependency(raw, "legacy-protected")
                insertCurrentTask(raw, currentTutorRequest())
                insertCurrentTask(raw, captureRequestWithFieldLookingQuestionText())
            }

            val store = StudyDatabaseFactory.open(context, databaseName)
            assertNotNull(store.readModelTask("current-v8"))
            assertNotNull(store.readModelTask("current-capture"))
            assertNull(store.readModelTask("legacy-running"))
            assertNull(store.readModelTask("legacy-complete"))
            assertNull(store.readModelTask("legacy-failed"))
            store.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { raw ->
                assertEquals(
                    0,
                    count(
                        raw,
                        "model_task",
                        "request_id IN ('legacy-running', 'legacy-complete', 'legacy-failed')",
                    ),
                )
                assertEquals(
                    0,
                    count(
                        raw,
                        "model_task_event",
                        "task_id IN ('task-legacy-running', 'task-legacy-complete', " +
                            "'task-legacy-failed')",
                    ),
                )
                assertEquals(0, count(raw, "model_task_operation", "operation_fingerprint = '${"a".repeat(64)}'"))
                assertEquals(1, count(raw, "model_task", "request_id = 'legacy-protected'"))
                assertEquals(1, count(raw, "tutor_visual_target_evidence", "model_task_request_id = 'legacy-protected'"))
                assertEquals(1, count(raw, "model_task", "request_id = 'current-v8'"))
                assertEquals(1, count(raw, "model_task", "request_id = 'current-capture'"))
            }

            val reopened = StudyDatabaseFactory.open(context, databaseName)
            assertNotNull(reopened.readModelTask("current-v8"))
            reopened.close()
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun insertLegacyTutorFamily(
        database: SQLiteDatabase,
        taskId: String,
        requestId: String,
        operationFingerprint: String,
        status: String,
        evidenceMass: Double,
    ) {
        insertOperation(
            database = database,
            operationFingerprint = operationFingerprint,
            subjectId = "legacy-session",
            taskKind = "TUTOR_PLAN",
        )
        val snapshot =
            """
            {
              "schemaVersion":7,
              "requestId":"$requestId",
              "input":{
                "type":"tutor_plan",
                "sessionId":"legacy-session",
                "draftRevisionNumber":1,
                "subject":"MATH",
                "questionDocument":{
                  "id":"question-1",
                  "blocks":[{"type":"paragraph","id":"stem","markdown":"求函数单调区间"}]
                },
                "relevantLearningEvidence":[{
                  "knowledgeNodeId":"node-1",
                  "displayName":"导数",
                  "level":"LEARNING",
                  "independentCorrectLowerBound":0.4,
                  "evidenceMass":$evidenceMass,
                  "independentCorrectObservationCount":2,
                  "latestEvidenceRecency":"WITHIN_7_DAYS",
                  "latestIndependentErrorRecency":"UNKNOWN"
                }],
                "projectionIsCurrent":true,
                "reviewedTeachingReferences":[],
                "cycleOrdinal":1,
                "priorConversationMemory":null,
                "priorCycleStudentMessages":[],
                "turnOrdinal":1,
                "priorTurns":[],
                "explanationMode":"GUIDED",
                "modeVersion":0,
                "learningWritePermissionVersion":0,
                "allowLongTermLearningWrites":true
              },
              "occurredAtEpochMillis":10,
              "egressManifest":null
            }
            """.trimIndent()
        insertTaskAndEvent(
            database = database,
            taskId = taskId,
            requestId = requestId,
            requestFingerprint = requestId.padEnd(64, 'c').take(64),
            operationFingerprint = operationFingerprint,
            snapshot = snapshot,
            taskKind = "TUTOR_PLAN",
            subjectId = "legacy-session",
            status = status,
        )
    }

    private fun insertCurrentTask(database: SQLiteDatabase, request: ModelTaskRequest) {
        val operationFingerprint = ModelTaskLogicalOperationFingerprint.of(request)
        insertOperation(
            database = database,
            operationFingerprint = operationFingerprint,
            subjectId = request.input.subjectId,
            taskKind = request.input.kind.name,
        )
        insertTaskAndEvent(
            database = database,
            taskId = "task-${request.requestId}",
            requestId = request.requestId,
            requestFingerprint = ModelTaskFingerprint.of(request),
            operationFingerprint = operationFingerprint,
            snapshot = ModelTaskCodec.encodeRequest(request),
            taskKind = request.input.kind.name,
            subjectId = request.input.subjectId,
            status = ModelTaskStatus.WAITING_FOR_MODEL.name,
        )
    }

    private fun insertOperation(
        database: SQLiteDatabase,
        operationFingerprint: String,
        subjectId: String,
        taskKind: String,
    ) {
        database.execSQL(
            """
            INSERT OR IGNORE INTO model_task_operation (
                operation_fingerprint, subject_id, task_kind, dispatch_count,
                created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES (?, ?, ?, 0, 10, 10)
            """.trimIndent(),
            arrayOf(operationFingerprint, subjectId, taskKind),
        )
    }

    private fun insertTaskAndEvent(
        database: SQLiteDatabase,
        taskId: String,
        requestId: String,
        requestFingerprint: String,
        operationFingerprint: String,
        snapshot: String,
        taskKind: String,
        subjectId: String,
        status: String,
    ) {
        database.execSQL(
            """
            INSERT INTO model_task (
                task_id, request_id, request_fingerprint, operation_fingerprint,
                request_snapshot, task_kind, subject_id, tutor_response_ordinal,
                status, state_version, stage, user_message, attempt_count,
                provider_snapshot, output_snapshot, failure_code, failure_message,
                failure_retryable, created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, 'WAITING', '等待', 0,
                      NULL, NULL, NULL, NULL, NULL, 10, 10)
            """.trimIndent(),
            arrayOf(
                taskId,
                requestId,
                requestFingerprint,
                operationFingerprint,
                snapshot,
                taskKind,
                subjectId,
                status,
            ),
        )
        database.execSQL(
            """
            INSERT INTO model_task_event (
                task_id, state_version, previous_status, next_status, stage, user_message,
                attempt_count, provider_snapshot, output_snapshot, failure_code,
                failure_message, failure_retryable, created_at_epoch_millis
            ) VALUES (?, 0, NULL, ?, 'WAITING', '等待', 0, NULL, NULL, NULL, NULL, NULL, 10)
            """.trimIndent(),
            arrayOf(taskId, status),
        )
    }

    private fun insertVisualEvidenceDependency(database: SQLiteDatabase, requestId: String) {
        database.execSQL(
            """
            INSERT INTO tutor_visual_target_evidence (
                model_task_request_id, session_id, question_document_id, revision_number,
                cycle_ordinal, turn_ordinal, surface_kind, response_ordinal, scene_source_kind,
                scene_task_request_id, scene_id, scene_fingerprint, hit_proof_id, panel_id,
                frame_fingerprint, step_index, selected_target_id, selection_was_correct,
                submitted_at_epoch_millis
            ) VALUES (?, 'legacy-session', 'question-1', 1, 1, 1, 'PLAN', NULL, 'PLAN',
                      'scene-request', 'scene-1', ?, 'hit-1', 'panel-1', ?, 0, 'target-1', 1, 10)
            """.trimIndent(),
            arrayOf(requestId, "d".repeat(64), "e".repeat(64)),
        )
    }

    private fun currentTutorRequest() = ModelTaskRequest(
        schemaVersion = ModelTaskRequest.TUTOR_TEACHING_CONSTRAINT_SCHEMA_VERSION,
        requestId = "current-v8",
        input = TutorPlanInput(
            sessionId = "current-session",
            draftRevisionNumber = 1,
            subject = "MATH",
            questionDocument = QuestionDocument(
                id = "current-question",
                blocks = listOf(
                    ContentBlock.Paragraph(
                        id = "stem",
                        markdown = "题面提到 evidenceMass 与 independentCorrectLowerBound，但只是文字。",
                    ),
                ),
            ),
        ),
        occurredAtEpochMillis = 20,
    )

    private fun captureRequestWithFieldLookingQuestionText() = ModelTaskRequest(
        requestId = "current-capture",
        input = CaptureAssessmentInput(
            draftId = "draft-evidenceMass",
            sourceAssetId = "asset-independentCorrectLowerBound",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 800,
            imageHeight = 600,
        ),
        occurredAtEpochMillis = 20,
    )

    private fun count(database: SQLiteDatabase, table: String, where: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
