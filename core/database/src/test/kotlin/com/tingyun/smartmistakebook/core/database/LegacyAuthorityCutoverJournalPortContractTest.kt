package com.tingyun.smartmistakebook.core.database

import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LegacyAuthorityCutoverJournalPortContractTest {
    @Test
    fun publicAbiIsAppendAndOrderedReadOnly() {
        val allowedMethods = setOf("appendStageReceipt", "readStageReceipts")
        val interfaceMethods =
            LegacyAuthorityCutoverJournalPort::class.java.methods.domainMethodNames()
        val adapterMethods =
            StudyDatabaseLegacyAuthorityCutoverJournalAdapter::class.java.methods
                .domainMethodNames()

        assertEquals(allowedMethods, interfaceMethods)
        assertEquals(allowedMethods, adapterMethods)
        assertFalse(
            (interfaceMethods + adapterMethods).any { methodName ->
                FORBIDDEN_METHOD_FRAGMENTS.any(methodName.lowercase()::contains)
            },
        )
        assertFalse(
            StudyDatabaseLegacyAuthorityCutoverJournalAdapter::class.java.constructors
                .flatMap { constructor -> constructor.parameterTypes.asList() }
                .contains(StudyDatabasePort::class.java),
        )
    }

    @Test
    fun adapterForwardsOnlyInjectedJournalCapabilities() {
        val command = firstCommand()
        val receipt = LegacyAuthorityCutoverJournalChain.receipt(command)
        val expectedWrite = LegacyAuthorityCutoverJournalWriteResult(
            outcome = LegacyAuthorityCutoverJournalWriteOutcome.INSERTED,
            receipt = receipt,
        )
        val expectedRead = listOf(receipt)
        var capturedCommand: AppendLegacyAuthorityCutoverStageCommand? = null
        val adapter = StudyDatabaseLegacyAuthorityCutoverJournalAdapter(
            appendReceipt = { submitted ->
                capturedCommand = submitted
                expectedWrite
            },
            readReceipts = { expectedRead },
        )

        assertSame(expectedWrite, runImmediately { adapter.appendStageReceipt(command) })
        assertSame(command, capturedCommand)
        assertSame(expectedRead, runImmediately { adapter.readStageReceipts() })
    }

    @Test
    fun receiptFingerprintBindsEveryDurableField() {
        val command = firstCommand()
        val repeated = LegacyAuthorityCutoverJournalChain.receipt(command)
        val changed = LegacyAuthorityCutoverJournalChain.receipt(
            command.copy(checkpoint = "student-mistakes:page:2"),
        )

        assertEquals(
            LegacyAuthorityCutoverJournalChain.receipt(command).receiptFingerprint,
            repeated.receiptFingerprint,
        )
        assertNotEquals(repeated.receiptFingerprint, changed.receiptFingerprint)
    }

    private fun firstCommand() = AppendLegacyAuthorityCutoverStageCommand(
        stageOrdinal = 1,
        stageName = "student-mistakes-authority",
        targetDatabaseName = LegacyAuthorityDatabaseName.STUDENT_MISTAKES,
        migratedRecordCount = 12,
        checkpoint = "student-mistakes:page:1",
        destinationFingerprint = "a".repeat(64),
        completedAtEpochMillis = 100,
        predecessorReceiptFingerprint = null,
    )

    private fun Array<Method>.domainMethodNames(): Set<String> =
        filterNot { method -> method.declaringClass == Any::class.java }
            .mapTo(linkedSetOf()) { method -> method.name }

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
            ?: error("The journal capability unexpectedly suspended")
    }

    private companion object {
        val FORBIDDEN_METHOD_FRAGMENTS = setOf(
            "delete",
            "rollback",
            "repair",
            "update",
            "mistake",
            "mastery",
            "knowledge",
            "projection",
            "learningevent",
        )
    }
}
