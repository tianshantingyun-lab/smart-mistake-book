package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentProblemKnowledgeAttributionPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemKnowledgeAttributionSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationKnowledgeSnapshot
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlin.jvm.JvmSynthetic

/**
 * Student-owned organization attribution, freshly reverified by the independent knowledge
 * authority. It provides evidence provenance only; there is no model, SQL, or mastery-write API.
 */
class VerifiedStudentProblemKnowledgeAttribution internal constructor(
    val persisted: StudentProblemKnowledgeAttributionSnapshot,
    verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
) {
    val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof> =
        Collections.unmodifiableList(verifiedKnowledgeProofs.toList())

    init {
        val exactRefs =
            persisted.stepAttributions
                .map { attribution -> attribution.binding.knowledgeNode }
                .distinct()
        require(
            this.verifiedKnowledgeProofs.size == exactRefs.size &&
                this.verifiedKnowledgeProofs.zip(exactRefs).all { (proof, ref) ->
                    proof.ref == ref &&
                        proof.manifestFingerprint ==
                        persisted.knowledgeSnapshot.manifestFingerprint &&
                        proof.activationGeneration ==
                        persisted.knowledgeSnapshot.activationGeneration
                },
        ) {
            "Verified knowledge proofs do not match the exact persisted attribution"
        }
    }

    fun proofFor(node: KnowledgeNodeRef): VerifiedKnowledgeReferenceProof? =
        verifiedKnowledgeProofs.singleOrNull { proof -> proof.ref == node }
}

/** Read-only learner-bound capability intended for trusted tutor/mastery coordinators. */
interface LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort {
    val learnerId: String

    suspend fun readCurrent(
        problemRevision: StudentProblemRevisionRef,
    ): VerifiedStudentProblemKnowledgeAttribution?
}

/** Trusted core:data assembly only; feature and model code cannot pair forged stores with catalog. */
@JvmSynthetic
internal fun createVerifiedStudentProblemKnowledgeAttributionPort(
    persistedAttributions: LearnerBoundStudentProblemKnowledgeAttributionPort,
    knowledgeCatalog: HighSchoolKnowledgeCatalog,
): LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort =
    createVerifiedStudentProblemKnowledgeAttributionPort(
        persistedAttributions = persistedAttributions,
        knowledgeVerifier = CatalogCurrentKnowledgeReferenceVerifier(knowledgeCatalog),
    )

@JvmSynthetic
internal fun createVerifiedStudentProblemKnowledgeAttributionPort(
    persistedAttributions: LearnerBoundStudentProblemKnowledgeAttributionPort,
    knowledgeVerifier: CurrentStudentProblemKnowledgeReferenceVerifier,
): LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort =
    VerifiedStudentProblemKnowledgeAttributionReader(
        persistedAttributions = persistedAttributions,
        knowledgeVerifier = knowledgeVerifier,
    )

internal fun interface CurrentStudentProblemKnowledgeReferenceVerifier {
    suspend fun verifyCurrent(
        refs: List<KnowledgeNodeRef>,
        requiredSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
    ): List<VerifiedKnowledgeReferenceProof>?
}

private class CatalogCurrentKnowledgeReferenceVerifier(
    private val catalog: HighSchoolKnowledgeCatalog,
) : CurrentStudentProblemKnowledgeReferenceVerifier {
    override suspend fun verifyCurrent(
        refs: List<KnowledgeNodeRef>,
        requiredSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
    ): List<VerifiedKnowledgeReferenceProof>? {
        if (refs.isEmpty() || refs.size > MAX_CURRENT_KNOWLEDGE_REFERENCES) return null
        val manifestBefore = catalog.readManifest()
        if (
            manifestBefore.contentFingerprint != requiredSnapshot.manifestFingerprint ||
            refs.any { ref ->
                ref.taxonomyVersion != manifestBefore.taxonomyVersion ||
                    ref.knowledgePackVersion != manifestBefore.knowledgePackVersion
            }
        ) {
            return null
        }
        val proofs = catalog.verifyReferences(refs)
        if (
            proofs.size != refs.size ||
            proofs.zip(refs).any { (proof, ref) ->
                proof.ref != ref ||
                    proof.manifestFingerprint != requiredSnapshot.manifestFingerprint ||
                    proof.activationGeneration != requiredSnapshot.activationGeneration
            }
        ) {
            return null
        }
        return proofs.takeIf { catalog.readManifest() == manifestBefore }
    }
}

private class VerifiedStudentProblemKnowledgeAttributionReader(
    private val persistedAttributions: LearnerBoundStudentProblemKnowledgeAttributionPort,
    private val knowledgeVerifier: CurrentStudentProblemKnowledgeReferenceVerifier,
) : LearnerBoundVerifiedStudentProblemKnowledgeAttributionPort {
    override val learnerId: String = persistedAttributions.learnerId

    override suspend fun readCurrent(
        problemRevision: StudentProblemRevisionRef,
    ): VerifiedStudentProblemKnowledgeAttribution? {
        require(problemRevision.problem.learnerId == learnerId) {
            "Verified knowledge attribution read crosses learner scope"
        }
        repeat(MAX_STABLE_READ_ATTEMPTS) {
            val expectedSnapshot =
                persistedAttributions.readCurrentKnowledgeSnapshot(problemRevision)
                    ?: return null
            val candidate =
                readFailClosed {
                    persistedAttributions.readCurrentKnowledgeAttribution(
                        problemRevision = problemRevision,
                        requiredKnowledgeSnapshot = expectedSnapshot,
                    )
                } ?: return@repeat
            val refs =
                candidate.stepAttributions
                    .map { attribution -> attribution.binding.knowledgeNode }
                    .distinct()
            knowledgeVerifier.verifyCurrent(refs, expectedSnapshot)
                ?: return null
            val stable =
                readFailClosed {
                    persistedAttributions.readCurrentKnowledgeAttribution(
                        problemRevision = problemRevision,
                        requiredKnowledgeSnapshot = expectedSnapshot,
                    )
                } ?: return@repeat
            if (stable.canonicalFingerprint != candidate.canonicalFingerprint) {
                return@repeat
            }
            val currentProofs =
                knowledgeVerifier.verifyCurrent(refs, expectedSnapshot)
                    ?: return null
            return VerifiedStudentProblemKnowledgeAttribution(
                persisted = stable,
                verifiedKnowledgeProofs = currentProofs,
            )
        }
        return null
    }
}

private suspend fun <T> readFailClosed(
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

private const val MAX_STABLE_READ_ATTEMPTS = 2
private const val MAX_CURRENT_KNOWLEDGE_REFERENCES = 64
