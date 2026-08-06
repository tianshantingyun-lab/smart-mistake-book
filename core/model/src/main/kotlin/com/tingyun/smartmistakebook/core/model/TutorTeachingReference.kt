package com.tingyun.smartmistakebook.core.model

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reviewed teaching context for knowledge already linked to the learner's current question.
 *
 * It is not an assessment item: it has no answer key, score, difficulty, scheduling identity, or
 * authority to create learning evidence. A reference may contain a complete worked example because
 * examples and method models are teaching material, not a pool of questions to assign.
 */
@Serializable
data class TutorTeachingReference(
    val materialId: String,
    val subject: String,
    val materialType: KnowledgeTeachingMaterialType,
    val title: String,
    val summaryMarkdown: String,
    val applicabilityMarkdown: String,
    val contentMarkdown: String,
    val boundaryMarkdown: String,
    val knowledgeNodeIds: List<String>,
    @Serializable(with = KnowledgeNodeProvenanceListSerializer::class)
    val boundKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    val manifestFingerprint: String? = null,
    val activationGeneration: Long? = null,
) {
    init {
        materialId.requireSafeModelText(
            "Tutor teaching-reference id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        subject.requireSafeModelText(
            "Tutor teaching-reference subject",
            TutorPlanInput.MAX_SUBJECT_CHARS,
            false,
        )
        title.requireSafeModelText("Tutor teaching-reference title", MAX_TITLE_CHARS, false)
        summaryMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference summary",
            MAX_SUMMARY_CHARS,
        )
        applicabilityMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference applicability",
            MAX_APPLICABILITY_CHARS,
        )
        contentMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference content",
            MAX_CONTENT_CHARS,
        )
        boundaryMarkdown.requireTutorMarkdown(
            "Tutor teaching-reference boundary",
            MAX_BOUNDARY_CHARS,
        )
        require(knowledgeNodeIds.size in 1..MAX_KNOWLEDGE_NODES) {
            "Tutor teaching reference needs a bounded knowledge-point scope"
        }
        require(knowledgeNodeIds.distinct().size == knowledgeNodeIds.size) {
            "Tutor teaching-reference knowledge ids must be unique"
        }
        knowledgeNodeIds.forEach { knowledgeNodeId ->
            knowledgeNodeId.requireSafeModelText(
                "Tutor teaching-reference knowledge id",
                ModelTaskRequest.MAX_ID_CHARS,
                false,
            )
        }
        if (boundKnowledgeNodes.isNotEmpty()) {
            require(boundKnowledgeNodes.map(KnowledgeNodeRef::knowledgeNodeId) == knowledgeNodeIds) {
                "Tutor teaching-reference node witnesses must match disclosed ids"
            }
            require(boundKnowledgeNodes.all { it.subject.name == subject }) {
                "Tutor teaching-reference node witnesses must stay within the current subject"
            }
            require(manifestFingerprint?.let(SHA_256::matches) == true) {
                "Tutor teaching-reference witness requires a manifest fingerprint"
            }
            require((activationGeneration ?: 0L) > 0L) {
                "Tutor teaching-reference witness requires an activated catalog generation"
            }
        } else {
            require(manifestFingerprint == null && activationGeneration == null) {
                "Tutor teaching-reference provenance requires node witnesses"
            }
        }
    }

    val markdownChars: Int
        get() = summaryMarkdown.length +
            applicabilityMarkdown.length +
            contentMarkdown.length +
            boundaryMarkdown.length

    /** Local-only catalog authority. Provider projections deliberately omit these fields. */
    val hasCompleteCatalogProvenance: Boolean
        get() = boundKnowledgeNodes.isNotEmpty() &&
            manifestFingerprint != null &&
            activationGeneration != null

    companion object {
        const val MAX_TITLE_CHARS = 240
        const val MAX_SUMMARY_CHARS = 4_000
        const val MAX_APPLICABILITY_CHARS = 8_000
        const val MAX_CONTENT_CHARS = 32_000
        const val MAX_BOUNDARY_CHARS = 8_000
        const val MAX_KNOWLEDGE_NODES = 16
        private val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

@Serializable
private data class KnowledgeNodeProvenance(
    val subject: String,
    val nodeId: String,
    val taxonomyVersion: String,
    val packVersion: String,
    val refSchemaVersion: Int,
)

private object KnowledgeNodeProvenanceListSerializer : KSerializer<List<KnowledgeNodeRef>> {
    private val delegate = ListSerializer(KnowledgeNodeProvenance.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<KnowledgeNodeRef>) {
        delegate.serialize(
            encoder,
            value.map { reference ->
                KnowledgeNodeProvenance(
                    subject = reference.subject.name,
                    nodeId = reference.knowledgeNodeId,
                    taxonomyVersion = reference.taxonomyVersion,
                    packVersion = reference.knowledgePackVersion,
                    refSchemaVersion = reference.schemaVersion,
                )
            },
        )
    }

    override fun deserialize(decoder: Decoder): List<KnowledgeNodeRef> =
        delegate.deserialize(decoder).map { provenance ->
            try {
                KnowledgeNodeRef(
                    subject = SubjectKind.valueOf(provenance.subject),
                    knowledgeNodeId = provenance.nodeId,
                    taxonomyVersion = provenance.taxonomyVersion,
                    knowledgePackVersion = provenance.packVersion,
                    schemaVersion = provenance.refSchemaVersion,
                )
            } catch (error: IllegalArgumentException) {
                throw SerializationException("Invalid tutor knowledge-node provenance", error)
            }
        }
}

internal fun List<TutorTeachingReference>.requireValidTutorTeachingReferences(
    subject: String,
    label: String,
) {
    require(size <= TutorPlanInput.MAX_TEACHING_REFERENCES) {
        "$label disclosed too many teaching references"
    }
    require(map(TutorTeachingReference::materialId).distinct().size == size) {
        "$label teaching-reference ids must be unique"
    }
    require(all { reference -> reference.subject == subject }) {
        "$label teaching references must stay within the current subject"
    }
    require(
        sumOf(TutorTeachingReference::markdownChars) <=
            TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS,
    ) {
        "$label teaching references exceed their total text budget"
    }
}
