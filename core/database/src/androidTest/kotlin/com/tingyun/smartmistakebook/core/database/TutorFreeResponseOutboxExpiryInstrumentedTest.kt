package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.entity.TutorFreeResponseOutboxEntity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorFreeResponseOutboxExpiryInstrumentedTest {
    @Test
    fun constructionArmExpiresIdleRowByCapturedCutoffDespiteWallClockRollback(): Unit =
        runBlocking {
            val clock = AtomicLong(10_000L)
            val factory = ManualExpirySchedulerFactory()
            StudyDatabaseFactory.openInMemory(
                context = context(),
                freeResponseOutboxCipher = NoOpOutboxCipher,
                clock = clock::get,
                freeResponseOutboxExpirySchedulerFactory = factory,
            ).use { store ->
                createAuthorityConversation(store)
                val row = pendingOutbox("idle", discardAfter = 11_000L)
                store.database.currentTutorInteractionSessionDao().insertFreeResponseOutbox(row)

                assertEquals(1, factory.scheduler.refreshRequestCount)
                factory.scheduler.drainRefresh()
                assertEquals(11_000L, factory.scheduler.armedCutoffEpochMillis)

                clock.set(1_000L)
                assertTrue(factory.scheduler.fireArmedCutoff())
                assertTerminallyWiped(store, row)
            }
        }

    @Test
    fun earlierDeadlineReplacesArmAndTerminalRefreshCancelsWithoutExtraFire(): Unit = runBlocking {
        val factory = ManualExpirySchedulerFactory()
        StudyDatabaseFactory.openInMemory(
            context = context(),
            freeResponseOutboxCipher = NoOpOutboxCipher,
            clock = { 10_000L },
            freeResponseOutboxExpirySchedulerFactory = factory,
        ).use { store ->
            createAuthorityConversation(store)
            val later = pendingOutbox("later", discardAfter = 30_000L)
            val earlier = pendingOutbox("earlier", discardAfter = 20_000L)
            val dao = store.database.currentTutorInteractionSessionDao()
            dao.insertFreeResponseOutbox(later)
            factory.scheduler.drainRefresh()
            assertEquals(30_000L, factory.scheduler.armedCutoffEpochMillis)

            dao.insertFreeResponseOutbox(earlier)
            factory.scheduler.requestRefresh()
            factory.scheduler.drainRefresh()
            assertEquals(20_000L, factory.scheduler.armedCutoffEpochMillis)

            dao.failClosedPendingFreeResponseOutboxesThrough(30_000L)
            factory.scheduler.requestRefresh()
            factory.scheduler.drainRefresh()
            assertNull(factory.scheduler.armedCutoffEpochMillis)
            assertFalse(factory.scheduler.fireArmedCutoff())
            assertEquals(0, factory.scheduler.fireCount)
        }
    }

    @Test
    fun inconsistentInFlightLeaseTupleIsArmedForImmediateFailClosedWipe(): Unit = runBlocking {
        val factory = ManualExpirySchedulerFactory()
        StudyDatabaseFactory.openInMemory(
            context = context(),
            freeResponseOutboxCipher = NoOpOutboxCipher,
            clock = { 10_000L },
            freeResponseOutboxExpirySchedulerFactory = factory,
        ).use { store ->
            createAuthorityConversation(store)
            val splitLease = pendingOutbox("split", discardAfter = 90_000L).copy(
                status = "IN_FLIGHT",
                dispatchAttemptCount = 1,
                leaseOwnerId = "owner",
                leaseGenerationId = null,
                leaseToken = SHA_B,
                leaseExpiresAtEpochMillis = 50_000L,
            )
            store.database.currentTutorInteractionSessionDao()
                .insertFreeResponseOutbox(splitLease)

            factory.scheduler.drainRefresh()
            assertEquals(0L, factory.scheduler.armedCutoffEpochMillis)
            assertTrue(factory.scheduler.fireArmedCutoff())
            assertTerminallyWiped(store, splitLease)
        }
    }

    @Test
    fun databaseCloseClosesAndJoinsSchedulerBeforeClosingRoom(): Unit = runBlocking {
        val factory = ManualExpirySchedulerFactory()
        val store = StudyDatabaseFactory.openInMemory(
            context = context(),
            freeResponseOutboxCipher = NoOpOutboxCipher,
            clock = { 10_000L },
            freeResponseOutboxExpirySchedulerFactory = factory,
        )
        createAuthorityConversation(store)
        store.database.currentTutorInteractionSessionDao().insertFreeResponseOutbox(
            pendingOutbox("close", discardAfter = 20_000L),
        )
        factory.scheduler.drainRefresh()
        assertEquals(20_000L, factory.scheduler.armedCutoffEpochMillis)

        store.close()

        assertTrue(factory.scheduler.closed)
        assertFalse(factory.scheduler.fireArmedCutoff())
        assertEquals(0, factory.scheduler.taskCallsAfterClose)
    }

    private suspend fun createAuthorityConversation(store: RoomStudyDatabase) {
        store.createTutorConversation(
            CreateTutorConversationCommand(
                conversationId = AUTHORITY_CONVERSATION,
                learnerId = LEARNER,
                generation = 1L,
                idempotencyKey = "create-expiry-test-authority",
                payloadFingerprint = SHA_A,
            ),
        )
    }

    private fun pendingOutbox(
        suffix: String,
        discardAfter: Long,
    ) = TutorFreeResponseOutboxEntity(
        learnerId = LEARNER,
        sessionId = "visible-session",
        authorityConversationId = AUTHORITY_CONVERSATION,
        conversationGeneration = 1L,
        actionToken = "action-$suffix",
        actionExpiresAtEpochMillis = 15_000L,
        workId = "work-$suffix",
        workStateVersion = 1L,
        workStateFingerprint = SHA_A,
        presentationToken = "presentation-$suffix",
        evidenceRequestId = "evidence-$suffix",
        answerBinding = SHA_B,
        payloadFingerprint = SHA_A,
        canonicalOccurredAtEpochMillis = 10_000L,
        status = "NEEDS_DISPATCH",
        encryptedAnswer = byteArrayOf(9, 8, 7),
        nonce = byteArrayOf(6, 5, 4),
        keyVersion = 1,
        dispatchAttemptCount = 0,
        nextDispatchAtEpochMillis = 10_000L,
        discardAfterEpochMillis = discardAfter,
        leaseOwnerId = null,
        leaseGenerationId = null,
        leaseToken = null,
        leaseExpiresAtEpochMillis = null,
        candidateIdempotencyKey = null,
        candidateReceiptFingerprint = null,
        claimedAtEpochMillis = 10_000L,
        completedAtEpochMillis = null,
        updatedAtEpochMillis = 10_000L,
    )

    private suspend fun assertTerminallyWiped(
        store: RoomStudyDatabase,
        expected: TutorFreeResponseOutboxEntity,
    ) {
        val actual = store.database.currentTutorInteractionSessionDao().readFreeResponseOutbox(
            expected.learnerId,
            expected.sessionId,
            expected.actionToken,
        )
        requireNotNull(actual)
        assertEquals("FAILED_CLOSED", actual.status)
        assertNull(actual.encryptedAnswer)
        assertNull(actual.nonce)
        assertNull(actual.leaseOwnerId)
        assertNull(actual.leaseGenerationId)
        assertNull(actual.leaseToken)
        assertNull(actual.leaseExpiresAtEpochMillis)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class ManualExpirySchedulerFactory :
        TutorFreeResponseOutboxExpirySchedulerFactory {
        lateinit var scheduler: ManualExpiryScheduler

        override fun create(
            clock: () -> Long,
            task: TutorFreeResponseOutboxExpiryTask,
        ): TutorFreeResponseOutboxExpiryScheduler =
            ManualExpiryScheduler(task).also { scheduler = it }
    }

    private class ManualExpiryScheduler(
        private val task: TutorFreeResponseOutboxExpiryTask,
    ) : TutorFreeResponseOutboxExpiryScheduler {
        var armedCutoffEpochMillis: Long? = null
            private set
        var refreshRequestCount: Int = 0
            private set
        var fireCount: Int = 0
            private set
        var taskCallsAfterClose: Int = 0
            private set
        var closed: Boolean = false
            private set
        private var refreshPending: Boolean = false

        override fun requestRefresh() {
            if (closed) return
            refreshPending = true
            refreshRequestCount += 1
        }

        suspend fun drainRefresh() {
            if (closed || !refreshPending) return
            refreshPending = false
            armedCutoffEpochMillis = callTask { task.nextPendingCutoffEpochMillis() }
        }

        suspend fun fireArmedCutoff(): Boolean {
            if (closed) return false
            val capturedCutoff = armedCutoffEpochMillis ?: return false
            armedCutoffEpochMillis = null
            callTask { task.wipePendingOutboxesThrough(capturedCutoff) }
            fireCount += 1
            requestRefresh()
            drainRefresh()
            return true
        }

        override fun closeAndJoin() {
            closed = true
            refreshPending = false
            armedCutoffEpochMillis = null
        }

        private suspend fun <T> callTask(block: suspend () -> T): T {
            if (closed) taskCallsAfterClose += 1
            return block()
        }
    }

    private object NoOpOutboxCipher : TutorFreeResponseOutboxCipher {
        override fun encrypt(
            aad: ByteArray,
            plaintext: String,
        ) = TutorFreeResponseEncryptedAnswer(1, byteArrayOf(1), byteArrayOf(2))

        override fun decrypt(
            aad: ByteArray,
            encrypted: TutorFreeResponseEncryptedAnswer,
        ): String? = null

        override fun answerBinding(bindingContext: ByteArray, plaintext: String): String = SHA_A
    }

    private companion object {
        const val LEARNER = "expiry-learner"
        const val AUTHORITY_CONVERSATION = "expiry-authority"
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)
    }
}
