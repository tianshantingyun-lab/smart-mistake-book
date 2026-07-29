package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class StoreReferencesTest {
    @Test
    fun `stable references have deterministic field-sensitive fingerprints`() {
        val problem = problemRef()
        val sameProblem = problemRef()
        val anotherProblem = problemRef(problemId = "problem-2")
        assertEquals(problem.canonicalFingerprint, sameProblem.canonicalFingerprint)
        assertNotEquals(problem.canonicalFingerprint, anotherProblem.canonicalFingerprint)

        val knowledge = knowledgeNodeRef()
        assertEquals(knowledge.canonicalFingerprint, knowledgeNodeRef().canonicalFingerprint)
        assertNotEquals(
            knowledge.canonicalFingerprint,
            knowledgeNodeRef(knowledgePackVersion = "knowledge-pack-v2").canonicalFingerprint,
        )

        val binding = bindingRef()
        assertEquals(binding.canonicalFingerprint, bindingRef().canonicalFingerprint)
        assertNotEquals(
            binding.canonicalFingerprint,
            binding.copy(bindingCanonicalFingerprint = "d".repeat(64)).canonicalFingerprint,
        )
    }

    @Test
    fun `knowledge references require exact subject ids and versions`() {
        assertIllegalArgument {
            knowledgeNodeRef().copy(schemaVersion = 2)
        }
        assertIllegalArgument {
            knowledgeNodeRef(knowledgeNodeId = "")
        }
        assertIllegalArgument {
            knowledgeNodeRef(taxonomyVersion = " taxonomy-v1")
        }
        assertIllegalArgument {
            knowledgeNodeRef(knowledgePackVersion = "")
        }
        assertIllegalArgument {
            knowledgeNodeRef(subject = SubjectKind.GENERAL)
        }
    }

    @Test
    fun `problem and evidence references reject ambiguous identities and versions`() {
        assertIllegalArgument {
            problemRef().copy(schemaVersion = 2)
        }
        assertIllegalArgument {
            problemRef(problemId = "problem-1\n")
        }
        assertIllegalArgument {
            revisionRef().copy(schemaVersion = 0)
        }
        assertIllegalArgument {
            revisionRef(revisionNumber = 0)
        }
        assertIllegalArgument {
            revisionRef(documentCanonicalFingerprint = "ABC")
        }
        assertIllegalArgument {
            LearningEvidenceRef(
                learnerId = "learner-1",
                eventKind = "ATTEMPT",
                eventId = "",
                eventSequence = 1,
                eventCanonicalFingerprint = "e".repeat(64),
            )
        }
        assertIllegalArgument {
            LearningEvidenceRef(
                learnerId = "learner-1",
                eventKind = "ATTEMPT",
                eventId = "attempt-1",
                eventSequence = 0,
                eventCanonicalFingerprint = "e".repeat(64),
            )
        }
        assertIllegalArgument {
            LearningEvidenceRef(
                learnerId = "learner-1",
                eventKind = "ATTEMPT",
                eventId = "attempt-1",
                eventSequence = 1,
                eventCanonicalFingerprint = "e".repeat(64),
                schemaVersion = 2,
            )
        }
    }

    @Test
    fun `problem knowledge binding requires a canonical proof in the same subject`() {
        assertIllegalArgument {
            bindingRef().copy(schemaVersion = 2)
        }
        assertIllegalArgument {
            bindingRef(bindingCanonicalFingerprint = "not-a-fingerprint")
        }
        assertIllegalArgument {
            bindingRef(
                knowledgeNode = knowledgeNodeRef(subject = SubjectKind.PHYSICS),
            )
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}

private fun problemRef(
    learnerId: String = "learner-1",
    subject: SubjectKind = SubjectKind.MATH,
    problemId: String = "problem-1",
    practiceUnitId: String = "practice-1",
) = StudentProblemRef(
    learnerId = learnerId,
    subject = subject,
    problemId = problemId,
    practiceUnitId = practiceUnitId,
)

private fun revisionRef(
    problem: StudentProblemRef = problemRef(),
    revisionId: String = "revision-1",
    revisionNumber: Int = 1,
    documentCanonicalFingerprint: String = "a".repeat(64),
) = StudentProblemRevisionRef(
    problem = problem,
    revisionId = revisionId,
    revisionNumber = revisionNumber,
    documentCanonicalFingerprint = documentCanonicalFingerprint,
)

private fun knowledgeNodeRef(
    subject: SubjectKind = SubjectKind.MATH,
    knowledgeNodeId: String = "kb:math:atomic:monotonicity",
    taxonomyVersion: String = "taxonomy-v1",
    knowledgePackVersion: String = "knowledge-pack-v1",
) = KnowledgeNodeRef(
    subject = subject,
    knowledgeNodeId = knowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    knowledgePackVersion = knowledgePackVersion,
)

private fun bindingRef(
    bindingId: String = "binding-1",
    problemRevision: StudentProblemRevisionRef = revisionRef(),
    knowledgeNode: KnowledgeNodeRef = knowledgeNodeRef(),
    bindingCanonicalFingerprint: String = "b".repeat(64),
) = ProblemKnowledgeBindingRef(
    bindingId = bindingId,
    problemRevision = problemRevision,
    knowledgeNode = knowledgeNode,
    bindingCanonicalFingerprint = bindingCanonicalFingerprint,
)
