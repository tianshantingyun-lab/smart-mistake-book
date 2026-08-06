package com.tingyun.smartmistakebook.core.data.openresponse

import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOpenResponseAdmissionMode
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeEffect
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeRole
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationCandidateOutput
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryOpenResponseWeakCandidateOwnerAdapterTest {
    @Test
    fun mapsExactHostIdentityAndVerifiedKnowledgeScopeWithoutNumericInfluence() {
        val proposal = proposal()

        val command = proposal.toLearnerMasteryCommand()

        assertEquals(proposal.learnerId, command.learnerId)
        assertEquals(proposal.sourceFactId, command.sourceFactId)
        assertEquals(proposal.reviewCaseId, command.reviewCaseId)
        assertEquals(proposal.scope.canonicalFingerprint, command.scopeFingerprint)
        assertEquals(proposal.scope.conversationGeneration, command.conversationGeneration)
        assertEquals(proposal.scope.conversationStateVersion, command.conversationStateVersion)
        assertEquals(proposal.scope.questionRevisionNumber, command.questionRevisionNumber)
        assertEquals(proposal.scope.turnGeneration, command.turnGeneration)
        assertEquals(proposal.scope.modeVersion, command.modeVersion)
        assertEquals(proposal.scope.requestVersion, command.requestVersion)
        assertEquals(proposal.modelTaskRequestId, command.modelTaskRequestId)
        assertEquals(
            TutorOpenResponseEvaluationCandidateOutput.CURRENT_EVALUATION_SCHEMA_VERSION,
            command.modelResponseSchemaVersion,
        )
        assertEquals(proposal.evaluatorRequestVersion, command.evaluatorRequestVersion)
        assertEquals(proposal.candidateIdempotencyKey, command.candidateIdempotencyKey)
        assertNull(command.revisionOfCandidateIdempotencyKey)
        assertEquals(proposal.evidenceFingerprint, command.evidenceFingerprint)
        assertEquals(proposal.modelOutputFingerprint, command.modelOutputFingerprint)
        assertEquals(proposal.modelVersion, command.modelVersion)
        assertEquals(proposal.outcome, command.outcome)
        assertEquals(
            LearnerMasteryOpenResponseAdmissionMode.REVIEW_ONLY,
            command.admissionMode,
        )
        assertEquals(1, command.authorizedKnowledgeScope.size)
        assertEquals(
            OpenResponseKnowledgeRole.LOCATED_GAP,
            command.authorizedKnowledgeScope.single().evaluationRole,
        )

        val publicSurface =
            command.javaClass.methods
                .filterNot { it.isSynthetic }
                .joinToString("\n") { "${it.name}:${it.returnType.name}" }
                .lowercase()
        listOf("knowledgeeffect", "knowledgereference", "weight", "confidence", "projection")
            .forEach { forbidden ->
                assertFalse("Learner admission command exposes $forbidden", forbidden in publicSurface)
            }
    }

    @Test
    fun adapterSourceMapsOnlyQualitativeRolesIntoTheVerifiedHostScope() {
        val source = sourceFile().readText()
        val normalizedSource = source.replace(Regex("\\s+"), " ")

        assertTrue("knowledgeEffects" in source)
        assertFalse("evidenceMass" in source)
        assertFalse("confidence =" in source)
        assertFalse("direction =" in source)
        assertTrue(
            "bindLearnerMasteryOpenResponseWeakCandidateAuthorization( owner," in
                normalizedSource,
        )
        assertTrue("authorization.requireCurrentEpoch()" in source)
        assertTrue("LearnerMasteryOpenResponseScopeLease" in source)
        assertFalse("Runnable" in source)
        assertTrue("STORAGE_UNAVAILABLE" in source)
    }

    private fun proposal(): OpenResponseWeakCandidateProposal {
        val scope =
            CurrentOpenResponseLearningScope(
                learnerId = "learner-local",
                conversationId = "conversation-current",
                conversationGeneration = 3,
                conversationStateVersion = 7,
                questionDocumentId = "question-document",
                questionRevisionNumber = 2,
                subject = SubjectKind.MATH,
                questionFingerprint = fingerprint("question"),
                responseBinding = fingerprint("answer-binding"),
                evidenceRequestId = "guided-evidence-request",
                modeVersion = 5,
                turnReferenceId = "turn-current",
                turnOrdinal = 4,
                turnGeneration = 6,
                attemptOrdinal = 2,
                hintCount = 1,
                answerWasRevealed = true,
                requestVersion = 9,
            )
        val knowledgeNode =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "math.function.monotonicity",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "knowledge-pack-v1",
            )
        val manifestFingerprint = fingerprint("manifest")
        val knowledgeRef =
            OpenResponseEvaluationTaskFingerprints.knowledgeScopeReference(
                questionFingerprint = scope.questionFingerprint,
                knowledgeNodeReferenceFingerprint = knowledgeNode.canonicalFingerprint,
                knowledgeManifestFingerprint = manifestFingerprint,
                knowledgeActivationGeneration = 3L,
            )
        return OpenResponseWeakCandidateProposal(
            learnerId = scope.learnerId,
            sourceFactId = "ordinary-study:source-fact",
            reviewCaseId = "open-response-review-case",
            scope = scope,
            modelTaskRequestId = "open-response-model-task",
            evaluatorRequestVersion = scope.requestVersion,
            candidateIdempotencyKey = fingerprint("candidate"),
            revisionOfCandidateIdempotencyKey = null,
            evidenceFingerprint = fingerprint("evidence"),
            modelOutputFingerprint = fingerprint("model-output"),
            modelVersion = "gpt-evaluator-v1",
            outcome = OpenResponseEvaluationOutcome.INCORRECT,
            knowledgeEffects =
                listOf(
                    OpenResponseKnowledgeEffect(
                        refFingerprint = knowledgeRef,
                        role = OpenResponseKnowledgeRole.LOCATED_GAP,
                    ),
                ),
            authorizedKnowledgeScope =
                listOf(
                    OpenResponseAuthorizedKnowledgeScopeEntry(
                        refFingerprint = knowledgeRef,
                        knowledgeNode = knowledgeNode,
                        manifestFingerprint = manifestFingerprint,
                        activationGeneration = 3L,
                    ),
                ),
            attemptOrdinal = scope.attemptOrdinal,
            hintCount = scope.hintCount,
            answerWasRevealed = scope.answerWasRevealed,
            occurredAtEpochMillis = 10_000,
        )
    }

    private fun sourceFile(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }
            .map { root ->
                File(
                    root,
                    "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/" +
                        "openresponse/LearnerMasteryOpenResponseWeakCandidateOwnerAdapter.kt",
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate learner-mastery open-response adapter")

    private fun fingerprint(seed: String): String =
        CanonicalSha256("learner-mastery-open-response-owner-adapter-test")
            .field("seed", seed)
            .finish()
}
