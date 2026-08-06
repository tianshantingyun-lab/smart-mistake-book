package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceAction
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression boundary for the retired feature-owned choice learning writer. */
class CapturedTutorChoiceLearningMemoryControllerTest {
    @Test
    fun `current session choice action carries only opaque presentation identity and choice`() {
        val fields = TutorCurrentSessionChoiceAction::class.java.declaredFields
            .filterNot { field -> Modifier.isStatic(field.modifiers) }
            .map { field -> field.name }
            .toSet()

        assertEquals(setOf("sessionId", "presentationToken", "choiceId"), fields)
        listOf(
            "learnerScopeId",
            "subject",
            "selectionWasCorrect",
            "feedbackMarkdown",
            "evidenceRequestId",
        ).forEach { forbidden ->
            assertFalse("Choice action exposes $forbidden", forbidden in fields)
        }
    }

    @Test
    fun `production routes receive the current session host instead of raw repositories`() {
        val capturedSignature = publicFunctionSignature(
            source("feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "CapturedTutorSessionRoute.kt"),
            "CapturedTutorSessionRoute",
        )
        val savedSignature = publicFunctionSignature(
            source("feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "SavedMistakeTutorRoute.kt"),
            "SavedMistakeTutorRoute",
        )
        listOf(capturedSignature, savedSignature).forEach { signature ->
            assertTrue("TutorCurrentSessionHostPort" in signature)
            assertFalse("TutorInteractionRepository" in signature)
            assertFalse("TutorLearningMemoryRepository" in signature)
        }
    }

    @Test
    fun `production capability publishes no raw interaction or learning memory repository`() {
        val capability = source(
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "TutorProductionCapability.kt",
        )
        val constructor = capability.substringAfter("class TutorProductionCapability(")
            .substringBefore("\n)")

        assertTrue("TutorCurrentSessionHostPort" in capability)
        assertTrue("currentSessionHost" in constructor)
        assertFalse("TutorInteractionRepository" in capability)
        assertFalse("TutorLearningMemoryRepository" in capability)
        assertFalse("openResponseLearningHost" in constructor)
    }

    @Test
    fun `host panel submits no correctness feedback or evidence identity`() {
        val panel = source(
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "TutorCurrentSessionHostPanel.kt",
        )
        val submission = panel.substringAfter("TutorCurrentSessionChoiceAction(")
            .substringBefore("),")

        assertTrue("presentationToken = visible.presentationToken" in submission)
        assertTrue("choiceId = choiceId" in submission)
        assertFalse("selectionWasCorrect" in submission)
        assertFalse("feedbackMarkdown" in submission)
        assertFalse("evidenceRequestId" in submission)
        assertTrue("if (presentationAllowed)" in panel)
    }

    @Test
    fun `feature owned choice learning controller is retired`() {
        assertFalse(
            Files.exists(
                repositoryRoot().resolve(
                    "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                        "CapturedTutorChoiceLearningMemoryController.kt",
                ),
            ),
        )
    }

    private fun source(relativePath: String): String =
        Files.readString(repositoryRoot().resolve(relativePath))

    private fun publicFunctionSignature(source: String, functionName: String): String =
        source.substringAfter("fun $functionName(").substringBefore("\n) {")

    private fun repositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate repository root")
    }
}
