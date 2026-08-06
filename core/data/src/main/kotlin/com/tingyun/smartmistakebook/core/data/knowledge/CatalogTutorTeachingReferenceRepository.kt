package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.domain.ConfirmedKnowledgeNodeBinding
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogTeachingMaterial
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

object TutorTeachingReferenceRepositoryFactory {
    internal fun create(
        catalog: HighSchoolKnowledgeCatalog,
    ): TutorTeachingReferenceRepository =
        CatalogTutorTeachingReferenceRepository(catalog)
}

internal class CatalogTutorTeachingReferenceRepository(
    private val catalog: HighSchoolKnowledgeCatalog,
) : TutorTeachingReferenceRepository {
    override suspend fun referencesFor(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<TutorTeachingReference> {
        require(knowledgeNodeIds.all(String::isNotBlank))
        require(
            knowledgeNodeIds.size <= TutorTeachingReference.MAX_KNOWLEDGE_NODES,
        ) {
            "Teaching-reference lookup exceeds its knowledge-point budget"
        }
        require(limit in 1..TutorPlanInput.MAX_TEACHING_REFERENCES)
        if (knowledgeNodeIds.isEmpty()) return emptyList()

        val subjectKind =
            runCatching { SubjectKind.valueOf(subject) }
                .getOrElse { throw IllegalArgumentException("Unknown high-school subject", it) }
        require(subjectKind != SubjectKind.GENERAL) {
            "Teaching references require a specific high-school subject"
        }
        val verifiedNodes =
            catalog.resolveNodes(
                subject = subjectKind,
                knowledgeNodeIds = knowledgeNodeIds.sorted(),
            )
        if (verifiedNodes.isEmpty()) return emptyList()
        val materials =
            catalog.readTeachingMaterials(
                nodes = verifiedNodes,
                limit = limit,
            )
        return materials.toTutorReferences(
            requestedKnowledgeNodeIds = knowledgeNodeIds,
            limit = limit,
        )
    }

    override suspend fun referencesForConfirmedNodes(
        subject: String,
        directKnowledgeNodes: Set<ConfirmedKnowledgeNodeBinding>,
        limit: Int,
    ): List<TutorTeachingReference> {
        require(limit in 1..TutorPlanInput.MAX_TEACHING_REFERENCES)
        if (directKnowledgeNodes.isEmpty()) return emptyList()
        if (directKnowledgeNodes.size > TutorTeachingReference.MAX_KNOWLEDGE_NODES) {
            return emptyList()
        }
        val subjectKind = runCatching { SubjectKind.valueOf(subject) }.getOrNull()
            ?.takeUnless { it == SubjectKind.GENERAL }
            ?: return emptyList()
        if (directKnowledgeNodes.any { it.ref.subject != subjectKind }) return emptyList()

        val bindingsById = directKnowledgeNodes.associateBy { it.ref.knowledgeNodeId }
        if (bindingsById.size != directKnowledgeNodes.size) return emptyList()
        val expectedManifest = directKnowledgeNodes.first().manifestFingerprint
        val expectedGeneration = directKnowledgeNodes.first().activationGeneration
        if (
            directKnowledgeNodes.any {
                it.manifestFingerprint != expectedManifest ||
                    it.activationGeneration != expectedGeneration
            }
        ) {
            return emptyList()
        }

        val verifiedNodes = catalog.resolveNodes(subjectKind, bindingsById.keys.sorted())
        val handlesById = verifiedNodes.associateBy { it.ref.knowledgeNodeId }
        if (
            handlesById.size != bindingsById.size ||
            bindingsById.any { (nodeId, binding) ->
                val handle = handlesById[nodeId]
                handle == null ||
                    handle.ref != binding.ref ||
                    handle.manifestFingerprint != binding.manifestFingerprint ||
                    handle.activationGeneration != binding.activationGeneration ||
                    handle.node.verificationStatus == KnowledgeNodeVerificationStatus.MODEL_CANDIDATE
            }
        ) {
            return emptyList()
        }

        return catalog.readTeachingMaterials(verifiedNodes, limit).toTutorReferences(
            directKnowledgeNodes = bindingsById,
            manifestFingerprint = expectedManifest,
            activationGeneration = expectedGeneration,
            limit = limit,
        )
    }
}

private fun List<KnowledgeCatalogTeachingMaterial>.toTutorReferences(
    requestedKnowledgeNodeIds: Set<String>,
    limit: Int,
): List<TutorTeachingReference> {
    var remainingChars = TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS
    return groupBy(KnowledgeCatalogTeachingMaterial::materialId)
        .values
        .asSequence()
        .mapNotNull { rows ->
            val material = rows.first()
            val matchedNodeIds =
                rows
                    .map { it.knowledgeNodeRef.knowledgeNodeId }
                    .filter(requestedKnowledgeNodeIds::contains)
                    .distinct()
            if (matchedNodeIds.isEmpty()) return@mapNotNull null

            TutorTeachingReference(
                materialId = material.materialId,
                subject = material.subject.name,
                materialType = material.materialType,
                title = material.title,
                summaryMarkdown = material.summaryMarkdown,
                applicabilityMarkdown = material.applicabilityMarkdown,
                contentMarkdown = material.contentMarkdown,
                boundaryMarkdown = material.boundaryMarkdown,
                knowledgeNodeIds = matchedNodeIds,
            )
        }
        .filter { reference ->
            val accepted =
                reference.markdownChars <= remainingChars &&
                    remainingChars > 0
            if (accepted) remainingChars -= reference.markdownChars
            accepted
        }
        .take(limit)
        .toList()
}

private fun List<KnowledgeCatalogTeachingMaterial>.toTutorReferences(
    directKnowledgeNodes: Map<String, ConfirmedKnowledgeNodeBinding>,
    manifestFingerprint: String,
    activationGeneration: Long,
    limit: Int,
): List<TutorTeachingReference> {
    var remainingChars = TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS
    return groupBy(KnowledgeCatalogTeachingMaterial::materialId)
        .values
        .asSequence()
        .mapNotNull { rows ->
            val material = rows.first()
            if (rows.any { it.subject != material.subject }) return@mapNotNull null
            if (
                rows.any { row ->
                    directKnowledgeNodes[row.knowledgeNodeRef.knowledgeNodeId]?.ref !=
                        row.knowledgeNodeRef
                }
            ) {
                return@mapNotNull null
            }
            val matchedRefs = rows.map { it.knowledgeNodeRef }.distinct()
            if (matchedRefs.isEmpty()) return@mapNotNull null

            TutorTeachingReference(
                materialId = material.materialId,
                subject = material.subject.name,
                materialType = material.materialType,
                title = material.title,
                summaryMarkdown = material.summaryMarkdown,
                applicabilityMarkdown = material.applicabilityMarkdown,
                contentMarkdown = material.contentMarkdown,
                boundaryMarkdown = material.boundaryMarkdown,
                knowledgeNodeIds = matchedRefs.map { it.knowledgeNodeId },
                boundKnowledgeNodes = matchedRefs,
                manifestFingerprint = manifestFingerprint,
                activationGeneration = activationGeneration,
            )
        }
        .filter { reference ->
            val accepted = reference.markdownChars <= remainingChars && remainingChars > 0
            if (accepted) remainingChars -= reference.markdownChars
            accepted
        }
        .take(limit)
        .toList()
}
