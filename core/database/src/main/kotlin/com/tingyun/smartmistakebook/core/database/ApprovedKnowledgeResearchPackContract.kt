package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object ApprovedKnowledgeResearchPackContract {
    fun validate(
        review: KnowledgeResearchReviewBundleRecord,
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): String {
        requireApproved(review.bundleId == command.reviewBundleId) {
            "Approved pack targets a different research review"
        }
        val reviewedAt = review.reviewedAtEpochMillis
            ?: throw DatabaseContractViolationException(
                "Knowledge research must be reviewed before application",
            )
        requireApproved(
            review.status in setOf(
                StudyDbValue.KnowledgeResearchReviewStatus.APPROVED,
                StudyDbValue.KnowledgeResearchReviewStatus.APPLIED,
            ) &&
                review.reviewerReference != null &&
                review.decisionNote != null,
        ) { "Knowledge research must be approved before application" }
        requireApproved(command.appliedAtEpochMillis >= reviewedAt) {
            "Approved pack application cannot predate review"
        }

        val resolution = command.pack.resolutions.singleOrNull()
            ?: throw DatabaseContractViolationException(
                "One approved research review must resolve exactly one knowledge gap",
            )
        requireApproved(
                resolution.groundingKey == review.groundingKey &&
                resolution.subject == review.subject &&
                resolution.resolvedAtEpochMillis in
                reviewedAt..command.appliedAtEpochMillis,
        ) { "Approved pack resolution does not match the reviewed knowledge gap" }
        requireApproved(command.pack.sources.isNotEmpty()) {
            "Approved research pack must import at least one reviewed source"
        }
        val reviewedSources = review.sources.associateBy {
            it.canonicalSourceUri to it.contentFingerprint.lowercase()
        }
        command.pack.sources.forEach { source ->
            val reviewed = source.sourceUri?.let { uri ->
                reviewedSources[uri to source.contentFingerprint.lowercase()]
            }
            requireApproved(
                reviewed != null &&
                    source.subject == review.subject &&
                    source.title == reviewed.title &&
                    source.publisher == reviewed.publisher &&
                    source.sourceType == reviewed.sourceType &&
                    source.licenseStatus == reviewed.licenseStatus &&
                    source.contentUsePolicy ==
                        KnowledgeSourceContentUsePolicy.REVIEWED_SYNTHESIS_ONLY.name &&
                    source.importedAtEpochMillis in
                    reviewedAt..command.appliedAtEpochMillis,
            ) { "Approved pack source does not match a verified reviewed source" }
        }
        val importedSourceIds = command.pack.sources
            .mapTo(mutableSetOf(), KnowledgeSourceSeedRecord::sourceId)
        requireApproved(
            command.pack.bindings.all { it.sourceId in importedSourceIds },
        ) { "Approved research nodes must use sources from the same reviewed pack" }
        requireApproved(command.pack.nodes.all { it.subject == review.subject }) {
            "Approved research pack cannot cross subjects"
        }
        requireApproved(
            command.pack.bindings.all {
                it.reviewedAtEpochMillis?.let { bindingReviewedAt ->
                    bindingReviewedAt in reviewedAt..command.appliedAtEpochMillis
                } == true
            },
        ) { "Approved research source bindings must be reviewed inside the audit window" }
        requireApproved(
            command.pack.relations.all {
                it.subject == review.subject &&
                    it.reviewedAtEpochMillis in
                    reviewedAt..command.appliedAtEpochMillis
            },
        ) { "Approved research relations must stay inside the reviewed audit window" }
        return ReviewedKnowledgePackFingerprint.of(command.pack)
    }

    private inline fun requireApproved(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }
}

internal object ReviewedKnowledgePackFingerprint {
    fun of(command: ApplyReviewedKnowledgePackCommand): String {
        val canonical = CanonicalFingerprintText()
        command.sources.sortedBy(KnowledgeSourceSeedRecord::sourceId).forEach { source ->
            canonical.record(
                "source",
                source.sourceId,
                source.subject,
                source.sourceType,
                source.title,
                source.publisher,
                source.edition,
                source.sourceUri,
                source.licenseStatus,
                source.contentUsePolicy,
                source.licenseExpression,
                source.licenseUri,
                source.attributionText,
                source.contentFingerprint.lowercase(),
                source.importedAtEpochMillis,
            )
        }
        command.nodes.sortedBy(KnowledgeNodeSeedRecord::knowledgeNodeId).forEach { node ->
            canonical.record(
                "node",
                node.knowledgeNodeId,
                node.stableCode,
                node.subject,
                node.displayName,
                node.parentKnowledgeNodeId,
                node.taxonomyVersion,
                node.createdAtEpochMillis,
                node.canonicalName,
                node.nodeKind,
                node.granularity,
                node.aliases.sorted().joinToString("\u001F"),
                node.boundaryMarkdown,
                node.verificationStatus,
            )
        }
        command.bindings.sortedWith(
            compareBy<KnowledgeNodeSourceBindingSeedRecord>(
                KnowledgeNodeSourceBindingSeedRecord::knowledgeNodeId,
                KnowledgeNodeSourceBindingSeedRecord::sourceId,
                KnowledgeNodeSourceBindingSeedRecord::sourceLocator,
            ),
        ).forEach { binding ->
            canonical.record(
                "binding",
                binding.knowledgeNodeId,
                binding.sourceId,
                binding.sourceLocator,
                binding.derivationNote,
                binding.reviewedAtEpochMillis,
            )
        }
        command.relations.sortedBy(KnowledgeNodeRelationRecord::relationId).forEach { relation ->
            canonical.record(
                "relation",
                relation.relationId,
                relation.subject,
                relation.prerequisiteKnowledgeNodeId,
                relation.dependentKnowledgeNodeId,
                relation.relationType,
                relation.sourceId,
                relation.sourceLocator,
                relation.reviewedAtEpochMillis,
            )
        }
        command.resolutions.sortedBy(ResolveKnowledgeGroundingCommand::groundingKey)
            .forEach { resolution ->
                canonical.record(
                    "resolution",
                    resolution.groundingKey,
                    resolution.subject,
                    resolution.knowledgeNodeId,
                    resolution.resolvedAtEpochMillis,
                )
            }
        return canonical.sha256()
    }
}

private class CanonicalFingerprintText {
    private val value = StringBuilder()

    fun record(type: String, vararg fields: Any?) {
        field(type)
        fields.forEach(::field)
    }

    fun sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun field(raw: Any?) {
        if (raw == null) {
            value.append("-1:")
        } else {
            val text = raw.toString()
            value.append(text.length).append(':').append(text)
        }
        value.append('|')
    }
}
