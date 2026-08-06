package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentProblemKnowledgeAttributionPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemKnowledgeAttributionSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationKnowledgeSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemResolvedErrorKnowledgeAttribution
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemStepKnowledgeAttribution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedStudentProblemKnowledgeAttributionReaderTest {
    @Test
    fun exactCurrentAttributionIsReverifiedAndRepeatedReadsAreIdempotent() = runBlocking {
        val persisted = attributionSnapshot(receiptId = "receipt-1", organizationRevision = 1)
        val store = FakePersistedAttributionPort(persisted = persisted)
        val verifier = FakeCurrentKnowledgeVerifier()
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(store, verifier)

        val first = checkNotNull(reader.readCurrent(REVISION))
        val second = checkNotNull(reader.readCurrent(REVISION))

        assertEquals(persisted.canonicalFingerprint, first.persisted.canonicalFingerprint)
        assertEquals(first.persisted.canonicalFingerprint, second.persisted.canonicalFingerprint)
        assertEquals(listOf(KNOWLEDGE_NODE), first.verifiedKnowledgeProofs.map { it.ref })
        checkNotNull(first.proofFor(KNOWLEDGE_NODE))
        assertEquals(2, store.knowledgeSnapshotReads)
        assertEquals(4, store.attributionReads)
        assertEquals(4, verifier.calls)
    }

    @Test
    fun crossLearnerReadIsRejectedBeforeStoreOrKnowledgeAuthorityAccess() {
        val store = FakePersistedAttributionPort(persisted = attributionSnapshot())
        val verifier = FakeCurrentKnowledgeVerifier()
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(store, verifier)
        val otherLearnerRevision =
            REVISION.copy(
                problem = REVISION.problem.copy(learnerId = "learner-2"),
            )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reader.readCurrent(otherLearnerRevision) }
        }

        assertEquals(0, store.knowledgeSnapshotReads)
        assertEquals(0, store.attributionReads)
        assertEquals(0, verifier.calls)
    }

    @Test
    fun staleKnowledgeGenerationReturnsNoAttribution() = runBlocking {
        val persisted = attributionSnapshot()
        val store = FakePersistedAttributionPort(persisted = persisted)
        val verifier =
            FakeCurrentKnowledgeVerifier(
                acceptedGenerations = listOf(KNOWLEDGE_GENERATION + 1),
            )
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(store, verifier)

        assertNull(reader.readCurrent(REVISION))

        assertEquals(1, store.knowledgeSnapshotReads)
        assertEquals(1, store.attributionReads)
        assertEquals(1, verifier.calls)
    }

    @Test
    fun organizationRevisionChangeDuringReadRetriesAndReturnsOnlyOneStableRevision() = runBlocking {
        val first = attributionSnapshot(receiptId = "receipt-1", organizationRevision = 1)
        val second = attributionSnapshot(receiptId = "receipt-2", organizationRevision = 2)
        val store =
            FakePersistedAttributionPort(
                persisted = first,
                attributionSequence = listOf(first, second, second, second),
            )
        val verifier = FakeCurrentKnowledgeVerifier()
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(store, verifier)

        val result = checkNotNull(reader.readCurrent(REVISION))

        assertEquals("receipt-2", result.persisted.receiptId)
        assertEquals(2, result.persisted.organizationRevision)
        assertEquals(second.canonicalFingerprint, result.persisted.canonicalFingerprint)
        assertEquals(4, store.attributionReads)
        assertEquals(3, verifier.calls)
    }

    @Test
    fun knowledgeGenerationChangeBeforeReturnFailsClosed() = runBlocking {
        val persisted = attributionSnapshot()
        val store = FakePersistedAttributionPort(persisted = persisted)
        val verifier =
            FakeCurrentKnowledgeVerifier(
                acceptedGenerations =
                    listOf(KNOWLEDGE_GENERATION, KNOWLEDGE_GENERATION + 1),
            )
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(store, verifier)

        assertNull(reader.readCurrent(REVISION))

        assertEquals(2, store.attributionReads)
        assertEquals(2, verifier.calls)
    }

    @Test
    fun continuouslyChangingOrganizationNeverEscapesAsMixedAttribution() = runBlocking {
        val first = attributionSnapshot(receiptId = "receipt-1", organizationRevision = 1)
        val second = attributionSnapshot(receiptId = "receipt-2", organizationRevision = 2)
        val store =
            FakePersistedAttributionPort(
                persisted = first,
                attributionSequence = listOf(first, second, first, second),
            )
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(
                store,
                FakeCurrentKnowledgeVerifier(),
            )

        assertNull(reader.readCurrent(REVISION))
        assertEquals(4, store.attributionReads)
    }

    @Test
    fun cancellationIsNeverConvertedIntoMissingEvidence() {
        val store =
            FakePersistedAttributionPort(
                persisted = attributionSnapshot(),
                failure = CancellationException("cancelled"),
            )
        val reader =
            createVerifiedStudentProblemKnowledgeAttributionPort(
                store,
                FakeCurrentKnowledgeVerifier(),
            )

        assertThrows(CancellationException::class.java) {
            runBlocking { reader.readCurrent(REVISION) }
        }
    }

    @Test
    fun mismatchedKnowledgeProofCannotConstructVerifiedAttribution() {
        val persisted = attributionSnapshot()
        val wrongProof =
            PROOF_AUTHORITY.issuer.issue(
                KNOWLEDGE_NODE,
                KNOWLEDGE_MANIFEST,
                KNOWLEDGE_GENERATION + 1,
            )

        assertThrows(IllegalArgumentException::class.java) {
            VerifiedStudentProblemKnowledgeAttribution(
                persisted = persisted,
                verifiedKnowledgeProofs = listOf(wrongProof),
            )
        }
    }

    @Test
    fun resolvedErrorCannotPointAtAnotherKnowledgeBinding() {
        val persisted = attributionSnapshot()
        val mismatchedError =
            StudentProblemResolvedErrorKnowledgeAttribution(
                attributionId = "error-attribution-1",
                solutionAnalysisId = "solution-1",
                stepOrdinal = 1,
                knowledgeReferenceId = "reference-1",
                knowledgeBindingId = "binding-other",
                evidenceRefs =
                    listOf(
                        ProblemErrorEvidenceRef(
                            blockId = "student-work-1",
                            sourceAssetId = "source-page-1",
                            evidenceKind = ProblemErrorEvidenceKind.STUDENT_WORK,
                        ),
                    ),
                resultCanonicalFingerprint = fingerprint("error-attribution-1"),
                recordedAtEpochMillis = 95,
            )

        assertThrows(IllegalArgumentException::class.java) {
            StudentProblemKnowledgeAttributionSnapshot(
                receiptId = persisted.receiptId,
                requestId = persisted.requestId,
                requestCanonicalFingerprint = persisted.requestCanonicalFingerprint,
                organizationRevision = persisted.organizationRevision,
                problemRevision = persisted.problemRevision,
                organizationPayloadCanonicalFingerprint =
                    persisted.organizationPayloadCanonicalFingerprint,
                knowledgeSnapshot = persisted.knowledgeSnapshot,
                stepAttributions = persisted.stepAttributions,
                resolvedErrorAttributions = listOf(mismatchedError),
                completedAtEpochMillis = persisted.completedAtEpochMillis,
            )
        }
    }

    @Test
    fun capabilitySurfacesContainNoWriterDatabaseOrMasteryMethod() {
        val rawNames =
            LearnerBoundStudentProblemKnowledgeAttributionPort::class.java.methods
                .mapTo(mutableSetOf()) { method -> method.name.lowercase() }
        val verifiedNames =
            LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort::class.java.methods
                .mapTo(mutableSetOf()) { method -> method.name.lowercase() }
        val forbiddenFragments =
            listOf("organize", "write", "insert", "update", "delete", "sql", "dao", "mastery")

        forbiddenFragments.forEach { fragment ->
            assertFalse(rawNames.any { name -> fragment in name })
            assertFalse(verifiedNames.any { name -> fragment in name })
        }
        val factoryMethods =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.mistake." +
                    "VerifiedStudentProblemKnowledgeAttributionReaderKt",
            ).declaredMethods.filter { method ->
                method.name.startsWith("createVerifiedStudentProblemKnowledgeAttributionPort")
            }
        assertTrue(factoryMethods.isNotEmpty())
        assertTrue(factoryMethods.all { method -> method.isSynthetic })
    }

    private class FakePersistedAttributionPort(
        private val persisted: StudentProblemKnowledgeAttributionSnapshot,
        private val attributionSequence: List<StudentProblemKnowledgeAttributionSnapshot> =
            listOf(persisted),
        private val failure: Exception? = null,
    ) : LearnerBoundStudentProblemKnowledgeAttributionPort {
        override val learnerId: String = LEARNER_ID
        var knowledgeSnapshotReads: Int = 0
        var attributionReads: Int = 0

        override suspend fun readCurrentKnowledgeSnapshot(
            problemRevision: StudentProblemRevisionRef,
        ): StudentProblemOrganizationKnowledgeSnapshot? {
            knowledgeSnapshotReads += 1
            return persisted.knowledgeSnapshot
        }

        override suspend fun readCurrentKnowledgeAttribution(
            problemRevision: StudentProblemRevisionRef,
            requiredKnowledgeSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
        ): StudentProblemKnowledgeAttributionSnapshot? {
            attributionReads += 1
            failure?.let { throw it }
            if (requiredKnowledgeSnapshot != persisted.knowledgeSnapshot) return null
            val index = (attributionReads - 1).coerceAtMost(attributionSequence.lastIndex)
            return attributionSequence[index]
        }
    }

    private class FakeCurrentKnowledgeVerifier(
        private val acceptedGenerations: List<Long> = listOf(KNOWLEDGE_GENERATION),
    ) : CurrentStudentProblemKnowledgeReferenceVerifier {
        var calls: Int = 0

        init {
            require(acceptedGenerations.isNotEmpty())
        }

        override suspend fun verifyCurrent(
            refs: List<KnowledgeNodeRef>,
            requiredSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
        ): List<VerifiedKnowledgeReferenceProof>? {
            calls += 1
            val acceptedGeneration =
                acceptedGenerations[(calls - 1).coerceAtMost(acceptedGenerations.lastIndex)]
            if (
                requiredSnapshot.manifestFingerprint != KNOWLEDGE_MANIFEST ||
                requiredSnapshot.activationGeneration != acceptedGeneration
            ) {
                return null
            }
            return refs.map { ref ->
                PROOF_AUTHORITY.issuer.issue(
                    ref,
                    requiredSnapshot.manifestFingerprint,
                    requiredSnapshot.activationGeneration,
                )
            }
        }
    }

    private companion object {
        const val LEARNER_ID = "learner-1"
        const val KNOWLEDGE_GENERATION = 7L
        val PROOF_AUTHORITY: KnowledgeReferenceProofAuthority =
            KnowledgeReferenceProofAuthority.create()
        val KNOWLEDGE_MANIFEST: String = fingerprint("knowledge-manifest")
        val REVISION: StudentProblemRevisionRef =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        problemId = "problem-1",
                        practiceUnitId = "practice-unit-1",
                    ),
                revisionId = "problem-revision-1",
                revisionNumber = 1,
                documentCanonicalFingerprint = fingerprint("problem-document"),
            )
        val KNOWLEDGE_NODE: KnowledgeNodeRef =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "math-function-monotonicity",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "knowledge-pack-v1",
            )

        fun attributionSnapshot(
            receiptId: String = "receipt-1",
            organizationRevision: Int = 1,
        ): StudentProblemKnowledgeAttributionSnapshot {
            val step =
                StudentProblemStepKnowledgeAttribution(
                    binding =
                        ProblemKnowledgeBindingRef(
                            bindingId = "binding-1",
                            problemRevision = REVISION,
                            knowledgeNode = KNOWLEDGE_NODE,
                            bindingCanonicalFingerprint = fingerprint("binding-1"),
                        ),
                    solutionAnalysisId = "solution-1",
                    stepId = "step-1",
                    stepOrdinal = 1,
                    knowledgeReferenceId = "reference-1",
                    recordedAtEpochMillis = 90,
                )
            return StudentProblemKnowledgeAttributionSnapshot(
                receiptId = receiptId,
                requestId = "request-$organizationRevision",
                requestCanonicalFingerprint = fingerprint("request-$organizationRevision"),
                organizationRevision = organizationRevision,
                problemRevision = REVISION,
                organizationPayloadCanonicalFingerprint =
                    fingerprint("payload-$organizationRevision"),
                knowledgeSnapshot =
                    StudentProblemOrganizationKnowledgeSnapshot(
                        manifestFingerprint = KNOWLEDGE_MANIFEST,
                        activationGeneration = KNOWLEDGE_GENERATION,
                    ),
                stepAttributions = listOf(step),
                resolvedErrorAttributions = emptyList(),
                completedAtEpochMillis = 100,
            )
        }

        fun fingerprint(value: String): String =
            CanonicalSha256("attribution-reader-test-v1")
                .field("value", value)
                .finish()
    }
}
