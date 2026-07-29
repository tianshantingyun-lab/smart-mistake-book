package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import kotlinx.coroutines.flow.Flow

/** Exact, reviewable context for one model-assisted organization request. */
data class MistakeOrganizationPreparation(
    val request: ModelTaskRequest,
    val relatedCandidateTitles: List<String>,
    val knowledgeContextCount: Int = 0,
)

/** Indexes refer to the persisted successful model output, never caller-provided suggestions. */
data class ProblemOrganizationSelection(
    val classificationIndexes: Set<Int>,
    val relationIndexes: Set<Int>,
    val userClassifications: List<UserProblemClassification> = emptyList(),
    /** Only relations explicitly marked by the user are removed. */
    val relationRemovals: Set<ProblemOrganizationRelationKey> = emptySet(),
    /**
     * False merges selected relation suggestions into durable relations. True is reserved for the
     * explicit "remove existing relations" action and replaces the complete outgoing set.
     */
    val replaceRelations: Boolean = false,
) {
    init {
        require(classificationIndexes.all { it >= 0 })
        require(relationIndexes.all { it >= 0 })
        require(relationRemovals.size <= MAX_RELATION_REMOVALS) {
            "Too many relations were removed at once"
        }
        require(!replaceRelations || relationRemovals.isEmpty()) {
            "Full replacement and exact relation removal cannot be combined"
        }
        require(userClassifications.size <= MAX_USER_CLASSIFICATIONS) {
            "Too many user classifications were added at once"
        }
        require(classificationIndexes.size + userClassifications.size <= MAX_TOTAL_CLASSIFICATIONS) {
            "Too many classifications were selected at once"
        }
        require(
            userClassifications.distinctBy { it.dimension to it.displayName.lowercase() }.size ==
                userClassifications.size,
        ) { "User classifications must be unique" }
    }

    companion object {
        const val MAX_USER_CLASSIFICATIONS = 32
        const val MAX_TOTAL_CLASSIFICATIONS = 32
        const val MAX_RELATION_REMOVALS = 64
    }
}

data class ProblemOrganizationRelationKey(
    val targetProblemId: String,
    val targetProblemRevisionId: String,
    val kind: ProblemRelationKind,
) {
    init {
        require(targetProblemId.isNotBlank()) { "Relation target problem must not be blank" }
        require(targetProblemRevisionId.isNotBlank()) { "Relation target revision must not be blank" }
    }
}

data class UserProblemClassification(
    val dimension: ClassificationDimension,
    val displayName: String,
) {
    init {
        require(dimension in USER_EDITABLE_DIMENSIONS) {
            "This classification dimension is not user editable"
        }
        require(displayName == displayName.trim() && displayName.length in 1..96) {
            "User classification must be trimmed and between 1 and 96 characters"
        }
        require(displayName.none { it.isISOControl() || it == '\n' || it == '\r' }) {
            "User classification contains unsupported control characters"
        }
    }
}

val USER_EDITABLE_ORGANIZATION_DIMENSIONS: List<ClassificationDimension> = listOf(
    ClassificationDimension.CHAPTER,
    ClassificationDimension.KNOWLEDGE,
)

private val USER_EDITABLE_DIMENSIONS = USER_EDITABLE_ORGANIZATION_DIMENSIONS.toSet()

data class ConfirmedProblemClassification(
    val dimension: ClassificationDimension,
    val labelId: String,
    val displayName: String,
)

data class ConfirmedProblemRelation(
    val targetProblemId: String,
    val targetProblemRevisionId: String,
    val kind: ProblemRelationKind,
    val confidence: Double,
)

data class ConfirmedMistakeOrganization(
    val classifications: List<ConfirmedProblemClassification> = emptyList(),
    val relations: List<ConfirmedProblemRelation> = emptyList(),
    /** Exact accepted bindings for this problem revision; never inferred from display labels. */
    val knowledgeNodeIds: Set<String> = emptySet(),
)

data class ProblemOrganizationConfirmation(
    val created: Boolean,
    val classificationCount: Int,
    val relationCount: Int,
    val applied: Boolean = true,
    val preservedUserCorrection: Boolean = false,
)

data class ProblemOrganizationWorkCompletionAuthority(
    val workId: String,
    val expectedStateVersion: Long,
    val leaseOwner: String,
    val requestId: String,
) {
    init {
        require(workId.isNotBlank()) { "workId must not be blank" }
        require(expectedStateVersion >= 0) { "expectedStateVersion must not be negative" }
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
    }
}

enum class ProblemOrganizationWorkCompletionOutcome {
    COMPLETED,
    LOST_AUTHORITY,
}

data class ProblemOrganizationReauthorizationPreparation(
    val key: MistakeRevisionKey,
    val workId: String,
    val expectedStateVersion: Long,
    val requiresStudentConfirmation: Boolean,
    val durableStatus: ProblemOrganizationDurableStatus =
        ProblemOrganizationDurableStatus.WAITING_AUTHORIZATION,
) {
    init {
        require(workId.isNotBlank()) { "Work id must not be blank" }
        require(expectedStateVersion >= 0) { "Expected state version must not be negative" }
        require(
            durableStatus == ProblemOrganizationDurableStatus.WAITING_AUTHORIZATION ||
                !requiresStudentConfirmation,
        ) { "Only waiting organization work can require renewed confirmation" }
    }
}

enum class ProblemOrganizationDurableStatus {
    WAITING_AUTHORIZATION,
    ACTIVE,
    TERMINAL,
}

enum class ProblemOrganizationReauthorizationOutcome {
    REAUTHORIZED,
    REPLAYED,
    LOST_AUTHORITY,
}

interface MistakeOrganizationRepository {
    suspend fun prepare(
        key: MistakeRevisionKey,
        profile: StudyProfileOverview,
        provider: ProviderCapabilitySnapshot,
        attempt: Int,
        occurredAtEpochMillis: Long,
        approvedAtEpochMillis: Long,
    ): MistakeOrganizationPreparation

    /**
     * Prepares image-grounded organization for one exact committed import occurrence.
     * Implementations must derive the document and source assets from [workId], never from caller
     * supplied content.
     */
    suspend fun prepareCommittedWork(
        workId: String,
        provider: ProviderCapabilitySnapshot,
        authorization: ProblemOrganizationAuthorizationGrant,
        requestVersion: Long,
        occurredAtEpochMillis: Long,
    ): MistakeOrganizationPreparation = throw UnsupportedOperationException(
        "Committed organization work preparation is not implemented",
    )

    /**
     * Finds at most one durable organization occurrence for the exact mistake revision. Active and
     * terminal occurrences remain visible to the presentation policy so it cannot fall back to the
     * legacy request path.
     */
    suspend fun prepareReauthorization(
        key: MistakeRevisionKey,
        provider: ProviderCapabilitySnapshot,
        occurredAtEpochMillis: Long,
    ): ProblemOrganizationReauthorizationPreparation? = null

    /**
     * Renews only the exact durable work selected by [preparation]. Implementations must rebuild
     * the grant from the committed draft and current provider; caller-supplied assets are forbidden.
     */
    suspend fun reauthorize(
        preparation: ProblemOrganizationReauthorizationPreparation,
        provider: ProviderCapabilitySnapshot,
        approvedAtEpochMillis: Long,
    ): ProblemOrganizationReauthorizationOutcome = throw UnsupportedOperationException(
        "Committed organization work reauthorization is not implemented",
    )

    fun observeConfirmed(key: MistakeRevisionKey): Flow<ConfirmedMistakeOrganization>

    /**
     * Applies a successful persisted model result through the deterministic local policy.
     * Incomplete or low-confidence classifications return [ProblemOrganizationConfirmation.applied]
     * as false and do not replace any accepted organization facts.
     */
    suspend fun applySuccessfulOrganization(
        requestId: String,
    ): ProblemOrganizationConfirmation

    /**
     * Applies a successful persisted result and completes its durable work under one database
     * lease transaction. A lost lease must produce no organization, grounding, or work writes.
     */
    suspend fun completeSuccessfulOrganizationWork(
        authority: ProblemOrganizationWorkCompletionAuthority,
    ): ProblemOrganizationWorkCompletionOutcome = throw UnsupportedOperationException(
        "Atomic committed organization completion is not implemented",
    )

    /**
     * Persists an explicit user correction against a successful task for the exact revision.
     * The implementation reconstructs every durable fact from that trusted task snapshot.
     */
    suspend fun confirm(
        requestId: String,
        selection: ProblemOrganizationSelection,
        acceptedAtEpochMillis: Long,
    ): ProblemOrganizationConfirmation
}
