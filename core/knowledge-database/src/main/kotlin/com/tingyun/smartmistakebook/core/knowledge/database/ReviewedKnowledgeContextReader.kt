package com.tingyun.smartmistakebook.core.knowledge.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import java.io.Closeable
import java.util.Collections

data class ReviewedKnowledgeContextRequest(
    val subject: SubjectKind,
    val currentQuestion: String,
    val currentSubquestion: String? = null,
    val surroundingContext: String? = null,
) {
    constructor(
        subject: SubjectKind,
        query: String,
    ) : this(subject = subject, currentQuestion = query)

    init {
        require(subject != SubjectKind.GENERAL) {
            "Reviewed knowledge context requires one high-school subject"
        }
        KnowledgeRecallQuery(
            currentQuestion = currentQuestion,
            currentSubquestion = currentSubquestion,
            surroundingContext = surroundingContext,
        )
    }

    internal fun toRecallQuery(): KnowledgeRecallQuery =
        KnowledgeRecallQuery(
            currentQuestion = currentQuestion,
            currentSubquestion = currentSubquestion,
            surroundingContext = surroundingContext,
        )
}

private enum class SelectedNodeOrigin {
    DIRECT_MATCH,
    RELATED,
}

/**
 * Bounded node description safe to place in model context.
 *
 * It deliberately omits database handles, catalog references, learner state, and write authority.
 */
class ReviewedKnowledgeContextNode internal constructor(
    val alias: String,
    val subject: SubjectKind,
    val displayName: String,
    val canonicalName: String,
    val aliases: List<String>,
    val parentDisplayName: String?,
    val boundaryMarkdown: String?,
) {
    internal val textCharacterCount: Int
        get() =
            alias.length +
                displayName.length +
                canonicalName.length +
                aliases.sumOf(String::length) +
                parentDisplayName.orEmpty().length +
                boundaryMarkdown.orEmpty().length
}

class ReviewedKnowledgeContextRelation internal constructor(
    val fromAlias: String,
    val toAlias: String,
    val relationship: String,
)

/**
 * Reviewed explanatory material, not an assessment item or question-bank record.
 */
class ReviewedKnowledgeContextMaterial internal constructor(
    val alias: String,
    val subject: SubjectKind,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val knowledgeAliases: List<String>,
) {
    val markdownCharacterCount: Int
        get() =
            alias.length +
                title.length +
                summaryMarkdown.length +
                applicabilityMarkdown.length +
                contentMarkdown.length +
                boundaryMarkdown.length +
                knowledgeAliases.sumOf(String::length)
}

class ReviewedKnowledgeContext internal constructor(
    val subject: SubjectKind,
    val nodes: List<ReviewedKnowledgeContextNode>,
    val relations: List<ReviewedKnowledgeContextRelation>,
    val teachingMaterials: List<ReviewedKnowledgeContextMaterial>,
) {
    init {
        require(
            nodes.sumOf(ReviewedKnowledgeContextNode::textCharacterCount) +
                relations.sumOf { relation ->
                    relation.fromAlias.length +
                        relation.toAlias.length +
                        relation.relationship.length
                } +
                teachingMaterials.sumOf(ReviewedKnowledgeContextMaterial::markdownCharacterCount) <=
                ReviewedKnowledgeContextReader.MAX_TOTAL_MODEL_CONTEXT_CHARS,
        ) {
            "Reviewed knowledge context exceeds its total character budget"
        }
    }
}

/**
 * One bounded read operation for model context.
 *
 * The implementation always constrains the subject before using the local normalized full-text
 * and alias index, then expands only a small deterministic relationship neighborhood. The public
 * surface exposes no catalog object, store reference, DAO, database, SQL, or mutation method.
 */
interface ReviewedKnowledgeContextReader : Closeable {
    suspend fun read(request: ReviewedKnowledgeContextRequest): ReviewedKnowledgeContext

    companion object {
        const val MAX_DIRECT_NODES = 6
        const val MAX_RELATED_NODES = 12
        const val MAX_RELATIONS = 24
        const val MAX_RELATIONS_PER_DIRECT_NODE = 4
        const val MAX_NODE_TEXT_CHARS = 24_000
        const val MAX_TEACHING_MATERIALS = TutorPlanInput.MAX_TEACHING_REFERENCES
        const val MAX_TEACHING_MATERIAL_MARKDOWN_CHARS =
            TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS
        const val MAX_TOTAL_MODEL_CONTEXT_CHARS = 64_000
    }
}

object ReviewedKnowledgeContextReaderFactory {
    fun open(context: Context): ReviewedKnowledgeContextReader =
        CatalogBackedReviewedKnowledgeContextReader(
            HighSchoolKnowledgeCatalogFactory.open(context.applicationContext),
        )
}

private class CatalogBackedReviewedKnowledgeContextReader(
    private val catalog: HighSchoolKnowledgeCatalog,
) : ReviewedKnowledgeContextReader {
    override suspend fun read(
        request: ReviewedKnowledgeContextRequest,
    ): ReviewedKnowledgeContext {
        val neighborhood =
            catalog.readNeighborhood(
                subject = request.subject,
                query = request.toRecallQuery(),
                directLimit = ReviewedKnowledgeContextReader.MAX_DIRECT_NODES,
                relatedLimit = ReviewedKnowledgeContextReader.MAX_RELATED_NODES,
                relationLimit = ReviewedKnowledgeContextReader.MAX_RELATIONS,
            )
        val directHits = neighborhood.directHits
        val handlesById =
            if (directHits.isEmpty()) {
                emptyMap()
            } else {
                catalog.resolveNodes(
                    subject = request.subject,
                    knowledgeNodeIds =
                        directHits.map { hit -> hit.node.ref.knowledgeNodeId },
                ).associateBy { handle -> handle.ref.knowledgeNodeId }
            }
        val directHandles =
            directHits.mapNotNull { hit ->
                handlesById[hit.node.ref.knowledgeNodeId]?.let { handle -> hit to handle }
            }

        val reviewedParentsByRef =
            neighborhood.parentNodes
                .filter { node ->
                    node.subject == request.subject &&
                        node.verificationStatus != KnowledgeNodeVerificationStatus.MODEL_CANDIDATE
                }
                .associateBy(KnowledgeCatalogNode::ref)
        val nodesById = LinkedHashMap<String, SelectedContextNode>()
        var nodeTextCharacterCount = 0
        fun addNode(node: SelectedContextNode): Boolean {
            if (node.knowledgeNodeId in nodesById) return true
            val directCount =
                nodesById.values.count { existing ->
                    existing.origin == SelectedNodeOrigin.DIRECT_MATCH
                }
            val relatedCount = nodesById.size - directCount
            if (
                node.origin == SelectedNodeOrigin.DIRECT_MATCH &&
                directCount >= ReviewedKnowledgeContextReader.MAX_DIRECT_NODES
            ) {
                return false
            }
            if (
                node.origin == SelectedNodeOrigin.RELATED &&
                relatedCount >= ReviewedKnowledgeContextReader.MAX_RELATED_NODES
            ) {
                return false
            }
            if (
                nodeTextCharacterCount + node.textCharacterCount >
                    ReviewedKnowledgeContextReader.MAX_NODE_TEXT_CHARS
            ) {
                return false
            }
            nodesById[node.knowledgeNodeId] = node
            nodeTextCharacterCount += node.textCharacterCount
            return true
        }

        val acceptedDirectHandles = ArrayList<VerifiedKnowledgeNodeHandle>()
        directHandles.forEach { (_, handle) ->
            val node =
                handle.node.toSelectedContextNode(
                    origin = SelectedNodeOrigin.DIRECT_MATCH,
                    parentDisplayName =
                        handle.node.parentRef
                            ?.let(reviewedParentsByRef::get)
                            ?.displayName,
                )
            if (node != null && addNode(node)) acceptedDirectHandles += handle
        }

        val relationsById = LinkedHashMap<String, KnowledgeCatalogRelation>()
        val relatedByRef = neighborhood.relatedNodes.associateBy(KnowledgeCatalogNode::ref)
        for (relation in neighborhood.relations) {
            if (relationsById.size >= ReviewedKnowledgeContextReader.MAX_RELATIONS) break
            if (relation.subject != request.subject) continue
            val directHandle =
                acceptedDirectHandles.firstOrNull { handle ->
                    relation.from == handle.ref || relation.to == handle.ref
                } ?: continue
            val relatedRef =
                when {
                    relation.from == directHandle.ref -> relation.to
                    relation.to == directHandle.ref -> relation.from
                    else -> continue
                }
            val related =
                relatedByRef[relatedRef]
                    ?: acceptedDirectHandles.firstOrNull { it.ref == relatedRef }?.node
                    ?: continue
            val relatedNode =
                related.toSelectedContextNode(
                    origin = SelectedNodeOrigin.RELATED,
                    parentDisplayName =
                        related.parentRef
                            ?.let(reviewedParentsByRef::get)
                            ?.displayName,
                ) ?: continue
            if (!addNode(relatedNode)) continue
            if (
                relation.from.knowledgeNodeId !in nodesById ||
                relation.to.knowledgeNodeId !in nodesById
            ) {
                continue
            }
            relationsById.putIfAbsent(relation.relationId, relation)
        }

        val materialById = LinkedHashMap<String, MaterialAccumulator>()
        var materialMarkdownCharacterCount = 0
        val candidates =
            if (acceptedDirectHandles.isEmpty()) {
                emptyList()
            } else {
                catalog.readTeachingMaterials(
                    nodes = acceptedDirectHandles,
                    limit = ReviewedKnowledgeContextReader.MAX_TEACHING_MATERIALS,
                )
            }
        for (candidate in candidates) {
            if (candidate.subject != request.subject) continue
            val existing = materialById[candidate.materialId]
            if (existing != null) {
                check(existing.material.contentFingerprint == candidate.contentFingerprint) {
                    "One reviewed material id resolved to conflicting content"
                }
                existing.knowledgeNodeIds += candidate.knowledgeNodeRef.knowledgeNodeId
                continue
            }
            if (
                materialById.size >=
                    ReviewedKnowledgeContextReader.MAX_TEACHING_MATERIALS
            ) {
                break
            }
            if (
                materialMarkdownCharacterCount + candidate.markdownCharacterCount >
                    ReviewedKnowledgeContextReader
                        .MAX_TEACHING_MATERIAL_MARKDOWN_CHARS
            ) {
                continue
            }
            if (!candidate.hasModelSafeMaterialText()) continue
            materialById[candidate.materialId] =
                MaterialAccumulator(
                    material = candidate,
                    knowledgeNodeIds = linkedSetOf(candidate.knowledgeNodeRef.knowledgeNodeId),
                )
            materialMarkdownCharacterCount += candidate.markdownCharacterCount
        }

        val nodeAliasById =
            nodesById.keys.mapIndexed { index, nodeId ->
                nodeId to "knowledge-${index + 1}"
            }.toMap()
        val nodes =
            nodesById.values.map { node ->
                node.toReviewedContextNode(nodeAliasById.getValue(node.knowledgeNodeId))
            }
        val relations =
            relationsById.values.mapNotNull { relation ->
                relation.toReviewedContextRelation(nodeAliasById)
            }
        var totalContextCharacters =
            nodes.sumOf(ReviewedKnowledgeContextNode::textCharacterCount) +
                relations.sumOf { relation ->
                    relation.fromAlias.length +
                        relation.toAlias.length +
                        relation.relationship.length
                }
        var acceptedMaterialMarkdownCharacters = 0
        val materials = ArrayList<ReviewedKnowledgeContextMaterial>()
        materialById.values.forEach { accumulator ->
            val material =
                accumulator.material.toReviewedContextMaterial(
                    alias = "material-${materials.size + 1}",
                    knowledgeAliases =
                        accumulator.knowledgeNodeIds.mapNotNull(nodeAliasById::get),
                ) ?: return@forEach
            if (
                acceptedMaterialMarkdownCharacters + material.markdownCharacterCount >
                    ReviewedKnowledgeContextReader.MAX_TEACHING_MATERIAL_MARKDOWN_CHARS ||
                totalContextCharacters + material.markdownCharacterCount >
                    ReviewedKnowledgeContextReader.MAX_TOTAL_MODEL_CONTEXT_CHARS
            ) {
                return@forEach
            }
            materials += material
            acceptedMaterialMarkdownCharacters += material.markdownCharacterCount
            totalContextCharacters += material.markdownCharacterCount
        }

        return ReviewedKnowledgeContext(
            subject = request.subject,
            nodes = Collections.unmodifiableList(nodes),
            relations = Collections.unmodifiableList(relations),
            teachingMaterials = Collections.unmodifiableList(materials),
        )
    }

    override fun close() {
        catalog.close()
    }
}

private data class MaterialAccumulator(
    val material: KnowledgeCatalogTeachingMaterial,
    val knowledgeNodeIds: LinkedHashSet<String>,
)

private data class SelectedContextNode(
    val catalogNode: KnowledgeCatalogNode,
    val origin: SelectedNodeOrigin,
    val selectedAliases: List<String>,
    val parentDisplayName: String?,
    val selectedBoundaryMarkdown: String?,
) {
    val knowledgeNodeId: String
        get() = catalogNode.ref.knowledgeNodeId

    val textCharacterCount: Int
        get() =
            catalogNode.displayName.length +
                catalogNode.canonicalName.length +
                selectedAliases.sumOf(String::length) +
                parentDisplayName.orEmpty().length +
                selectedBoundaryMarkdown.orEmpty().length

    fun toReviewedContextNode(alias: String): ReviewedKnowledgeContextNode =
        ReviewedKnowledgeContextNode(
            alias = alias,
            subject = catalogNode.subject,
            displayName = catalogNode.displayName,
            canonicalName = catalogNode.canonicalName,
            aliases = Collections.unmodifiableList(selectedAliases),
            parentDisplayName = parentDisplayName,
            boundaryMarkdown = selectedBoundaryMarkdown,
        )
}

private fun KnowledgeCatalogNode.toSelectedContextNode(
    origin: SelectedNodeOrigin,
    parentDisplayName: String?,
): SelectedContextNode? {
    if (
        verificationStatus == KnowledgeNodeVerificationStatus.MODEL_CANDIDATE ||
        displayName.length > MODEL_CONTEXT_MAX_NODE_NAME_CHARS ||
        canonicalName.length > MODEL_CONTEXT_MAX_NODE_NAME_CHARS
    ) {
        return null
    }
    val selectedAliases =
        aliases
            .asSequence()
            .filter { alias -> alias.length <= MODEL_CONTEXT_MAX_ALIAS_CHARS }
            .sorted()
            .take(MODEL_CONTEXT_MAX_ALIASES)
            .toList()
    return SelectedContextNode(
        catalogNode = this,
        origin = origin,
        selectedAliases = selectedAliases,
        parentDisplayName = parentDisplayName,
        selectedBoundaryMarkdown =
            boundaryMarkdown?.takeIf { value ->
                value.length <= MODEL_CONTEXT_MAX_NODE_BOUNDARY_CHARS
            },
    )
}

private fun KnowledgeCatalogRelation.toReviewedContextRelation(
    nodeAliasById: Map<String, String>,
): ReviewedKnowledgeContextRelation? {
    val fromAlias = nodeAliasById[from.knowledgeNodeId] ?: return null
    val toAlias = nodeAliasById[to.knowledgeNodeId] ?: return null
    return ReviewedKnowledgeContextRelation(
        fromAlias = fromAlias,
        toAlias = toAlias,
        relationship =
            when (relationType) {
                "PREREQUISITE_OF" -> "前者是后者的基础"
                "PART_OF", "BELONGS_TO" -> "前者属于后者"
                "EQUIVALENT_TO" -> "两者含义相近"
                else -> "两者相关"
            },
    )
}

private fun KnowledgeCatalogTeachingMaterial.hasModelSafeMaterialText(): Boolean =
    !(
        title.length > TutorTeachingReference.MAX_TITLE_CHARS ||
            summaryMarkdown.length > TutorTeachingReference.MAX_SUMMARY_CHARS ||
            applicabilityMarkdown.length > TutorTeachingReference.MAX_APPLICABILITY_CHARS ||
            contentMarkdown.length > TutorTeachingReference.MAX_CONTENT_CHARS ||
            boundaryMarkdown.length > TutorTeachingReference.MAX_BOUNDARY_CHARS
    )

private fun KnowledgeCatalogTeachingMaterial.toReviewedContextMaterial(
    alias: String,
    knowledgeAliases: List<String>,
): ReviewedKnowledgeContextMaterial? {
    if (!hasModelSafeMaterialText() || knowledgeAliases.isEmpty()) return null
    return ReviewedKnowledgeContextMaterial(
        alias = alias,
        subject = subject,
        title = title,
        summaryMarkdown = summaryMarkdown,
        applicabilityMarkdown = applicabilityMarkdown,
        contentMarkdown = contentMarkdown,
        boundaryMarkdown = boundaryMarkdown,
        knowledgeAliases =
            Collections.unmodifiableList(
                knowledgeAliases.distinct(),
            ),
    )
}

private const val MODEL_CONTEXT_MAX_NODE_NAME_CHARS = 512
private const val MODEL_CONTEXT_MAX_NODE_BOUNDARY_CHARS = 2_000
private const val MODEL_CONTEXT_MAX_ALIASES = 4
private const val MODEL_CONTEXT_MAX_ALIAS_CHARS = 128
