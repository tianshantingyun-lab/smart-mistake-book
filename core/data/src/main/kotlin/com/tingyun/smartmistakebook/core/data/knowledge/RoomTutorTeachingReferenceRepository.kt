package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

class RoomTutorTeachingReferenceRepository(
    private val database: StudyDatabasePort,
) : TutorTeachingReferenceRepository {
    override suspend fun referencesFor(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<TutorTeachingReference> {
        require(subject.isNotBlank())
        require(knowledgeNodeIds.all(String::isNotBlank))
        require(limit in 1..TutorPlanInput.MAX_TEACHING_REFERENCES)
        if (knowledgeNodeIds.isEmpty()) return emptyList()

        val materials = database.readKnowledgeTeachingMaterialsForNodes(
            subject = subject,
            knowledgeNodeIds = knowledgeNodeIds,
            limit = limit,
        )
        if (materials.isEmpty()) return emptyList()
        val bindings = database.readKnowledgeTeachingMaterialNodeBindings(
            materials.mapTo(linkedSetOf(), KnowledgeTeachingMaterialRecord::materialId),
        )
        return TutorTeachingReferenceSelector.select(
            subject = subject,
            requestedKnowledgeNodeIds = knowledgeNodeIds,
            materials = materials,
            bindings = bindings,
            limit = limit,
        )
    }
}

object TutorTeachingReferenceRepositoryFactory {
    fun create(database: StudyDatabasePort): TutorTeachingReferenceRepository =
        RoomTutorTeachingReferenceRepository(database)
}

internal object TutorTeachingReferenceSelector {
    fun select(
        subject: String,
        requestedKnowledgeNodeIds: Set<String>,
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        limit: Int,
    ): List<TutorTeachingReference> {
        require(limit in 1..TutorPlanInput.MAX_TEACHING_REFERENCES)
        val bindingsByMaterial = bindings.groupBy(
            KnowledgeTeachingMaterialNodeBindingRecord::materialId,
        )
        var remainingChars = TutorPlanInput.MAX_TEACHING_REFERENCE_MARKDOWN_CHARS
        return buildList {
            materials.asSequence()
                .filter { material -> material.subject == subject }
                .mapNotNull { material ->
                    val matchedKnowledgeNodeIds = bindingsByMaterial[material.materialId]
                        .orEmpty()
                        .map(KnowledgeTeachingMaterialNodeBindingRecord::knowledgeNodeId)
                        .filter(requestedKnowledgeNodeIds::contains)
                        .distinct()
                    if (matchedKnowledgeNodeIds.isEmpty()) return@mapNotNull null
                    TutorTeachingReference(
                        materialId = material.materialId,
                        subject = material.subject,
                        materialType = enumValueOf<KnowledgeTeachingMaterialType>(
                            material.materialType,
                        ),
                        title = material.title,
                        summaryMarkdown = material.summaryMarkdown,
                        applicabilityMarkdown = material.applicabilityMarkdown,
                        contentMarkdown = material.contentMarkdown,
                        boundaryMarkdown = material.boundaryMarkdown,
                        knowledgeNodeIds = matchedKnowledgeNodeIds,
                    )
                }
                .forEach { reference ->
                    if (size < limit && reference.markdownChars <= remainingChars) {
                        add(reference)
                        remainingChars -= reference.markdownChars
                    }
                }
        }
    }
}
