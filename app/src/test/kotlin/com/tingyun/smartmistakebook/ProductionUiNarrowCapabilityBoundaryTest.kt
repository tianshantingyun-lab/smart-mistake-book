package com.tingyun.smartmistakebook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionUiNarrowCapabilityBoundaryTest {
    @Test
    fun appAndFeatureMainDoNotDependOnTheBroadStudyExperienceContract() {
        val offending =
            productionUiSources()
                .filter { source ->
                    val text = Files.readString(source)
                    FORBIDDEN_BROAD_TYPES.any(text::contains)
                }

        assertTrue(
            "Production UI still depends on the broad study snapshot: $offending",
            offending.isEmpty(),
        )
    }

    @Test
    fun rootUsesRealNarrowEntriesWithoutTheCuratedTutorPath() {
        val root = Files.readString(appMainSource("SmartMistakeBookRoot.kt"))
        val application = Files.readString(appMainSource("SmartMistakeBookApplication.kt"))

        listOf(
            "TutorLobbyRoute(",
            "conversationLobby = productionCapabilities.tutorConversationLobby",
            "productionCapabilities.studentMistakeCatalog",
            "productionCapabilities.captureWorkflow.observePendingCaptures()",
            ".learningMasteryDisplay",
            "CapturedTutorSessionRoute(",
            "SavedMistakeTutorRoute(",
            "sessionHost = productionCapabilities.tutorSession.currentSessionHost",
        ).forEach { required ->
            assertTrue("Root is missing the narrow production path: $required", root.contains(required))
        }
        listOf(
            "productionCapabilities.studyExperience",
            "tutorExample",
            "submitChoice",
            "revealAnswer",
            "StudyDataStatusLine",
            "productionCapabilities.tutorLearningMemory",
            "cleanupScope = applicationUiScope",
            "productionCapabilities.tutorSession.openResponseLearningHost",
            "TutorOpenResponseLearningHostPort",
        ).forEach { forbidden ->
            assertFalse("Root still contains the retired path: $forbidden", root.contains(forbidden))
        }
        assertFalse(application.contains("StudyExperienceRepository"))
        assertFalse(application.contains("studyRepository"))
        assertFalse(application.contains("refreshStudyExperience"))
        assertFalse(application.contains("TutorInteractionRepository"))
        assertFalse(application.contains("TutorLearningMemoryRepository"))
        assertFalse(application.contains("tutorInteractionRepository"))
        assertFalse(application.contains("tutorLearningMemoryRepository"))
    }

    private fun productionUiSources(): List<Path> {
        val root = projectRoot()
        return listOf(root.resolve("app/src/main"), root.resolve("feature"))
            .flatMap { directory ->
                Files.walk(directory).use { paths ->
                    paths
                        .filter { path -> Files.isRegularFile(path) }
                        .filter { path ->
                            path.toString().endsWith(".kt") || path.toString().endsWith(".java")
                        }
                        .filter { path ->
                            directory.fileName.toString() != "feature" ||
                                path.toString().replace('\\', '/').contains("/src/main/")
                        }
                        .toList()
                }
            }
    }

    private fun appMainSource(fileName: String): Path =
        projectRoot().resolve(
            Paths.get(
                "app",
                "src",
                "main",
                "kotlin",
                "com",
                "tingyun",
                "smartmistakebook",
                fileName,
            ),
        )

    private fun projectRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate project root")
    }

    private companion object {
        val FORBIDDEN_BROAD_TYPES =
            listOf(
                "StudyExperienceRepository",
                "StudyExperienceSnapshot",
            )
    }
}
