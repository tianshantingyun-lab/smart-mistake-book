package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.port.SplitImportPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * In-memory fake of [SplitImportPort] for the split-import repositor/ui
 * flows. Persists jobs in maps with the Record types the port defines, so
 * the split-import domain layer can be tested with a full duplicate/replay
 * contract without touching SQLite.
 */
class SplitImportPortFake : SplitImportPort {
    private val jobs = linkedMapOf<String, SplitImportJobRecord>()
    private val questionsByJob = linkedMapOf<String, MutableList<SplitImportQuestionRecord>>()

    fun seed(
        command: CreateSplitImportJobCommand,
        questions: List<SplitImportQuestionSeed>,
        status: String = StudyDbValue.SplitImportStatus.PREPARING,
        questionCountOverride: Int? = null,
    ): SplitImportJobRecord {
        val records = questions.mapIndexed { index, q ->
            SplitImportQuestionRecord(
                jobId = command.jobId,
                questionOrdinal = index,
                pageIndex = q.pageIndex,
                left = q.regionLeft(),
                top = q.regionTop(),
                right = q.regionRight(),
                bottom = q.regionBottom(),
                selected = q.prioritised,
                confirmState = StudyDbValue.SplitImportConfirmState.PENDING,
                splitDraftId = null,
            )
        }
        val job = SplitImportJobRecord(
            jobId = command.jobId,
            sourceKind = command.sourceKind,
            sourceFingerprint = command.sourceFingerprint,
            sourceUri = command.sourceUri,
            pageCount = command.pageCount,
            questionCount = questionCountOverride ?: records.size,
            status = status,
            createdAtEpochMillis = command.createdAtEpochMillis,
            updatedAtEpochMillis = command.createdAtEpochMillis,
            questions = records,
        )
        jobs[command.jobId] = job
        questionsByJob[command.jobId] = records.toMutableList()
        return job
    }

    fun readByFingerprint(sourceFingerprint: String): SplitImportJobRecord? =
        jobs.values.firstOrNull { it.sourceFingerprint == sourceFingerprint }

    private fun rebuild(jobId: String): SplitImportJobRecord {
        val existing = jobs.getValue(jobId)
        return existing.copy(questions = questionsByJob.getValue(jobId).toList())
    }

    private fun activeSnapshot(): List<SplitImportJobRecord> =
        jobs.keys.mapNotNull { id -> rebuild(id) }
            .filter { it.status !in setOf(
                StudyDbValue.SplitImportStatus.COMPLETED,
                StudyDbValue.SplitImportStatus.ABANDONED,
            ) }

    override fun observeActiveSplitImports(): Flow<List<SplitImportJobRecord>> =
        MutableStateFlow(Unit).map { activeSnapshot() }

    override suspend fun readSplitImportJob(jobId: String): SplitImportJobRecord? =
        jobs[jobId]?.let { rebuild(it.jobId) }

    override suspend fun createSplitImportJob(
        command: CreateSplitImportJobCommand,
        questions: List<SplitImportQuestionSeed>,
    ): SplitImportJobRecord {
        readByFingerprint(command.sourceFingerprint)?.takeIf { it.jobId != command.jobId }?.let {
            throw ImmutablePayloadConflictException("split_import_source", command.sourceFingerprint)
        }
        return seed(command, questions)
    }

    override suspend fun markSplitImportReady(
        jobId: String,
        questionCount: Int,
        occurredAtEpochMillis: Long,
    ): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status != StudyDbValue.SplitImportStatus.PREPARING) return false
        val updated = job.copy(
            status = StudyDbValue.SplitImportStatus.READY,
            questionCount = questionCount,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
        jobs[jobId] = updated
        return true
    }

    override suspend fun updateSplitImportSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status != StudyDbValue.SplitImportStatus.READY) return false
        val qs = questionsByJob.getValue(jobId)
        val index = qs.indexOfFirst { it.questionOrdinal == questionOrdinal }
        if (index < 0) return false
        qs[index] = qs[index].copy(selected = selected)
        jobs[jobId] = job.copy(updatedAtEpochMillis = occurredAtEpochMillis)
        return true
    }

    override suspend fun markSplitImportQuestionConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status != StudyDbValue.SplitImportStatus.READY) return false
        val qs = questionsByJob.getValue(jobId)
        val index = qs.indexOfFirst { it.questionOrdinal == questionOrdinal }
        if (index < 0) return false
        val resolved = when (confirmState) {
            StudyDbValue.SplitImportConfirmState.SAVED,
            StudyDbValue.SplitImportConfirmState.TUTOR_SESSION,
            -> splitDraftId
            else -> null
        }
        qs[index] = qs[index].copy(confirmState = confirmState, splitDraftId = resolved)
        jobs[jobId] = job.copy(updatedAtEpochMillis = occurredAtEpochMillis)
        return true
    }

    override suspend fun completeSplitImportJob(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status !in setOf(
                StudyDbValue.SplitImportStatus.READY,
                StudyDbValue.SplitImportStatus.PREPARING,
            )
        ) return false
        jobs[jobId] = job.copy(
            status = StudyDbValue.SplitImportStatus.COMPLETED,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
        return true
    }

    override suspend fun abandonSplitImportJob(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean {
        val job = jobs[jobId] ?: return false
        if (job.status !in setOf(
                StudyDbValue.SplitImportStatus.READY,
                StudyDbValue.SplitImportStatus.PREPARING,
            )
        ) return false
        jobs[jobId] = job.copy(
            status = StudyDbValue.SplitImportStatus.ABANDONED,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
        return true
    }
}

class SplitImportPortFakeTest {
    private fun pluginCommand(jobId: String = "job-1") = CreateSplitImportJobCommand(
        jobId = jobId,
        sourceKind = "PDF",
        sourceFingerprint = "a".repeat(64),
        sourceUri = "content://split/fake-page-1",
        pageCount = 2,
        createdAtEpochMillis = 1_000L,
    )

    private fun seedQuestion(index: Int) = SplitImportQuestionSeed(
        pageIndex = 0,
        left = 0.1,
        top = 0.2 * index,
        right = 0.9,
        bottom = 0.2 * index + 0.15,
        prioritised = index == 0,
    )

    @Test
    fun duplicateSourceFingerprintIsRejectedWithBusinessConflict() = kotlinx.coroutines.runBlocking {
        val fake = SplitImportPortFake()
        fake.createSplitImportJob(pluginCommand("job-a"), listOf(seedQuestion(0)))
        try {
            fake.createSplitImportJob(pluginCommand("job-b"), listOf(seedQuestion(0)))
            fail("Expected ImmutablePayloadConflictException for duplicate source")
        } catch (expected: ImmutablePayloadConflictException) {
            assertEquals(
                "split_import_source aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa already exists with a different payload",
                expected.message,
            )
        }
    }

    @Test
    fun updateSelectionAndConfirmRoundTripThroughReadyGate() = kotlinx.coroutines.runBlocking {
        val fake = SplitImportPortFake()
        fake.createSplitImportJob(pluginCommand(), listOf(seedQuestion(0), seedQuestion(1)))

        // Selection is only allowed once the job is READY.
        assertFalse(
            fake.updateSplitImportSelection("job-1", 0, true, 1_500L),
        )
        assertTrue(fake.markSplitImportReady("job-1", 2, 1_500L))
        assertTrue(fake.updateSplitImportSelection("job-1", 1, true, 2_000L))
        assertFalse(fake.updateSplitImportSelection("job-missing", 0, true, 2_000L))

        assertTrue(
            fake.markSplitImportQuestionConfirmed(
                "job-1",
                0,
                StudyDbValue.SplitImportConfirmState.SAVED,
                "draft-1",
                2_500L,
            ),
        )
        val job = fake.readSplitImportJob("job-1")
        assertNotNull(job)
        assertEquals(StudyDbValue.SplitImportStatus.READY, job!!.status)
        assertEquals("draft-1", job.questions.first { it.questionOrdinal == 0 }.splitDraftId)
        assertEquals(
            StudyDbValue.SplitImportConfirmState.PENDING,
            job.questions.first { it.questionOrdinal == 1 }.confirmState,
        )
    }

    @Test
    fun completeRemovesJobFromActiveFlow() = kotlinx.coroutines.runBlocking {
        val fake = SplitImportPortFake()
        fake.createSplitImportJob(pluginCommand(), listOf(seedQuestion(0)))
        fake.markSplitImportReady("job-1", 1, 2_000L)
        assertTrue(fake.completeSplitImportJob("job-1", 3_000L))
        assertNull(fake.readSplitImportJob("job-1")?.status?.takeIf {
            it in setOf(
                StudyDbValue.SplitImportStatus.READY,
                StudyDbValue.SplitImportStatus.PREPARING,
            )
        })
        val active = fake.readSplitImportJob("job-1")
        assertNull(active?.status?.takeIf {
            it in setOf(
                StudyDbValue.SplitImportStatus.READY,
                StudyDbValue.SplitImportStatus.PREPARING,
            )
        })
    }
}