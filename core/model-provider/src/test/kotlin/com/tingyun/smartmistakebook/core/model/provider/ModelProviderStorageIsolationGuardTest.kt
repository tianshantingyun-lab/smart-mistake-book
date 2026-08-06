package com.tingyun.smartmistakebook.core.model.provider

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderStorageIsolationGuardTest {
    @Test
    fun providerBuildHasNoProductionDatabaseDependency() {
        val root = projectRoot()
        val buildFile = File(root, "core/model-provider/build.gradle.kts")
        val buildSource = buildFile.readText()
        val violations =
            PRODUCTION_DEPENDENCY
                .findAll(buildSource)
                .map { match -> match.value.trim() }
                .filter { dependency ->
                    FORBIDDEN_DATABASE_DEPENDENCY.containsMatchIn(dependency)
                }
                .toList()

        assertTrue(
            "core/model-provider/build.gradle.kts has a production database dependency: " +
                violations,
            violations.isEmpty(),
        )
    }

    @Test
    fun providerSourceCannotImportOrExposeSqlDaoOrDatabaseTypes() {
        val root = projectRoot()
        val providerSourceRoot = File(root, "core/model-provider/src/main")
        val violations =
            providerSourceRoot.walkTopDown()
                .onEnter { directory -> directory.name !in IGNORED_DIRECTORY_NAMES }
                .filter(File::isFile)
                .filter { source -> source.extension in setOf("kt", "java") }
                .mapNotNull { source ->
                    val code = source.readText().withoutCommentsAndStrings()
                    val forbidden =
                        FORBIDDEN_PROVIDER_CODE
                            .firstOrNull { pattern -> pattern.containsMatchIn(code) }
                            ?: return@mapNotNull null
                    "${source.relativeTo(root).invariantSeparatorsPath}: ${forbidden.pattern}"
                }
                .toList()

        assertTrue(
            "The external model provider imports or exposes a database, SQL, or DAO surface: " +
                violations,
            violations.isEmpty(),
        )
    }

    @Test
    fun providerSourceCannotLogExternalRequestOrResponsePayloads() {
        val root = projectRoot()
        val providerSourceRoot = File(root, "core/model-provider/src/main")
        val violations =
            providerSourceRoot.walkTopDown()
                .onEnter { directory -> directory.name !in IGNORED_DIRECTORY_NAMES }
                .filter(File::isFile)
                .filter { source -> source.extension in setOf("kt", "java") }
                .mapNotNull { source ->
                    val code = source.readText().withoutCommentsAndStrings()
                    val forbidden =
                        FORBIDDEN_PROVIDER_LOGGING
                            .firstOrNull { pattern -> pattern.containsMatchIn(code) }
                            ?: return@mapNotNull null
                    "${source.relativeTo(root).invariantSeparatorsPath}: ${forbidden.pattern}"
                }
                .toList()

        assertTrue(
            "The external model provider must not log request or response payloads: $violations",
            violations.isEmpty(),
        )
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun String.withoutCommentsAndStrings(): String =
        RAW_STRING.replace(this, " ")
            .let { source -> REGULAR_STRING.replace(source, " ") }
            .let { source -> BLOCK_COMMENT.replace(source, " ") }
            .let { source -> LINE_COMMENT.replace(source, " ") }
            .let { source -> CHARACTER_LITERAL.replace(source, " ") }

    private companion object {
        val IGNORED_DIRECTORY_NAMES =
            setOf("build", ".gradle", ".git", ".idea", ".kotlin")

        val PRODUCTION_DEPENDENCY =
            Regex(
                """(?m)^\s*(?:api|implementation|compileOnly|runtimeOnly|ksp)""" +
                    """\s*\([\s\S]*?\)""",
            )

        val FORBIDDEN_DATABASE_DEPENDENCY =
            Regex(
                """(?i)(?:database|room|sqlite|sqldelight|jdbc)""",
            )

        val FORBIDDEN_PROVIDER_CODE =
            listOf(
                Regex(
                    """(?m)^\s*import\s+(?:android\.database|androidx\.room|""" +
                        """androidx\.sqlite|java\.sql|""" +
                        """com\.tingyun\.smartmistakebook\..*\.database)(?:\.|\s|$)""",
                ),
                Regex(
                    """\b(?:RoomDatabase|SQLiteDatabase|SQLiteConnection|""" +
                        """SupportSQLite\w*|SqlDriver)\b""",
                ),
                Regex("""@\s*(?:Dao|Query|RawQuery|Database|Entity)\b"""),
                Regex("""\b(?:execSQL|rawQuery)\s*\("""),
                Regex("""(?i)\b(?:sql[A-Za-z0-9_]*|[A-Za-z0-9_]*Dao)\s*:"""),
            )

        val FORBIDDEN_PROVIDER_LOGGING =
            listOf(
                Regex("""(?m)^\s*import\s+android\.util\.Log(?:\.|\s|$)"""),
                Regex("""(?m)^\s*import\s+(?:java\.util\.logging|org\.slf4j)(?:\.|\s|$)"""),
                Regex("""\b(?:Log|Timber|LoggerFactory)\s*\."""),
                Regex("""\b(?:logger|log)\s*\.""", RegexOption.IGNORE_CASE),
                Regex("""\b(?:print|println|printStackTrace)\s*\("""),
            )

        val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        val REGULAR_STRING = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val LINE_COMMENT = Regex("//[^\\r\\n]*")
        val CHARACTER_LITERAL = Regex("'(?:\\\\.|[^'\\\\])'")
    }
}
