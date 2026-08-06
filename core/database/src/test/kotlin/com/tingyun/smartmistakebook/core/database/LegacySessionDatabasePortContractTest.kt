package com.tingyun.smartmistakebook.core.database

import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class LegacySessionDatabasePortContractTest {
    @Test
    fun publicAbiContainsOnlyStartupOrganizationSchedulingCapability() {
        val allowedMethods = setOf(
            "observeSchedulableProblemOrganizationWorks",
            "readRunningProblemOrganizationWorks",
        )

        assertEquals(
            allowedMethods,
            LegacySessionDatabasePort::class.java.methods.domainMethodNames(),
        )
        assertEquals(
            allowedMethods,
            StudyDatabaseLegacySessionAdapter::class.java.methods.domainMethodNames(),
        )

        val forbiddenStudyDatabaseMethods =
            StudyDatabasePort::class.java.methods.domainMethodNames() - allowedMethods
        assertEquals(
            emptySet<String>(),
            StudyDatabaseLegacySessionAdapter::class.java.methods
                .domainMethodNames()
                .intersect(forbiddenStudyDatabaseMethods),
        )
        assertFalse(
            StudyDatabaseLegacySessionAdapter::class.java.constructors
                .flatMap { constructor -> constructor.parameterTypes.asList() }
                .contains(StudyDatabasePort::class.java),
        )
    }

    @Test
    fun adapterForwardsOnlyTheInjectedSchedulerCapabilities() {
        val expectedWork = problemOrganizationWork()
        val expectedFlow = flowOf(listOf(expectedWork))
        var capturedArguments: List<Any?>? = null
        val adapter = StudyDatabaseLegacySessionAdapter(
            readRunningWorks = { limit, leaseExpiry, updatedAt, workId ->
                capturedArguments = listOf(
                    limit,
                    leaseExpiry,
                    updatedAt,
                    workId,
                )
                listOf(expectedWork)
            },
            observeSchedulableWorks = { expectedFlow },
        )

        val result = runImmediately {
            adapter.readRunningProblemOrganizationWorks(
                limit = 17,
                afterLeaseExpiresAtEpochMillis = 100L,
                afterUpdatedAtEpochMillis = 200L,
                afterWorkId = "work-before",
            )
        }

        assertEquals(listOf(expectedWork), result)
        assertEquals(listOf(17, 100L, 200L, "work-before"), capturedArguments)
        assertSame(expectedFlow, adapter.observeSchedulableProblemOrganizationWorks())
    }

    private fun Array<Method>.domainMethodNames(): Set<String> =
        filterNot { method -> method.declaringClass == Any::class.java }
            .mapTo(linkedSetOf()) { method -> method.name }

    private fun problemOrganizationWork() = ProblemOrganizationWorkRecord(
        workId = "work-1",
        commitReceiptCommandId = "commit-1",
        status = StudyDbValue.ProblemOrganizationWorkStatus.RUNNING,
        stateVersion = 3,
        attemptCount = 1,
        notBeforeEpochMillis = 90,
        requestId = "request-1",
        requestSnapshot = "{}",
        authorizationGrantSnapshot = "{}",
        leaseOwner = "process-1",
        leaseExpiresAtEpochMillis = 120,
        failureCode = null,
        failureMessage = null,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 100,
    )

    private fun <T> runImmediately(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return outcome?.getOrThrow()
            ?: error("The capability unexpectedly suspended")
    }
}
