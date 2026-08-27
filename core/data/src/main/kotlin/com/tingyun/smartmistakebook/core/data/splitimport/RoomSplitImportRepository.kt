package com.tingyun.smartmistakebook.core.data.splitimport

import com.tingyun.smartmistakebook.core.database.CreateSplitImportJobCommand
import com.tingyun.smartmistakebook.core.database.SplitImportQuestionSeed
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.SplitImportJobSummary
import com.tingyun.smartmistakebook.core.domain.SplitImportQuestionSummary
import com.tingyun.smartmistakebook.core.domain.SplitImportRepository
import com.tingyun.smartmistakebook.core.domain.SplitRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSplitImportRepository(
    private val database: StudyDatabasePort,
) : SplitImportRepository {
    internal suspend fun createSplitJob(
        command: CreateSplitImportJobCommand,
        questions: List<SplitImportQuestionSeed>,
    ): com.tingyun.smartmistakebook.core.database.SplitImportJobRecord =
        database.createSplitImportJob(command, questions)

    internal suspend fun markReady(jobId: String, questionCount: Int, occurredAtEpochMillis: Long): Boolean =
        database.markSplitImportReady(jobId, questionCount, occurredAtEpochMillis)
    override fun observeActiveImports(): Flow<List<SplitImportJobSummary>> =
        database.observeActiveSplitImports().map { jobs -> jobs.map(SplitImportJobSummaryMapper::toSummary) }

    override suspend fun readImport(jobId: String): SplitImportJobSummary? =
        withContext(Dispatchers.IO) {
            database.readSplitImportJob(jobId)?.let(SplitImportJobSummaryMapper::toSummary)
        }

    override suspend fun updateSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        database.updateSplitImportSelection(jobId, questionOrdinal, selected, occurredAtEpochMillis)
    }

    override suspend fun markConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        database.markSplitImportQuestionConfirmed(
            jobId,
            questionOrdinal,
            confirmState,
            splitDraftId,
            occurredAtEpochMillis,
        )
    }

    override suspend fun complete(jobId: String, occurredAtEpochMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            database.completeSplitImportJob(jobId, occurredAtEpochMillis)
        }

    override suspend fun abandon(jobId: String, occurredAtEpochMillis: Long): Boolean =
        withContext(Dispatchers.IO) {
            database.abandonSplitImportJob(jobId, occurredAtEpochMillis)
        }
}

object SplitImportRepositoryFactory {
    fun create(database: StudyDatabasePort): SplitImportRepository =
        RoomSplitImportRepository(database)

    fun createConcrete(database: StudyDatabasePort): RoomSplitImportRepository =
        RoomSplitImportRepository(database)
}

internal object SplitImportJobSummaryMapper {
    fun toSummary(record: com.tingyun.smartmistakebook.core.database.SplitImportJobRecord) =
        SplitImportJobSummary(
            jobId = record.jobId,
            sourceKind = record.sourceKind,
            pageCount = record.pageCount,
            questionCount = record.questionCount,
            status = record.status,
            createdAtEpochMillis = record.createdAtEpochMillis,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
            sourceUri = record.sourceUri,
            questions = record.questions.map { question ->
                SplitImportQuestionSummary(
                    jobId = question.jobId,
                    questionOrdinal = question.questionOrdinal,
                    pageIndex = question.pageIndex,
                    region = SplitRegion(
                        left = question.left,
                        top = question.top,
                        right = question.right,
                        bottom = question.bottom,
                    ),
                    selected = question.selected,
                    confirmState = question.confirmState,
                    splitDraftId = question.splitDraftId,
                )
            },
        )
}