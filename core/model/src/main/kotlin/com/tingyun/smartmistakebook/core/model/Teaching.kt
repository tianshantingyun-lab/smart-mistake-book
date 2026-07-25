package com.tingyun.smartmistakebook.core.model

enum class TeachingArtifactVerification {
    HUMAN_VERIFIED,
    CURATED_REFERENCE,
    DETERMINISTICALLY_VALIDATED,
}

data class VerifiedTeachingFollowUp(
    val id: String,
    val label: String,
    val contentMarkdown: String,
) {
    init {
        require(id.isNotBlank()) { "Follow-up id must not be blank" }
        require(label.isNotBlank()) { "Follow-up label must not be blank" }
        require(contentMarkdown.isNotBlank()) { "Follow-up content must not be blank" }
    }
}

/**
 * Teaching content may enter the tutor only after one of the explicit verification paths succeeds.
 * Unchecked OCR or model output intentionally has no representation as this type.
 */
data class VerifiedTeachingArtifact(
    val id: String,
    val subject: String,
    val title: String,
    val problemMarkdown: String,
    val explanationMarkdown: String,
    val verification: TeachingArtifactVerification,
    val assessmentItems: List<TutorAssessmentItem>,
    val followUps: List<VerifiedTeachingFollowUp> = emptyList(),
    val knowledgeNodeIds: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "Artifact id must not be blank" }
        require(subject.isNotBlank()) { "Artifact subject must not be blank" }
        require(title.isNotBlank()) { "Artifact title must not be blank" }
        require(problemMarkdown.isNotBlank()) { "Artifact problem must not be blank" }
        require(explanationMarkdown.isNotBlank()) { "Artifact explanation must not be blank" }
        require(assessmentItems.map(TutorAssessmentItem::id).distinct().size == assessmentItems.size) {
            "Assessment item ids must be unique within an artifact"
        }
        require(followUps.map(VerifiedTeachingFollowUp::id).distinct().size == followUps.size) {
            "Follow-up ids must be unique within an artifact"
        }
        val availableFollowUpIds = followUps.mapTo(mutableSetOf(), VerifiedTeachingFollowUp::id)
        val referencedFollowUpIds = assessmentItems.flatMap { item ->
            item.initialFollowUpIds + item.choices.flatMap(TutorChoice::followUpIds)
        }
        require(referencedFollowUpIds.all(availableFollowUpIds::contains)) {
            "Every assessment follow-up must belong to its verified teaching artifact"
        }
    }

    fun requireFollowUp(followUpId: String): VerifiedTeachingFollowUp =
        followUps.firstOrNull { it.id == followUpId }
            ?: throw IllegalArgumentException("Follow-up $followUpId does not belong to artifact $id")
}
