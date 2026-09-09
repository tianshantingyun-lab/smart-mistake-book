package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/**
 * Teaching advisories of the study loop: the student's declared focus labels
 * and the tutor's misconception notes, plus the observation stream the tutor
 * tab reads. Extracted so advisory writes share one deterministic id shape and
 * one clock, away from the repository's snapshot orchestration.
 */
internal class StudyAdvisoryStore(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val clock: Clock,
) {
    suspend fun recordTeachingFocus(
        sessionId: String,
        practiceUnitId: String,
        labels: List<String>,
        cycleOrdinal: Int,
    ) {
        val usable = labels.map(String::trim).filter(String::isNotBlank)
        if (usable.isEmpty()) return
        insertAdvisory(
            sessionId = sessionId,
            practiceUnitId = practiceUnitId,
            advisoryKind = TeachingAdvisoryRecord.KIND_TEACHING_FOCUS,
            advisoryId = "advisory:$sessionId:$cycleOrdinal:${TeachingAdvisoryRecord.KIND_TEACHING_FOCUS}",
            payloadMarkdown = usable.joinToString(separator = "、"),
            cycleOrdinal = cycleOrdinal,
        )
    }

    suspend fun recordMisconceptionAdvisory(
        sessionId: String,
        practiceUnitId: String,
        payloadMarkdown: String,
        cycleOrdinal: Int,
    ) {
        insertAdvisory(
            sessionId = sessionId,
            practiceUnitId = practiceUnitId,
            advisoryKind = TeachingAdvisoryRecord.KIND_MISCONCEPTION,
            advisoryId = "advisory:$sessionId:$cycleOrdinal:${TeachingAdvisoryRecord.KIND_MISCONCEPTION}",
            payloadMarkdown = payloadMarkdown,
            cycleOrdinal = cycleOrdinal,
        )
    }

    fun observeTeachingAdvisories(practiceUnitId: String?): Flow<List<TeachingAdvisoryRecord>> =
        database.observeTeachingAdvisories(learnerId, practiceUnitId)

    private suspend fun insertAdvisory(
        sessionId: String,
        practiceUnitId: String,
        advisoryKind: String,
        advisoryId: String,
        payloadMarkdown: String,
        cycleOrdinal: Int,
    ) {
        database.recordTeachingAdvisories(
            listOf(
                TeachingAdvisoryRecord(
                    advisoryId = advisoryId,
                    learnerId = learnerId,
                    practiceUnitId = practiceUnitId,
                    knowledgeNodeId = null,
                    advisoryKind = advisoryKind,
                    payloadMarkdown = payloadMarkdown,
                    confidence = null,
                    sourceId = "$sessionId:$cycleOrdinal",
                    createdAtEpochMillis = clock.millis(),
                ),
            ),
        )
    }
}
