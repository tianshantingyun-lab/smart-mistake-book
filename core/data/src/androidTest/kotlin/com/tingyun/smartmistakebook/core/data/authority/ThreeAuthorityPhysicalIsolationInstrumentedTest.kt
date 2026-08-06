package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.database.LegacySessionDatabaseOwnerFactory
import com.tingyun.smartmistakebook.core.knowledge.database.DebugBoundaryKnowledgePackFixture
import com.tingyun.smartmistakebook.core.knowledge.database.HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalogFactory
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgePackProvisioner
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackProvisionOutcome
import com.tingyun.smartmistakebook.core.mastery.database.LEARNER_MASTERY_DATABASE_NAME
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryOwnerCapabilities
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.student.mistake.database.STUDENT_MISTAKE_DATABASE_NAME
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentMistakeOwnerCapabilities
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeAuthorityPhysicalIsolationInstrumentedTest {
    @Test
    fun authorityStoresUseThreePhysicallyIsolatedDatabases() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        clearProductionAuthorities(context)
        try {
            // This non-production fixture exercises physical ownership, not corpus activation.
            val provision =
                HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                    context = context,
                    pack = DebugBoundaryKnowledgePackFixture.reviewedPack(),
                )
            assertEquals(KnowledgePackProvisionOutcome.ACTIVATED, provision.outcome)
            assertFalse(provision.productionCutoverEligible)
            assertEquals(
                DebugBoundaryKnowledgePackFixture.GENERATION,
                provision.activation.generation,
            )
            val proofAuthority = KnowledgeReferenceProofAuthority.create()

            // The pre-cutover database is retained only as a coordination and migration-evidence
            // store. Opening and closing its narrow production owner also proves that the file was
            // created through the same fail-closed path used by the app.
            LegacySessionDatabaseOwnerFactory.open(context).use { }

            HighSchoolKnowledgeCatalogFactory.open(
                context,
                proofAuthority.issuer,
            ).use { knowledgeCatalog ->
                openStudentMistakeOwnerCapabilities(
                    context,
                    LEARNER_ID,
                    proofAuthority.verifier,
                ).use { studentMistakes ->
                    openLearnerMasteryOwnerCapabilities(
                        context,
                        LEARNER_ID,
                        proofAuthority.verifier,
                    ).use { learnerMastery ->
                        val manifest = knowledgeCatalog.readManifest()
                        assertEquals(
                            DebugBoundaryKnowledgePackFixture.PACK_ID,
                            manifest.packId,
                        )
                        assertEquals(
                            DebugBoundaryKnowledgePackFixture.KNOWLEDGE_PACK_VERSION,
                            manifest.knowledgePackVersion,
                        )
                        assertEquals(
                            DebugBoundaryKnowledgePackFixture.TAXONOMY_VERSION,
                            manifest.taxonomyVersion,
                        )
                        assertEquals(
                            provision.activation.manifestFingerprint,
                            manifest.contentFingerprint,
                        )

                        studentMistakes.library.readFacets()
                        learnerMastery.reader.queryDigest(
                            subject = SubjectKind.MATH,
                            focusLimit = 1,
                        )
                    }
                }
            }

            val replay =
                HighSchoolKnowledgePackProvisioner.provisionApkBundledReviewedPack(
                    context = context,
                    pack = DebugBoundaryKnowledgePackFixture.reviewedPack(),
                )
            assertEquals(KnowledgePackProvisionOutcome.ALREADY_ACTIVE, replay.outcome)
            assertFalse(replay.productionCutoverEligible)
            assertEquals(
                provision.activation.manifestFingerprint,
                replay.activation.manifestFingerprint,
            )

            val authorityFiles =
                listOf(
                    AuthorityFile(
                        databaseName = STUDENT_MISTAKE_DATABASE_NAME,
                        businessTablePrefix = "student_",
                        requiredTable = "student_problem_document",
                        forbiddenColumns =
                            setOf(
                                "positive_evidence_micros",
                                "negative_evidence_micros",
                                "evidence_mass_micros",
                                "mastery_score_micros",
                                "mastery_state",
                                "memory_strength_micros",
                                "memory_stability_millis",
                                "recall_due_at_epoch_millis",
                                "projection_policy_version",
                                "boundary_markdown",
                                "material_body_markdown",
                                "method_markdown",
                                "example_markdown",
                                "knowledge_body_markdown",
                            ),
                    ),
                    AuthorityFile(
                        databaseName = LEARNER_MASTERY_DATABASE_NAME,
                        businessTablePrefix = "mastery_",
                        requiredTable = "mastery_knowledge_projection",
                        forbiddenColumns =
                            setOf(
                                "problem_markdown",
                                "stem_markdown",
                                "answer_markdown",
                                "solution_markdown",
                                "content_markdown",
                                "document_json",
                                "captured_question_document_wire",
                                "storage_uri",
                                "local_content_uri",
                                "image_uri",
                                "asset_uri",
                                "stem_preview",
                                "message_markdown",
                                "message_text",
                                "boundary_markdown",
                                "material_body_markdown",
                                "method_markdown",
                                "example_markdown",
                                "knowledge_body_markdown",
                            ),
                    ),
                    AuthorityFile(
                        databaseName = HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME,
                        businessTablePrefix = "knowledge_",
                        requiredTable = "knowledge_node",
                        forbiddenColumns =
                            setOf(
                                "learner_id",
                                "student_id",
                                "problem_id",
                                "practice_unit_id",
                                "attempt_id",
                                "conversation_id",
                                "mastery_score_micros",
                                "mastery_state",
                                "memory_stability_millis",
                                "learning_event_id",
                                "observation_id",
                                "evidence_id",
                                "review_session_id",
                                "collection_entry_id",
                            ),
                    ),
                )
            assertEquals(3, authorityFiles.map(AuthorityFile::databaseName).toSet().size)

            val physicalFiles =
                authorityFiles.map { authority ->
                    context.getDatabasePath(authority.databaseName).canonicalFile
                }
            assertEquals(3, physicalFiles.map { file -> file.canonicalPath }.toSet().size)
            physicalFiles.forEach { file ->
                assertTrue("Production database does not exist: ${file.name}", file.isFile)
            }

            val schemas =
                authorityFiles.associateWith { authority ->
                    inspectSchema(context.getDatabasePath(authority.databaseName))
                }
            schemas.forEach { (authority, schema) ->
                assertAuthorityBoundary(authority, schema)
            }
            val legacyDatabaseFile =
                context.getDatabasePath(
                    LegacySessionDatabaseOwnerFactory.DEFAULT_DATABASE_NAME,
                ).canonicalFile
            assertTrue("Legacy coordination database does not exist", legacyDatabaseFile.isFile)
            assertEquals(
                4,
                (physicalFiles + legacyDatabaseFile)
                    .map { file -> file.canonicalPath }
                    .toSet()
                    .size,
            )
            assertLegacyDatabaseIsCoordinationOnly(inspectSchema(legacyDatabaseFile))
            val studentCutoverTables =
                setOf(
                    "student_cutover_fence",
                    "student_cutover_completion_receipt",
                )
            assertTrue(
                studentCutoverTables.all(
                    schemas.getValue(authorityFiles.first()).allTables::contains,
                ),
            )
            authorityFiles.drop(1).forEach { authority ->
                assertTrue(
                    "${authority.databaseName} must not own student cutover state",
                    schemas.getValue(authority).allTables.intersect(studentCutoverTables).isEmpty(),
                )
            }
            assertBusinessTablesAreDisjoint(schemas)
        } finally {
            clearProductionAuthorities(context)
        }
    }

    private fun assertAuthorityBoundary(
        authority: AuthorityFile,
        schema: SchemaSnapshot,
    ) {
        assertTrue(
            "${authority.databaseName} is missing ${authority.requiredTable}",
            authority.requiredTable in schema.businessTables,
        )
        val foreignBusinessTables =
            schema.businessTables.filterNot { table ->
                table.startsWith(authority.businessTablePrefix)
            }
        assertTrue(
            "${authority.databaseName} contains foreign business tables: $foreignBusinessTables",
            foreignBusinessTables.isEmpty(),
        )

        assertTrue(
            "${authority.databaseName} has no main database",
            "main" in schema.databaseFiles,
        )
        val attachedDatabaseNames = schema.databaseFiles.keys - ALLOWED_DATABASE_BINDINGS
        assertTrue(
            "${authority.databaseName} attached other databases: $attachedDatabaseNames",
            attachedDatabaseNames.isEmpty(),
        )
        assertEquals(
            schema.databaseFile.canonicalPath,
            File(schema.databaseFiles.getValue("main")).canonicalPath,
        )

        schema.foreignKeyTargets.forEach { (sourceTable, targetTables) ->
            val missingLocalTargets = targetTables - schema.allTables
            assertTrue(
                "$sourceTable in ${authority.databaseName} references non-local tables: " +
                    missingLocalTargets,
                missingLocalTargets.isEmpty(),
            )
        }
        val leakedColumns =
            schema.tableColumns
                .flatMap { (table, columns) ->
                    columns
                        .intersect(authority.forbiddenColumns)
                        .map { column -> "$table.$column" }
                }
        assertTrue(
            "${authority.databaseName} contains data owned by another authority: $leakedColumns",
            leakedColumns.isEmpty(),
        )
    }

    private fun assertBusinessTablesAreDisjoint(
        schemas: Map<AuthorityFile, SchemaSnapshot>,
    ) {
        val entries = schemas.entries.toList()
        for (leftIndex in entries.indices) {
            for (rightIndex in leftIndex + 1 until entries.size) {
                val left = entries[leftIndex]
                val right = entries[rightIndex]
                val overlap = left.value.businessTables intersect right.value.businessTables
                assertTrue(
                    "${left.key.databaseName} and ${right.key.databaseName} share tables: $overlap",
                    overlap.isEmpty(),
                )
            }
        }
    }

    private fun assertLegacyDatabaseIsCoordinationOnly(schema: SchemaSnapshot) {
        assertTrue("Legacy database has no main database", "main" in schema.databaseFiles)
        val attachedDatabaseNames = schema.databaseFiles.keys - ALLOWED_DATABASE_BINDINGS
        assertTrue(
            "Legacy database attached other databases: $attachedDatabaseNames",
            attachedDatabaseNames.isEmpty(),
        )

        val retainedReadOnlyTables =
            schema.businessTables -
                LEGACY_WRITABLE_COORDINATION_TABLES -
                LEGACY_BUSINESS_WRITE_BARRIER_TABLE
        assertTrue("Legacy migration evidence unexpectedly disappeared", retainedReadOnlyTables.isNotEmpty())
        retainedReadOnlyTables.forEach { table ->
            LEGACY_BLOCKED_MUTATIONS.forEach { mutation ->
                val triggerName =
                    "legacy_business_barrier_${mutation.lowercase()}_$table"
                val trigger = schema.triggers[triggerName]
                assertTrue(
                    "Legacy $mutation remains writable for $table",
                    trigger != null &&
                        trigger.tableName == table &&
                        trigger.sql.contains("RAISE(ABORT", ignoreCase = true),
                )
            }
        }

        assertTrue(
            "Legacy write barrier is missing",
            LEGACY_BUSINESS_WRITE_BARRIER_TABLE in schema.businessTables,
        )
        LEGACY_BARRIER_PROTECTION_TRIGGERS.forEach { triggerName ->
            val trigger = schema.triggers[triggerName]
            assertTrue(
                "Legacy write barrier can be altered through $triggerName",
                trigger != null &&
                    trigger.tableName == LEGACY_BUSINESS_WRITE_BARRIER_TABLE &&
                    trigger.sql.contains("RAISE(ABORT", ignoreCase = true),
            )
        }

        val unclassifiedTables =
            schema.businessTables -
                retainedReadOnlyTables -
                LEGACY_WRITABLE_COORDINATION_TABLES -
                LEGACY_BUSINESS_WRITE_BARRIER_TABLE
        assertTrue(
            "Legacy database contains unclassified writable tables: $unclassifiedTables",
            unclassifiedTables.isEmpty(),
        )
    }

    private fun inspectSchema(databaseFile: File): SchemaSnapshot {
        val canonicalFile = databaseFile.canonicalFile
        return SQLiteDatabase.openDatabase(
            canonicalFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            val allTables =
                database.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                    emptyArray(),
                ).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
            val businessTables =
                allTables.filterTo(linkedSetOf()) { table ->
                    table !in SQLITE_INFRASTRUCTURE_TABLES && !table.startsWith("sqlite_")
                }
            val databaseFiles =
                database.rawQuery("PRAGMA database_list", emptyArray()).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getString(1), cursor.getString(2))
                        }
                    }
                }
            val foreignKeyTargets =
                businessTables.associateWith { table ->
                    database.rawQuery(
                        "PRAGMA foreign_key_list(${quoteIdentifier(table)})",
                        emptyArray(),
                    ).use { cursor ->
                        buildSet {
                            while (cursor.moveToNext()) add(cursor.getString(2))
                        }
                        }
                }
            val tableColumns =
                businessTables.associateWith { table ->
                    database.rawQuery(
                        "PRAGMA table_info(${quoteIdentifier(table)})",
                        emptyArray(),
                    ).use { cursor ->
                        buildSet {
                            while (cursor.moveToNext()) add(cursor.getString(1))
                        }
                    }
                }
            val triggers =
                database.rawQuery(
                    "SELECT name, tbl_name, sql FROM sqlite_master " +
                        "WHERE type = 'trigger' ORDER BY name",
                    emptyArray(),
                ).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            put(
                                cursor.getString(0),
                                TriggerSnapshot(
                                    tableName = cursor.getString(1),
                                    sql = cursor.getString(2).orEmpty(),
                                ),
                            )
                        }
                    }
                }
            SchemaSnapshot(
                databaseFile = canonicalFile,
                allTables = allTables,
                businessTables = businessTables,
                databaseFiles = databaseFiles,
                foreignKeyTargets = foreignKeyTargets,
                tableColumns = tableColumns,
                triggers = triggers,
            )
        }
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"${identifier.replace("\"", "\"\"")}\""

    private fun clearProductionAuthorities(context: Context) {
        listOf(
            STUDENT_MISTAKE_DATABASE_NAME,
            LEARNER_MASTERY_DATABASE_NAME,
            HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME,
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.next",
            LegacySessionDatabaseOwnerFactory.DEFAULT_DATABASE_NAME,
        ).forEach { databaseName ->
            context.deleteDatabase(databaseName)
        }

        val databaseDirectory =
            requireNotNull(context.getDatabasePath(HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME).parentFile)
        listOf(
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation",
            "$HIGH_SCHOOL_KNOWLEDGE_DATABASE_NAME.activation.pending",
        ).forEach { name ->
            File(databaseDirectory, name).delete()
        }
    }

    private data class AuthorityFile(
        val databaseName: String,
        val businessTablePrefix: String,
        val requiredTable: String,
        val forbiddenColumns: Set<String>,
    )

    private data class SchemaSnapshot(
        val databaseFile: File,
        val allTables: Set<String>,
        val businessTables: Set<String>,
        val databaseFiles: Map<String, String>,
        val foreignKeyTargets: Map<String, Set<String>>,
        val tableColumns: Map<String, Set<String>>,
        val triggers: Map<String, TriggerSnapshot>,
    )

    private data class TriggerSnapshot(
        val tableName: String,
        val sql: String,
    )

    private companion object {
        const val LEARNER_ID = "learner-three-authority-isolation"

        val SQLITE_INFRASTRUCTURE_TABLES =
            setOf(
                "android_metadata",
                "room_master_table",
            )
        val ALLOWED_DATABASE_BINDINGS = setOf("main", "temp")
        const val LEGACY_BUSINESS_WRITE_BARRIER_TABLE = "legacy_business_write_barrier"
        val LEGACY_BLOCKED_MUTATIONS = setOf("INSERT", "UPDATE", "DELETE")
        val LEGACY_BARRIER_PROTECTION_TRIGGERS =
            setOf(
                "legacy_business_barrier_immutable_update",
                "legacy_business_barrier_immutable_delete",
                "legacy_business_barrier_conflicting_insert",
            )
        val LEGACY_WRITABLE_COORDINATION_TABLES =
            setOf(
                "batch_capture_content_binding",
                "batch_import_boundary_resolution_receipt",
                "capture_draft_batch_import_receipt",
                "capture_draft_merge_session_receipt",
                "capture_student_save_handoff",
                "legacy_authority_cutover_stage_receipt",
                "tutor_conversation",
                "tutor_current_host_work",
                "tutor_current_interaction_event",
                "tutor_current_interaction_head",
                "tutor_current_interaction_scope",
                "tutor_current_policy",
                "tutor_evidence_request",
                "tutor_free_response_outbox",
                "tutor_learning_evidence_finalization_receipt",
                "tutor_turn_receipt",
            )
    }
}
