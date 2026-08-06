package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Exact visible-session facts from the Tutor route.
 *
 * The host may add storage-owned problem identities when it issues the final context, but it may
 * not change any fact in this request.
 */
class TutorOpenResponseLearningContextRequest(
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    questionDocument: QuestionDocument,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val evidenceRequestId: String,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
) {
    val questionDocument: QuestionDocument =
        questionDocument.copy(
            blocks = Collections.unmodifiableList(questionDocument.blocks.toList()),
        )

    init {
        require(conversationId.isNotBlank())
        require(conversationGeneration >= 0L)
        require(conversationStateVersion >= 0L)
        require(questionDocumentId == this.questionDocument.id)
        require(questionRevisionNumber > 0)
        require(subject != SubjectKind.GENERAL)
        require(modeVersion >= 0L)
        require(turnReferenceId.isNotBlank())
        require(turnOrdinal >= 0)
        require(turnGeneration >= 0L)
        require(evidenceRequestId.isNotBlank())
        require(attemptOrdinal in 1..17)
        require(hintCount in 0..32)
        require(requestVersion >= 0L)
    }
}

/**
 * App-issued capability for open-response learning.
 *
 * A route receives either this complete capability or `null`. There is no feature-owned fallback:
 * unavailable context issuance means the visible conversation continues without a memory write.
 */
interface TutorOpenResponseLearningHostPort : OpenResponseLearningEvidenceSubmissionPort {
    val directIntentClassifier: DirectOpenResponseLearningIntentClassifier

    /** Issued off the presentation path; null keeps the visible reply and performs no write. */
    suspend fun issueContext(
        request: TutorOpenResponseLearningContextRequest,
    ): TutorOpenResponseLearningContext?
}

internal data class TutorOpenResponseLearningVisibleSubmission(
    val contextRequest: TutorOpenResponseLearningContextRequest,
    val submissionId: String,
    val sourceMessageId: String,
    val source: TutorOpenResponseMessageSource,
    val rawAnswer: String,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
)

/**
 * Route lifecycle adapter. It never exposes evaluation state to Compose and never reports a
 * memory success to the student.
 */
internal class TutorOpenResponseLearningRouteBridge(
    scope: CoroutineScope,
    private val hostPort: TutorOpenResponseLearningHostPort?,
) : AutoCloseable {
    private val lock = Any()
    private val parentContext = scope.coroutineContext
    private val controller =
        hostPort?.let { host ->
            TutorOpenResponseLearningEvidenceController(
                parentScope = scope,
                submissions = host,
                directIntentClassifier = host.directIntentClassifier,
            )
        }
    private var ownerVersion = 0L
    private var generationJob: Job = SupervisorJob(parentContext[Job])
    private var closed = false

    fun submitVisible(
        submission: TutorOpenResponseLearningVisibleSubmission,
    ): TutorOpenResponseLearningDispatch {
        val host = hostPort ?: return TutorOpenResponseLearningDispatch.NotCurrent
        val activeController = controller ?: return TutorOpenResponseLearningDispatch.NotCurrent
        val ticket: Long
        val worker: CoroutineScope
        synchronized(lock) {
            if (closed) return TutorOpenResponseLearningDispatch.NotCurrent
            generationJob.cancel()
            ownerVersion += 1L
            ticket = ownerVersion
            generationJob = SupervisorJob(parentContext[Job])
            worker = CoroutineScope(parentContext.minusKey(Job) + generationJob)
            activeController.clearCurrent()
        }
        worker.launch {
            val context =
                try {
                    host.issueContext(submission.contextRequest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@launch
            if (
                !isCurrent(ticket) ||
                !context.matches(submission.contextRequest, host.learnerId)
            ) {
                return@launch
            }
            val message =
                try {
                    TutorOpenResponseLearningMessage(
                        context = context,
                        submissionId = submission.submissionId,
                        sourceMessageId = submission.sourceMessageId,
                        source = submission.source,
                        rawAnswer = submission.rawAnswer,
                        elapsedDurationMillis = submission.elapsedDurationMillis,
                        occurredAtEpochMillis = submission.occurredAtEpochMillis,
                    )
                } catch (_: IllegalArgumentException) {
                    return@launch
                }
            try {
                if (!isCurrent(ticket)) return@launch
                activeController.bindCurrent(context)
                activeController.submitVisibleMessage(message)
            } catch (_: IllegalStateException) {
                // A concurrent route clear or close revoked this submission.
            }
        }
        val operationId =
            CanonicalSha256("feature-tutor-open-response-route-dispatch-v1")
                .field("submissionId", submission.submissionId)
                .field("sourceMessageId", submission.sourceMessageId)
                .field("conversationId", submission.contextRequest.conversationId)
                .field("conversationGeneration", submission.contextRequest.conversationGeneration)
                .field("ownerVersion", ticket)
                .finish()
        return TutorOpenResponseLearningDispatch.Scheduled(operationId)
    }

    fun clearCurrent() {
        synchronized(lock) {
            if (closed) return
            generationJob.cancel()
            ownerVersion += 1L
            generationJob = SupervisorJob(parentContext[Job])
            controller?.clearCurrent()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generationJob.cancel()
            ownerVersion += 1L
            controller?.close()
        }
    }

    private fun isCurrent(ticket: Long): Boolean =
        synchronized(lock) {
            !closed && ownerVersion == ticket && generationJob.isActive
        }
}

internal data class TutorOpenResponseRouteAdmission(
    val source: TutorOpenResponseMessageSource,
    val evidenceRequestId: String,
)

internal fun resolveTutorOpenResponseRouteAdmission(
    explanationMode: TutorExplanationMode,
    pendingEvidenceRequestId: String?,
    visibleFreeResponseRequestId: String?,
    directEvidenceRequestId: String,
    selectedChoiceId: String?,
    requestedMove: TutorMoveType?,
    isHintRequest: Boolean,
    allowLongTermLearningWrites: Boolean,
): TutorOpenResponseRouteAdmission? {
    if (
        !allowLongTermLearningWrites ||
        selectedChoiceId != null ||
        requestedMove != null ||
        isHintRequest
    ) {
        return null
    }
    return when (explanationMode) {
        TutorExplanationMode.DIRECT ->
            directEvidenceRequestId
                .takeIf(String::isNotBlank)
                ?.let { requestId ->
                    TutorOpenResponseRouteAdmission(
                        source = TutorOpenResponseMessageSource.DIRECT_CONVERSATION,
                        evidenceRequestId = requestId,
                    )
                }

        TutorExplanationMode.GUIDED ->
            pendingEvidenceRequestId
                ?.takeIf { requestId ->
                    requestId.isNotBlank() && requestId == visibleFreeResponseRequestId
                }
                ?.let { requestId ->
                    TutorOpenResponseRouteAdmission(
                        source = TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE,
                        evidenceRequestId = requestId,
                    )
                }
    }
}

private fun TutorOpenResponseLearningContext.matches(
    request: TutorOpenResponseLearningContextRequest,
    expectedLearnerId: String,
): Boolean =
    learnerId == expectedLearnerId &&
        conversationId == request.conversationId &&
        conversationGeneration == request.conversationGeneration &&
        conversationStateVersion == request.conversationStateVersion &&
        questionDocumentId == request.questionDocumentId &&
        questionRevisionNumber == request.questionRevisionNumber &&
        subject == request.subject &&
        questionDocument == request.questionDocument &&
        explanationMode == request.explanationMode &&
        modeVersion == request.modeVersion &&
        turnReferenceId == request.turnReferenceId &&
        turnOrdinal == request.turnOrdinal &&
        turnGeneration == request.turnGeneration &&
        evidenceRequestId == request.evidenceRequestId &&
        attemptOrdinal == request.attemptOrdinal &&
        hintCount == request.hintCount &&
        answerWasRevealed == request.answerWasRevealed &&
        requestVersion == request.requestVersion
