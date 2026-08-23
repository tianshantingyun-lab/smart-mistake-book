package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.domain.visual.VisualInteractionEventSink
import com.tingyun.smartmistakebook.core.model.VisualInteractionAttempt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room-backed visual interaction sink (acceptance audit §12 / PR-11).
 * Mirrors the prediction-audit sink contract: every write degrades
 * silently, because a persistence hiccup must never block or break the
 * student's live interaction with the teaching GUI.
 */
internal class RoomVisualInteractionEventSink(
    private val store: VisualInteractionAttemptStore,
) : VisualInteractionEventSink {

    override suspend fun record(attempt: VisualInteractionAttempt) {
        runCatching {
            store.store(
                VisualInteractionAttemptRecord(
                    attemptId = attempt.attemptId,
                    problemRevisionId = attempt.problemRevisionId,
                    actionKind = attempt.action::class.simpleName.orEmpty(),
                    actionPayload = ACTION_JSON.encodeToString(attempt.action),
                    feasible = attempt.feasible,
                    feedback = attempt.feedback,
                    attemptedAtEpochMillis = attempt.attemptedAtEpochMillis,
                ),
            )
        }
    }

    private companion object {
        val ACTION_JSON = Json { encodeDefaults = true }
    }
}

/**
 * Narrow storage seam so the sink's degrade-silently behaviour stays
 * JVM-testable without a full [StudyDatabasePort] fake.
 */
internal fun interface VisualInteractionAttemptStore {
    suspend fun store(record: VisualInteractionAttemptRecord)
}

/** Public factory consumed by the app module. */
object VisualInteractionEventSinkFactory {
    fun create(database: StudyDatabasePort): VisualInteractionEventSink =
        RoomVisualInteractionEventSink { record -> database.recordVisualInteractionAttempt(record) }
}
