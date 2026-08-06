package com.tingyun.smartmistakebook.core.data.study

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStudyProductionClosureTest {
    @Test
    fun retiredLegacyStudyImplementationCannotReenterProductionSources() {
        val root = projectRoot()
        val productionSources = productionKotlinSources(root)
        val symbolViolations = productionSources
            .filter { source ->
                FORBIDDEN_PRODUCTION_SYMBOLS.any(source.readText()::contains)
            }
            .map { source -> source.relativePath(root) }
            .sorted()
        val sourceFileViolations = productionSources
            .filter { source -> source.name in FORBIDDEN_PRODUCTION_SOURCE_FILES }
            .map { source -> source.relativePath(root) }
            .sorted()

        assertTrue(
            "Retired legacy study symbols remain in production sources: $symbolViolations",
            symbolViolations.isEmpty(),
        )
        assertTrue(
            "Retired legacy study files remain in production sources: $sourceFileViolations",
            sourceFileViolations.isEmpty(),
        )
    }

    @Test
    fun curatedM1FixtureExistsOnlyInTheDebugSourceSet() {
        val root = projectRoot()
        val fixturePaths = root.walkTopDown()
            .onEnter(::enterSourceDirectory)
            .filter(File::isFile)
            .filter { file -> file.name == M1_FIXTURE_SOURCE_FILE }
            .map { file -> file.relativePath(root) }
            .sorted()
            .toList()

        assertEquals(listOf(M1_DEBUG_FIXTURE_PATH), fixturePaths)
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun productionKotlinSources(root: File): List<File> =
        root.walkTopDown()
            .onEnter(::enterSourceDirectory)
            .filter(File::isFile)
            .filter { file ->
                "/src/main/" in file.invariantSeparatorsPath &&
                    file.extension in PRODUCTION_SOURCE_EXTENSIONS
            }
            .toList()

    private fun enterSourceDirectory(directory: File): Boolean =
        directory.name !in IGNORED_DIRECTORY_NAMES

    private fun File.relativePath(root: File): String =
        relativeTo(root).invariantSeparatorsPath

    private companion object {
        const val M1_FIXTURE_SOURCE_FILE = "M1CuratedStudySeed.kt"
        const val M1_DEBUG_FIXTURE_PATH =
            "core/data/src/debug/kotlin/com/tingyun/smartmistakebook/core/data/" +
                M1_FIXTURE_SOURCE_FILE

        val FORBIDDEN_PRODUCTION_SYMBOLS =
            setOf(
                "M1CuratedStudySeed",
                "RoomBackedStudyExperienceRepository",
                "StudyExperienceRepositoryFactory",
            )

        val FORBIDDEN_PRODUCTION_SOURCE_FILES =
            setOf(
                M1_FIXTURE_SOURCE_FILE,
                "RoomBackedStudyExperienceRepository.kt",
                "StudyExperienceRepositoryFactory.kt",
            )

        val IGNORED_DIRECTORY_NAMES =
            setOf("build", ".gradle", ".git", ".idea", ".kotlin")

        val PRODUCTION_SOURCE_EXTENSIONS =
            setOf("kt", "java")
    }
}
