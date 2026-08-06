package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryCalibrationInfrastructureContractTest {
    @Test
    fun currentSchemaDoesNotActivateAnUnreleasedProjectionOrCalibrationPolicy() {
        assertEquals(22, LEARNER_MASTERY_DATABASE_VERSION)
        assertEquals(
            "learner-mastery-projection-v3",
            LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        )
        assertEquals(
            "learner-mastery-calibration-v4",
            LEARNER_MASTERY_CALIBRATION_VERSION,
        )
    }

    @Test
    fun projectionInputFactsContainEligibilityButNoWeightOrMasteryOutput() {
        val fieldNames =
            MasteryProjectionInputFactEntity::class.java.declaredFields
                .map { field -> field.name.lowercase() }

        assertTrue(fieldNames.any { field -> "eligibility" in field })
        assertTrue(
            fieldNames.none { field ->
                "weight" in field ||
                    "coefficient" in field ||
                    "probability" in field ||
                    "masteryscore" in field ||
                    "logodds" in field
            },
        )
    }

    @Test
    fun everyDb14CalibrationFoundationTableIsAppendOnly() {
        assertTrue(
            LEARNER_MASTERY_DB14_IMMUTABLE_TABLE_NAMES.isNotEmpty() &&
                LEARNER_MASTERY_DB14_IMMUTABLE_TABLE_NAMES.all(
                    LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES::contains,
                ),
        )
    }
}
