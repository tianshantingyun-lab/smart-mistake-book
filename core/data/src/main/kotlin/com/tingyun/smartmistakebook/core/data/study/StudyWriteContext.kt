package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * Write-path primitives shared by the study repository's collaborators:
 * deterministic ids, the study-day stamp, the per-source cooldown lookup and
 * the verified-catalog guard. Kept in one place so ingestion, submission
 * preparation and rating writes cannot drift apart on any of them.
 */
internal class StudyWriteContext(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val studyZoneId: ZoneId,
    private val fixtureSource: StudyFixtureSource,
) {
    fun stableId(namespace: String, requestId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$learnerId\n$requestId".toByteArray(StandardCharsets.UTF_8))
        val digest = bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "$namespace-$digest"
    }

    fun studyDayAt(occurredAtEpochMillis: Long): StudyDayContext {
        val local = Instant.ofEpochMilli(occurredAtEpochMillis).atZone(studyZoneId)
        check(local.offset.totalSeconds % 60 == 0) {
            "Study time-zone offset must be minute-aligned"
        }
        return StudyDayContext(
            epochDay = local.toLocalDate().toEpochDay(),
            timeZoneId = studyZoneId.id,
            utcOffsetMinutes = local.offset.totalSeconds / 60,
        )
    }

    suspend fun isWithinCooldown(
        practiceUnitId: String,
        sourceKind: String,
        cooldownMillis: Long,
        atEpochMillis: Long,
    ): Boolean {
        val last = database.readLastReviewLogAt(
            learnerId = learnerId,
            practiceUnitId = practiceUnitId,
            sourceKind = sourceKind,
        ) ?: return false
        return atEpochMillis - last in 0 until cooldownMillis
    }

    fun requireTeachingArtifact(practiceUnitId: String): VerifiedTeachingArtifact =
        requireNotNull(fixtureSource.teachingArtifactForPracticeUnit(practiceUnitId)) {
            "Practice unit $practiceUnitId is outside the verified M1 catalog"
        }
}
