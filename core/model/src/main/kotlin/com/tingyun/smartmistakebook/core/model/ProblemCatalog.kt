package com.tingyun.smartmistakebook.core.model

/** Stable problem identity. Editable content lives in immutable [ProblemRevision] records. */
data class Problem(
    val id: String,
    val currentRevisionId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
) {
    init {
        require(id.isNotBlank()) { "Problem id must not be blank" }
        require(currentRevisionId.isNotBlank()) { "Current revision id must not be blank" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Update time must not precede creation"
        }
    }
}

data class ProblemRevision(
    val id: String,
    val problemId: String,
    val revisionNumber: Int,
    val subject: SubjectKind,
    val stemMarkdown: String,
    val contentFingerprint: String,
    val createdAtEpochMillis: Long,
    val title: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Problem revision id must not be blank" }
        require(problemId.isNotBlank()) { "Problem id must not be blank" }
        require(revisionNumber > 0) { "Revision number must be positive" }
        require(stemMarkdown.isNotBlank()) { "Problem stem must not be blank" }
        require(contentFingerprint.isNotBlank()) { "Content fingerprint must not be blank" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
        require(title == null || title.isNotBlank()) { "Title must not be blank when provided" }
    }
}

enum class PracticeUnitKind {
    WHOLE_PROBLEM,
    PROBLEM_PART,
    SHARED_STIMULUS_GROUP,
}

/** The smallest independently assessed and scheduled part of a problem. */
data class PracticeUnit(
    val id: String,
    val problemId: String,
    val problemRevisionId: String,
    val kind: PracticeUnitKind,
    val title: String,
    val itemFamilyId: String,
    val difficulty: Double,
    val estimatedDurationSeconds: Int,
    val createdAtEpochMillis: Long,
    val partIds: List<String> = emptyList(),
    val sourceBundleId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Practice unit id must not be blank" }
        require(problemId.isNotBlank()) { "Problem id must not be blank" }
        require(problemRevisionId.isNotBlank()) { "Problem revision id must not be blank" }
        require(title.isNotBlank()) { "Practice unit title must not be blank" }
        require(itemFamilyId.isNotBlank()) { "Item family id must not be blank" }
        require(difficulty.isFinite() && difficulty in 0.0..1.0) {
            "Difficulty must be between zero and one"
        }
        require(estimatedDurationSeconds > 0) { "Estimated duration must be positive" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
        require(partIds.none(String::isBlank)) { "Part ids must not be blank" }
        require(partIds.distinct().size == partIds.size) { "Part ids must be unique" }
        require(sourceBundleId == null || sourceBundleId.isNotBlank()) {
            "Source bundle id must not be blank when provided"
        }
        when (kind) {
            PracticeUnitKind.WHOLE_PROBLEM -> require(partIds.isEmpty()) {
                "A whole-problem unit must not name individual parts"
            }

            PracticeUnitKind.PROBLEM_PART,
            PracticeUnitKind.SHARED_STIMULUS_GROUP,
            -> require(partIds.isNotEmpty()) { "A partial practice unit must name its parts" }
        }
    }
}

enum class ErrorBookEntryStatus {
    ACTIVE,
    ARCHIVED,
    TRASHED,
}

data class ErrorBookEntry(
    val id: String,
    val practiceUnitId: String,
    val addedAtEpochMillis: Long,
    val status: ErrorBookEntryStatus = ErrorBookEntryStatus.ACTIVE,
) {
    init {
        require(id.isNotBlank()) { "Error-book entry id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(addedAtEpochMillis >= 0) { "Added time must not be negative" }
    }
}

/**
 * The catalog is a subject-scoped coordinate system, not a question bank. [TOPIC] nodes keep the
 * student-facing hierarchy stable; every other kind is an observable atomic capability that may be
 * reused by any problem in the same subject.
 */
enum class KnowledgeNodeKind {
    TOPIC,
    CONCEPT,
    PROCEDURE,
    REASONING,
    REPRESENTATION,
    EXPERIMENT,
    EXPRESSION,
}

enum class KnowledgeNodeGranularity {
    TOPIC,
    ATOMIC,
}

enum class KnowledgeNodeVerificationStatus {
    CURATED,
    SOURCE_GROUNDED,
    MODEL_CANDIDATE,
}

enum class KnowledgeSourceType {
    OFFICIAL_CURRICULUM_STANDARD,
    TEXTBOOK,
    AUTHORIZED_EDUCATION_MATERIAL,
    MANUAL_RESEARCH,
}

enum class KnowledgeSourceLicenseStatus {
    PUBLIC_OFFICIAL,
    LICENSED,
    REFERENCE_ONLY,
}

/**
 * Governs reuse of source expression independently from source authority.
 *
 * A source can be authoritative while still permitting only independently reviewed synthesis.
 */
enum class KnowledgeSourceContentUsePolicy {
    REVIEWED_SYNTHESIS_ONLY,
    EXCERPT_ALLOWED,
    ADAPTATION_ALLOWED,
}

/**
 * Reviewed teaching support attached to the knowledge graph.
 *
 * These records help a tutor explain an already supplied question. They are not assessment items,
 * cannot enter a review queue, and never become learning evidence by themselves.
 */
enum class KnowledgeTeachingMaterialType {
    CONCEPT_EXPLANATION,
    METHOD_MODEL,
    WORKED_EXAMPLE,
    COMPLETE_SOLUTION,
    DERIVATION,
    MISCONCEPTION_GUIDE,
    REPRESENTATION_GUIDE,
}

/**
 * Describes how bundled teaching prose was produced so copyright policy can be enforced locally.
 */
enum class KnowledgeMaterialDerivationKind {
    REVIEWED_SYNTHESIS,
    PUBLIC_OFFICIAL_EXCERPT,
    LICENSED_EXCERPT,
    LICENSED_ADAPTATION,
}

enum class KnowledgeMaterialNodeRole {
    PRIMARY,
    SUPPORTING,
    PREREQUISITE,
}

data class KnowledgeNode(
    val id: String,
    val subject: SubjectKind,
    val displayName: String,
    val taxonomyVersion: String,
    val prerequisiteNodeIds: Set<String> = emptySet(),
    val canonicalName: String = displayName,
    val kind: KnowledgeNodeKind = KnowledgeNodeKind.TOPIC,
    val granularity: KnowledgeNodeGranularity = KnowledgeNodeGranularity.TOPIC,
    val aliases: Set<String> = emptySet(),
    val boundaryMarkdown: String? = null,
    val verificationStatus: KnowledgeNodeVerificationStatus =
        KnowledgeNodeVerificationStatus.MODEL_CANDIDATE,
) {
    init {
        require(id.isNotBlank()) { "Knowledge-node id must not be blank" }
        require(displayName.isNotBlank()) { "Knowledge-node name must not be blank" }
        require(canonicalName.isNotBlank()) { "Knowledge-node canonical name must not be blank" }
        require(taxonomyVersion.isNotBlank()) { "Taxonomy version must not be blank" }
        require((kind == KnowledgeNodeKind.TOPIC) == (granularity == KnowledgeNodeGranularity.TOPIC)) {
            "Only topic nodes may use topic granularity"
        }
        require(prerequisiteNodeIds.none(String::isBlank)) {
            "Prerequisite node ids must not be blank"
        }
        require(id !in prerequisiteNodeIds) { "A knowledge node cannot require itself" }
        require(aliases.none(String::isBlank)) { "Knowledge-node aliases must not be blank" }
        require(aliases.size <= 12) { "A knowledge node may keep at most twelve aliases" }
        require(aliases.none { it == canonicalName }) {
            "Knowledge-node aliases must not repeat the canonical name"
        }
        require(boundaryMarkdown == null || boundaryMarkdown.isNotBlank()) {
            "Knowledge-node boundary must not be blank"
        }
    }
}

enum class BindingAcceptanceSource {
    LOCAL_POLICY_ACCEPTED,
    USER_CORRECTED,
    /** Legacy persisted value retained for backward-compatible reads. */
    USER_CONFIRMED,
    CURATED_REFERENCE,
    DETERMINISTIC_RULE,
}

/** Accepted knowledge binding; model-only suggestions intentionally use a different type. */
data class PracticeUnitKnowledgeBinding(
    val id: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val basisRevisionId: String,
    val taxonomyVersion: String,
    val acceptanceSource: BindingAcceptanceSource,
    val acceptedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Knowledge binding id must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
        require(knowledgeNodeId.isNotBlank()) { "Knowledge-node id must not be blank" }
        require(basisRevisionId.isNotBlank()) { "Basis revision id must not be blank" }
        require(taxonomyVersion.isNotBlank()) { "Taxonomy version must not be blank" }
        require(acceptedAtEpochMillis >= 0) { "Acceptance time must not be negative" }
    }
}

enum class ClassificationDimension {
    SUBJECT,
    GRADE,
    CURRICULUM_SCOPE,
    CHAPTER,
    KNOWLEDGE,
    QUESTION_TYPE,
    REPRESENTATION,
    COGNITIVE_DEMAND,
    ERROR_CAUSE,
    SOURCE,
    USER_TAG,
}

/** A user- or rule-accepted classification, never an unreviewed model suggestion. */
data class ProblemClassificationBinding(
    val id: String,
    val problemId: String,
    val basisRevisionId: String,
    val dimension: ClassificationDimension,
    val labelId: String,
    val displayName: String,
    val taxonomyVersion: String,
    val acceptanceSource: BindingAcceptanceSource,
    val acceptedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Classification binding id must not be blank" }
        require(problemId.isNotBlank()) { "Problem id must not be blank" }
        require(basisRevisionId.isNotBlank()) { "Basis revision id must not be blank" }
        require(labelId.isNotBlank()) { "Classification label id must not be blank" }
        require(displayName.isNotBlank()) { "Classification display name must not be blank" }
        require(taxonomyVersion.isNotBlank()) { "Taxonomy version must not be blank" }
        require(acceptedAtEpochMillis >= 0) { "Acceptance time must not be negative" }
    }
}

enum class ProblemRelationKind {
    SAME_KNOWLEDGE,
    SAME_ERROR_PATTERN,
    VARIANT_OF,
    PREREQUISITE_OF,
    SAME_SOURCE_BUNDLE,
    SHARES_STIMULUS,
    CONTINUATION_OF,
    ANSWER_FOR,
    SAME_FIGURE_PATTERN,
    POSSIBLE_DUPLICATE,
    DERIVED_FROM,
}

enum class ProblemRelationStatus {
    ACTIVE,
    STALE,
    REJECTED,
}

data class ProblemRelation(
    val id: String,
    val fromProblemId: String,
    val toProblemId: String,
    val kind: ProblemRelationKind,
    val basisRevisionIds: Set<String>,
    val weight: Double,
    val status: ProblemRelationStatus,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Problem relation id must not be blank" }
        require(fromProblemId.isNotBlank() && toProblemId.isNotBlank()) {
            "Related problem ids must not be blank"
        }
        require(fromProblemId != toProblemId) { "A problem cannot relate to itself" }
        require(basisRevisionIds.isNotEmpty() && basisRevisionIds.none(String::isBlank)) {
            "A relation must name at least one non-blank basis revision"
        }
        require(weight.isFinite() && weight in 0.0..1.0) {
            "Relation weight must be between zero and one"
        }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
    }
}
