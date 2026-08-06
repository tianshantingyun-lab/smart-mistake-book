package com.tingyun.smartmistakebook.feature.tutor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorCurrentSessionHostVisualContractTest {
    @Test
    fun `host visual uses the generic generate review chain and cannot emit learning evidence`() {
        val visual = source("TutorCurrentSessionHostVisual.kt")

        listOf(
            "ModelTaskKind.TUTOR_VISUAL_GENERATE",
            "ModelTaskKind.TUTOR_VISUAL_REVIEW",
            "buildTutorVisualGenerateRequest(",
            "buildTutorVisualReviewRequest(",
            "TutorVisualResolution.Preparing",
            "TutorVisualResolution.Reviewing",
            "TutorVisualResolution.Ready",
            "TutorVisualResolution.Fallback",
            "completedSemanticRequestIds",
            "onTargetHit = onTargetHit",
        ).forEach { required ->
            assertTrue("Host visual is missing $required", required in visual)
        }
        listOf("recordChoice(", "recordVisualTargetEvidence(", "finalizeEvidence(").forEach {
            forbidden -> assertFalse("Host visual must not write $forbidden", forbidden in visual)
        }
    }

    @Test
    fun `host visual is fenced to the activated text owner`() {
        val panel = source("TutorCurrentSessionHostPanel.kt")

        assertTrue("activatedTaskRequestId" in panel)
        assertTrue("presentationOwnerTask" in panel)
        assertTrue("ownerTask = presentationOwnerTask" in panel)
        assertTrue("compositionEgressLease = compositionEgressLease" in panel)
    }

    @Test
    fun `host panel restores durable policy before publishing a replacement`() {
        val panel = source("TutorCurrentSessionHostPanel.kt")

        listOf(
            "sessionHost.resume(question.sessionId)",
            "sessionHost.currentPolicy(question.sessionId)",
            "if (!policyRecoveryComplete) return@LaunchedEffect",
            "visualIntent = requireNotNull(presentation).visualIntent",
            "visible.visualIntentVersion",
        ).forEach { required ->
            assertTrue("Host panel recovery is missing $required", required in panel)
        }
    }

    private fun source(fileName: String): String = Files.readString(
        repositoryRoot().resolve(
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/$fileName",
        ),
    )

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate repository root")
    }
}
