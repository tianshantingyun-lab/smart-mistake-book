package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.session.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionProblemOrganizationSchedulingTest {
    @Test
    fun appFacingScheduleContainsNoPayloadProofOrLearnerIdentity() = runBlocking {
        val sessions = FakeSessions(schedulable = listOf(snapshot("work-1")))
        val leaseAuthority = leaseAuthority()
        val scheduling =
            ProductionProblemOrganizationScheduling.issue(
                TEST_SCOPE,
                sessions,
                leaseAuthority,
            ) { true }

        val actual = scheduling.observeSchedulable().first().single()

        assertEquals(
            ProductionProblemOrganizationWorkSchedule(
                workId = "work-1",
                stateVersion = 3,
                eligibleAtEpochMillis = 12_000,
                updatedAtEpochMillis = 11_000,
                executionLeaseToken = leaseAuthority.issue("work-1", 3),
            ),
            actual,
        )
        assertTrue(actual.executionLeaseToken.matches(Regex("[0-9a-f]{64}")))
        val fieldNames =
            ProductionProblemOrganizationWorkSchedule::class.java.declaredFields
                .filterNot { it.isSynthetic }
                .map { it.name }
        listOf("payload", "proof", "learner", "answer", "authorization").forEach { forbidden ->
            assertFalse(fieldNames.any { it.contains(forbidden, ignoreCase = true) })
        }
    }

    @Test
    fun runningRecoveryUsesBoundScopeAndStableKeysetCursor() = runBlocking {
        val running =
            snapshot("work-2").copy(
                status = ProblemOrganizationWorkSessionStatus.RUNNING,
                leaseOwner = "worker",
                leaseExpiresAtEpochMillis = 20_000,
            )
        val sessions = FakeSessions(running = listOf(running))
        val leaseAuthority = leaseAuthority()
        val scheduling =
            ProductionProblemOrganizationScheduling.issue(
                TEST_SCOPE,
                sessions,
                leaseAuthority,
            ) { true }
        val cursor =
            ProductionProblemOrganizationRecoveryCursor(
                eligibleAtEpochMillis = 19_000,
                updatedAtEpochMillis = 18_000,
                workId = "work-before",
            )

        assertEquals(
            listOf(
                ProductionProblemOrganizationWorkSchedule(
                    workId = "work-2",
                    stateVersion = 3,
                    eligibleAtEpochMillis = 20_000,
                    updatedAtEpochMillis = 11_000,
                    executionLeaseToken = leaseAuthority.issue("work-2", 3),
                ),
            ),
            scheduling.readRunningRecoveryPage(
                ProductionProblemOrganizationRecoveryQuery(limit = 50, after = cursor),
            ),
        )
        assertEquals(
            ProblemOrganizationWorkRecoveryQuery(
                scope = TEST_SCOPE,
                limit = 50,
                after =
                    ProblemOrganizationWorkRecoveryCursor(
                        leaseExpiresAtEpochMillis = 19_000,
                        updatedAtEpochMillis = 18_000,
                        workId = "work-before",
                    ),
            ),
            sessions.lastRecoveryQuery,
        )
    }

    @Test
    fun staleProductionGenerationCannotReadSchedulingState() {
        val sessions = FakeSessions(schedulable = listOf(snapshot("work-1")))
        val scheduling =
            ProductionProblemOrganizationScheduling.issue(
                TEST_SCOPE,
                sessions,
                leaseAuthority(),
            ) { false }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { scheduling.observeSchedulable().first() }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                scheduling.readRunningRecoveryPage(
                    ProductionProblemOrganizationRecoveryQuery(limit = 1),
                )
            }
        }
    }

    @Test
    fun generationRevokedDuringRecoveryCannotReturnStaleSchedulingState() {
        val running =
            snapshot("work-3").copy(
                status = ProblemOrganizationWorkSessionStatus.RUNNING,
                leaseOwner = "worker",
                leaseExpiresAtEpochMillis = 20_000,
            )
        val sessions = FakeSessions(running = listOf(running))
        var generationChecks = 0
        val scheduling =
            ProductionProblemOrganizationScheduling.issue(
                TEST_SCOPE,
                sessions,
                leaseAuthority(),
            ) {
                generationChecks++ == 0
            }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                scheduling.readRunningRecoveryPage(
                    ProductionProblemOrganizationRecoveryQuery(limit = 1),
                )
            }
        }
    }

    @Test
    fun generationRevokedAfterTokenIssuanceCannotPublishThoseTokens() {
        val running =
            snapshot("work-after-token").copy(
                status = ProblemOrganizationWorkSessionStatus.RUNNING,
                leaseOwner = "worker",
                leaseExpiresAtEpochMillis = 20_000,
            )
        val sessions = FakeSessions(running = listOf(running))
        var generationChecks = 0
        val scheduling =
            ProductionProblemOrganizationScheduling.issue(
                TEST_SCOPE,
                sessions,
                leaseAuthority(),
            ) {
                generationChecks++ < 2
            }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                scheduling.readRunningRecoveryPage(
                    ProductionProblemOrganizationRecoveryQuery(limit = 1),
                )
            }
        }
        assertEquals(3, generationChecks)
    }

    private class FakeSessions(
        private val schedulable: List<ProblemOrganizationWorkSessionSnapshot> = emptyList(),
        private val running: List<ProblemOrganizationWorkSessionSnapshot> = emptyList(),
    ) : ProblemOrganizationWorkSessionPort {
        var lastRecoveryQuery: ProblemOrganizationWorkRecoveryQuery? = null

        override fun observeSchedulable(
            scope: SessionScope,
        ): Flow<List<ProblemOrganizationWorkSessionSnapshot>> {
            assertEquals(TEST_SCOPE, scope)
            return flowOf(schedulable)
        }

        override suspend fun read(
            query: ProblemOrganizationWorkSessionReadQuery,
        ): ProblemOrganizationWorkSessionSnapshot? = error("not used")

        override suspend fun readSourceReceipt(
            scope: SessionScope,
            sourceCommitReceiptId: String,
        ): OrganizationSourceCommitSessionReceipt? = error("not used")

        override suspend fun authorize(
            command: AuthorizeProblemOrganizationWorkSessionCommand,
        ): ProblemOrganizationWorkSessionMutationResult = error("not used")

        override suspend fun claim(
            command: ClaimProblemOrganizationWorkSessionCommand,
        ): ProblemOrganizationWorkSessionMutationResult = error("not used")

        override suspend fun transition(
            command: ProblemOrganizationWorkSessionTransition,
        ): ProblemOrganizationWorkSessionMutationResult = error("not used")

        override suspend fun readRunningRecoveryPage(
            query: ProblemOrganizationWorkRecoveryQuery,
        ): List<ProblemOrganizationWorkSessionSnapshot> {
            lastRecoveryQuery = query
            return running
        }
    }

    private companion object {
        val TEST_SCOPE = SessionScope("production-scheduling-learner")

        fun leaseAuthority() =
            ProductionProblemOrganizationExecutionLeaseAuthority(ByteArray(32) { 7 })

        fun snapshot(workId: String) =
            ProblemOrganizationWorkSessionSnapshot(
                scope = TEST_SCOPE,
                workId = workId,
                sourceCommitReceiptId = "receipt-$workId",
                status = ProblemOrganizationWorkSessionStatus.PENDING,
                version = SessionVersion(3, "3".padStart(64, '0')),
                attemptCount = 0,
                notBeforeEpochMillis = 12_000,
                requestId = "request-$workId",
                requestPayload = SessionOpaquePayload("organization-request-v1", "secret"),
                authorizationPayload = SessionOpaquePayload("authorization-v1", "secret"),
                leaseOwner = null,
                leaseExpiresAtEpochMillis = null,
                failureCode = null,
                failureMessage = null,
                createdAtEpochMillis = 10_000,
                updatedAtEpochMillis = 11_000,
            )
    }
}
