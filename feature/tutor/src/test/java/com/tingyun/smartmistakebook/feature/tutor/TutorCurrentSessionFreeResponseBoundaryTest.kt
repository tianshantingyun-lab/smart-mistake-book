package com.tingyun.smartmistakebook.feature.tutor

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorCurrentSessionFreeResponseBoundaryTest {
    @Test
    fun `trusted Host owns free response capability and composer responds immediately`() {
        val panel = source(
            "feature/tutor/src/main/java/com/tingyun/smartmistakebook/feature/tutor/" +
                "TutorCurrentSessionHostPanel.kt",
        )

        assertFalse("Compose received the broad learning host", "TutorOpenResponseLearningHostPort" in panel)
        assertTrue("Free response did not use the shared composer", "TutorChatComposer(" in panel)
        assertTrue("Opaque action token was not submitted", "actionToken = interaction.actionToken" in panel)
        assertTrue(
            "Student answer is not shown before asynchronous dispatch",
            panel.indexOf("submittedFreeResponse = answer") <
                panel.indexOf("sessionHost.submitFreeResponse("),
        )
        assertTrue(
            "A recovered retry still asks the student to re-enter the answer",
            "TutorCurrentSessionFreeResponseStatus.RETRY_AVAILABLE" in panel &&
                "text = \"重试\"" in panel &&
                "sessionHost.retryFreeResponse(" in panel,
        )
        assertFalse(
            "Raw free-response draft entered SavedState",
            "freeResponseDraft by rememberSaveable" in panel,
        )
        assertFalse(
            "Submitted raw answer entered SavedState",
            "submittedFreeResponse by rememberSaveable" in panel,
        )
        assertFalse("Raw answer gained a Saver", "Saver<" in panel || "Parcelable" in panel)
        assertTrue(
            "Retry capability unexpectedly carries the raw answer",
            "TutorCurrentSessionFreeResponseRetryAction(" in panel &&
                "actionToken = interaction.actionToken" in panel,
        )
        assertFalse("Host panel exposed an evidence id", "evidenceRequestId" in panel)
    }

    private fun source(path: String): String = File(projectRoot(), path).readText()

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")
}
