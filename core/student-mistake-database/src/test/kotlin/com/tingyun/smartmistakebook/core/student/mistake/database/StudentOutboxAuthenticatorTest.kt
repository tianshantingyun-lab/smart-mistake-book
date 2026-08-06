package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentOutboxAuthenticatorTest {
    @Test
    fun `proof binds every immutable review and envelope authority field`() {
        val fixture = Fixture()
        val original = fixture.envelope()
        val originalContext = fixture.contextFingerprint(original)
        val payload = original.payload as ReviewObservationCapturedV2
        val changedPayloads =
            listOf(
                payload.copy(verificationOutcome = ReviewVerificationOutcome.INCORRECT),
                payload.copy(responseOpaqueBinding = "f".repeat(64)),
                payload.copy(attemptOrdinal = 2),
                payload.copy(hintCount = 1),
                payload.copy(answerWasRevealed = true),
                payload.copy(reviewSessionId = "review-session-2"),
                payload.copy(reviewQueueItemId = "queue-item-2"),
                payload.copy(presentationId = "presentation-2"),
                payload.copy(submissionId = "submission-2"),
                payload.copy(capturedAtEpochMillis = payload.capturedAtEpochMillis + 1L),
            )
        val changedEnvelopes =
            buildList {
                changedPayloads.forEach { changed ->
                    add(
                        original.copy(
                            aggregateId = changed.aggregateId,
                            aggregateVersion = changed.attemptOrdinal.toLong(),
                            occurredAtEpochMillis = changed.occurredAtEpochMillis,
                            payload = changed,
                            payloadType = changed.payloadType,
                            payloadVersion = changed.payloadVersion,
                            payloadCanonicalFingerprint = changed.payloadCanonicalFingerprint,
                        ),
                    )
                }
                add(original.copy(eventId = "event-forged"))
                add(original.copy(idempotencyKey = "idempotency-forged"))
                add(original.copy(sourceStoreGeneration = "student-generation-v2"))
                add(original.copy(aggregateVersion = original.aggregateVersion + 1L))
            }

        changedEnvelopes.forEach { changed ->
            assertNotEquals(originalContext, fixture.contextFingerprint(changed))
        }
    }

    @Test
    fun `arbitrary structural proof cannot forge an owner message`() {
        val fixture = Fixture()
        val envelope = fixture.envelope()
        val forgedProof =
            StudentOutboxAuthenticityProof(
                protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
                algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                issuerKeyId = fixture.activeKey.keyId,
                learnerId = LEARNER_ID,
                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                tagHex = "0".repeat(64),
            )
        val message =
            StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(envelope, forgedProof)

        assertTrue(runCatching { fixture.authenticator.verifier.requireAuthentic(message) }.isFailure)
    }

    @Test
    fun `proof fails after outcome tamper and row swap`() {
        val fixture = Fixture()
        val first = fixture.envelope()
        val firstMessage = fixture.authenticator.attest(first)
        val second =
            fixture.envelope(
                observationId = "observation-2",
                submissionId = "submission-2",
                eventId = "event-2",
                idempotencyKey = "idempotency-2",
            )
        val secondMessage = fixture.authenticator.attest(second)
        val changedPayload =
            (first.payload as ReviewObservationCapturedV2).copy(
                verificationOutcome = ReviewVerificationOutcome.INCORRECT,
            )
        val changedEnvelope =
            first.copy(
                payload = changedPayload,
                payloadType = changedPayload.payloadType,
                payloadVersion = changedPayload.payloadVersion,
                payloadCanonicalFingerprint = changedPayload.payloadCanonicalFingerprint,
            )
        val tamperedMessage =
            StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
                changedEnvelope,
                firstMessage.authenticityProof.copy(
                    envelopeCanonicalFingerprint = changedEnvelope.canonicalFingerprint,
                ),
            )
        val swappedMessage =
            StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
                second,
                firstMessage.authenticityProof.copy(
                    envelopeCanonicalFingerprint = second.canonicalFingerprint,
                ),
            )

        assertTrue(
            runCatching { fixture.authenticator.verifier.requireAuthentic(tamperedMessage) }
                .isFailure,
        )
        assertTrue(
            runCatching { fixture.authenticator.verifier.requireAuthentic(swappedMessage) }
                .isFailure,
        )
        fixture.authenticator.verifier.requireAuthentic(secondMessage)
    }

    @Test
    fun `exact replay verifies after process recreation`() {
        val fixture = Fixture()
        val message = fixture.authenticator.attest(fixture.envelope())
        val recreated =
            StudentOutboxAuthenticator(
                LEARNER_ID,
                fixture.activeKey,
                fixture.keyStore,
            )

        fixture.authenticator.verifier.requireAuthentic(message)
        recreated.verifier.requireAuthentic(message)
        assertEquals(
            message.authenticityProof.tagHex,
            recreated.attest(message.envelope).authenticityProof.tagHex,
        )
    }

    @Test
    fun `key loss fails closed without creating a replacement`() {
        val fixture = Fixture()
        val message = fixture.authenticator.attest(fixture.envelope())
        fixture.keyStore.key = null

        assertTrue(
            runCatching {
                fixture.authenticator.attest(fixture.envelope())
            }.isFailure,
        )
        assertTrue(
            runCatching { fixture.authenticator.verifier.requireAuthentic(message) }.isFailure,
        )
        assertEquals(0, fixture.keyStore.bootstrapCreationCount)
    }

    @Test
    fun `bootstrap creates only without durable state and rejects missing active alias`() =
        runBlocking {
            val keyStore = FakeHmacKeyStore(key = null)
            val statePort = MemoryKeyStatePort()
            val bootstrap =
                StudentOutboxAuthenticityKeyBootstrap(
                    statePort = statePort,
                    keyStore = keyStore,
                    sourceStoreGeneration = SOURCE_GENERATION,
                    nowEpochMillis = { 100L },
                    newKeyId = { "student-outbox-auth-key:test" },
                    newKeyAlias = { TEST_KEY_ALIAS },
                    newRelayEpoch = { RELAY_EPOCH },
                )

            val first = bootstrap.loadOrProvision()
            val second = bootstrap.loadOrProvision()

            assertEquals(first, second)
            assertEquals(1, keyStore.bootstrapCreationCount)
            keyStore.key = null
            assertTrue(runCatching { bootstrap.loadOrProvision() }.isFailure)
            assertEquals(1, keyStore.bootstrapCreationCount)
        }

    @Test
    fun `bootstrap sentinel binds random key identity generation and relay epoch`() =
        runBlocking {
            val keyStore = FakeHmacKeyStore(key = null)
            val statePort = MemoryKeyStatePort()
            val bootstrap =
                StudentOutboxAuthenticityKeyBootstrap(
                    statePort = statePort,
                    keyStore = keyStore,
                    sourceStoreGeneration = SOURCE_GENERATION,
                    nowEpochMillis = { 101L },
                    newKeyId = { "student-outbox-auth-key:random-fixture" },
                    newKeyAlias = { TEST_KEY_ALIAS },
                    newRelayEpoch = { RELAY_EPOCH },
                )

            val active = bootstrap.loadOrProvision()
            assertEquals("student-outbox-auth-key:random-fixture", active.keyId)
            assertEquals(TEST_KEY_ALIAS, active.keyAlias)
            assertEquals(SOURCE_GENERATION, active.sourceStoreGeneration)
            assertEquals(RELAY_EPOCH, active.relayEpoch)

            statePort.state =
                checkNotNull(statePort.state).copy(keyedSentinelTag = "0".repeat(64))
            assertTrue(runCatching { bootstrap.loadOrProvision() }.isFailure)

            statePort.state = null
            bootstrap.loadOrProvision()
            val wrongGeneration =
                StudentOutboxAuthenticityKeyBootstrap(
                    statePort = statePort,
                    keyStore = keyStore,
                    sourceStoreGeneration = "different-generation",
                    nowEpochMillis = { 102L },
                    newKeyId = { "student-outbox-auth-key:other" },
                    newKeyAlias = { TEST_KEY_ALIAS },
                    newRelayEpoch = { "other-relay-epoch" },
                )
            assertTrue(runCatching { wrongGeneration.loadOrProvision() }.isFailure)
        }

    @Test
    fun `proof cannot cross learner scope`() {
        val fixture = Fixture()
        val message = fixture.authenticator.attest(fixture.envelope())
        val otherLearner =
            StudentOutboxAuthenticator(
                "learner-2",
                fixture.activeKey,
                fixture.keyStore,
            )

        assertTrue(runCatching { otherLearner.verifier.requireAuthentic(message) }.isFailure)
    }

    @Test
    fun `authenticator rejects a source generation switch after binding`() {
        val fixture = Fixture()
        val message = fixture.authenticator.attest(fixture.envelope())
        val switched = fixture.envelope().copy(sourceStoreGeneration = "student-generation-v2")

        assertTrue(
            runCatching {
                fixture.authenticator.attest(switched)
            }.isFailure,
        )
        fixture.authenticator.verifier.requireAuthentic(message)
    }

    @Test
    fun `review bindings use a separate domain and bind generation relay epoch and response`() {
        val fixture = Fixture()
        val scope = "a".repeat(64)
        val responseTag = fixture.authenticator.issueReviewResponseBinding(scope, "choice\u001fC")
        val repeated = fixture.authenticator.issueReviewResponseBinding(scope, "choice\u001fC")
        val changedResponse = fixture.authenticator.issueReviewResponseBinding(scope, "choice\u001fD")
        val changedEpoch =
            StudentOutboxAuthenticator(
                LEARNER_ID,
                fixture.activeKey.copy(relayEpoch = "relay-epoch-v2"),
                fixture.keyStore,
            ).issueReviewResponseBinding(scope, "choice\u001fC")
        val changedGeneration =
            StudentOutboxAuthenticator(
                LEARNER_ID,
                fixture.activeKey.copy(sourceStoreGeneration = "student-generation-v2"),
                fixture.keyStore,
            ).issueReviewResponseBinding(scope, "choice\u001fC")
        val envelope = fixture.envelope()
        val outboxTag = fixture.authenticator.attest(envelope).authenticityProof.tagHex

        assertEquals(responseTag, repeated)
        assertNotEquals(responseTag, changedResponse)
        assertNotEquals(responseTag, changedEpoch)
        assertNotEquals(responseTag, changedGeneration)
        assertNotEquals(responseTag, outboxTag)

        val swappedProof =
            fixture.authenticator.attest(envelope).authenticityProof.copy(tagHex = responseTag)
        val swapped = StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(envelope, swappedProof)
        val failure =
            runCatching { fixture.authenticator.verifier.requireAuthentic(swapped) }.exceptionOrNull()
        assertTrue(failure is StudentOutboxInvalidProofException)
    }

    @Test
    fun `verification infrastructure failure is retryable and not a row rejection`() {
        val fixture = Fixture()
        val message = fixture.authenticator.attest(fixture.envelope())
        fixture.keyStore.failHmac = true

        val failure =
            runCatching { fixture.authenticator.verifier.requireAuthentic(message) }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertTrue(failure !is StudentOutboxInvalidProofException)
    }

    private class Fixture {
        val keyStore = FakeHmacKeyStore()
        val activeKey =
            ActiveStudentOutboxAuthenticityKey(
                keyId = "student-outbox-auth-key:test",
                keyVersion = StudentOutboxAuthenticator.KEY_VERSION,
                keyAlias = TEST_KEY_ALIAS,
                algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                sourceStoreGeneration = SOURCE_GENERATION,
                relayEpoch = RELAY_EPOCH,
            )
        val authenticator =
            StudentOutboxAuthenticator(
                LEARNER_ID,
                activeKey,
                keyStore,
            )

        fun contextFingerprint(envelope: CrossStoreEventEnvelope): String =
            StudentOutboxAuthenticator.authenticatedContextFingerprint(
                envelope,
                LEARNER_ID,
                activeKey.keyId,
                StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            )

        fun envelope(
            observationId: String = "observation-1",
            submissionId: String = "submission-1",
            eventId: String = "event-1",
            idempotencyKey: String = "idempotency-1",
        ): CrossStoreEventEnvelope {
            val payload =
                ReviewObservationCapturedV2(
                    problemRevision = revision(),
                    reviewSessionId = "review-session-1",
                    reviewQueueItemId = "queue-item-1",
                    observationId = observationId,
                    submissionId = submissionId,
                    presentationId = "presentation-1",
                    responseForm = ReviewResponseForm.CHOICE,
                    responseOpaqueBinding = "b".repeat(64),
                    responseBindingAlgorithmVersion =
                        "android-keystore-hmac-sha256-v1",
                    verificationOutcome = ReviewVerificationOutcome.CORRECT,
                    attemptOrdinal = 1,
                    hintCount = 0,
                    answerWasRevealed = false,
                    verificationPolicyVersion = "student-review-owner-v2:fixture",
                    elapsedDurationMillis = 1_000L,
                    capturedAtEpochMillis = 1_000L,
                )
            return CrossStoreEventEnvelope(
                eventId = eventId,
                sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                destinationStore = StudyStoreKind.LEARNER_MASTERY,
                aggregateId = payload.aggregateId,
                aggregateVersion = payload.attemptOrdinal.toLong(),
                occurredAtEpochMillis = payload.occurredAtEpochMillis,
                idempotencyKey = idempotencyKey,
                sourceStoreGeneration = SOURCE_GENERATION,
                payload = payload,
            )
        }
    }

    private class FakeHmacKeyStore(
        var key: SecretKey? = TEST_KEY,
    ) : StudentOutboxHmacKeyStore() {
        var bootstrapCreationCount: Int = 0
        var failHmac: Boolean = false

        override fun contains(keyAlias: String): Boolean = key != null

        override fun loadOrCreateBootstrap(keyAlias: String) {
            if (key != null) return
            bootstrapCreationCount += 1
            key = TEST_KEY
        }

        override fun issueHmac(
            keyAlias: String,
            authenticatedContextFingerprint: String,
        ): String {
            if (failHmac) throw SecurityException("test key temporarily unavailable")
            val current = checkNotNull(key)
            return Mac.getInstance("HmacSHA256").run {
                init(current)
                update(StudentOutboxAuthenticityProof.MAC_DOMAIN.toByteArray(StandardCharsets.UTF_8))
                update(0)
                doFinal(authenticatedContextFingerprint.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            }
        }

        override fun issueReviewResponseBinding(
            keyAlias: String,
            authenticatedBindingContextFingerprint: String,
        ): String {
            if (failHmac) throw SecurityException("test key temporarily unavailable")
            return StudentReviewResponseHmac.issue(
                checkNotNull(key),
                authenticatedBindingContextFingerprint,
            )
        }
    }

    private class MemoryKeyStatePort : StudentOutboxAuthenticityKeyStatePort {
        var state: StudentOutboxAuthenticityKeyStateEntity? = null

        override suspend fun read(): StudentOutboxAuthenticityKeyStateEntity? = state

        override suspend fun insertIfAbsent(
            state: StudentOutboxAuthenticityKeyStateEntity,
        ): Boolean {
            if (this.state != null) return false
            this.state = state
            return true
        }
    }

    companion object {
        private const val LEARNER_ID = "learner-1"
        private const val SOURCE_GENERATION = "student-generation-v1"
        private const val RELAY_EPOCH = "relay-epoch-v1"
        private const val TEST_KEY_ALIAS =
            "com.tingyun.smartmistakebook.student.outbox.authenticity.hmac.test"
        private val TEST_KEY =
            SecretKeySpec(
                ByteArray(32) { index -> (index + 1).toByte() },
                "HmacSHA256",
            )

        private fun revision(): StudentProblemRevisionRef =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        problemId = "problem-1",
                        practiceUnitId = "practice-1",
                    ),
                revisionId = "revision-1",
                revisionNumber = 1,
                documentCanonicalFingerprint = "a".repeat(64),
            )
    }
}
