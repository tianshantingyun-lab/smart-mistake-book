package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.TutorConversationSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionDatabasePort
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceSubmission
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLearningEvidenceArchitectureContractTest {
    @Test
    fun narrowSessionAbiHasExactlyBeginAndAcknowledge() {
        assertEquals(
            setOf("begin", "acknowledge"),
            TutorLearningEvidenceSessionPort::class.java.declaredMethods
                .mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun databaseSessionCapabilitiesExposeNoSubmittedFactWriterOrRawPayload() {
        val signatures =
            listOf(
                TutorConversationSessionDatabasePort::class.java,
                TutorLearningEvidenceSessionDatabasePort::class.java,
            ).flatMap { type ->
                type.methods.map { it.toGenericString() }
            }.joinToString("\n")

        listOf(
            "FinalizeTutorEvidenceRequestCommand",
            "LearningObservationSourceFact",
            "responseSummary",
            "answerMarkdown",
            "weight",
            "projection",
        ).forEach { forbidden ->
            assertFalse("Unexpected tutor session capability token: $forbidden", signatures.contains(forbidden))
        }
    }

    @Test
    fun domainSubmissionAndResultExposeNoLegacyLearningFact() {
        val signatures =
            listOf(
                TutorLearningEvidenceSubmission::class.java,
                FinalizeTutorEvidenceResult::class.java,
            ).flatMap { type ->
                type.declaredFields.map { it.toGenericString() } +
                    type.declaredMethods.map { it.toGenericString() } +
                    type.declaredClasses.flatMap { nested ->
                        nested.declaredFields.map { it.toGenericString() } +
                            nested.declaredMethods.map { it.toGenericString() }
                    }
            }.joinToString("\n")

        assertFalse(signatures.contains("LearningObservationSourceFact"))
        assertFalse(signatures.contains("LearningProblemAnchor"))
        assertFalse(signatures.contains("VerifiedKnowledgeReferenceProof"))
        assertFalse(signatures.contains("TrustedLearningObservationSource"))
        assertFalse(signatures.contains("core.data"))
        assertFalse(signatures.contains("responseSummary"))
        assertFalse(signatures.contains("answerMarkdown"))
    }

    @Test
    fun productionLegacyAdaptersCannotSubmitOrHandoffLearningFacts() {
        val repository = source("core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/RoomTutorLearningMemoryRepository.kt")
        val session = source("core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/LegacyTutorConversationSessionAdapter.kt")
        val route = source(
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "CapturedTutorSessionRoute.kt",
        )
        val trustedChoice = source(
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                "TrustedTutorChoiceInteractionRepository.kt",
        )
        val runtime = source(
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                "LocalLearningAuthorityRuntime.kt",
        )

        assertTrue(repository.contains("Legacy tutor learning-fact submission is disabled"))
        assertTrue(session.contains("Legacy tutor learning-fact submission is disabled"))
        assertFalse(session.contains("TutorEvidenceSubmission"))
        assertFalse(session.contains("LearningObservationSourceFact"))
        assertFalse(session.contains("toOpaqueHandoff"))
        assertFalse(repository.contains("TutorLearningMemoryDatabasePort"))
        assertFalse(session.contains("TutorLearningMemoryDatabasePort"))
        assertFalse(route.contains("TutorLearningEvidenceSubmission"))
        assertFalse(route.contains("currentSessionReference"))
        assertFalse(route.contains("LearningObservationSourceFact"))
        assertTrue(trustedChoice.contains("learningFinalizer.finalizeRecordedChoice(persisted)"))
        assertTrue(runtime.contains("TrustedTutorChoiceInteractionRepositoryFactory.create("))
        assertTrue(runtime.contains("currentTutorSessionOwner.trustedChoiceLearningFinalizer"))
    }

    @Test
    fun productionTutorAssemblyRequiresCurrentSessionProofAndMasteryAuthorizer() {
        val assembly =
            source(
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "TutorLearningMemoryProductionAssembly.kt",
            )
        val repository =
            source(
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "LearnerMasteryTutorLearningMemoryRepository.kt",
            )

        assertTrue("currentSessionProofSource: TutorLearningEvidenceCurrentSessionProofSource" in assembly)
        assertTrue("knowledgeEvidenceAuthorizer: TutorKnowledgeEvidenceAuthorizer" in assembly)
        assertTrue("productionOwnerIsCurrent: () -> Boolean" in assembly)
        assertFalse("currentSessionProofSource: TutorLearningEvidenceCurrentSessionProofSource?" in repository)
        assertFalse("knowledgeEvidenceAuthorizer: TutorKnowledgeEvidenceAuthorizer?" in repository)
        assertFalse("productionOwnerIsCurrent: () -> Boolean =" in repository)
    }

    @Test
    fun guidedChoiceBehaviorAndAuthorityAreDerivedOnlyInsideCurrentSessionOwner() {
        val owner = source(
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                "CurrentTutorSessionProductionOwner.kt",
        )
        val projection = source(
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                "CurrentTutorSessionProjection.kt",
        )
        val route = source(
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "CapturedTutorSessionRoute.kt",
        )
        val sessionOwnerSources = listOf(owner, projection)

        assertTrue(
            sessionOwnerSources.any { "attemptOrdinal = choice.attemptOrdinal" in it },
        )
        assertTrue(
            sessionOwnerSources.any { "retryCount = choice.attemptOrdinal - 1" in it },
        )
        assertTrue(sessionOwnerSources.any { "hintCount = choice.hintCount" in it })
        assertTrue(sessionOwnerSources.any { "answerWasRevealed = choice.answerWasRevealed" in it })
        assertTrue(sessionOwnerSources.any { "independentlyAnswered = false" in it })
        assertTrue(
            sessionOwnerSources.any {
                "authorizationFingerprint = learningEvidenceAuthorizationFingerprint()" in it
            },
        )
        assertTrue(
            sessionOwnerSources.any {
                "request.status != TutorEvidenceRequestStatus.CANCELLED" in it
            },
        )
        assertFalse("TutorLearningEvidenceSubmission" in route)
        assertFalse("FinalizeTutorEvidenceCommand" in route)
        assertFalse("TutorLearningEvidenceCurrentSessionReference" in route)
    }

    private fun source(relativePath: String): String {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve(relativePath)
            if (Files.isRegularFile(candidate)) return Files.readString(candidate)
            current = current.parent ?: return@repeat
        }
        error("Cannot locate repository source: $relativePath")
    }
}
