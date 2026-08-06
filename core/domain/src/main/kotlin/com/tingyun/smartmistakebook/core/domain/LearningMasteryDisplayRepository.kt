package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Read-only, learner-bound projection for the "学习掌握" UI.
 *
 * Implementations resolve the current learner internally. This boundary must never expose
 * persistence records, evidence calculations, knowledge-catalog records, or identifiers that
 * have meaning outside this display projection.
 */
interface LearningMasteryDisplayRepository {
    fun observeSubjectOverview(): Flow<LearningMasteryLoadState<LearningMasteryOverview>>

    fun observeSubjectTimeline(
        request: LearningMasteryTimelineRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryTimeline>>

    /**
     * Reads one keyset page from the exact [LearningMasteryPageRequest.revision].
     *
     * When the current projection revision differs from the request, implementations emit
     * [LearningMasteryLoadState.Error] with [LearningMasteryDisplayError.PROJECTION_CHANGED]
     * and no page content. The caller then reloads the overview and starts again without an old
     * cursor.
     */
    fun observeKnowledgePage(
        request: LearningMasteryPageRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryKnowledgePage>>
}

sealed interface LearningMasteryLoadState<out T> {
    data object Loading : LearningMasteryLoadState<Nothing>

    data class Content<T>(
        val value: T,
    ) : LearningMasteryLoadState<T>

    data class Empty(
        val revision: LearningMasteryProjectionRevision,
    ) : LearningMasteryLoadState<Nothing>

    /**
     * A UI-safe failure. There is deliberately no message, cause, status code, or storage detail.
     */
    data class Error(
        val reason: LearningMasteryDisplayError,
        val currentRevision: LearningMasteryProjectionRevision? = null,
    ) : LearningMasteryLoadState<Nothing> {
        init {
            require(
                (reason == LearningMasteryDisplayError.PROJECTION_CHANGED) ==
                    (currentRevision != null),
            ) {
                "Only a changed projection carries its replacement revision"
            }
        }
    }
}

enum class LearningMasteryDisplayError {
    TEMPORARILY_UNAVAILABLE,
    PROJECTION_CHANGED,
}

data class LearningMasteryOverview(
    val revision: LearningMasteryProjectionRevision,
    val subjects: List<LearningMasterySubjectOverview>,
) {
    init {
        require(subjects.isNotEmpty()) {
            "An overview without subjects must use the empty load state"
        }
        require(subjects.size <= LearningMasterySubject.entries.size) {
            "A mastery overview cannot contain more than the supported subjects"
        }
        require(subjects.map(LearningMasterySubjectOverview::subject).distinct().size == subjects.size) {
            "A mastery overview cannot repeat a subject"
        }
    }
}

enum class LearningMasteryTimelineRange(
    val days: Int,
) {
    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
}

data class LearningMasteryTimelineRequest(
    val subject: LearningMasterySubject,
    val revision: LearningMasteryProjectionRevision,
    val range: LearningMasteryTimelineRange,
)

data class LearningMasteryTimeline(
    val revision: LearningMasteryProjectionRevision,
    val subject: LearningMasterySubject,
    val entries: List<LearningMasteryTimelineEntry>,
) {
    init {
        require(entries.size <= LearningMasteryTimelineRange.LAST_30_DAYS.days) {
            "A mastery timeline cannot exceed the supported day budget"
        }
        require(entries.map(LearningMasteryTimelineEntry::day).distinct().size == entries.size) {
            "A mastery timeline cannot repeat a day"
        }
        require(entries == entries.sortedBy(LearningMasteryTimelineEntry::day)) {
            "A mastery timeline must be ordered by day"
        }
    }
}

data class LearningMasteryTimelineEntry(
    val day: Long,
    val signal: LearningMasteryTimelineSignal,
    val activity: LearningMasteryTimelineActivity,
    val attempts: Int,
    val knowledgePoints: Int,
) {
    init {
        require(attempts >= 0) {
            "Mastery timeline attempts must not be negative"
        }
        require(knowledgePoints >= 0) {
            "Mastery timeline knowledge points must not be negative"
        }
    }
}

enum class LearningMasteryTimelineSignal {
    PROGRESS,
    MIXED,
    NEEDS_ATTENTION,
    NO_ACTIVITY,
}

enum class LearningMasteryTimelineActivity {
    LIGHT,
    REGULAR,
    INTENSIVE,
    NONE,
}

data class LearningMasterySubjectOverview(
    val subject: LearningMasterySubject,
    val status: LearningMasteryStatus,
    val trend: LearningMasteryTrend,
)

enum class LearningMasterySubject {
    CHINESE,
    MATHEMATICS,
    ENGLISH,
    PHYSICS,
    CHEMISTRY,
    BIOLOGY,
    HISTORY,
    GEOGRAPHY,
    IDEOLOGY_AND_POLITICS,
}

/** UI maps these values to short, ordinary-language labels. */
enum class LearningMasteryStatus {
    NOT_YET_LEARNED,
    GETTING_FAMILIAR,
    FAIRLY_STEADY,
    NEEDS_REINFORCEMENT,
}

/** UI maps these values to short, ordinary-language labels. */
enum class LearningMasteryTrend {
    NO_CLEAR_CHANGE,
    IMPROVING,
    STEADY,
    RECENTLY_FLUCTUATING,
}

data class LearningMasteryPageRequest(
    val subject: LearningMasterySubject,
    val revision: LearningMasteryProjectionRevision,
    val cursor: LearningMasteryPageCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) {
            "Mastery page limit must be between 1 and $MAX_LIMIT"
        }
        require(cursor == null || cursor.subject == subject) {
            "Mastery page cursor belongs to a different subject"
        }
        require(cursor == null || cursor.revision == revision) {
            "Mastery page cursor belongs to a different projection revision"
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 32
        const val MAX_LIMIT = 64
    }
}

data class LearningMasteryKnowledgePage(
    val subject: LearningMasterySubject,
    val revision: LearningMasteryProjectionRevision,
    val items: List<LearningMasteryKnowledgeItem>,
    val nextCursor: LearningMasteryPageCursor? = null,
) {
    init {
        require(items.isNotEmpty()) {
            "A page without knowledge items must use the empty load state"
        }
        require(items.size <= LearningMasteryPageRequest.MAX_LIMIT) {
            "A mastery page exceeds the public page limit"
        }
        require(items.map(LearningMasteryKnowledgeItem::key).distinct().size == items.size) {
            "A mastery page cannot repeat a knowledge item"
        }
        require(nextCursor == null || nextCursor.subject == subject) {
            "Next mastery cursor belongs to a different subject"
        }
        require(nextCursor == null || nextCursor.revision == revision) {
            "Next mastery cursor belongs to a different projection revision"
        }
    }
}

data class LearningMasteryKnowledgeItem(
    val key: LearningMasteryKnowledgeKey,
    val displayName: String,
    val displayPath: List<String>,
    val status: LearningMasteryStatus,
    val trend: LearningMasteryTrend,
) {
    init {
        require(displayName.isValidDisplaySegment(MAX_DISPLAY_NAME_LENGTH)) {
            "Mastery display name is invalid"
        }
        require(displayPath.size <= MAX_DISPLAY_PATH_SEGMENTS) {
            "Mastery display path is too deep"
        }
        require(displayPath.all { segment -> segment.isValidDisplaySegment(MAX_PATH_SEGMENT_LENGTH) }) {
            "Mastery display path contains an invalid segment"
        }
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 96
        const val MAX_DISPLAY_PATH_SEGMENTS = 6
        const val MAX_PATH_SEGMENT_LENGTH = 64
    }
}

@JvmInline
value class LearningMasteryKnowledgeKey private constructor(
    val opaqueValue: String,
) {
    companion object {
        fun fromOpaque(opaqueValue: String): LearningMasteryKnowledgeKey {
            require(opaqueValue.isValidOpaqueValue(MAX_LENGTH)) {
                "Mastery knowledge key is invalid"
            }
            return LearningMasteryKnowledgeKey(opaqueValue)
        }

        private const val MAX_LENGTH = 160
    }
}

@JvmInline
value class LearningMasteryProjectionRevision private constructor(
    val opaqueValue: String,
) {
    companion object {
        fun fromOpaque(opaqueValue: String): LearningMasteryProjectionRevision {
            require(opaqueValue.isValidOpaqueValue(MAX_LENGTH)) {
                "Mastery projection revision is invalid"
            }
            return LearningMasteryProjectionRevision(opaqueValue)
        }

        private const val MAX_LENGTH = 128
    }
}

class LearningMasteryPageCursor private constructor(
    val opaqueValue: String,
    val subject: LearningMasterySubject,
    val revision: LearningMasteryProjectionRevision,
) {
    override fun equals(other: Any?): Boolean =
        other is LearningMasteryPageCursor &&
            opaqueValue == other.opaqueValue &&
            subject == other.subject &&
            revision == other.revision

    override fun hashCode(): Int {
        var result = opaqueValue.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + revision.hashCode()
        return result
    }

    override fun toString(): String = "LearningMasteryPageCursor(opaque)"

    companion object {
        fun fromOpaque(
            opaqueValue: String,
            subject: LearningMasterySubject,
            revision: LearningMasteryProjectionRevision,
        ): LearningMasteryPageCursor {
            require(opaqueValue.isValidOpaqueValue(MAX_LENGTH)) {
                "Mastery page cursor is invalid"
            }
            return LearningMasteryPageCursor(
                opaqueValue = opaqueValue,
                subject = subject,
                revision = revision,
            )
        }

        private const val MAX_LENGTH = 16_512
    }
}

private fun String.isValidOpaqueValue(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && this == trim() && none(Char::isISOControl)

private fun String.isValidDisplaySegment(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && this == trim() && none(Char::isISOControl)
