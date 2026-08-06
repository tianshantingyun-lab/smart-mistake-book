package com.tingyun.smartmistakebook.core.mastery.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryOpenResponseLegacyQuarantineContractTest {
    @Test
    fun quarantineIsASeparateImmutableNegativeAuthorizationLedger() {
        assertEquals(22, LEARNER_MASTERY_DATABASE_VERSION)
        assertTrue(
            LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE in
                LEARNER_MASTERY_TABLE_NAMES,
        )
        assertTrue(
            LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE in
                LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES,
        )
        val fields =
            MasteryOpenResponseLegacyQuarantineEntity::class.java.declaredFields
                .map { field -> field.name.lowercase() }
        assertTrue(fields.none { field -> "model" in field || "provider" in field })
    }

    @Test
    fun canonicalQuarantineBindsEventDecisionPolicyReasonAndCutoff() {
        val baseline =
            openResponseLegacyQuarantineFingerprint(
                acceptedEventId = "event-a",
                eventCanonicalFingerprint = fingerprint("event"),
                decisionFingerprint = fingerprint("decision"),
                quarantinedAtEpochMillis = 100L,
            )

        assertEquals(
            baseline,
            openResponseLegacyQuarantineFingerprint(
                acceptedEventId = "event-a",
                eventCanonicalFingerprint = fingerprint("event"),
                decisionFingerprint = fingerprint("decision"),
                quarantinedAtEpochMillis = 100L,
            ),
        )
        assertNotEquals(
            baseline,
            openResponseLegacyQuarantineFingerprint(
                acceptedEventId = "event-a",
                eventCanonicalFingerprint = fingerprint("event"),
                decisionFingerprint = fingerprint("other-decision"),
                quarantinedAtEpochMillis = 100L,
            ),
        )
        assertNotEquals(
            baseline,
            openResponseLegacyQuarantineFingerprint(
                acceptedEventId = "event-a",
                eventCanonicalFingerprint = fingerprint("event"),
                decisionFingerprint = fingerprint("decision"),
                quarantinedAtEpochMillis = 101L,
            ),
        )
    }

    private fun fingerprint(seed: String): String =
        com.tingyun.smartmistakebook.core.model.CanonicalSha256(
            "learner-mastery-open-response-quarantine-contract-test",
        ).field("seed", seed).finish()
}
