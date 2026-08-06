package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryPresentationFingerprintBudgetEpochContractTest {
    @Test
    fun epochUsesIndependentAppendOnlyMarkersWithoutChangingTheProjectionPolicy() {
        assertEquals("learner-mastery-projection-v3", LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
        assertNotEquals(
            PRESENTATION_FINGERPRINT_BUDGET_REBUILD_REQUIRED_METADATA_KEY,
            PRESENTATION_FINGERPRINT_BUDGET_REBUILD_COMPLETED_METADATA_KEY,
        )
        assertTrue(PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH.isNotBlank())
        assertEquals(14, LEARNER_MASTERY_MIGRATION_14_15.startVersion)
        assertEquals(15, LEARNER_MASTERY_MIGRATION_14_15.endVersion)
    }

    @Test
    fun everyBuildingGenerationGetsItsOwnImmutableEpochBindingKey() {
        val first = presentationFingerprintBudgetGenerationEpochMetadataKey(1L)
        val second = presentationFingerprintBudgetGenerationEpochMetadataKey(2L)

        assertNotEquals(first, second)
        assertTrue(first.endsWith(":1"))
        assertTrue(second.endsWith(":2"))
        assertTrue(
            runCatching {
                presentationFingerprintBudgetGenerationEpochMetadataKey(0L)
            }.isFailure,
        )
    }
}
