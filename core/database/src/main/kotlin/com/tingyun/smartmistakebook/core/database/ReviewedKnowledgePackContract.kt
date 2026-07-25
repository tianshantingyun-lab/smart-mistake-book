package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus

internal data class ValidatedKnowledgeGroundingResolution(
    val command: ResolveKnowledgeGroundingCommand,
    val resolutionId: String,
)

internal object ReviewedKnowledgePackContract {
    private const val MAX_RESOLUTIONS = 4_096

    fun validate(
        command: ApplyReviewedKnowledgePackCommand,
        existingSources: List<KnowledgeSourceSeedRecord> = emptyList(),
        existingParentNodes: List<KnowledgeNodeSeedRecord> = emptyList(),
    ): List<ValidatedKnowledgeGroundingResolution> {
        KnowledgeBaseImportContract.validate(
            sources = command.sources,
            nodes = command.nodes,
            bindings = command.bindings,
            existingSources = existingSources,
            existingParentNodes = existingParentNodes,
        )
        requirePack(command.resolutions.isNotEmpty()) {
            "A reviewed knowledge pack needs at least one grounding resolution"
        }
        requirePack(command.resolutions.size <= MAX_RESOLUTIONS) {
            "A reviewed knowledge pack has too many grounding resolutions"
        }
        requirePack(
            command.resolutions.map(ResolveKnowledgeGroundingCommand::groundingKey)
                .distinct().size == command.resolutions.size,
        ) { "A reviewed knowledge pack cannot resolve one grounding key more than once" }

        val nodesById = command.nodes.associateBy(KnowledgeNodeSeedRecord::knowledgeNodeId)
        return command.resolutions.map { resolution ->
            val target = nodesById[resolution.knowledgeNodeId]
                ?: throw DatabaseContractViolationException(
                    "Every grounding resolution must target an imported atomic node",
                )
            requirePack(
                target.subject == resolution.subject &&
                    target.granularity == KnowledgeNodeGranularity.ATOMIC.name &&
                    target.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
            ) {
                "Every grounding resolution must target an imported same-subject atomic node"
            }
            requirePack(target.createdAtEpochMillis <= resolution.resolvedAtEpochMillis) {
                "A grounding resolution cannot predate its imported knowledge node"
            }
            ValidatedKnowledgeGroundingResolution(
                command = resolution,
                resolutionId = KnowledgeGroundingResolutionContract.validate(resolution),
            )
        }
    }

    private inline fun requirePack(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }
}
