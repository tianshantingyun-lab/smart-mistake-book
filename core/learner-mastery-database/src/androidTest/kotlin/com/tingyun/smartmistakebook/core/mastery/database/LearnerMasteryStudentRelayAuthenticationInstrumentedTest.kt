package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryStudentRelayAuthenticationInstrumentedTest {
    @Test
    fun verifiedReplayIsIdempotentAndChangedGenerationOrEpochIsRejected() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName =
                "mastery-student-auth-pin-${System.nanoTime()}.mastery-test.db"
            val databasePath = context.getDatabasePath(databaseName)
            context.deleteDatabase(databaseName)
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { RECEIVED_AT },
                ).use { store ->
                    val first =
                        committedProblemDelivery(
                            eventId = "verified-event-1",
                            generation = "student-generation-1",
                            relayEpoch = "student-relay-epoch-1",
                        )
                    assertEquals(
                        MasteryInboundDisposition.APPLIED,
                        store.acceptStudentProblemReference(first, RECEIVED_AT),
                    )
                    assertEquals(
                        MasteryInboundDisposition.DUPLICATE,
                        store.acceptStudentProblemReference(first, RECEIVED_AT + 1),
                    )
                    assertEquals(
                        MasteryInboundDisposition.CONFLICT,
                        store.acceptStudentProblemReference(
                            committedProblemDelivery(
                                eventId = "verified-event-new-generation",
                                generation = "student-generation-2",
                                relayEpoch = "student-relay-epoch-2",
                            ),
                            RECEIVED_AT + 2,
                        ),
                    )
                    assertEquals(
                        MasteryInboundDisposition.CONFLICT,
                        store.acceptStudentProblemReference(
                            committedProblemDelivery(
                                eventId = "verified-event-new-epoch",
                                generation = "student-generation-1",
                                relayEpoch = "student-relay-epoch-2",
                            ),
                            RECEIVED_AT + 3,
                        ),
                    )
                }

                SQLiteDatabase.openDatabase(
                    databasePath.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        1L,
                        sqlite.longForStudentRelayAuthQuery(
                            "SELECT COUNT(*) FROM mastery_student_relay_source_binding",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.longForStudentRelayAuthQuery(
                            "SELECT COUNT(*) FROM " +
                                "mastery_authenticated_student_inbox_receipt",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.longForStudentRelayAuthQuery(
                            "SELECT COUNT(*) FROM mastery_cross_store_inbox",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun verifiedCarrierRejectsAnEnvelopeFingerprintThatWasNotInTheSourceReceipt() {
        val envelope = committedProblemEnvelope("verified-carrier-mismatch", "student-generation-1")
        val raw = envelope.rawRelayMessage()
        assertThrows(SecurityException::class.java) {
            VerifiedStudentMistakeDelivery(
                envelope,
                LOCAL_LEARNER_ID,
                envelope.sourceStoreGeneration,
                "student-relay-epoch-1",
                TEST_KEY_ID,
                StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                raw.authenticityProof.canonicalFingerprint,
                "f".repeat(64),
                "e".repeat(64),
            )
        }
    }
}

private fun committedProblemDelivery(
    eventId: String,
    generation: String,
    relayEpoch: String,
): VerifiedStudentMistakeDelivery {
    val envelope = committedProblemEnvelope(eventId, generation)
    val raw = envelope.rawRelayMessage()
    return VerifiedStudentMistakeDelivery(
        envelope,
        LOCAL_LEARNER_ID,
        generation,
        relayEpoch,
        TEST_KEY_ID,
        StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
        raw.authenticityProof.canonicalFingerprint,
        envelope.canonicalFingerprint,
        CanonicalSha256("student-outbox-verification-receipt-v1")
            .field("learnerId", LOCAL_LEARNER_ID)
            .field("sourceStoreGeneration", generation)
            .field("relayEpoch", relayEpoch)
            .field("issuerKeyId", TEST_KEY_ID)
            .field("algorithmVersion", StudentOutboxAuthenticityProof.ALGORITHM_VERSION)
            .field("envelopeCanonicalFingerprint", envelope.canonicalFingerprint)
            .field("proofCanonicalFingerprint", raw.authenticityProof.canonicalFingerprint)
            .finish(),
    )
}

private fun committedProblemEnvelope(
    eventId: String,
    generation: String,
): CrossStoreEventEnvelope {
    val revision =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = LOCAL_LEARNER_ID,
                    subject = SubjectKind.MATH,
                    problemId = "problem:$eventId",
                    practiceUnitId = "practice:$eventId",
                ),
            revisionId = "revision:$eventId",
            revisionNumber = 1,
            documentCanonicalFingerprint = "a".repeat(64),
        )
    val payload =
        ProblemRevisionCommittedV1(
            revision = revision,
            commitReceiptId = "commit:$eventId",
            commitReceiptCanonicalFingerprint = "b".repeat(64),
            committedAtEpochMillis = 100L,
        )
    return CrossStoreEventEnvelope(
        eventId = eventId,
        sourceStore = StudyStoreKind.STUDENT_MISTAKES,
        destinationStore = StudyStoreKind.LEARNER_MASTERY,
        aggregateId = payload.aggregateId,
        aggregateVersion = 1L,
        occurredAtEpochMillis = payload.occurredAtEpochMillis,
        idempotencyKey = "idempotency:$eventId",
        sourceStoreGeneration = generation,
        payload = payload,
    )
}

private fun CrossStoreEventEnvelope.rawRelayMessage(): StudentMistakeRelayMessage =
    StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
        this,
        StudentOutboxAuthenticityProof(
            protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
            algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            issuerKeyId = TEST_KEY_ID,
            learnerId = LOCAL_LEARNER_ID,
            envelopeCanonicalFingerprint = canonicalFingerprint,
            tagHex = "0".repeat(64),
        ),
    )

private fun SQLiteDatabase.longForStudentRelayAuthQuery(sql: String): Long =
    rawQuery(sql, emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private const val TEST_KEY_ID = "student-test-key"
private const val RECEIVED_AT = 1_000L
