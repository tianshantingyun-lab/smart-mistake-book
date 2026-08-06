package com.tingyun.smartmistakebook

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanInvariantCoverageTest {
    @Test
    fun everyPlanInvariantCategoryHasAMachineCheckableRegression() {
        coverage.forEach { (category, path, fragment) ->
            assertCoverage(category, path, fragment)
        }
    }

    private fun assertCoverage(
        category: String,
        relativePath: String,
        fragment: String,
    ) {
        val file = File(projectRoot(), relativePath)
        assertTrue("Missing $category regression file: $relativePath", file.isFile)
        val text = file.readText()
        assertTrue(
            "$category regression no longer contains $fragment in $relativePath",
            fragment in text,
        )
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate the project root")

    private val coverage =
        listOf(
            Triple(
                "导航职责",
                "app/src/test/kotlin/com/tingyun/smartmistakebook/RootNavigationPolicyTest.kt",
                "secondary workflows do not acquire the bottom bar",
            ),
            Triple(
                "学生语言",
                "core/model/src/test/kotlin/com/tingyun/smartmistakebook/core/model/" +
                    "StudentFacingLanguagePolicyTest.kt",
                "student-facing-language-v8",
            ),
            Triple(
                "学生语言源审计",
                "tools/tests/test_production_ui_honesty.py",
                "FORBIDDEN_STUDENT_LANGUAGE_PHRASES",
            ),
            Triple(
                "模型权限",
                "core/model/src/test/kotlin/com/tingyun/smartmistakebook/core/model/ModelEgressTest.kt",
                "externalProviderCannotRunWithoutStudentApproval",
            ),
            Triple(
                "学习证据",
                "core/model/src/test/kotlin/com/tingyun/smartmistakebook/core/model/" +
                    "LearningLedgerFingerprintTest.kt",
                "learning-ledger-attempt-canonical-v3",
            ),
            Triple(
                "时间语义",
                "app/src/test/kotlin/com/tingyun/smartmistakebook/" +
                    "ReviewReminderCoordinatorTest.kt",
                "aDifferentLocalDayInAnotherTimeZoneCanNotifyOnce",
            ),
            Triple(
                "预算",
                "core/domain/src/test/kotlin/com/tingyun/smartmistakebook/core/domain/" +
                    "ThreeAuthorityReviewBulkSimulationTest.kt",
                "bulkSimulationAcrossScalesServesEveryCandidateWithinFairHorizon",
            ),
            Triple(
                "数据隔离",
                "core/data/src/test/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ThreeDatabaseStaticArchitectureGuardTest.kt",
                "legacyDatabaseReferencesStayInsideMigrationAndSessionOwnership",
            ),
            Triple(
                "生命周期",
                "app/src/test/kotlin/com/tingyun/smartmistakebook/" +
                    "ProblemOrganizationWorkSchedulingCoordinatorTest.kt",
                "schedulableFeedStartsOnlyAfterRunningRecoveryCompletes",
            ),
            Triple(
                "发布资产",
                "tools/tests/test_source_governance.py",
                "SourceArtifactGateTest",
            ),
        )
}
