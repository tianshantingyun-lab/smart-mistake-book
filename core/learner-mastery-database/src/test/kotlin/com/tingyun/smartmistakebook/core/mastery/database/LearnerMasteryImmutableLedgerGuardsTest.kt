package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryImmutableLedgerGuardsTest {
    @Test
    fun currentGuardSetHasOneCanonicalDefinitionForEveryOperation() {
        val definitions = learnerMasteryImmutableLedgerTriggerDefinitions()

        assertEquals(
            LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES.size * 2 +
                TERMINAL_INSERT_GUARD_COUNT +
                AUDIT_METADATA_INSERT_GUARD_COUNT,
            definitions.size,
        )
        assertEquals(definitions.size, definitions.map { it.name }.distinct().size)
        assertTrue(
            LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES.all { tableName ->
                definitions.any { it.name == "immutable_${tableName}_update" } &&
                    definitions.any { it.name == "immutable_${tableName}_delete" }
            },
        )
        assertTrue(
            definitions.none {
                canonicalizeLearnerMasterySql(it.sql)
                    .contains("IF NOT EXISTS", ignoreCase = true)
            },
        )
    }

    @Test
    fun canonicalSqlComparisonNormalizesTokensButPreservesQuotedContent() {
        val compact =
            "CREATE TRIGGER guard BEFORE UPDATE ON sample " +
                "BEGIN SELECT RAISE(ABORT, 'two  spaces'); END"
        val formatted =
            """
            CREATE   TRIGGER guard
            BEFORE UPDATE ON sample
            BEGIN
                SELECT RAISE(ABORT, 'two  spaces');
            END
            """.trimIndent()
        val changedLiteral = formatted.replace("'two  spaces'", "'two spaces'")

        assertEquals(
            canonicalizeLearnerMasterySql(compact),
            canonicalizeLearnerMasterySql(formatted),
        )
        assertNotEquals(
            canonicalizeLearnerMasterySql(formatted),
            canonicalizeLearnerMasterySql(changedLiteral),
        )
        assertFalse(
            canonicalizeLearnerMasterySql("SELECT [two  spaces], \"a  b\", `c  d`")
                .contains("[two spaces]"),
        )
    }

    @Test
    fun canonicalSqlComparisonAcceptsPlatformFormattingButStillChecksTriggerSemantics() {
        val canonical =
            """
            CREATE TRIGGER immutable_sample_update
            BEFORE UPDATE ON sample
            BEGIN
                SELECT RAISE(ABORT, 'immutable learner-mastery record');
            END
            """.trimIndent()
        val platformFormatted =
            """
            create trigger immutable_sample_update before update on sample
            begin select raise ( abort , 'immutable learner-mastery record' ) ; end ;
            """.trimIndent()

        assertEquals(
            canonicalizeLearnerMasterySql(canonical),
            canonicalizeLearnerMasterySql(platformFormatted),
        )
        assertNotEquals(
            canonicalizeLearnerMasterySql(canonical),
            canonicalizeLearnerMasterySql(platformFormatted.replace("before update", "before delete")),
        )
        assertNotEquals(
            canonicalizeLearnerMasterySql(canonical),
            canonicalizeLearnerMasterySql(platformFormatted.replace("on sample", "on other_table")),
        )
        assertNotEquals(
            canonicalizeLearnerMasterySql(canonical),
            canonicalizeLearnerMasterySql(platformFormatted.replace("immutable learner", "mutable learner")),
        )
        assertNotEquals(
            canonicalizeLearnerMasterySql(canonical),
            canonicalizeLearnerMasterySql(
                platformFormatted.replace(
                    "create trigger",
                    "create trigger if not exists",
                ),
            ),
        )
    }

    @Test
    fun metadataInsertGuardRejectsReplaceWithADifferentExistingValue() {
        val sql =
            canonicalizeLearnerMasterySql(
                learnerMasteryCalibrationAuditMetadataInsertTriggerDefinition().sql,
            )

        assertTrue(
            sql.contains(canonicalizeLearnerMasterySql("before insert on mastery_store_metadata")),
        )
        assertTrue(
            sql.contains(canonicalizeLearnerMasterySql("existing.metadata_key = new.metadata_key")),
        )
        assertTrue(
            sql.contains(
                canonicalizeLearnerMasterySql(
                    "existing.metadata_value is not new.metadata_value",
                ),
            ),
        )
    }

    private companion object {
        const val TERMINAL_INSERT_GUARD_COUNT = 6
        const val AUDIT_METADATA_INSERT_GUARD_COUNT = 1
    }
}
