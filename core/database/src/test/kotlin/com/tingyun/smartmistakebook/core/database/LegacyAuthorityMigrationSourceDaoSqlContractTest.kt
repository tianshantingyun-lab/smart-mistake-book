package com.tingyun.smartmistakebook.core.database

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAuthorityMigrationSourceDaoSqlContractTest {
    @Test
    fun exactCaptureHeadersUseIndexedIdentitiesAndAssetRangesStayBounded() {
        val source = daoSource()
        val adapter = projectRoot().source(ROOM_STUDY_DATABASE)
        val legacyAuthoritySupport = projectRoot().source(ROOM_STUDY_DATABASE_LEGACY_AUTHORITY_SUPPORT)
        val claim = source.queryBefore("readClaimableCaptureStudentSave")
        val receipt = source.queryBefore("readExactReceiptCaptureStudentDocument")
        val prepared = source.queryBefore("readExactPreparedHandoffCaptureStudentDocument")
        val exactAssets = source.queryBefore("readExactStudentDocumentAssets")
        val exactDraftAssets = source.queryBefore("readExactDraftSourceAssets")

        claim.requires(
            "FROM problem_draft_commit_receipt AS receipt",
            "receipt.command_id = :intentId",
            "JOIN problem_draft AS draft",
            "draft.current_revision_number = receipt.draft_revision_number",
            "JOIN problem_draft_revision AS draft_revision",
            "draft_revision.revision_number = receipt.draft_revision_number",
            "LEFT JOIN tutor_session AS tutor",
            "ON tutor.draft_id = receipt.draft_id",
            "draft.origin = 'LIBRARY'",
            "draft.origin = 'TUTOR'",
            "tutor.draft_revision_number = :draftRevisionNumber",
            "LIMIT 1",
        )
        assertFalse(
            "The unique tutor-session lookup must not hide a mismatched revision in its JOIN",
            Regex(
                """ON\s+tutor\.draft_id\s*=\s*receipt\.draft_id\s+""" +
                    """AND\s+tutor\.draft_revision_number""",
            ).containsMatchIn(claim),
        )
        listOf(claim, receipt, prepared).forEach { query ->
            assertFalse(
                "A repeated capture may reuse a canonical revision with another draft fingerprint",
                "revision.content_fingerprint = draft_revision.document_fingerprint" in query,
            )
        }
        receipt.requires(
            "FROM problem_draft_commit_receipt AS receipt",
            "receipt.command_id = :intentId",
            "receipt.payload_fingerprint = :intentCanonicalFingerprint",
            "receipt.draft_id = :draftId",
            "receipt.draft_revision_number = :draftRevisionNumber",
            "JOIN capture_student_save_handoff AS handoff",
            "handoff.intent_id = receipt.command_id",
            "JOIN problem_draft_revision AS draft_revision",
            "draft_revision.revision_number = receipt.draft_revision_number",
            "entry.current_revision_id = receipt.problem_revision_id",
            "unit.problem_revision_id = receipt.problem_revision_id",
            "draft.origin = 'LIBRARY'",
            "draft.origin = 'TUTOR'",
            "LIMIT 2",
        )
        prepared.requires(
            "FROM capture_student_save_handoff AS handoff",
            "handoff.intent_id = :intentId",
            "JOIN problem_draft_commit_receipt AS receipt",
            "receipt.command_id = handoff.intent_id",
            "receipt.draft_revision_number = handoff.draft_revision_number",
            "receipt.problem_revision_id = handoff.target_revision_id",
            "receipt.practice_unit_id = handoff.target_practice_unit_id",
            "entry.current_revision_id = receipt.problem_revision_id",
            "unit.problem_revision_id = receipt.problem_revision_id",
            "draft.origin = 'LIBRARY'",
            "draft.origin = 'TUTOR'",
            "LIMIT 2",
        )
        exactAssets.requires(
            "FROM problem_revision_source_asset AS link",
            "draft_asset.draft_id = :draftId",
            "LEFT JOIN canonical_source_asset AS asset",
            "WHERE link.problem_revision_id = :revisionId",
            "LIMIT :limit",
        )
        assertFalse(
            "Exact asset reads must return every revision link instead of collapsing the set",
            "MIN(draft_asset.page_index)" in exactAssets || "GROUP BY" in exactAssets,
        )
        exactDraftAssets.requires(
            "FROM problem_draft_source_asset",
            "WHERE draft_id = :draftId",
            "LIMIT :limit",
        )
        assertTrue(
            "Duplicate exact header rows must fail closed",
            "val row = rows.singleOrNull()" in adapter ||
                "val row = rows.singleOrNull()" in legacyAuthoritySupport,
        )

        val exactSql =
            listOf(claim, receipt, prepared, exactAssets, exactDraftAssets).joinToString()
        assertFalse(
            "The legacy migration source must not attach another database",
            "ATTACH" in source.uppercase(),
        )
        assertFalse(
            "The legacy migration source DAO must remain read-only",
            listOf("@Insert", "@Update", "@Delete").any { annotation ->
                annotation in source
            },
        )
        assertFalse(
            "Exact legacy reads must not query a destination student database",
            "student_mistake" in exactSql.lowercase(),
        )
        assertFalse(
            "Exact legacy reads must not query a destination mastery database",
            "learner_mastery" in exactSql.lowercase(),
        )
        assertFalse(
            "Runtime exact document reads must not begin with an error-book scan",
            Regex("""(?i)FROM\s+error_book_entry\s+AS\s+entry""").containsMatchIn(
                receipt + prepared,
            ),
        )
    }

    @Test
    fun exactLookupColumnsArePrimaryOrUniqueLegacyKeys() {
        val schema = projectRoot().latestStudyDatabaseSchema()

        schema.entity("capture_student_save_handoff")
            .requiresPrimaryKey("intent_id")
        schema.entity("problem_draft_commit_receipt").run {
            requiresPrimaryKey("command_id")
            requiresUniqueIndex("draft_id")
        }
        schema.entity("problem_draft").requiresPrimaryKey("draft_id")
        schema.entity("problem_draft_revision")
            .requiresPrimaryKey("draft_id", "revision_number")
        schema.entity("problem_draft_source_asset").run {
            requiresPrimaryKey("draft_id", "page_index")
            requiresUniqueIndex("draft_id", "source_asset_id")
        }
        schema.entity("tutor_session").run {
            requiresPrimaryKey("session_id")
            requiresUniqueIndex("draft_id")
        }
        schema.entity("problem").requiresPrimaryKey("problem_id")
        schema.entity("problem_revision").run {
            requiresPrimaryKey("revision_id")
            requiresUniqueIndex("problem_id", "revision_id")
            requiresUniqueIndex("problem_id", "content_fingerprint")
        }
        schema.entity("practice_unit").run {
            requiresPrimaryKey("practice_unit_id")
            requiresUniqueIndex("practice_unit_id", "problem_id")
            requiresUniqueIndex("practice_unit_id", "problem_revision_id")
        }
        schema.entity("error_book_entry").run {
            requiresPrimaryKey("entry_id")
            requiresUniqueIndex("practice_unit_id")
        }
        schema.entity("problem_revision_source_asset")
            .requiresPrimaryKey("problem_revision_id", "source_asset_id", "role")
    }

    @Test
    fun bulkMigrationPinsTheEntryHeadAndPracticeUnitIndependently() {
        val source = daoSource()
        val page = source.queryBefore("readStudentDocumentPage")
        val assets = source.queryBefore("readStudentDocumentAssets")
        val stats = source.queryBefore("readStats")

        page.requires(
            "FROM error_book_entry AS entry",
            "revision.revision_id = entry.current_revision_id",
            "revision.problem_id = entry.problem_id",
            "unit.practice_unit_id = entry.practice_unit_id",
            "unit.problem_id = entry.problem_id",
        )
        assertFalse(
            "The practice unit keeps its own immutable basis revision",
            "unit.problem_revision_id = entry.current_revision_id" in page,
        )
        assets.requires(
            "receipt.command_id = (",
            "FROM problem_draft_commit_receipt AS candidate",
            "candidate.problem_revision_id = link.problem_revision_id",
            "SELECT COUNT(*)",
            "candidate_asset.draft_id = candidate.draft_id",
            "candidate.committed_at_epoch_millis DESC",
            "LIMIT 1",
        )
        assertFalse(
            "Bulk assets must use one complete-order witness instead of merging draft page orders",
            "MIN(draft_asset.page_index)" in assets || "GROUP BY" in assets,
        )
        assertTrue(
            "Snapshot counts must use the same current-revision identity as the migration page",
            Regex("""revision\.revision_id\s*=\s*entry\.current_revision_id""")
                .findAll(stats)
                .count() >= 5,
        )
    }

    private fun daoSource(): String = projectRoot().source(DAO_SOURCE)

    private fun String.queryBefore(methodName: String): String {
        val method = indexOf("suspend fun $methodName")
        check(method >= 0) { "Missing DAO method $methodName" }
        val annotation = lastIndexOf("@Query(", method)
        check(annotation >= 0) { "Missing @Query for $methodName" }
        return substring(annotation, method)
    }

    private fun String.requires(vararg fragments: String) {
        fragments.forEach { fragment ->
            assertTrue("Missing SQL/schema contract fragment: $fragment", fragment in this)
        }
    }

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private fun File.source(relativePath: String): String =
        File(this, relativePath).also { source ->
            check(source.isFile) { "Missing source contract: $relativePath" }
        }.readText()

    private fun File.latestStudyDatabaseSchema(): String {
        val directory = File(this, STUDY_DATABASE_SCHEMAS)
        check(directory.isDirectory) {
            "Missing StudyDatabase schema exports: $STUDY_DATABASE_SCHEMAS"
        }
        val latest =
            directory.listFiles()
                .orEmpty()
                .filter { schema -> schema.extension == "json" }
                .maxByOrNull { schema ->
                    schema.nameWithoutExtension.toIntOrNull() ?: Int.MIN_VALUE
                }
                ?: error("StudyDatabase has no exported schema")
        return latest.readText()
    }

    private fun String.entity(tableName: String): String {
        val marker = "\"tableName\": \"$tableName\""
        val markerOffset = indexOf(marker)
        check(markerOffset >= 0) { "Missing schema entity: $tableName" }
        val objectStart = lastIndexOf('{', markerOffset)
        check(objectStart >= 0) { "Malformed schema entity: $tableName" }

        var depth = 0
        var inString = false
        var escaped = false
        for (offset in objectStart until length) {
            val character = this[offset]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return substring(objectStart, offset + 1)
                    }
                }
            }
        }
        error("Unterminated schema entity: $tableName")
    }

    private fun String.requiresPrimaryKey(vararg columns: String) {
        compactJson().requires(
            "\"primaryKey\":{\"autoGenerate\":false,\"columnNames\":[" +
                columns.joinToString(",") { column -> "\"$column\"" } +
                "]}",
        )
    }

    private fun String.requiresUniqueIndex(vararg columns: String) {
        compactJson().requires(
            "\"unique\":true,\"columnNames\":[" +
                columns.joinToString(",") { column -> "\"$column\"" } +
                "]",
        )
    }

    private fun String.compactJson(): String = filterNot { character -> character.isWhitespace() }

    private companion object {
        const val DAO_SOURCE =
            "core/database/src/main/kotlin/com/tingyun/smartmistakebook/" +
                "core/database/dao/LegacyAuthorityMigrationSourceDao.kt"
        const val STUDY_DATABASE_SCHEMAS =
            "core/database/schemas/" +
                "com.tingyun.smartmistakebook.core.database.StudyDatabase"
        const val ROOM_STUDY_DATABASE =
            "core/database/src/main/kotlin/com/tingyun/smartmistakebook/" +
                "core/database/RoomStudyDatabase.kt"
        const val ROOM_STUDY_DATABASE_LEGACY_AUTHORITY_SUPPORT =
            "core/database/src/main/kotlin/com/tingyun/smartmistakebook/" +
                "core/database/RoomStudyDatabaseLegacyAuthoritySupport.kt"
    }
}
