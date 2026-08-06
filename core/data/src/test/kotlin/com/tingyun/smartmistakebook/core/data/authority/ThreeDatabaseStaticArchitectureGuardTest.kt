package com.tingyun.smartmistakebook.core.data.authority

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeDatabaseStaticArchitectureGuardTest {
    @Test
    fun formalProductionModulesDoNotDependOnTheLegacyDatabaseModule() {
        val root = projectRoot()
        val dependencyPattern =
            Regex(
                """project\s*\(\s*(?:path\s*=\s*)?["']:core:database["']""" +
                    """|projects\.core\.database\b""",
            )
        val violations =
            moduleBuildFiles(root)
                .filterNot { buildFile ->
                    buildFile.relativePath(root) in LEGACY_DATABASE_OWNER_BUILD_FILES
                }
                .filter { buildFile ->
                    dependencyPattern.containsMatchIn(buildFile.readText())
                }
                .map { buildFile -> buildFile.relativePath(root) }
                .sorted()

        assertTrue(
            "Formal production modules depend on the legacy core:database module: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun productionSqlCannotAttachOrQualifyAnotherDatabase() {
        val root = projectRoot()
        val violationsByFile =
            productionSourceFiles(root)
                .flatMap { source ->
                    source.sqlTextFragments()
                        .filter { sql ->
                            isForbiddenCrossDatabaseSql(sql) &&
                                !source.isAuditedLegacyMetadataQuery(root, sql)
                        }
                        .map { sql ->
                            source.relativePath(root) to sql.singleLinePreview()
                        }
                }
                .groupBy(
                    keySelector = { (path, _) -> path },
                    valueTransform = { (_, preview) -> preview },
                )
                .toSortedMap()
                .map { (path, previews) ->
                    "$path: ${previews.distinct()}"
                }

        assertTrue(
            "Production SQL must operate through one typed database owner and must not attach or " +
                "qualify another SQLite database: $violationsByFile",
            violationsByFile.isEmpty(),
        )
    }

    @Test
    fun crossDatabaseSqlGuardRecognizesControlAndQualifiedObjectSyntax() {
        listOf(
            "ATTACH DATABASE ? AS legacy",
            "DETACH DATABASE legacy",
            "PRAGMA database_list",
            "SELECT * FROM legacy.problem",
            "SELECT * FROM \"student-db\".\"problem\"",
            "PRAGMA legacy.table_info",
            "CREATE INDEX revision_lookup ON [legacy].[problem_revision](revision_id)",
            "SELECT * FROM \${databaseAlias}.problem",
        ).forEach { sql ->
            assertTrue("Expected cross-database SQL to be rejected: $sql", isForbiddenCrossDatabaseSql(sql))
        }
        listOf(
            "SELECT entry.problem_id FROM error_book_entry AS entry",
            "CREATE INDEX revision_lookup ON problem_revision(revision_id)",
            "PRAGMA foreign_keys = ON",
        ).forEach { sql ->
            assertFalse("Ordinary single-database SQL was rejected: $sql", isForbiddenCrossDatabaseSql(sql))
        }
    }

    @Test
    fun legacyDatabaseReferencesStayInsideMigrationAndSessionOwnership() {
        val root = projectRoot()
        val violations =
            productionSourceFiles(root)
                .filterNot { source -> source.isLegacyMigrationOrSessionOwner(root) }
                .filter { source ->
                    val lexemes = source.sourceLexemes()
                    LEGACY_DATABASE_CODE_REFERENCE.containsMatchIn(lexemes.code) ||
                        lexemes.strings.any { text -> LEGACY_DATABASE_FILE_NAME in text }
                }
                .map { source -> source.relativePath(root) }
                .sorted()

        assertTrue(
            "$LEGACY_DATABASE_FILE_NAME and core:database APIs escaped the audited " +
                "migration/session boundary: $violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun legacyOwnershipDoesNotExpandWithPackagePlacement() {
        val root = projectRoot()

        assertTrue(
            File(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyModelTaskSessionAdapter.kt",
            ).isLegacyMigrationOrSessionOwner(root),
        )
        assertFalse(
            File(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "UnexpectedLegacyAdapter.kt",
            ).isLegacyMigrationOrSessionOwner(root),
        )
        assertFalse(
            File(
                root,
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "UnexpectedLegacyAdapter.kt",
            ).isLegacyMigrationOrSessionOwner(root),
        )
    }

    @Test
    fun onlyExactLegacySchemaMetadataQueriesAreAudited() {
        val root = projectRoot()
        val migration =
            File(
                root,
                "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "LegacyBusinessWriteBarrierMigration.kt",
            )

        assertTrue(
            migration.isAuditedLegacyMetadataQuery(
                root,
                "SELECT name FROM main.sqlite_master WHERE type = 'table' ORDER BY name",
            ),
        )
        assertFalse(
            migration.isAuditedLegacyMetadataQuery(
                root,
                "SELECT * FROM main.problem",
            ),
        )
        assertFalse(
            File(root, "core/database/src/main/kotlin/UnexpectedMigration.kt")
                .isAuditedLegacyMetadataQuery(
                    root,
                    "SELECT name FROM main.sqlite_master WHERE type = 'table' ORDER BY name",
                ),
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

    private fun moduleBuildFiles(root: File): List<File> =
        productionModuleDirectories(root)
            .map { module -> File(module, "build.gradle.kts") }
            .filter(File::isFile)

    private fun productionSourceFiles(root: File): List<File> =
        productionModuleDirectories(root)
            .flatMap { module ->
                module.walkTopDown()
                    .onEnter(::enterSourceDirectory)
                    .filter(File::isFile)
                    .filter { file ->
                        "/src/main/" in file.invariantSeparatorsPath &&
                            file.extension in PRODUCTION_SOURCE_EXTENSIONS
                    }
                    .toList()
            }

    private fun productionModuleDirectories(root: File): List<File> =
        INCLUDED_MODULE
            .findAll(File(root, "settings.gradle.kts").readText())
            .map { match ->
                File(root, match.groupValues[1].removePrefix(":").replace(':', File.separatorChar))
            }
            .filter(File::isDirectory)
            .toList()

    private fun enterSourceDirectory(directory: File): Boolean =
        directory.name !in IGNORED_DIRECTORY_NAMES

    private fun File.isLegacyMigrationOrSessionOwner(root: File): Boolean {
        val path = relativePath(root)
        return path.startsWith("core/database/src/main/") ||
            path in AUDITED_LEGACY_OWNER_SOURCE_FILES
    }

    private fun File.isAuditedLegacyMetadataQuery(
        root: File,
        sql: String,
    ): Boolean =
        relativePath(root) == AUDITED_LEGACY_SCHEMA_MIGRATION_SOURCE &&
            sql.singleLinePreview() in AUDITED_LEGACY_SCHEMA_METADATA_QUERIES

    private fun File.sqlTextFragments(): List<String> =
        if (extension in SQL_SOURCE_EXTENSIONS) {
            listOf(readText())
        } else {
            sourceLexemes().strings
        }

    private fun isForbiddenCrossDatabaseSql(sql: String): Boolean =
        FORBIDDEN_DATABASE_CONTROL_SQL.containsMatchIn(sql) ||
            (
                SQL_STATEMENT.containsMatchIn(sql) &&
                    (
                        QUALIFIED_DATABASE_OBJECT_SQL.containsMatchIn(sql) ||
                            QUALIFIED_INDEX_TARGET_SQL.containsMatchIn(sql)
                        )
                )

    private fun File.sourceLexemes(): SourceLexemes = lexSource(readText())

    private fun File.relativePath(root: File): String =
        relativeTo(root).invariantSeparatorsPath

    private fun String.singleLinePreview(): String =
        replace(Regex("""\s+"""), " ").trim().take(SQL_PREVIEW_LENGTH)

    private data class SourceLexemes(
        val code: String,
        val strings: List<String>,
    )

    private fun lexSource(source: String): SourceLexemes {
        val code = StringBuilder(source.length)
        val strings = mutableListOf<String>()
        var index = 0

        fun appendBlank(character: Char) {
            code.append(if (character == '\n') '\n' else ' ')
        }

        fun skipCharacters(count: Int) {
            repeat(count) {
                appendBlank(source[index])
                index += 1
            }
        }

        while (index < source.length) {
            when {
                source.startsWith("//", index) -> {
                    while (index < source.length && source[index] != '\n') {
                        appendBlank(source[index])
                        index += 1
                    }
                }

                source.startsWith("/*", index) -> {
                    var depth = 1
                    skipCharacters(2)
                    while (index < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", index) -> {
                                depth += 1
                                skipCharacters(2)
                            }

                            source.startsWith("*/", index) -> {
                                depth -= 1
                                skipCharacters(2)
                            }

                            else -> {
                                appendBlank(source[index])
                                index += 1
                            }
                        }
                    }
                }

                source.startsWith("\"\"\"", index) -> {
                    skipCharacters(3)
                    val content = StringBuilder()
                    while (index < source.length && !source.startsWith("\"\"\"", index)) {
                        content.append(source[index])
                        appendBlank(source[index])
                        index += 1
                    }
                    if (index < source.length) skipCharacters(3)
                    strings += content.toString()
                }

                source[index] == '"' -> {
                    skipCharacters(1)
                    val content = StringBuilder()
                    while (index < source.length && source[index] != '"') {
                        if (source[index] == '\\' && index + 1 < source.length) {
                            content.append(source[index])
                            appendBlank(source[index])
                            index += 1
                        }
                        content.append(source[index])
                        appendBlank(source[index])
                        index += 1
                    }
                    if (index < source.length) skipCharacters(1)
                    strings += content.toString()
                }

                source[index] == '\'' -> {
                    skipCharacters(1)
                    while (index < source.length && source[index] != '\'') {
                        if (source[index] == '\\' && index + 1 < source.length) {
                            appendBlank(source[index])
                            index += 1
                        }
                        appendBlank(source[index])
                        index += 1
                    }
                    if (index < source.length) skipCharacters(1)
                }

                else -> {
                    code.append(source[index])
                    index += 1
                }
            }
        }

        return SourceLexemes(code = code.toString(), strings = strings)
    }

    private companion object {
        const val LEGACY_DATABASE_FILE_NAME = "smart-mistake-book.db"
        const val SQL_PREVIEW_LENGTH = 160

        val IGNORED_DIRECTORY_NAMES =
            setOf("build", ".gradle", ".git", ".idea", ".kotlin")

        val PRODUCTION_SOURCE_EXTENSIONS =
            setOf("kt", "java", "kts", "sql", "sq")

        val SQL_SOURCE_EXTENSIONS = setOf("sql", "sq")

        val INCLUDED_MODULE = Regex("""["'](:[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*)["']""")

        val LEGACY_DATABASE_OWNER_BUILD_FILES =
            setOf(
                "core/database/build.gradle.kts",
                "core/data/build.gradle.kts",
            )

        const val AUDITED_LEGACY_SCHEMA_MIGRATION_SOURCE =
            "core/database/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                "LegacyBusinessWriteBarrierMigration.kt"

        val AUDITED_LEGACY_SCHEMA_METADATA_QUERIES =
            setOf(
                "SELECT name FROM main.sqlite_master WHERE type = 'table' ORDER BY name",
                "SELECT sql FROM main.sqlite_master WHERE type = 'table' AND name = ?",
                "SELECT name, tbl_name, sql FROM main.sqlite_master WHERE type = 'trigger' ORDER BY name",
            )

        val AUDITED_LEGACY_OWNER_SOURCE_FILES =
            setOf(
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/database/" +
                    "LegacyTerminalCutoverJournalLease.java",
                "core/data/src/main/java/com/tingyun/smartmistakebook/core/database/" +
                    "LegacyTerminalBarrierHandoffLease.java",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "ProductionLegacyBarrierHandoff.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "CanonicalProductionAuthorityPublicationPreparer.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "CurrentGenerationLearningAuthorityBinding.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ExactLegacyAuthorityManifest.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ExistingTerminalJournalPolicyVerifier.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LearnerMasteryAuthorityCutoverAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LegacyAuthorityCutoverJournalAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LegacyAuthorityPreparation.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LegacyStudentDocumentMapper.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "LocalLearningAuthorityRuntime.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ProductionAuthorityCutoverPrefix.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ProductionLearnerMasteryTerminalAuthorityAttestationOwner.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ProductionTerminalAuthorityCutoverWriter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "StudentAuthorityCutoverAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ThreeAuthorityLegacyBusinessWriteBarrierOwner.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/authority/" +
                    "ThreeAuthorityCutover.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "ProductionStudentProblemOrganizationOwner.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "RoomMistakeDetailRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "RoomMistakeOrganizationRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                    "RoomMistakeOrganizationRepositoryMappings.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/" +
                    "AndroidRestrictedModelAssetSource.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/model/" +
                    "RoomModelTaskRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/capture/" +
                    "LegacyRoomCaptureAssetBridge.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/capture/" +
                    "LegacyRoomCaptureSessionStateStore.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "CurrentTutorSessionHostCoordinator.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "CurrentTutorSessionHostSupport.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "CurrentTutorSessionProductionOwner.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "CurrentTutorSessionProjection.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyBatchImportSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyCaptureModelDependencyReadAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyModelTaskSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyProblemOrganizationWorkSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyRoomCaptureSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyTutorConversationSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/" +
                    "LegacyTutorInteractionSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/session/migration/" +
                    "LegacyRoomBatchImportRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "RoomTutorInteractionRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "RoomTutorLearningEvidenceSessionAdapter.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "RoomTutorLearningMemoryRepository.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                    "TutorLearningMemoryProductionAssembly.kt",
                "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/database/" +
                    "ProductionTerminalMigrationWriterSession.kt",
            )

        val LEGACY_DATABASE_CODE_REFERENCE =
            Regex(
                """\bcom\.tingyun\.smartmistakebook\.core\.database(?:\.|\b)""",
            )

        val SQL_STATEMENT =
            Regex(
                """(?is)\b(?:SELECT|INSERT|REPLACE|UPDATE|DELETE|CREATE|ALTER|DROP|WITH|PRAGMA)\b""",
            )

        val FORBIDDEN_DATABASE_CONTROL_SQL =
            Regex(
                """(?is)\b(?:ATTACH|DETACH)\s+(?:DATABASE\s+)?|\bPRAGMA\s+database_list\b""",
            )

        val QUALIFIED_DATABASE_OBJECT_SQL =
            Regex(
                """(?is)\b(?:FROM|JOIN|UPDATE|INTO|DELETE\s+FROM|TABLE|VIEW|TRIGGER|INDEX|PRAGMA)""" +
                    """\s+(?:IF\s+(?:NOT\s+)?EXISTS\s+)?""" +
                    SQL_IDENTIFIER +
                    """\s*\.\s*""" +
                    SQL_IDENTIFIER,
            )

        val QUALIFIED_INDEX_TARGET_SQL =
            Regex(
                """(?is)\bCREATE\s+(?:UNIQUE\s+)?INDEX\b[\s\S]*?\bON\s+""" +
                    SQL_IDENTIFIER +
                    """\s*\.\s*""" +
                    SQL_IDENTIFIER,
            )

        const val SQL_IDENTIFIER =
            "(?:`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]|\\$\\{[^}]+\\}|" +
                "\\$[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*)"
    }
}
