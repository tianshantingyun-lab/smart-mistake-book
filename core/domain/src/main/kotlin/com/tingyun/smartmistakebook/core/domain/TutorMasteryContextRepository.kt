package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef

/**
 * Narrow read boundary for tutoring. Implementations own learner resolution and storage details;
 * neither is part of this request or its model-facing result.
 */
fun interface TutorMasteryContextRepository {
    suspend fun read(request: TutorMasteryContextRequest): TutorMasteryContext
}

data class TutorMasteryContextRequest(
    val subject: SubjectKind,
    val questionKnowledgeNodes: List<KnowledgeNodeRef>,
    val fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    val relatedKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
) {
    init {
        require(subject != SubjectKind.GENERAL) {
            "Tutor mastery context requires one specific school subject"
        }
        require(questionKnowledgeNodes.size <= MAX_QUESTION_KNOWLEDGE_NODES) {
            "Tutor mastery context contains too many question knowledge nodes"
        }
        require(fallbackKnowledgeNodes.size <= MAX_FALLBACK_KNOWLEDGE_NODES) {
            "Tutor mastery context contains too many fallback knowledge nodes"
        }
        require(relatedKnowledgeNodes.size <= MAX_RELATED_KNOWLEDGE_NODES) {
            "Tutor mastery context contains too many related knowledge nodes"
        }
        val allNodes = questionKnowledgeNodes + fallbackKnowledgeNodes + relatedKnowledgeNodes
        require(allNodes.isNotEmpty()) {
            "Tutor mastery context requires at least one bounded knowledge node"
        }
        require(allNodes.all { node -> node.subject == subject }) {
            "Tutor mastery context cannot cross subjects"
        }
        require(allNodes.map(KnowledgeNodeRef::canonicalFingerprint).distinct().size == allNodes.size) {
            "Tutor mastery context knowledge nodes must be unique"
        }
        require(allNodes.map(KnowledgeNodeRef::knowledgeNodeId).distinct().size == allNodes.size) {
            "Tutor mastery context knowledge node ids must be unique"
        }
    }

    val allowedKnowledgeNodes: List<KnowledgeNodeRef>
        get() = questionKnowledgeNodes + fallbackKnowledgeNodes

    val neighborhoodKnowledgeNodes: List<KnowledgeNodeRef>
        get() = relatedKnowledgeNodes

    companion object {
        const val MAX_QUESTION_KNOWLEDGE_NODES = 16
        const val MAX_FALLBACK_KNOWLEDGE_NODES = 4
        const val MAX_RELATED_KNOWLEDGE_NODES = 8
    }
}

data class TutorMasteryContext(
    val summaries: List<TutorMasterySummary> = emptyList(),
    val relatedSummaries: List<TutorMasterySummary> = emptyList(),
    val relatedKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    val projectionIsCurrent: Boolean = true,
) {
    init {
        require(summaries.size <= MAX_SUMMARIES) {
            "Tutor mastery context contains too many summaries"
        }
        require(relatedSummaries.size <= MAX_RELATED_SUMMARIES) {
            "Tutor mastery context contains too many related summaries"
        }
        require(relatedKnowledgeNodes.size <= MAX_RELATED_KNOWLEDGE_NODES) {
            "Tutor mastery context contains too many related knowledge nodes"
        }
        require(relatedKnowledgeNodes.all { it.subject != SubjectKind.GENERAL }) {
            "Tutor mastery context related knowledge nodes require a specific subject"
        }
        val allSummaries = summaries + relatedSummaries
        require(
            allSummaries.map { summary -> summary.knowledgeNode.canonicalFingerprint }
                .distinct()
                .size == allSummaries.size,
        ) {
            "Tutor mastery context summary nodes must be unique"
        }
        require(
            allSummaries.map { summary -> summary.knowledgeNode.knowledgeNodeId }
                .distinct()
                .size == allSummaries.size,
        ) {
            "Tutor mastery context summary node ids must be unique"
        }
        require(allSummaries.map { summary -> summary.knowledgeNode.subject }.distinct().size <= 1) {
            "Tutor mastery context cannot contain summaries from different subjects"
        }
    }

    /**
     * Treats repository output as untrusted at the feature boundary: only exact requested refs,
     * in request order, survive.
     */
    fun boundedTo(request: TutorMasteryContextRequest): TutorMasteryContext {
        val summaryByFingerprint =
            (summaries + relatedSummaries).associateBy { summary ->
                summary.knowledgeNode.canonicalFingerprint
            }
        val effectiveRelatedNodes =
            if (request.relatedKnowledgeNodes.isNotEmpty()) {
                request.relatedKnowledgeNodes
            } else {
                relatedKnowledgeNodes
            }
        return TutorMasteryContext(
            summaries = request.allowedKnowledgeNodes.mapNotNull { node ->
                summaryByFingerprint[node.canonicalFingerprint]
                    ?.takeIf { summary -> summary.knowledgeNode.subject == request.subject }
            },
            relatedSummaries = effectiveRelatedNodes.mapNotNull { node ->
                summaryByFingerprint[node.canonicalFingerprint]
                    ?.takeIf { summary -> summary.knowledgeNode.subject == request.subject }
            },
            relatedKnowledgeNodes = effectiveRelatedNodes,
            projectionIsCurrent = projectionIsCurrent,
        )
    }

    companion object {
        const val MAX_SUMMARIES = TutorMasteryContextRequest.MAX_QUESTION_KNOWLEDGE_NODES +
            TutorMasteryContextRequest.MAX_FALLBACK_KNOWLEDGE_NODES
        const val MAX_RELATED_SUMMARIES = TutorMasteryContextRequest.MAX_RELATED_KNOWLEDGE_NODES
        const val MAX_RELATED_KNOWLEDGE_NODES = TutorMasteryContextRequest.MAX_RELATED_KNOWLEDGE_NODES

        val EMPTY = TutorMasteryContext()
    }
}

data class TutorMasterySummary(
    val knowledgeNode: KnowledgeNodeRef,
    val displayName: String,
    val status: TutorMasteryStatus,
    val trend: TutorMasteryTrend = TutorMasteryTrend.UNKNOWN,
    val recency: TutorMasteryRecency = TutorMasteryRecency.UNKNOWN,
    val evidenceQuality: TutorMasteryEvidenceQuality = TutorMasteryEvidenceQuality.UNKNOWN,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_CHARS) {
            "Tutor mastery display name is invalid"
        }
    }

    companion object {
        const val MAX_DISPLAY_NAME_CHARS = 96
    }
}

/** Student-facing, non-numeric mastery descriptions. */
enum class TutorMasteryStatus {
    UNKNOWN,
    NEEDS_PRACTICE,
    LEARNING,
    SOLID,
    NEEDS_REFRESH,
}

enum class TutorMasteryTrend {
    UNKNOWN,
    IMPROVING,
    STEADY,
    DECLINING,
}

enum class TutorMasteryRecency {
    UNKNOWN,
    RECENT,
    A_WHILE_AGO,
    OLD,
}

enum class TutorMasteryEvidenceQuality {
    UNKNOWN,
    LIMITED,
    MODERATE,
    STRONG,
}
