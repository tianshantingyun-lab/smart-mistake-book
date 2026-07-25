package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import java.util.Locale

data class TutorMistakeLookupItem(
    val entryId: String,
    val title: String,
    val subject: String,
    val chapterLabels: List<String>,
    val knowledgeLabels: List<String>,
    val masteryStatus: MasteryStatus,
)

data class TutorLearningProgressLookup(
    val recordedAttemptCount: Int,
    val projectionIsCurrent: Boolean,
    val needsAttention: List<StudyKnowledgeSummary>,
    val goingWell: List<StudyKnowledgeSummary>,
)

/** Builds small, display-only projections from the already loaded local snapshot. */
object TutorLocalReadProjection {
    fun mistakes(
        catalog: List<StudyCatalogEntry>,
        decision: TutorIntentDecision,
        authorization: TutorIntentAuthorization,
    ): List<TutorMistakeLookupItem> {
        require(TutorAuthorizedCapability.READ_MISTAKE_NOTEBOOK in authorization.capabilities)
        val limit = requireNotNull(authorization.mistakeReadLimit)
        val terms = decision.lookupTerms.map { it.lowercase(Locale.ROOT) }
        return catalog.asSequence()
            .map { entry -> entry to entry.matchScore(terms) }
            .filter { (_, score) -> terms.isEmpty() || score > 0 }
            .sortedWith(
                compareByDescending<Pair<StudyCatalogEntry, Int>> { it.second }
                    .thenBy { it.first.nextReviewAtEpochMillis ?: Long.MAX_VALUE }
                    .thenBy { it.first.entryId },
            )
            .take(limit)
            .map { (entry, _) ->
                TutorMistakeLookupItem(
                    entryId = entry.entryId,
                    title = entry.title,
                    subject = entry.subject,
                    chapterLabels = entry.chapterLabels.take(MAX_DISPLAY_LABELS),
                    knowledgeLabels = entry.knowledgeLabels.take(MAX_DISPLAY_LABELS),
                    masteryStatus = entry.masteryStatus,
                )
            }
            .toList()
    }

    fun learningProgress(
        profile: StudyProfileOverview,
        authorization: TutorIntentAuthorization,
    ): TutorLearningProgressLookup {
        require(TutorAuthorizedCapability.READ_LEARNING_PROGRESS in authorization.capabilities)
        val limit = requireNotNull(authorization.learningProgressReadLimit)
        return TutorLearningProgressLookup(
            recordedAttemptCount = profile.recordedAttemptCount,
            projectionIsCurrent = profile.projectionIsCurrent,
            needsAttention = profile.weaknesses.take(limit),
            goingWell = profile.strengths.take(limit),
        )
    }

    private fun StudyCatalogEntry.matchScore(terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val titleText = title.lowercase(Locale.ROOT)
        val subjectText = subject.lowercase(Locale.ROOT)
        val chapterText = chapterLabels.joinToString(" ").lowercase(Locale.ROOT)
        val knowledgeText = knowledgeLabels.joinToString(" ").lowercase(Locale.ROOT)
        return terms.sumOf { term ->
            when {
                titleText.contains(term) -> 6
                knowledgeText.contains(term) -> 5
                chapterText.contains(term) -> 4
                subjectText.contains(term) -> 3
                else -> 0
            }
        }
    }

    private const val MAX_DISPLAY_LABELS = 3
}
