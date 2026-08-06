package com.tingyun.smartmistakebook.core.data.openresponse

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDataTutorOpenResponseLearningHostBoundaryTest {
    @Test
    fun coreDataHostDoesNotCreateAFeatureDependencyCycleOrDatabaseBypass() {
        val source = sourceFile().readText()
        val build = moduleBuildFile().readText()

        assertFalse(
            "core:data must not import feature:tutor",
            Regex("""^\s*import\s+com\.tingyun\.smartmistakebook\.feature\.""", RegexOption.MULTILINE)
                .containsMatchIn(source),
        )
        assertFalse(
            "core:data must not depend on feature:tutor",
            """project(":feature:tutor")""" in build,
        )
        listOf(
            "androidx.room",
            "SupportSQLite",
            "RoomDatabase",
            "@Dao",
            "recordObservation(",
            "applyProjection",
        ).forEach { forbidden ->
            assertFalse("Host adapter contains forbidden storage bypass: $forbidden", forbidden in source)
        }
        assertTrue("Host must use the controlled coordinator", "OpenResponseLearningEvidenceCoordinator(" in source)
        assertTrue(
            "Unavailable learner admission owner must stay explicit",
            "LEARNER_WEAK_CANDIDATE_OWNER_UNAVAILABLE" in source,
        )
        assertTrue(
            "Every candidate handoff must re-enter current scope",
            "CurrentOpenResponseLearningScopeAuthorization" in source,
        )
    }

    @Test
    fun productionAssemblyRequiresTheRuntimeOneShotLearnerOwner() {
        val source =
            locate(
                "core",
                "data",
                "src",
                "main",
                "kotlin",
                "com",
                "tingyun",
                "smartmistakebook",
                "core",
                "data",
                "openresponse",
                "CoreDataTutorOpenResponseLearningHostAssembly.kt",
            ).readText()

        assertFalse("Production assembly must not open the unsealed owner", "openLearnerMasteryOpenResponseWeakCandidateOwner" in source)
        assertFalse("Production assembly must not instantiate the unsealed adapter", "LearnerMasteryOpenResponseWeakCandidateOwnerAdapter(" in source)
        assertTrue(
            "Production assembly must require the runtime-owned candidate capability",
            "candidateOwner: LearnerBoundOpenResponseWeakCandidateOwner" in source,
        )
        assertTrue(
            "Production model tasks must pass through the independent evaluator adapter",
            "ModelTaskRepositoryOpenResponseEvaluator(" in source,
        )
        listOf(
            "CoreDataLearnerMasteryOwnerBridge",
            "LearnerMasteryRuntimeCapabilities",
            "LearnerMasteryObservationSink",
            "LearnerMasteryRoomDatabase",
            "androidx.room",
            "Dao",
            "Sql",
        ).forEach { forbidden ->
            assertFalse("Host assembly bypasses its narrow owner with $forbidden", forbidden in source)
        }
    }

    private fun sourceFile(): File =
        locate(
            "core",
            "data",
            "src",
            "main",
            "kotlin",
            "com",
            "tingyun",
            "smartmistakebook",
            "core",
            "data",
            "openresponse",
            "CoreDataTutorOpenResponseLearningHostAdapter.kt",
        )

    private fun moduleBuildFile(): File =
        locate("core", "data", "build.gradle.kts")

    private fun locate(vararg segments: String): File {
        val relative = segments.joinToString(File.separator)
        var cursor = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(10) {
            val candidate = File(cursor, relative)
            if (candidate.isFile) return candidate
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate $relative from ${System.getProperty("user.dir")}")
    }
}
