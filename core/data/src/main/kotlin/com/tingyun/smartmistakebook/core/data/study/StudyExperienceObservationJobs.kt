package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeCoverageOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The four database observation loops that keep the study snapshot fresh:
 * mistakes, pending drafts, ledger head and knowledge coverage. Every emission
 * is serialized under the repository's mutex and handed to the injected
 * handler, so the repository keeps ownership of its state while this class owns
 * the subscription lifecycle (one place to cancel on close).
 */
internal class StudyExperienceObservationJobs(
    private val database: StudyDatabasePort,
    private val learnerId: String,
    private val mutex: Mutex,
    scope: CoroutineScope,
    private val coverageFlow: Flow<StudyKnowledgeCoverageOverview>,
    private val onMistakes: suspend (List<MistakeRecord>) -> Unit,
    private val onPendingDraftCount: suspend (Int) -> Unit,
    private val onLedgerChanged: suspend () -> Unit,
    private val onCoverage: suspend (StudyKnowledgeCoverageOverview) -> Unit,
    private val onFailure: suspend (Throwable) -> Unit,
) {
    private val jobs: List<Job> = listOf(
        scope.launch { collect(database.observeMistakes(), onMistakes) },
        scope.launch { collect(database.observePendingProblemDraftCount(), onPendingDraftCount) },
        scope.launch {
            collect(database.observeLearningLedgerHead(learnerId).distinctUntilChanged()) {
                onLedgerChanged()
            }
        },
        scope.launch { collect(coverageFlow, onCoverage) },
    )

    fun cancel() {
        jobs.forEach(Job::cancel)
    }

    private suspend fun <T> collect(flow: Flow<T>, handler: suspend (T) -> Unit) {
        try {
            flow.collect { value -> mutex.withLock { handler(value) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutex.withLock { onFailure(failure) }
        }
    }
}
