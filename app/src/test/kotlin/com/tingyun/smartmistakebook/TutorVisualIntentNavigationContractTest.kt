package com.tingyun.smartmistakebook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualIntentNavigationContractTest {
    @Test
    fun explicitVisualIntentIsBoundToCaptureAndCapturedSessionRoutes() {
        val root = Files.readString(appMainSource("SmartMistakeBookRoot.kt"))

        listOf(
            "onCaptureWithVisualIntent = { visualIntent ->",
            "onGalleryWithVisualIntent = { visualIntent ->",
            "Routes.captureTutor(visualIntent)",
            "Routes.capturedTutorSession(sessionId, captureVisualIntent)",
            "visualIntent = capturedVisualIntent",
            "defaultValue = TutorCurrentSessionVisualIntent.NONE.name",
        ).forEach { required ->
            assertTrue("Missing visual-intent handoff: $required", required in root)
        }

        assertFalse(
            "Visual handoff must not use a process-global mutable holder",
            "object PendingTutorVisual" in root,
        )
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
}
