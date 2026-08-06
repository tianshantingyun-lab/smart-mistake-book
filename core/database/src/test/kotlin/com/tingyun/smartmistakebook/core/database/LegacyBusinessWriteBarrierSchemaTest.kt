package com.tingyun.smartmistakebook.core.database

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBusinessWriteBarrierSchemaTest {
    @Test
    fun versionedInventoriesAreExhaustivelyPartitionedByWritePolicy() {
        assertEquals(10, LegacyBusinessWriteBarrierSchema.legacyCoordinationTables.size)
        assertEquals(16, LegacyBusinessWriteBarrierSchema.allowedCoordinationTables.size)
        assertEquals(87, LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables.size)
        assertTrue(
            LegacyBusinessWriteBarrierSchema.allowedCoordinationTables
                .intersect(LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables)
                .isEmpty(),
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.legacyCoordinationTables +
                LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables,
            LegacyBusinessWriteBarrierSchema.v44ApplicationTables,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.V44_APPLICATION_TABLE_COUNT,
            LegacyBusinessWriteBarrierSchema.v44ApplicationTables.size,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.v44ApplicationTables +
                LegacyBusinessWriteBarrierSchema.TABLE_NAME,
            LegacyBusinessWriteBarrierSchema.v45ApplicationTables,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.V45_APPLICATION_TABLE_COUNT,
            LegacyBusinessWriteBarrierSchema.v45ApplicationTables.size,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.v45ApplicationTables +
                LegacyBusinessWriteBarrierSchema.currentTutorInteractionCoordinationTables,
            LegacyBusinessWriteBarrierSchema.v46ApplicationTables,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.V46_APPLICATION_TABLE_COUNT,
            LegacyBusinessWriteBarrierSchema.v46ApplicationTables.size,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.v46ApplicationTables +
                "tutor_current_host_work",
            LegacyBusinessWriteBarrierSchema.v47ApplicationTables,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.V47_APPLICATION_TABLE_COUNT,
            LegacyBusinessWriteBarrierSchema.v47ApplicationTables.size,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.v47ApplicationTables + "tutor_current_policy",
            LegacyBusinessWriteBarrierSchema.v48ApplicationTables,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.V48_APPLICATION_TABLE_COUNT,
            LegacyBusinessWriteBarrierSchema.v48ApplicationTables.size,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.v48ApplicationTables +
                "tutor_free_response_outbox",
            LegacyBusinessWriteBarrierSchema.v49ApplicationTables,
        )
        assertEquals(
            LegacyBusinessWriteBarrierSchema.V49_APPLICATION_TABLE_COUNT,
            LegacyBusinessWriteBarrierSchema.v49ApplicationTables.size,
        )
    }

    @Test
    fun v46CurrentTutorInteractionTablesAreRegisteredAsWritableCoordinationState() {
        val expected =
            setOf(
                "tutor_current_interaction_event",
                "tutor_current_interaction_head",
                "tutor_current_interaction_scope",
            )

        assertEquals(
            expected,
            LegacyBusinessWriteBarrierSchema.currentTutorInteractionCoordinationTables,
        )
        assertEquals(
            expected,
            LegacyBusinessWriteBarrierSchema.v46ApplicationTables -
                LegacyBusinessWriteBarrierSchema.v45ApplicationTables,
        )
        assertTrue(LegacyBusinessWriteBarrierSchema.allowedCoordinationTables.containsAll(expected))
        assertTrue(
            LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables
                .intersect(expected)
                .isEmpty(),
        )
        expected.forEach { tableName ->
            assertFalse(
                LegacyBusinessWriteBarrierSchema.triggerNames().any { triggerName ->
                    triggerName.endsWith("_$tableName")
                },
            )
        }
    }

    @Test
    fun v47ThroughV49TutorHostTablesAreRegisteredOnlyAsWritableCoordinationState() {
        val v47Expected = setOf("tutor_current_host_work")
        val v48Expected = setOf("tutor_current_policy")
        val v49Expected = setOf("tutor_free_response_outbox")
        val expected = v47Expected + v48Expected + v49Expected

        assertEquals(expected, LegacyBusinessWriteBarrierSchema.currentTutorHostCoordinationTables)
        assertEquals(
            v47Expected,
            LegacyBusinessWriteBarrierSchema.v47ApplicationTables -
                LegacyBusinessWriteBarrierSchema.v46ApplicationTables,
        )
        assertEquals(
            v48Expected,
            LegacyBusinessWriteBarrierSchema.v48ApplicationTables -
                LegacyBusinessWriteBarrierSchema.v47ApplicationTables,
        )
        assertEquals(
            v49Expected,
            LegacyBusinessWriteBarrierSchema.v49ApplicationTables -
                LegacyBusinessWriteBarrierSchema.v48ApplicationTables,
        )
        assertTrue(LegacyBusinessWriteBarrierSchema.allowedCoordinationTables.containsAll(expected))
        assertTrue(
            LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables
                .intersect(expected)
                .isEmpty(),
        )
        expected.forEach { tableName ->
            assertFalse(
                LegacyBusinessWriteBarrierSchema.triggerNames().any { triggerName ->
                    triggerName.endsWith("_$tableName")
                },
            )
        }
    }

    @Test
    fun exportedRoomSchemasMatchTheirExactBarrierInventories() {
        assertEquals(
            exportedApplicationTables(version = 45),
            LegacyBusinessWriteBarrierSchema.v45ApplicationTables,
        )
        assertEquals(
            exportedApplicationTables(version = 46),
            LegacyBusinessWriteBarrierSchema.v46ApplicationTables,
        )
        assertEquals(
            exportedApplicationTables(version = 47),
            LegacyBusinessWriteBarrierSchema.v47ApplicationTables,
        )
        assertEquals(
            exportedApplicationTables(version = 48),
            LegacyBusinessWriteBarrierSchema.v48ApplicationTables,
        )
        assertEquals(
            exportedApplicationTables(version = 49),
            LegacyBusinessWriteBarrierSchema.v49ApplicationTables,
        )
    }

    @Test
    fun everyBlockedTableHasExactlyThreeStableMutationTriggers() {
        val expected =
            LegacyBusinessWriteBarrierSchema.blockedBusinessAuthorityTables.flatMapTo(
                mutableSetOf(),
            ) { tableName ->
                LegacyBusinessMutation.entries.map { operation ->
                    LegacyBusinessWriteBarrierSchema.triggerName(tableName, operation)
                }
            }

        assertEquals(87 * 3, expected.size)
        assertEquals(expected, LegacyBusinessWriteBarrierSchema.triggerNames())
        assertEquals(3, LegacyBusinessWriteBarrierSchema.barrierProtectionTriggerNames.size)
        assertEquals(
            87 * 3 + 3,
            LegacyBusinessWriteBarrierSchema.triggerDefinitions().size,
        )
        LegacyBusinessWriteBarrierSchema.triggerDefinitions().forEach {
                (name, definition) ->
            assertEquals(name, definition.name)
            assertTrue(definition.sql.startsWith("CREATE TRIGGER `$name`"))
            assertFalse(definition.sql.contains("IF NOT EXISTS"))
        }
        LegacyBusinessWriteBarrierSchema.allowedCoordinationTables.forEach { tableName ->
            assertFalse(
                LegacyBusinessWriteBarrierSchema.triggerNames().any { triggerName ->
                    triggerName.endsWith("_$tableName")
                },
            )
        }
    }

    @Test
    fun terminalCutoverPolicyIsExactAndEndsAtMasteryAuthority() {
        assertEquals(
            (1..12).toList(),
            LegacyBusinessWriteBarrierSchema.terminalCutoverStages.map { it.ordinal },
        )
        assertEquals(
            LegacyTerminalCutoverStageExpectation(
                ordinal = 12,
                stageName = "MASTERY_AUTHORITY_VERIFIED",
                targetDatabaseName = LegacyAuthorityDatabaseName.LEARNER_MASTERY,
            ),
            LegacyBusinessWriteBarrierSchema.terminalCutoverStages.last(),
        )
    }

    @Test
    fun freshBarrierHasNoInventedTerminalCutoverProof() {
        val entity = LegacyBusinessWriteBarrierSchema.freshEmptyEntity(activatedAtEpochMillis = 41)

        assertEquals(
            LegacyBusinessWriteBarrierActivationKind.FRESH_EMPTY.name,
            entity.activationKind,
        )
        assertNull(entity.terminalStageOrdinal)
        assertNull(entity.terminalReceiptFingerprint)
        assertEquals(64, entity.activationReceiptFingerprint.length)
    }

    @Test
    fun onlyTheExactRoomV45WeakTableExportAndItsSqliteMasterFormAreRecognized() {
        val roomV45ExportedSql =
            "CREATE TABLE IF NOT EXISTS `legacy_business_write_barrier` " +
                "(`barrier_key` TEXT NOT NULL, `activation_kind` TEXT NOT NULL, " +
                "`terminal_stage_ordinal` INTEGER, " +
                "`terminal_receipt_fingerprint` TEXT, " +
                "`activation_receipt_fingerprint` TEXT NOT NULL, " +
                "`activated_at_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`barrier_key`))"
        val sqliteMasterPersistedSql =
            roomV45ExportedSql.replace(
                "CREATE TABLE IF NOT EXISTS",
                "CREATE TABLE",
            )
        val whitespaceAndQuoteVariant =
            """
            CREATE TABLE "legacy_business_write_barrier"(
                "barrier_key" TEXT NOT NULL, "activation_kind" TEXT NOT NULL,
                "terminal_stage_ordinal" INTEGER,
                "terminal_receipt_fingerprint" TEXT,
                "activation_receipt_fingerprint" TEXT NOT NULL,
                "activated_at_epoch_millis" INTEGER NOT NULL,
                PRIMARY KEY ("barrier_key")
            );
            """.trimIndent()

        assertTrue(
            LegacyBusinessWriteBarrierSchema
                .isExactKnownRoomGeneratedWeakBarrierTableSql(roomV45ExportedSql),
        )
        assertTrue(
            LegacyBusinessWriteBarrierSchema
                .isExactKnownRoomGeneratedWeakBarrierTableSql(sqliteMasterPersistedSql),
        )
        assertTrue(
            LegacyBusinessWriteBarrierSchema
                .isExactKnownRoomGeneratedWeakBarrierTableSql(whitespaceAndQuoteVariant),
        )

        val semanticChanges =
            listOf(
                roomV45ExportedSql.replace(
                    "`terminal_stage_ordinal` INTEGER,",
                    "`terminal_stage_ordinal` INTEGER NOT NULL,",
                ),
                roomV45ExportedSql.replace(
                    "`barrier_key` TEXT NOT NULL,",
                    "`barrier_key` TEXT \"NOT\" NULL,",
                ),
                roomV45ExportedSql.replace(
                    "`activation_kind` TEXT NOT NULL, " +
                        "`terminal_stage_ordinal` INTEGER,",
                    "`terminal_stage_ordinal` INTEGER, " +
                        "`activation_kind` TEXT NOT NULL,",
                ),
                roomV45ExportedSql.replace(
                    "PRIMARY KEY(`barrier_key`)",
                    "PRIMARY KEY(`barrier_key`), CHECK(`activation_kind` = 'FRESH_EMPTY')",
                ),
            )
        semanticChanges.forEach { changedSql ->
            assertFalse(
                LegacyBusinessWriteBarrierSchema
                    .isExactKnownRoomGeneratedWeakBarrierTableSql(changedSql),
            )
        }
    }

    @Test
    fun triggerComparisonNormalizesOnlyDocumentedPersistenceDifferences() {
        val triggerName =
            LegacyBusinessWriteBarrierSchema.triggerName(
                "problem",
                LegacyBusinessMutation.INSERT,
            )
        val canonical =
            LegacyBusinessWriteBarrierSchema.triggerDefinitions().getValue(triggerName).sql
        val allowed =
            listOf(
                canonical,
                "\n\t$canonical; \r\n",
                canonical.replace('`', '"'),
                canonical.replace("`", ""),
            )

        allowed.forEach { sql ->
            assertTrue(
                LegacyBusinessWriteBarrierSchema
                    .isExactCanonicalTriggerSql(triggerName, sql),
            )
        }
    }

    @Test
    fun triggerComparisonRejectsLiteralCommentsAndEveryStructuralMutation() {
        val triggerName =
            LegacyBusinessWriteBarrierSchema.triggerName(
                "problem",
                LegacyBusinessMutation.INSERT,
            )
        val canonical =
            LegacyBusinessWriteBarrierSchema.triggerDefinitions().getValue(triggerName).sql
        val key = LEGACY_BUSINESS_WRITE_BARRIER_KEY
        val message = LEGACY_BUSINESS_WRITE_REJECTED_MESSAGE
        val rejected =
            listOf(
                canonical.replace(key, "legacy-business-`write`-barrier-v1"),
                canonical.replace(key, "legacy-business-\"write\"-barrier-v1"),
                canonical.replace(message, "legacy business `authority` is read-only after cutover"),
                canonical.replace(message, "legacy business \"authority\" is read-only after cutover"),
                canonical.replace(
                    message,
                    "legacy business authority''s data is read-only after cutover",
                ),
                canonical.replace("BEFORE INSERT", "BEFORE /* boundary-1 */ INSERT"),
                canonical.replace(
                    "`$triggerName`\nBEFORE",
                    "`$triggerName` -- boundary-2\nBEFORE",
                ),
                canonical.replace("WHEN EXISTS", "WHEN /* boundary-3 */ EXISTS"),
                canonical.replace(";\nEND", "; /* boundary-4 */\nEND"),
                canonical.replace("BEFORE INSERT", "BEFORE UPDATE"),
                canonical.replace("ON `problem`", "ON `problem_revision`"),
                canonical.replace("WHEN EXISTS", "WHEN NOT EXISTS"),
                canonical.replace("WHEN EXISTS", "\"WHEN\" EXISTS"),
                canonical.replace("SELECT RAISE", "\"SELECT\" RAISE"),
                canonical.replace("SELECT RAISE", "SELECT \"RAISE\""),
                canonical.replace("RAISE(ABORT", "RAISE(FAIL"),
                canonical.replace("RAISE(ABORT", "RAISE(\"ABORT\""),
                canonical.replace("read-only", "writable"),
                canonical.replace(
                    "`barrier_key` = '$key'",
                    "`barrier_key` COLLATE BINARY = '$key'",
                ),
                canonical.replace(
                    "`barrier_key` = '$key'",
                    "'$key' = `barrier_key`",
                ),
            )

        rejected.forEach { sql ->
            assertFalse(
                LegacyBusinessWriteBarrierSchema
                    .isExactCanonicalTriggerSql(triggerName, sql),
            )
        }
    }

    @Test
    fun capabilitySurfaceContainsNoSqlOrReversalOperation() {
        assertEquals(
            setOf("activate", "read"),
            LegacyBusinessWriteBarrierCapability::class.java.declaredMethods
                .map { method -> method.name.substringBefore('-') }
                .toSet(),
        )
        assertEquals(
            setOf("activateTerminalCutover", "readState"),
            LegacyBusinessWriteBarrierOwnerPort::class.java.declaredMethods
                .map { method -> method.name.substringBefore('-') }
                .toSet(),
        )
    }

    @Test
    fun terminalPolicyRequiresEveryExactStageAndTheClaimedJournalHead() {
        val valid = terminalReceipts()
        assertEquals(
            valid.last(),
            LegacyBusinessWriteBarrierTerminalPolicy.requireProof(
                receipts = valid,
                expectedTerminalFingerprint = valid.last().receiptFingerprint,
            ),
        )

        assertThrows(LegacyBusinessWriteBarrierEligibilityException::class.java) {
            LegacyBusinessWriteBarrierTerminalPolicy.requireProof(
                receipts = valid.dropLast(1),
                expectedTerminalFingerprint = valid.last().receiptFingerprint,
            )
        }
        assertThrows(LegacyBusinessWriteBarrierEligibilityException::class.java) {
            val wrongStageName = terminalReceipts { stage ->
                if (stage.ordinal == 4) stage.copy(stageName = "ALMOST_READ_ONLY") else stage
            }
            LegacyBusinessWriteBarrierTerminalPolicy.requireProof(
                receipts = wrongStageName,
                expectedTerminalFingerprint = wrongStageName.last().receiptFingerprint,
            )
        }
        assertThrows(LegacyBusinessWriteBarrierEligibilityException::class.java) {
            LegacyBusinessWriteBarrierTerminalPolicy.requireProof(
                receipts = valid,
                expectedTerminalFingerprint = "f".repeat(64),
            )
        }
    }

    private fun terminalReceipts(
        transform: (LegacyTerminalCutoverStageExpectation) ->
            LegacyTerminalCutoverStageExpectation = { it },
    ): List<LegacyAuthorityCutoverStageReceipt> {
        var predecessor: String? = null
        return LegacyBusinessWriteBarrierSchema.terminalCutoverStages.map { rawStage ->
            val stage = transform(rawStage)
            LegacyAuthorityCutoverJournalChain.receipt(
                AppendLegacyAuthorityCutoverStageCommand(
                    stageOrdinal = stage.ordinal,
                    stageName = stage.stageName,
                    targetDatabaseName = stage.targetDatabaseName,
                    migratedRecordCount = stage.ordinal.toLong(),
                    checkpoint = "checkpoint-${stage.ordinal}",
                    destinationFingerprint = stage.ordinal.toString(16).padStart(64, '0'),
                    completedAtEpochMillis = stage.ordinal.toLong(),
                    predecessorReceiptFingerprint = predecessor,
                ),
            ).also { receipt -> predecessor = receipt.receiptFingerprint }
        }
    }

    private fun exportedApplicationTables(version: Int): Set<String> {
        val relativePath =
            "core/database/schemas/" +
                "com.tingyun.smartmistakebook.core.database.StudyDatabase/$version.json"
        val schema =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { directory ->
                directory.parentFile
            }.map { directory -> File(directory, relativePath) }
                .firstOrNull(File::isFile)
                ?: error("Cannot find exported Room schema $relativePath")
        return TABLE_NAME_JSON_PATTERN.findAll(schema.readText())
            .mapTo(mutableSetOf()) { match -> match.groupValues[1] }
    }

    private companion object {
        val TABLE_NAME_JSON_PATTERN = Regex("\"tableName\"\\s*:\\s*\"([^\"]+)\"")
    }
}
