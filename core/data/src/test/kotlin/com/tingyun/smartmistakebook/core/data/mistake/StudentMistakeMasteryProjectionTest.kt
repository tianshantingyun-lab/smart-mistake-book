package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayKnowledgeItem
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeMasteryProjectionTest {
    @Test
    fun `maps mastery display states without inferring from missing data`() {
        val conflicted = knowledge("conflicted")
        val stale = knowledge("stale")
        val learning = knowledge("learning")
        val mastered = knowledge("mastered")
        val missing = knowledge("missing")
        val bindings =
            listOf(
                binding("mistake-conflicted", conflicted),
                binding("mistake-stale", stale),
                binding("mistake-learning", learning),
                binding("mistake-mastered", mastered),
                binding("mistake-missing", missing),
                binding("mistake-empty"),
            )

        val projection =
            projectStudentMistakeMastery(
                bindings = bindings,
                expectedRevision = REVISION,
                masteryBatches =
                    listOf(
                        batch(
                            displayItem(
                                conflicted,
                                KnowledgeMasteryState.STEADY,
                                KnowledgeMasteryTrend.WAVERING,
                            ),
                            displayItem(
                                stale,
                                KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                            ),
                            displayItem(
                                learning,
                                KnowledgeMasteryState.FAMILIARIZING,
                            ),
                            displayItem(mastered, KnowledgeMasteryState.STEADY),
                        ),
                    ),
            )

        assertEquals(MasteryStatus.CONFLICTED, projection.statusByMistakeId["mistake-conflicted"])
        assertEquals(MasteryStatus.STALE, projection.statusByMistakeId["mistake-stale"])
        assertEquals(MasteryStatus.LEARNING, projection.statusByMistakeId["mistake-learning"])
        assertEquals(MasteryStatus.MASTERED, projection.statusByMistakeId["mistake-mastered"])
        assertEquals(MasteryStatus.UNKNOWN, projection.statusByMistakeId["mistake-missing"])
        assertEquals(MasteryStatus.UNKNOWN, projection.statusByMistakeId["mistake-empty"])
        assertEquals(REVISION, projection.revision)
    }

    @Test
    fun `selects the status needing the most attention across knowledge nodes`() {
        val conflicted = knowledge("conflicted")
        val stale = knowledge("stale")
        val learning = knowledge("learning")
        val unknown = knowledge("unknown")
        val mastered = knowledge("mastered")
        val bindings =
            listOf(
                binding("conflict-first", mastered, stale, conflicted),
                binding("stale-first", mastered, learning, stale),
                binding("learning-first", mastered, unknown, learning),
                binding("unknown-first", mastered, unknown),
                binding("mastered-only", mastered),
            )

        val projection =
            projectStudentMistakeMastery(
                bindings = bindings,
                expectedRevision = REVISION,
                masteryBatches =
                    listOf(
                        batch(
                            displayItem(
                                conflicted,
                                KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                                KnowledgeMasteryTrend.WAVERING,
                            ),
                            displayItem(stale, KnowledgeMasteryState.NEEDS_REINFORCEMENT),
                            displayItem(learning, KnowledgeMasteryState.FAMILIARIZING),
                            displayItem(mastered, KnowledgeMasteryState.STEADY),
                        ),
                    ),
            )

        assertEquals(MasteryStatus.CONFLICTED, projection.statusByMistakeId["conflict-first"])
        assertEquals(MasteryStatus.STALE, projection.statusByMistakeId["stale-first"])
        assertEquals(MasteryStatus.LEARNING, projection.statusByMistakeId["learning-first"])
        assertEquals(MasteryStatus.UNKNOWN, projection.statusByMistakeId["unknown-first"])
        assertEquals(MasteryStatus.MASTERED, projection.statusByMistakeId["mastered-only"])
    }

    @Test
    fun `groups requests by subject and deduplicates exact stable references`() {
        val mathB = knowledge("b", subject = SubjectKind.MATH)
        val mathA = knowledge("a", subject = SubjectKind.MATH)
        val physics = knowledge("p", subject = SubjectKind.PHYSICS)

        val grouped =
            groupStudentMistakeMasteryRequests(
                listOf(
                    binding("math-1", mathB, mathA),
                    binding("math-2", mathA),
                    binding("physics-1", physics),
                ),
            )

        assertEquals(listOf(SubjectKind.MATH, SubjectKind.PHYSICS), grouped.keys.toList())
        assertEquals(listOf(mathA, mathB), grouped.getValue(SubjectKind.MATH))
        assertEquals(listOf(physics), grouped.getValue(SubjectKind.PHYSICS))
    }

    @Test
    fun `projects twenty thousand mistakes through bounded deduplicated windows`() {
        val references = (0 until 200).map { index -> knowledge("shared-$index") }
        val bindings =
            (0 until 20_000).map { index ->
                binding("mistake-$index", references[index % references.size])
            }
        val statusByMistakeId = linkedMapOf<String, MasteryStatus>()
        var maximumRequestedKnowledgeCount = 0

        bindings.chunked(64).forEach { window ->
            val requested =
                groupStudentMistakeMasteryRequests(window).getValue(SubjectKind.MATH)
            maximumRequestedKnowledgeCount = maxOf(maximumRequestedKnowledgeCount, requested.size)
            val batches =
                requested
                    .map { reference -> displayItem(reference, KnowledgeMasteryState.STEADY) }
                    .chunked(64)
                    .map { itemsInBatch -> batch(*itemsInBatch.toTypedArray()) }
            statusByMistakeId +=
                projectStudentMistakeMastery(window, REVISION, batches).statusByMistakeId
        }

        assertTrue(maximumRequestedKnowledgeCount <= 64)
        assertEquals(20_000, statusByMistakeId.size)
        assertEquals(MasteryStatus.MASTERED, statusByMistakeId["mistake-19999"])
    }

    @Test
    fun `rejects cross-subject and repeated stable identities inside one mistake`() {
        val math = knowledge("shared", subject = SubjectKind.MATH)
        val physics = knowledge("shared", subject = SubjectKind.PHYSICS)

        assertThrows(IllegalArgumentException::class.java) {
            binding("cross-subject", math, physics)
        }
        assertThrows(IllegalArgumentException::class.java) {
            binding("repeated", math, math.copy(knowledgePackVersion = "pack-2"))
        }
    }

    @Test
    fun `rejects duplicate mistake ids and conflicting references for one stable identity`() {
        val reference = knowledge("shared")

        assertThrows(IllegalArgumentException::class.java) {
            groupStudentMistakeMasteryRequests(
                listOf(binding("same-id", reference), binding("same-id", knowledge("other"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            groupStudentMistakeMasteryRequests(
                listOf(
                    binding("first", reference),
                    binding("second", reference.copy(knowledgePackVersion = "pack-2")),
                ),
            )
        }
    }

    @Test
    fun `rejects display data from another revision or outside the requested set`() {
        val requested = knowledge("requested")
        val unrequested = knowledge("unrequested")

        assertThrows(IllegalArgumentException::class.java) {
            projectStudentMistakeMastery(
                bindings = listOf(binding("mistake", requested)),
                expectedRevision = REVISION,
                masteryBatches =
                    listOf(
                        batch(
                            displayItem(requested, KnowledgeMasteryState.STEADY),
                            revision = REVISION.copy(ledgerSequence = REVISION.ledgerSequence + 1),
                        ),
                    ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            projectStudentMistakeMastery(
                bindings = listOf(binding("mistake", requested)),
                expectedRevision = REVISION,
                masteryBatches = listOf(batch(displayItem(unrequested, KnowledgeMasteryState.STEADY))),
            )
        }
    }

    @Test
    fun `rejects corrupt or repeated stable identity fingerprints`() {
        val requested = knowledge("requested")
        val item = displayItem(requested, KnowledgeMasteryState.STEADY)

        assertThrows(IllegalArgumentException::class.java) {
            projectStudentMistakeMastery(
                bindings = listOf(binding("mistake", requested)),
                expectedRevision = REVISION,
                masteryBatches =
                    listOf(
                        batch(
                            item.copy(stableNodeIdentityFingerprint = "0".repeat(64)),
                        ),
                    ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            projectStudentMistakeMastery(
                bindings = listOf(binding("mistake", requested)),
                expectedRevision = REVISION,
                masteryBatches = listOf(batch(item), batch(item)),
            )
        }
    }

    private fun binding(
        mistakeId: String,
        vararg knowledgeNodes: KnowledgeNodeRef,
    ): StudentMistakeMasteryBinding =
        StudentMistakeMasteryBinding(
            mistakeId = mistakeId,
            knowledgeNodes = knowledgeNodes.toList(),
        )

    private fun knowledge(
        id: String,
        subject: SubjectKind = SubjectKind.MATH,
        taxonomyVersion: String = "taxonomy-1",
        knowledgePackVersion: String = "pack-1",
    ): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = subject,
            knowledgeNodeId = id,
            taxonomyVersion = taxonomyVersion,
            knowledgePackVersion = knowledgePackVersion,
        )

    private fun displayItem(
        knowledgeNode: KnowledgeNodeRef,
        state: KnowledgeMasteryState,
        trend: KnowledgeMasteryTrend = KnowledgeMasteryTrend.STABLE,
    ): LearnerMasteryDisplayKnowledgeItem =
        LearnerMasteryDisplayKnowledgeItem(
            stableNodeIdentityFingerprint = stableFingerprint(knowledgeNode),
            knowledgeNode = knowledgeNode,
            currentRecallState = state,
            trend = trend,
        )

    private fun batch(
        vararg items: LearnerMasteryDisplayKnowledgeItem,
        revision: LearnerMasteryDisplayRevision = REVISION,
    ): StudentMistakeMasteryDisplayBatch =
        StudentMistakeMasteryDisplayBatch(
            revision = revision,
            items = items.toList(),
        )

    private fun stableFingerprint(knowledgeNode: KnowledgeNodeRef): String =
        CanonicalSha256("learner-mastery-stable-node-identity-v1")
            .field("subject", knowledgeNode.subject.name)
            .field("knowledgeNodeId", knowledgeNode.knowledgeNodeId)
            .field("taxonomyVersion", knowledgeNode.taxonomyVersion)
            .finish()

    private companion object {
        val REVISION = LearnerMasteryDisplayRevision(ledgerSequence = 7L, asOfEpochMillis = 1_000L)
    }
}
