package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorVisualExecutionSchedulerTest {
    @Test
    fun executionExceptionsBecomeRetryableFailures() = runTest {
        val key = executionKey(turnOrdinal = 1)

        val outcome = collectTutorVisualExecution(
            flow { throw IllegalStateException("provider failed") },
        )
        val failures = emptySet<TutorVisualExecutionKey>().afterExecution(key, outcome)

        assertEquals(TutorVisualExecutionOutcome.FAILED, outcome)
        assertEquals(setOf(key), failures)
        assertEquals(
            key,
            failures.failureFor(
                anchor = key.anchor,
                generationRequestId = key.semanticRequestId,
                reviewRequestId = null,
            ),
        )
    }

    @Test
    fun successfulRetryClearsOnlyItsExactFailure() = runTest {
        val retryKey = executionKey(turnOrdinal = 1)
        val otherKey = executionKey(turnOrdinal = 2)

        val outcome = collectTutorVisualExecution(flow { })
        val failures = setOf(retryKey, otherKey).afterExecution(retryKey, outcome)

        assertEquals(TutorVisualExecutionOutcome.COMPLETED, outcome)
        assertEquals(setOf(otherKey), failures)
    }

    @Test
    fun cancellationStillPropagatesToTheOwningComposition() = runTest {
        val expected = CancellationException("superseded")

        try {
            collectTutorVisualExecution(flow { throw expected })
            fail("Cancellation must not become a retryable provider failure")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    @Test
    fun blockedAnchorDoesNotStarveAnotherRequestAndDuplicatesAreRejected() = runTest {
        val scheduler = TutorVisualAnchorScheduler(this)
        val firstKey = executionKey(turnOrdinal = 1)
        val secondKey = executionKey(turnOrdinal = 2)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()

        assertTrue(
            scheduler.launch(firstKey) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            },
        )
        firstStarted.await()
        assertFalse(scheduler.launch(firstKey) { fail("Duplicate execution started") })
        assertTrue(scheduler.launch(secondKey) { secondCompleted.complete(Unit) })

        withTimeout(1_000) { secondCompleted.await() }
        releaseFirst.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun providerOrSemanticSwitchCancelsStaleWorkBeforeStartingTheNewRequest() = runTest {
        val scheduler = TutorVisualAnchorScheduler(this)
        val oldKey = executionKey(turnOrdinal = 1, semanticRequestId = "old-provider-request")
        val newKey = oldKey.copy(semanticRequestId = "new-provider-request")
        val oldStarted = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val newCompleted = AtomicBoolean(false)

        assertTrue(
            scheduler.launch(oldKey) {
                oldStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    oldCancelled.complete(Unit)
                }
            },
        )
        oldStarted.await()

        scheduler.cancelExcept(setOf(newKey))
        withTimeout(1_000) { oldCancelled.await() }
        assertTrue(scheduler.launch(newKey) { newCompleted.set(true) })
        advanceUntilIdle()

        assertTrue(newCompleted.get())
    }

    private fun executionKey(
        turnOrdinal: Int,
        semanticRequestId: String = "visual-request-$turnOrdinal",
    ) = TutorVisualExecutionKey(
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = 1,
            turnOrdinal = turnOrdinal,
        ),
        taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
        semanticRequestId = semanticRequestId,
    )
}
