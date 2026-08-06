package com.tingyun.smartmistakebook.feature.review

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewComposeContractTest {
    @Test
    fun productionReviewRouteHasOnlyTheFourLandingControls() {
        val source = source("ReviewRoute.kt")

        listOf(
            "review_scheduled_count",
            "review_estimated_minutes",
            "review_progress",
            "review_start_button",
        ).forEach { tag ->
            assertTrue("Missing review control: $tag", tag in source)
        }
        listOf(
            "StudyExperienceRepository",
            "StudyReviewOverview",
            "拍照",
            "录入",
            "积压",
            "错因",
        ).forEach { forbidden ->
            assertFalse("Review landing exposes forbidden content: $forbidden", forbidden in source)
        }
    }

    @Test
    fun savedQuestionFlowOffersExplanationAndPacingWithoutForcedInputCopy() {
        val source = source("DailyReviewSessionRoute.kt")

        listOf(
            "\"查看讲解\"",
            "\"做完了\"",
            "\"卡住了\"",
        ).forEach { required ->
            assertTrue("Missing review action: $required", required in source)
        }
        listOf(
            "请输入完整",
            "必须作答",
            "完成全部步骤",
            "记录掌握",
        ).forEach { forbidden ->
            assertFalse("Review flow contains coercive or misleading copy: $forbidden", forbidden in source)
        }
    }

    @Test
    fun productionAnswerEntryExposesOnlyRawLearnerInputToTheFeature() {
        val session = source("DailyReviewSessionRoute.kt")
        val production = source("ReviewProductionCapability.kt")
        val boundary = session + production

        listOf(
            "DailyReviewRawAnswerSubmission",
            "DailyReviewAnswerSubmissionPortFactory",
        ).forEach { required ->
            assertTrue("Missing raw answer boundary: $required", required in boundary)
        }
        listOf(
            "VerifiedDailyReviewAnswer",
            "SavedReviewEvidenceCommand",
            "DailyReviewVerifiedAnswerActionPort",
            "LearnerMastery",
            "correctChoiceId",
            "verificationOutcome =",
            "hintCount =",
            "answerWasRevealed =",
        ).forEach { forbidden ->
            assertFalse("Feature can reach trusted answer internals: $forbidden", forbidden in boundary)
        }
    }

    @Test
    fun explanationPublicationIsGatedByThePresentationBoundAssistancePort() {
        val source = source("DailyReviewSessionRoute.kt")

        val persistence = source.indexOf("assistanceActions.record(")
        val publication = source.indexOf("DailyReviewSessionCompletion.ExplanationReady(problem)")
        assertTrue(persistence >= 0)
        assertTrue(publication > persistence)
        assertTrue("DailyReviewAssistanceKind.ANSWER_REVEAL" in source)
        assertTrue("answerEvidenceAvailable" in source)
    }

    private fun source(
        name: String,
    ): String {
        val root =
            generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .first { File(it, "settings.gradle.kts").isFile }
        return File(
            root,
            "feature/review/src/main/kotlin/" +
                "com/tingyun/smartmistakebook/feature/review/$name",
        ).readText()
    }
}
