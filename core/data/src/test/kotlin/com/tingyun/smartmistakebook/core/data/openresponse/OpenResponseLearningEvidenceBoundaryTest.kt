package com.tingyun.smartmistakebook.core.data.openresponse

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenResponseLearningEvidenceBoundaryTest {
    @Test
    fun weakCandidateExposesNoWeightSqlOrProjectionAuthority() {
        val publicSurface =
            OpenResponseWeakCandidateProposal::class.java.methods
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .joinToString("\n") { "${it.name}:${it.returnType.name}" }
                .lowercase()

        listOf(
            "sql",
            "dao",
            "database",
            "weight",
            "confidence",
            "mastery",
            "projection",
            "insert",
            "update",
        ).forEach { forbidden ->
            assertFalse(
                "Weak candidate unexpectedly exposes $forbidden",
                forbidden in publicSurface,
            )
        }
    }

    @Test
    fun productionCoordinatorUsesOnlyNarrowOwnersAndTheDurableModelRepository() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/openresponse/" +
                    "OpenResponseLearningEvidenceCoordinator.kt",
            ).readText()

        listOf(
            "androidx.room",
            "RoomDatabase",
            "StudyDatabase",
            "LearnerMasteryRuntimeCapabilities",
            "LearnerMasteryObservationSink",
            "LearnerMasteryCandidateSink",
            "CoreDataLearnerMasteryOwnerBridge",
            "openLearnerMasteryOwnerCapabilities",
            "ModelGateway",
            "core.model.provider",
        ).forEach { forbidden ->
            assertFalse(
                "Open-response coordinator bypassed its narrow authority with $forbidden",
                forbidden in source,
            )
        }
        assertTrue("LearnerBoundLearningEvidencePort" in source)
        assertTrue("LearnerBoundOpenResponseWeakCandidateOwner" in source)
        assertTrue("ModelTaskRepository" in source)
        assertTrue("ProductionOpenResponseEvaluationTaskProducerFactory" in source)
        assertTrue("OpenResponseWeakCandidateSubmissionAuthorization" in source)
    }

    @Test
    fun modelOutputCannotChooseHostAttemptHintOrAnswerExposureFacts() {
        val source =
            File(
                projectRoot(),
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/openresponse/" +
                    "OpenResponseLearningEvidenceCoordinator.kt",
            ).readText()
        val proposalMapper =
            source.substringAfter(
                "private fun IndependentOpenResponseEvaluationResult.Candidate.toProposal",
            )

        assertTrue("attemptOrdinal = submission.scope.attemptOrdinal" in proposalMapper)
        assertTrue("hintCount = submission.scope.hintCount" in proposalMapper)
        assertTrue("answerWasRevealed = submission.scope.answerWasRevealed" in proposalMapper)
        assertFalse("weight =" in proposalMapper)
        assertFalse("confidence =" in proposalMapper)
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")
}
