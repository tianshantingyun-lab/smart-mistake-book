package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class TutorActiveStreamPhase {
    PREPARING,
    STREAMING,
    COMPLETED,
    FAILED,
}

internal enum class TutorActiveStreamRecovery {
    RETRY,
}

internal data class TutorActiveStreamMessage(
    val studentMessage: String,
    val ownerVersion: Long,
    val turnVersion: Long,
    val modeVersion: Long,
    val identity: TutorStreamIdentity? = null,
    val snapshot: TutorMarkdownSnapshot? = null,
    val phase: TutorActiveStreamPhase = TutorActiveStreamPhase.PREPARING,
    val showPlaceholder: Boolean = false,
    val retryable: Boolean = false,
) {
    val activityVisible: Boolean
        get() = phase == TutorActiveStreamPhase.PREPARING ||
            phase == TutorActiveStreamPhase.STREAMING

    val recoveryActions: List<TutorActiveStreamRecovery>
        get() = if (phase == TutorActiveStreamPhase.FAILED && retryable) {
            listOf(TutorActiveStreamRecovery.RETRY)
        } else {
            emptyList()
        }

    val renderVersion: Any
        get() = listOf(
            identity,
            snapshot,
            phase,
            showPlaceholder,
        )
}

internal data class TutorActiveStreamState(
    val active: TutorActiveStreamMessage? = null,
    val supersededRequestIds: Set<String> = emptySet(),
)

internal data class TutorPreparedStream(
    val requestId: String,
    val events: (TutorStreamIdentity) -> Flow<TutorStreamEvent>,
) {
    init {
        require(requestId.isNotBlank()) { "Tutor stream request id must not be blank" }
    }
}

/**
 * Process-local owner for one visible Tutor reply. Durable history still belongs to
 * [com.tingyun.smartmistakebook.core.domain.ModelTaskRepository].
 */
internal class TutorActiveStreamOwner(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cancelDurableRequest: suspend (String) -> Unit = {},
    private val beforeCoalescedPreviewApply: suspend () -> Unit = {},
    initialMode: TutorExplanationMode,
    private val ownerVersion: Long = nextOwnerVersion(),
    private val coalesceMillis: Long = DEFAULT_COALESCE_MILLIS,
    private val placeholderDelayMillis: Long = DEFAULT_PLACEHOLDER_DELAY_MILLIS,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(TutorActiveStreamState())
    val state: StateFlow<TutorActiveStreamState> = mutableState.asStateFlow()

    private var mode = initialMode
    private var modeVersion = 0L
    private var turnVersion = 0L
    private var activeJob: Job? = null
    private var closed = false
    private var retryPreparation: (suspend () -> TutorPreparedStream)? = null
    private var preparedForRetry: TutorPreparedStream? = null

    init {
        require(ownerVersion >= 0) { "Tutor stream owner version must not be negative" }
        require(coalesceMillis in MIN_COALESCE_MILLIS..MAX_COALESCE_MILLIS) {
            "Tutor stream coalescing must stay between 50 and 80 milliseconds"
        }
        require(placeholderDelayMillis >= 0) {
            "Tutor stream placeholder delay must not be negative"
        }
    }

    fun submit(
        studentMessage: String,
        startsNewTurn: Boolean = true,
        prepare: suspend () -> TutorPreparedStream,
    ) {
        require(studentMessage.isNotEmpty()) { "Tutor stream student message must not be empty" }
        val previousJob: Job?
        val durableRequestIdToCancel: String?
        val submission: Submission
        synchronized(lock) {
            if (closed) return
            previousJob = activeJob
            val current = mutableState.value
            val superseded = current.active
                ?.takeIf(TutorActiveStreamMessage::activityVisible)
                ?.identity
                ?.requestId
            durableRequestIdToCancel = superseded.takeIf { startsNewTurn }
            if (startsNewTurn || turnVersion == 0L) turnVersion += 1
            submission = Submission(ownerVersion, turnVersion, modeVersion)
            val retainedSnapshot = current.active
                ?.takeIf { !startsNewTurn && it.studentMessage == studentMessage }
                ?.snapshot
            mutableState.value = current.copy(
                active = TutorActiveStreamMessage(
                    studentMessage = studentMessage,
                    ownerVersion = submission.ownerVersion,
                    turnVersion = submission.turnVersion,
                    modeVersion = submission.modeVersion,
                    snapshot = retainedSnapshot,
                ),
                supersededRequestIds = current.supersededRequestIds
                    .plusIfNotNull(superseded),
            )
            retryPreparation = prepare
            preparedForRetry = null
            activeJob = launchSubmission(submission, prepare, prepared = null)
        }
        previousJob?.cancel()
        cancelDurableInWorker(durableRequestIdToCancel)
    }

    fun retry(): Boolean {
        val previousJob: Job?
        synchronized(lock) {
            if (closed) return false
            val active = mutableState.value.active ?: return false
            if (TutorActiveStreamRecovery.RETRY !in active.recoveryActions) return false
            val prepare = retryPreparation ?: return false
            val submission = active.toSubmission()
            val retainedIdentity = active.identity?.takeIf { identity ->
                preparedForRetry?.requestId == identity.requestId
            }
            previousJob = activeJob
            mutableState.update { current ->
                current.copy(
                    active = current.active?.copy(
                        identity = retainedIdentity,
                        phase = TutorActiveStreamPhase.PREPARING,
                        showPlaceholder = false,
                        retryable = false,
                    ),
                )
            }
            activeJob = launchSubmission(submission, prepare, preparedForRetry)
        }
        previousJob?.cancel()
        return true
    }

    fun updateMode(updatedMode: TutorExplanationMode) {
        val job: Job?
        val durableRequestIdToCancel: String?
        synchronized(lock) {
            if (closed || mode == updatedMode) return
            mode = updatedMode
            modeVersion += 1
            job = activeJob
            val current = mutableState.value
            val superseded = current.active
                ?.takeIf(TutorActiveStreamMessage::activityVisible)
                ?.identity
                ?.requestId
            durableRequestIdToCancel = superseded
            mutableState.value = current.copy(
                active = null,
                supersededRequestIds = current.supersededRequestIds
                    .plusIfNotNull(superseded),
            )
            retryPreparation = null
            preparedForRetry = null
            activeJob = null
        }
        job?.cancel()
        cancelDurableInWorker(durableRequestIdToCancel)
    }

    fun acknowledgeDurableSuccess(requestId: String) {
        mutableState.update { current ->
            val active = current.active
            if (
                active?.identity?.requestId == requestId &&
                active.phase == TutorActiveStreamPhase.COMPLETED
            ) {
                current.copy(active = null)
            } else {
                current
            }
        }
    }

    fun close() {
        val job: Job?
        synchronized(lock) {
            if (closed) return
            closed = true
            job = activeJob
            activeJob = null
            retryPreparation = null
            preparedForRetry = null
            mutableState.update { it.copy(active = null) }
        }
        job?.cancel()
    }

    private fun cancelDurableInWorker(requestId: String?) {
        if (requestId == null) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + workerDispatcher) {
                try {
                    cancelDurableRequest(requestId)
                } catch (_: Exception) {
                    // The durable task remains recoverable if local storage is temporarily unavailable.
                }
            }
        }
    }

    private fun launchSubmission(
        submission: Submission,
        prepare: suspend () -> TutorPreparedStream,
        prepared: TutorPreparedStream?,
    ): Job = scope.launch(workerDispatcher) {
        coroutineScope {
            val placeholderJob = launch {
                delay(placeholderDelayMillis)
                updateActive(submission) { active ->
                    if (
                        active.activityVisible &&
                        active.snapshot?.visibleMarkdown.isNullOrEmpty()
                    ) {
                        active.copy(showPlaceholder = true)
                    } else {
                        active
                    }
                }
            }
            var coalesceJob: Job? = null
            var pendingSnapshot: TutorMarkdownSnapshot? = null
            var terminalSeen = false
            val previewLock = Any()
            try {
                val resolved = prepared ?: prepare()
                val identity = TutorStreamIdentity(
                    requestId = resolved.requestId,
                    ownerVersion = submission.ownerVersion,
                    turnVersion = submission.turnVersion,
                    modeVersion = submission.modeVersion,
                )
                if (!attachIdentity(submission, identity)) return@coroutineScope
                synchronized(lock) {
                    if (isCurrentLocked(submission)) preparedForRetry = resolved
                }
                resolved.events(identity).collect { event ->
                    if (
                        terminalSeen ||
                        event.identity != identity ||
                        !isCurrent(submission, identity)
                    ) {
                        return@collect
                    }
                    when (event) {
                        is TutorStreamEvent.Started -> {
                            updateActive(submission) {
                                it.copy(phase = TutorActiveStreamPhase.STREAMING)
                            }
                        }

                        is TutorStreamEvent.Preview -> {
                            val scheduled = launch(
                                context = workerDispatcher,
                                start = CoroutineStart.LAZY,
                            ) {
                                delay(coalesceMillis)
                                val snapshot = synchronized(previewLock) {
                                    val latest = pendingSnapshot
                                    pendingSnapshot = null
                                    coalesceJob = null
                                    latest
                                }
                                snapshot?.let { latest ->
                                    beforeCoalescedPreviewApply()
                                    synchronized(previewLock) {
                                        if (!terminalSeen) {
                                            updateActive(submission) { active ->
                                                active.copy(
                                                    snapshot = latest,
                                                    phase = TutorActiveStreamPhase.STREAMING,
                                                    showPlaceholder = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val shouldStart = synchronized(previewLock) {
                                pendingSnapshot = event.snapshot
                                if (coalesceJob == null) {
                                    coalesceJob = scheduled
                                    true
                                } else {
                                    false
                                }
                            }
                            if (shouldStart) scheduled.start() else scheduled.cancel()
                        }

                        is TutorStreamEvent.Completed -> {
                            placeholderJob.cancel()
                            synchronized(previewLock) {
                                terminalSeen = true
                                pendingSnapshot = null
                                coalesceJob?.cancel()
                                coalesceJob = null
                            }
                            updateActive(submission) {
                                it.copy(
                                    snapshot = event.snapshot,
                                    phase = TutorActiveStreamPhase.COMPLETED,
                                    showPlaceholder = false,
                                    retryable = false,
                                )
                            }
                        }

                        is TutorStreamEvent.Failed -> {
                            placeholderJob.cancel()
                            synchronized(previewLock) {
                                terminalSeen = true
                                pendingSnapshot = null
                                coalesceJob?.cancel()
                                coalesceJob = null
                            }
                            updateActive(submission) {
                                it.copy(
                                    snapshot = event.snapshot,
                                    phase = TutorActiveStreamPhase.FAILED,
                                    showPlaceholder = false,
                                    retryable = event.retryable,
                                )
                            }
                        }
                    }
                }
                if (!terminalSeen) {
                    placeholderJob.cancel()
                    val latestSnapshot = synchronized(previewLock) {
                        terminalSeen = true
                        val latest = pendingSnapshot
                        pendingSnapshot = null
                        coalesceJob?.cancel()
                        coalesceJob = null
                        latest
                    }
                    updateActive(submission) { active ->
                        active.copy(
                            snapshot = latestSnapshot ?: active.snapshot,
                            phase = TutorActiveStreamPhase.FAILED,
                            showPlaceholder = false,
                            retryable = true,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(previewLock) {
                    terminalSeen = true
                    pendingSnapshot = null
                    coalesceJob?.cancel()
                    coalesceJob = null
                }
                updateActive(submission) {
                    it.copy(
                        phase = TutorActiveStreamPhase.FAILED,
                        showPlaceholder = false,
                        retryable = false,
                    )
                }
            } finally {
                placeholderJob.cancel()
                synchronized(previewLock) {
                    pendingSnapshot = null
                    coalesceJob?.cancel()
                    coalesceJob = null
                }
            }
        }
    }

    private fun attachIdentity(
        submission: Submission,
        identity: TutorStreamIdentity,
    ): Boolean {
        var attached = false
        updateActive(submission) { active ->
            attached = true
            active.copy(identity = identity)
        }
        return attached
    }

    private fun updateActive(
        submission: Submission,
        transform: (TutorActiveStreamMessage) -> TutorActiveStreamMessage,
    ) {
        mutableState.update { current ->
            val active = current.active
            if (active != null && active.toSubmission() == submission) {
                current.copy(active = transform(active))
            } else {
                current
            }
        }
    }

    private fun isCurrent(
        submission: Submission,
        identity: TutorStreamIdentity,
    ): Boolean {
        val active = mutableState.value.active ?: return false
        return active.toSubmission() == submission && active.identity == identity
    }

    private fun isCurrentLocked(submission: Submission): Boolean =
        mutableState.value.active?.toSubmission() == submission

    private data class Submission(
        val ownerVersion: Long,
        val turnVersion: Long,
        val modeVersion: Long,
    )

    private fun TutorActiveStreamMessage.toSubmission() = Submission(
        ownerVersion = ownerVersion,
        turnVersion = turnVersion,
        modeVersion = modeVersion,
    )

    private companion object {
        const val DEFAULT_COALESCE_MILLIS = 64L
        const val MIN_COALESCE_MILLIS = 50L
        const val MAX_COALESCE_MILLIS = 80L
        const val DEFAULT_PLACEHOLDER_DELAY_MILLIS = 300L
        val OWNER_SEQUENCE = AtomicLong(1)

        fun nextOwnerVersion(): Long = OWNER_SEQUENCE.getAndIncrement()
    }
}

private fun Set<String>.plusIfNotNull(value: String?): Set<String> =
    if (value == null) this else this + value
