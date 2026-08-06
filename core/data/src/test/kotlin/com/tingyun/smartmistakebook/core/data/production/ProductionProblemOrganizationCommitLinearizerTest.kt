package com.tingyun.smartmistakebook.core.data.production

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionProblemOrganizationCommitLinearizerTest {
    @Test
    fun revocationThatLinearizesFirstRejectsTheCommitBody() = runBlocking {
        val operationEntered = AtomicBoolean(false)
        val gate = ProductionProblemOrganizationCommitLinearizer { true }

        gate.close()
        val failure =
            runCatching {
                gate.linearizeCommit {
                    operationEntered.set(true)
                }
            }.exceptionOrNull()

        assertTrue(failure is ProductionProblemOrganizationExecutionRevokedException)
        assertFalse(operationEntered.get())
    }

    @Test
    fun earlierCommitKeepsRevocationBlockedThroughThePreCommitWindow() = runBlocking {
        val transactionEntered = CompletableDeferred<Unit>()
        val allowRoomToReturnAfterCommit = CompletableDeferred<Unit>()
        val committed = AtomicBoolean(false)
        val gate = ProductionProblemOrganizationCommitLinearizer { true }

        val commit =
            async {
                gate.linearizeCommit {
                    transactionEntered.complete(Unit)
                    allowRoomToReturnAfterCommit.await()
                    committed.set(true)
                    "receipt"
                }
            }
        transactionEntered.await()

        val revocation = async(Dispatchers.Default) { gate.close() }
        while (gate.isCurrent()) yield()
        assertFalse(revocation.isCompleted)
        assertFalse(committed.get())

        allowRoomToReturnAfterCommit.complete(Unit)
        assertEquals("receipt", commit.await())
        revocation.await()
        assertTrue(committed.get())
        assertFalse(gate.isCurrent())
    }
}
