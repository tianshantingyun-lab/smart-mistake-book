package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedKnowledgeNodeBinding
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMistakeTeachingReferenceWiringTest {
    @Test
    fun eachBindingEmissionClearsContextBeforeSuspendingLookup() = runTest {
        val key = MistakeRevisionKey("entry-1", "problem-1", "revision-1")
        val direct = binding("knowledge:math:direct")
        val organizations = MutableSharedFlow<ConfirmedMistakeOrganization>()
        val releaseLookup = CompletableDeferred<Unit>()
        val emissions = mutableListOf<DirectTeachingContext>()
        val repository = TutorTeachingReferenceRepository { _, _, _ ->
            releaseLookup.await()
            listOf(reference("material:direct", direct))
        }
        val collection = launch {
            observeDirectTeachingContext(
                key = key,
                subject = SubjectKind.MATH.name,
                confirmedOrganizations = organizations,
                teachingReferenceRepository = repository,
            ).collect(emissions::add)
        }
        runCurrent()

        organizations.emit(ConfirmedMistakeOrganization(directKnowledgeNodes = listOf(direct)))
        runCurrent()

        assertEquals(2, emissions.size)
        assertTrue(emissions.all { it.lookupState == DirectTeachingLookupState.PREPARING })
        assertTrue(emissions.all { it.references.isEmpty() })
        assertTrue(emissions.first().directKnowledgeNodeIds.isEmpty())
        assertEquals(setOf(direct.ref.knowledgeNodeId), emissions.last().directKnowledgeNodeIds)
        assertEquals(listOf(direct.ref), emissions.last().directKnowledgeNodeRefs)
        assertTrue(!emissions.last().isReadyForTutorPlan(waitExpired = false))
        assertTrue(emissions.last().isReadyForTutorPlan(waitExpired = true))
        releaseLookup.complete(Unit)
        runCurrent()
        assertEquals(setOf(direct.ref.knowledgeNodeId), emissions.last().directKnowledgeNodeIds)
        assertEquals(listOf(direct.ref), emissions.last().directKnowledgeNodeRefs)
        assertEquals(DirectTeachingLookupState.READY, emissions.last().lookupState)
        assertEquals(listOf("material:direct"), emissions.last().references.map { it.materialId })
        assertTrue(emissions.last().isReadyForTutorPlan(waitExpired = false))
        assertTrue(emissions.last().isCurrentFor(key, SubjectKind.MATH.name))
        collection.cancel()
    }

    @Test
    fun cancelledBindingLookupCannotInsertLateReferences() = runTest {
        val key = MistakeRevisionKey("entry-1", "problem-1", "revision-1")
        val first = binding("knowledge:math:first")
        val second = binding("knowledge:math:second")
        val organizations = MutableSharedFlow<ConfirmedMistakeOrganization>()
        val firstRelease = CompletableDeferred<Unit>()
        val emissions = mutableListOf<DirectTeachingContext>()
        val repository = object : TutorTeachingReferenceRepository {
            override suspend fun referencesFor(
                subject: String,
                knowledgeNodeIds: Set<String>,
                limit: Int,
            ): List<TutorTeachingReference> = error("Typed witnesses are required")

            override suspend fun referencesForConfirmedNodes(
                subject: String,
                directKnowledgeNodes: Set<ConfirmedKnowledgeNodeBinding>,
                limit: Int,
            ): List<TutorTeachingReference> {
                val binding = directKnowledgeNodes.single()
                if (binding == first) firstRelease.await()
                return listOf(reference("material:${binding.ref.knowledgeNodeId}", binding))
            }
        }
        val collection = launch {
            observeDirectTeachingContext(
                key = key,
                subject = SubjectKind.MATH.name,
                confirmedOrganizations = organizations,
                teachingReferenceRepository = repository,
            ).collect(emissions::add)
        }
        runCurrent()

        organizations.emit(ConfirmedMistakeOrganization(directKnowledgeNodes = listOf(first)))
        runCurrent()
        organizations.emit(ConfirmedMistakeOrganization(directKnowledgeNodes = listOf(second)))
        runCurrent()
        firstRelease.complete(Unit)
        runCurrent()

        val resolved = emissions.last()
        assertEquals(setOf(second.ref.knowledgeNodeId), resolved.directKnowledgeNodeIds)
        assertEquals(listOf(second.ref), resolved.directKnowledgeNodeRefs)
        assertEquals(listOf("material:${second.ref.knowledgeNodeId}"), resolved.references.map { it.materialId })
        assertTrue(resolved.references.none { it.materialId.contains("first") })
        assertTrue(
            !resolved.isCurrentFor(
                MistakeRevisionKey("entry-2", "problem-2", "revision-2"),
                SubjectKind.MATH.name,
            ),
        )
        collection.cancel()
    }

    @Test
    fun loadsOnlyReferencesBoundToTheExactDirectSnapshot() = runTest {
        val direct = binding("knowledge:math:direct")
        val related = binding("knowledge:math:related")
        val stale = binding(
            nodeId = direct.ref.knowledgeNodeId,
            packVersion = "pack-v2",
        )
        val crossSubject = binding(
            nodeId = "knowledge:physics:direct",
            subject = SubjectKind.PHYSICS,
        )
        val repository = object : TutorTeachingReferenceRepository {
            override suspend fun referencesFor(
                subject: String,
                knowledgeNodeIds: Set<String>,
                limit: Int,
            ): List<TutorTeachingReference> = error("Production route must use typed witnesses")

            override suspend fun referencesForConfirmedNodes(
                subject: String,
                directKnowledgeNodes: Set<ConfirmedKnowledgeNodeBinding>,
                limit: Int,
            ): List<TutorTeachingReference> {
                assertEquals(setOf(direct), directKnowledgeNodes)
                return listOf(
                    reference("material:direct", direct),
                    reference("material:related", related),
                    reference("material:stale", stale),
                    reference("material:cross-subject", crossSubject),
                )
            }
        }

        val references = loadDirectTeachingReferences(
            subject = SubjectKind.MATH.name,
            directKnowledgeNodes = listOf(direct),
            repository = repository,
        )

        assertEquals(listOf("material:direct"), references.map { it.materialId })
    }

    @Test
    fun lookupFailureAndTimeoutDegradeToNoReferences() = runTest {
        val direct = binding("knowledge:math:direct")
        val failing = TutorTeachingReferenceRepository { _, _, _ -> error("catalog unavailable") }
        val slow = TutorTeachingReferenceRepository { _, _, _ ->
            delay(1_000)
            listOf(reference("material:late", direct))
        }

        assertTrue(
            loadDirectTeachingReferences(
                subject = SubjectKind.MATH.name,
                directKnowledgeNodes = listOf(direct),
                repository = failing,
            ).isEmpty(),
        )
        assertTrue(
            loadDirectTeachingReferences(
                subject = SubjectKind.MATH.name,
                directKnowledgeNodes = listOf(direct),
                repository = slow,
                timeoutMillis = 10,
            ).isEmpty(),
        )
    }

    @Test
    fun effectiveMasteryNodesUseDirectBindingsOnlyWhenExplicitNodesAreAbsent() {
        val direct = binding("knowledge:math:direct")
        val explicit = binding("knowledge:math:explicit")
        val secondDirect = binding("knowledge:math:direct-second")

        assertEquals(
            listOf(explicit.ref),
            effectiveTutorMasteryKnowledgeNodes(
                explicit = listOf(explicit.ref),
                direct = listOf(direct.ref, secondDirect.ref),
            ),
        )
        assertEquals(
            listOf(direct.ref, secondDirect.ref),
            effectiveTutorMasteryKnowledgeNodes(
                explicit = emptyList(),
                direct = listOf(direct.ref, secondDirect.ref),
            ),
        )
        assertEquals(
            emptyList<KnowledgeNodeRef>(),
            effectiveTutorMasteryKnowledgeNodes(
                explicit = emptyList(),
                direct = emptyList(),
            ),
        )
    }

    private fun binding(
        nodeId: String,
        subject: SubjectKind = SubjectKind.MATH,
        packVersion: String = "pack-v1",
    ): ConfirmedKnowledgeNodeBinding = ConfirmedKnowledgeNodeBinding(
        ref = KnowledgeNodeRef(
            subject = subject,
            knowledgeNodeId = nodeId,
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = packVersion,
        ),
        manifestFingerprint = "a".repeat(64),
        activationGeneration = 7,
    )

    private fun reference(
        materialId: String,
        binding: ConfirmedKnowledgeNodeBinding,
    ): TutorTeachingReference = TutorTeachingReference(
        materialId = materialId,
        subject = binding.ref.subject.name,
        materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
        title = "概念说明",
        summaryMarkdown = "摘要",
        applicabilityMarkdown = "适用范围",
        contentMarkdown = "讲解正文",
        boundaryMarkdown = "边界说明",
        knowledgeNodeIds = listOf(binding.ref.knowledgeNodeId),
        boundKnowledgeNodes = listOf(binding.ref),
        manifestFingerprint = binding.manifestFingerprint,
        activationGeneration = binding.activationGeneration,
    )
}
