package com.tingyun.smartmistakebook.feature.tutor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorOpenResponseLearningRouteBoundaryTest {
    @Test
    fun productionRoutesPublishOnlyTheNarrowCurrentSessionHost() {
        val root = projectRoot()
        val routes = listOf(
            File(
                root,
                "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                    "CapturedTutorSessionRoute.kt",
            ),
            File(
                root,
                "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                    "SavedMistakeTutorRoute.kt",
            ),
        ).joinToString("\n", transform = File::readText)
        val appRoot = File(
            root,
            "app/src/main/kotlin/com/tingyun/smartmistakebook/SmartMistakeBookRoot.kt",
        ).readText()
        val source = "$routes\n$appRoot"

        listOf(
            "OpenResponseLearningEvidenceCoordinator",
            "KnowledgeReferenceProof",
            "LearningEvidenceWeight",
            "RoomDatabase",
            "StudyDatabase",
            "LearnerMasteryDao",
            "androidx.room",
            "SELECT ",
            "INSERT ",
            "UPDATE ",
        ).forEach { forbidden ->
            assertFalse(
                "Tutor production route bypassed its Host capability with $forbidden",
                forbidden in source,
            )
        }
        assertTrue("TutorCurrentSessionHostPort" in routes)
        assertFalse("TutorOpenResponseLearningHostPort" in source)
        assertFalse("TutorOpenResponseLearningRouteBridge" in source)
        assertFalse("issueContext(" in source)
        assertFalse("submitAdmitted(" in source)
    }

    @Test
    fun contextRequestExposesNoModelChosenKnowledgeOrMasteryFields() {
        val surface =
            TutorOpenResponseLearningContextRequest::class.java.methods
                .filterNot { it.isSynthetic }
                .joinToString("\n") { method ->
                    "${method.name}:${method.returnType.name}"
                }
                .lowercase()

        listOf(
            "knowledge",
            "mastery",
            "correct",
            "weight",
            "confidence",
            "projection",
            "sql",
            "dao",
            "database",
        ).forEach { forbidden ->
            assertFalse(
                "Route context request unexpectedly exposes $forbidden",
                forbidden in surface,
            )
        }
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")
}
