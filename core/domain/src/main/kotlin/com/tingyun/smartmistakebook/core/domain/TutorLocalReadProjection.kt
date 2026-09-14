package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import java.util.Locale

/**
 * 错题本检索的一条结果。
 *
 * 只有一个库在这里：错题本的目录条目。掌握情况**刻意不参与**——它由
 * [TutorLearningProgressLookup] 那一路单独提供，因为"某题掌握得怎样"与"这个知识点你
 * 掌握得怎样"是两个不同的问题（前者是目录元数据，后者需要知识点锚）。这条结果此前
 * 还携带过 `masteryStatus`，但既没被渲染也没参与排序，纯粹是把两个库的形状混在一起
 * 的残留，已移除。
 */
data class TutorMistakeLookupItem(
    val entryId: String,
    val title: String,
    val subject: String,
    val chapterLabels: List<String>,
    val knowledgeLabels: List<String>,
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
