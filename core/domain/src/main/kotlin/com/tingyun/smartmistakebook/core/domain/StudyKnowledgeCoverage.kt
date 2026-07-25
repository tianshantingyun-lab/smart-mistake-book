package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind

data class StudyKnowledgeSubjectCoverage(
    val subject: SubjectKind,
    val topicCount: Int,
    val atomicKnowledgeCount: Int,
    val reviewedSourceCount: Int,
    val latestReviewedAtEpochMillis: Long,
) {
    init {
        require(topicCount >= 0) { "Knowledge coverage topic count must not be negative" }
        require(atomicKnowledgeCount >= 0) {
            "Knowledge coverage atomic-knowledge count must not be negative"
        }
        require(reviewedSourceCount > 0) {
            "Knowledge coverage requires at least one reviewed source"
        }
        require(latestReviewedAtEpochMillis > 0) {
            "Knowledge coverage review time must be positive"
        }
    }
}

data class StudyKnowledgeCoverageGap(
    val groundingKey: String,
    val subject: SubjectKind,
    val expectedParentKnowledgeDisplayName: String,
    val query: String,
    val relatedQuestionCount: Int,
    val firstObservedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
) {
    init {
        require(groundingKey.isNotBlank()) { "Knowledge coverage key must not be blank" }
        require(expectedParentKnowledgeDisplayName.isNotBlank()) {
            "Knowledge coverage parent must not be blank"
        }
        require(query.isNotBlank()) { "Knowledge coverage query must not be blank" }
        require(relatedQuestionCount > 0) { "Knowledge coverage question count must be positive" }
        require(firstObservedAtEpochMillis >= 0) { "Knowledge coverage first observation is invalid" }
        require(lastObservedAtEpochMillis >= firstObservedAtEpochMillis) {
            "Knowledge coverage observations are out of order"
        }
    }
}

data class StudyKnowledgeCoverageOverview(
    val reviewedSubjects: List<StudyKnowledgeSubjectCoverage> = emptyList(),
    val pendingGaps: List<StudyKnowledgeCoverageGap> = emptyList(),
) {
    init {
        require(reviewedSubjects.map { it.subject }.distinct().size == reviewedSubjects.size) {
            "Knowledge coverage subjects must be unique"
        }
        require(pendingGaps.map { it.groundingKey }.distinct().size == pendingGaps.size) {
            "Knowledge coverage gaps must have unique keys"
        }
    }

    val pendingGapCount: Int
        get() = pendingGaps.size

    val pendingQuestionOccurrenceCount: Int = pendingGaps.fold(0) { total, gap ->
        Math.addExact(total, gap.relatedQuestionCount)
    }

    val reviewedSubjectCount: Int
        get() = reviewedSubjects.size

    val reviewedTopicCount: Int = reviewedSubjects.fold(0) { total, subject ->
        Math.addExact(total, subject.topicCount)
    }

    val reviewedAtomicKnowledgeCount: Int = reviewedSubjects.fold(0) { total, subject ->
        Math.addExact(total, subject.atomicKnowledgeCount)
    }

    val reviewedSourceCount: Int = reviewedSubjects.fold(0) { total, subject ->
        Math.addExact(total, subject.reviewedSourceCount)
    }
}
