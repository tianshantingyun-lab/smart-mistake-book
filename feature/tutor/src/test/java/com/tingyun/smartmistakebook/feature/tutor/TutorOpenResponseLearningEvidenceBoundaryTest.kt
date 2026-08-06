package com.tingyun.smartmistakebook.feature.tutor

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorOpenResponseLearningEvidenceBoundaryTest {
    @Test
    fun featureContractCannotChooseCorrectnessKnowledgeOrEvidenceMass() {
        val surfaces =
            listOf(
                TutorOpenResponseLearningMessage::class.java,
                TutorOpenResponseEvidenceAdmission::class.java,
                AdmittedTutorOpenResponseLearningEvidence::class.java,
                OpenResponseLearningEvidenceSubmissionPort::class.java,
                DirectOpenResponseLearningIntent::class.java,
            ).flatMap { type ->
                type.methods
                    .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                    .map { "${type.name}.${it.name}:${it.returnType.name}" }
            }.joinToString("\n").lowercase()

        listOf(
            "correct",
            "knowledgepoint",
            "knowledgeeffect",
            "mastery",
            "weight",
            "confidence",
            "projection",
            "sql",
            "dao",
            "database",
        ).forEach { forbidden ->
            assertFalse(
                "Feature open-response contract unexpectedly exposes $forbidden",
                forbidden in surfaces,
            )
        }
    }

    @Test
    fun controllerHasNoLateEvaluationUiCallbackOrObservableResultChannel() {
        val publicSurface =
            TutorOpenResponseLearningEvidenceController::class.java.methods
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .joinToString("\n") { "${it.name}:${it.returnType.name}" }
                .lowercase()

        listOf(
            "callback",
            "onresult",
            "stateflow",
            "sharedflow",
            "livedata",
            "mutableState",
            "correctness",
        ).forEach { forbidden ->
            assertFalse(
                "Controller leaks asynchronous evaluation into UI through $forbidden",
                forbidden.lowercase() in publicSurface,
            )
        }
        assertTrue("submitvisiblemessage" in publicSurface)
        assertTrue("bindcurrent" in publicSurface)
        assertTrue("clearcurrent" in publicSurface)
    }

    @Test
    fun productionFeatureSourceUsesOnlyHostIssuedPortsAndContainsNoDatabaseOrProviderAccess() {
        val source =
            File(
                projectRoot(),
                "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                    "TutorOpenResponseLearningEvidenceController.kt",
            ).readText()

        listOf(
            "OpenResponseLearningEvidenceCoordinator",
            "ModelTaskRepository",
            "ModelGateway",
            "LearnerMastery",
            "KnowledgeReferenceProof",
            "RoomDatabase",
            "StudyDatabase",
            "core.model.provider",
            "androidx.room",
        ).forEach { forbidden ->
            assertFalse(
                "Feature bypassed the host-issued submission boundary with $forbidden",
                forbidden in source,
            )
        }
        assertTrue("OpenResponseLearningEvidenceSubmissionPort" in source)
        assertTrue("DirectOpenResponseLearningIntentClassifier" in source)
        assertTrue("retainUnresolvedIntent" in source)
        assertTrue("GUIDED_ASK_FREE_RESPONSE" in source)
        assertTrue("DIRECT_CONVERSATION" in source)
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")
}
