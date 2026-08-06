package com.tingyun.smartmistakebook.core.data.production

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionProblemOrganizationExecutionTest {
    @Test
    fun generationRevokedWhileExecutionIsSuspendedCannotReturnAStaleOutcome() = runBlocking {
        val generationIsCurrent = AtomicBoolean(true)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val execution =
            ProductionProblemOrganizationExecution.issue(
                executeOperation = {
                    entered.complete(Unit)
                    resume.await()
                    ProductionProblemOrganizationExecutionResult.Finished
                },
                productionGenerationIsCurrent = generationIsCurrent::get,
            )

        val outcome = async {
            runCatching {
                execution.execute(
                    ProductionProblemOrganizationExecutionRequest(
                        attemptToken = "attempt-in-flight",
                        requestedAtEpochMillis = 1L,
                    ),
                )
            }
        }
        entered.await()
        generationIsCurrent.set(false)
        resume.complete(Unit)

        assertTrue(
            outcome.await().exceptionOrNull() is
                ProductionProblemOrganizationExecutionRevokedException,
        )
    }

    @Test
    fun executionLeaseCannotBeCopiedAcrossWorkStateOrOwnerGeneration() {
        val firstOwner =
            ProductionProblemOrganizationExecutionLeaseAuthority(ByteArray(32) { 1 })
        val nextOwner =
            ProductionProblemOrganizationExecutionLeaseAuthority(ByteArray(32) { 2 })
        val token = firstOwner.issue(workId = "work-1", stateVersion = 7)

        assertTrue(
            firstOwner.matches(
                ProductionProblemOrganizationExecutionLease("work-1", 7, token),
            ),
        )
        assertFalse(
            firstOwner.matches(
                ProductionProblemOrganizationExecutionLease("work-2", 7, token),
            ),
        )
        assertFalse(
            firstOwner.matches(
                ProductionProblemOrganizationExecutionLease("work-1", 8, token),
            ),
        )
        assertFalse(
            nextOwner.matches(
                ProductionProblemOrganizationExecutionLease("work-1", 7, token),
            ),
        )
        val nextOwnerToken = nextOwner.issue(workId = "work-1", stateVersion = 7)
        assertNotEquals(token, nextOwnerToken)
        assertTrue(
            nextOwner.matches(
                ProductionProblemOrganizationExecutionLease("work-1", 7, nextOwnerToken),
            ),
        )

        firstOwner.close()
        assertFalse(
            firstOwner.matches(
                ProductionProblemOrganizationExecutionLease("work-1", 7, token),
            ),
        )
    }
}
