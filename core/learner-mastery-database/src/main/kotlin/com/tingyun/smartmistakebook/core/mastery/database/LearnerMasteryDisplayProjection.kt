package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.util.Collections
import kotlinx.coroutines.flow.Flow

/**
 * Learner-bound read surface used only by the local display adapter.
 *
 * It exposes categorical projection state and typed knowledge references, never evidence mass,
 * attempts, source facts, persistence records, or a learner selector.
 */
interface LearnerMasteryDisplayReader {
    fun observeRevision(): Flow<LearnerMasteryDisplayRevision>

    suspend fun readSubjectTimeline(
        request: LearnerMasteryDisplayTimelineRequest,
    ): LearnerMasteryDisplayTimelineResult

    suspend fun readOverview(
        expectedRevision: LearnerMasteryDisplayRevision,
    ): LearnerMasteryDisplayOverviewResult

    suspend fun readKnowledgePage(
        request: LearnerMasteryDisplayPageRequest,
    ): LearnerMasteryDisplayPageResult
}

data class LearnerMasteryDisplayRevision(
    val ledgerSequence: Long,
    val asOfEpochMillis: Long,
) {
    init {
        require(ledgerSequence >= 0L) {
            "Mastery display revision must not be negative"
        }
        require(asOfEpochMillis >= 0L) {
            "Mastery display as-of time must not be negative"
        }
    }
}

data class LearnerMasteryDisplaySubjectOverview(
    val subject: SubjectKind,
    val currentState: KnowledgeMasteryState?,
    val trend: KnowledgeMasteryTrend?,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Mastery display overview requires a high-school subject"
        }
        require((currentState == null) == (trend == null)) {
            "A subject without mastery data cannot expose a trend"
        }
    }
}

data class LearnerMasteryDisplayTimelineRequest(
    val subject: SubjectKind,
    val expectedRevision: LearnerMasteryDisplayRevision,
    val sinceEpochMillis: Long,
    val dayLimit: Int,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Mastery display timeline requires a high-school subject"
        }
        require(sinceEpochMillis >= 0L) {
            "Mastery display timeline start must not be negative"
        }
        require(dayLimit in 1..LearnerMasteryReader.MAX_TIMELINE_DAY_LIMIT) {
            "Mastery display timeline day limit is out of range"
        }
    }
}

sealed interface LearnerMasteryDisplayTimelineResult {
    data class Current(
        val revision: LearnerMasteryDisplayRevision,
        val entries: List<SubjectMasteryTimelineEntry>,
    ) : LearnerMasteryDisplayTimelineResult {
        init {
            require(entries.size <= LearnerMasteryReader.MAX_TIMELINE_DAY_LIMIT) {
                "Mastery display timeline exceeds its day budget"
            }
            require(
                entries.map(SubjectMasteryTimelineEntry::utcEpochDay).distinct().size ==
                    entries.size,
            ) {
                "Mastery display timeline cannot repeat a day"
            }
            require(
                entries == entries.sortedBy(SubjectMasteryTimelineEntry::utcEpochDay),
            ) {
                "Mastery display timeline must be ordered by day"
            }
        }
    }

    data class RevisionChanged(
        val currentRevision: LearnerMasteryDisplayRevision,
    ) : LearnerMasteryDisplayTimelineResult
}

class LearnerMasteryDisplayOverviewSnapshot(
    val revision: LearnerMasteryDisplayRevision,
    subjects: List<LearnerMasteryDisplaySubjectOverview>,
    taxonomyVersions: Set<String>,
) {
    val subjects: List<LearnerMasteryDisplaySubjectOverview> =
        Collections.unmodifiableList(subjects.toList())
    val taxonomyVersions: Set<String> =
        Collections.unmodifiableSet(taxonomyVersions.toSortedSet())

    init {
        require(this.subjects.map { it.subject } == LEARNER_MASTERY_DISPLAY_SUBJECTS) {
            "Mastery display overview must contain the nine subjects in canonical order"
        }
        require(
            this.subjects.any { it.currentState != null } ==
                this.taxonomyVersions.isNotEmpty(),
        ) {
            "Mastery display overview data must identify its taxonomy"
        }
        this.taxonomyVersions.forEach { version ->
            requireMasteryVersion(version, "Mastery display taxonomy version")
        }
    }
}

sealed interface LearnerMasteryDisplayOverviewResult {
    data class Current(
        val snapshot: LearnerMasteryDisplayOverviewSnapshot,
    ) : LearnerMasteryDisplayOverviewResult

    data class RevisionChanged(
        val currentRevision: LearnerMasteryDisplayRevision,
    ) : LearnerMasteryDisplayOverviewResult
}

data class LearnerMasteryDisplayPageRequest(
    val subject: SubjectKind,
    val expectedRevision: LearnerMasteryDisplayRevision,
    val orderedKnowledgeNodes: List<KnowledgeNodeRef>,
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Mastery display page requires a high-school subject"
        }
        require(orderedKnowledgeNodes.isNotEmpty()) {
            "Mastery display lookup requires at least one knowledge node"
        }
        require(orderedKnowledgeNodes.size <= MAX_LIMIT) {
            "Mastery display lookup exceeds its node budget"
        }
        require(orderedKnowledgeNodes.all { it.subject == subject }) {
            "Mastery display lookup nodes must share the requested subject"
        }
        require(
            orderedKnowledgeNodes
                .map { node ->
                    Triple(node.subject, node.knowledgeNodeId, node.taxonomyVersion)
                }.distinct()
                .size == orderedKnowledgeNodes.size,
        ) {
            "Mastery display lookup cannot repeat a stable knowledge identity"
        }
    }

    companion object {
        const val MAX_LIMIT = 64
    }
}

data class LearnerMasteryDisplayKnowledgeItem(
    val stableNodeIdentityFingerprint: String,
    val knowledgeNode: KnowledgeNodeRef,
    val currentRecallState: KnowledgeMasteryState,
    val trend: KnowledgeMasteryTrend,
) {
    init {
        requireMasteryFingerprint(
            stableNodeIdentityFingerprint,
            "Mastery display knowledge identity",
        )
    }
}

sealed interface LearnerMasteryDisplayPageResult {
    data class Current(
        val revision: LearnerMasteryDisplayRevision,
        val items: List<LearnerMasteryDisplayKnowledgeItem>,
        val taxonomyVersions: Set<String>,
    ) : LearnerMasteryDisplayPageResult {
        init {
            require(items.size <= LearnerMasteryDisplayPageRequest.MAX_LIMIT) {
                "Mastery display page exceeds its item budget"
            }
            require(
                items.map(LearnerMasteryDisplayKnowledgeItem::stableNodeIdentityFingerprint)
                    .distinct()
                    .size == items.size,
            ) {
                "Mastery display page cannot repeat a stable knowledge identity"
            }
            taxonomyVersions.forEach { version ->
                requireMasteryVersion(version, "Mastery display taxonomy version")
            }
            require(items.isEmpty() || taxonomyVersions.isNotEmpty()) {
                "Mastery display page items must identify their taxonomy"
            }
        }
    }

    data class RevisionChanged(
        val currentRevision: LearnerMasteryDisplayRevision,
    ) : LearnerMasteryDisplayPageResult
}

internal data class BoundLearnerMasteryDisplayQuery(
    val learnerId: String,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
    }
}

internal data class BoundLearnerMasteryDisplayPageQuery(
    val learnerId: String,
    val request: LearnerMasteryDisplayPageRequest,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
    }
}

internal data class BoundLearnerMasteryDisplayTimelineQuery(
    val learnerId: String,
    val request: LearnerMasteryDisplayTimelineRequest,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
    }
}

internal val LEARNER_MASTERY_DISPLAY_SUBJECTS =
    listOf(
        SubjectKind.CHINESE,
        SubjectKind.MATH,
        SubjectKind.ENGLISH,
        SubjectKind.PHYSICS,
        SubjectKind.CHEMISTRY,
        SubjectKind.BIOLOGY,
        SubjectKind.HISTORY,
        SubjectKind.GEOGRAPHY,
        SubjectKind.POLITICS,
    )
