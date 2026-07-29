package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAnchorFingerprints
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictException
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryConflictReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorAssessmentItem
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Local bridge from one persisted legacy diagnostic choice to the v34 learning-memory ledger.
 *
 * The model supplies teaching content only. This controller owns every durable identity, verifies
 * the exact confirmed question and policy scope, and can only prepare or settle one CHOICE fact.
 */
internal class CapturedTutorChoiceLearningMemoryController(
    private val repository: TutorLearningMemoryRepository,
    private val learnerScopeId: String,
    private val session: ConfirmedTutorSession,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun ensurePrepared(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        modeVersion: Long,
        persistedResponse: TutorTurnResponse? = null,
    ): CapturedTutorChoiceLearningMemoryState = guarded {
        if (persistedResponse != null) {
            val recoveryContext = planContext(planTask, modeVersion)
                ?: return@guarded planIneligible(planTask)
            return@guarded recoverPreparedResponse(recoveryContext, persistedResponse)
        }
        val context = liveEligibleContext(planTask, guidanceState, modeVersion)
            ?: return@guarded ineligible(planTask, guidanceState)

        when (
            val opened = repository.openTurn(
                learnerScopeId = learnerScopeId,
                turnReceiptId = context.turnReceiptId,
            )
        ) {
            is OpenTutorTurnResult.Found -> prepare(context, opened.receipt)
            OpenTutorTurnResult.NotFound -> allocateAndPrepare(context)
        }
    }

    /**
     * Reconciles only the first durable interaction snapshot observed on entry. A pending bridge
     * without its exact response is closed instead of being prepared again, while pre-bridge
     * responses remain history and are never backfilled.
     */
    suspend fun recoverFromInteractionSnapshot(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        modeVersion: Long,
        responses: List<TutorTurnResponse>,
    ): CapturedTutorChoiceLearningMemoryState {
        val context = planContext(planTask, modeVersion)
            ?: return planIneligible(planTask)
        val exactResponses = responses.filter { response ->
            context.matchesTurn(response)
        }
        if (exactResponses.isEmpty()) {
            return cancelPreparedChoice(
                planTask = planTask,
                evidenceRequestId = context.planRequestId,
                modeVersion = modeVersion,
                reason = TutorLearningEvidenceCancellationReason.TURN_SUPERSEDED,
            )
        }
        if (exactResponses.size != 1) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        val persistedResponse = exactResponses.single()
        return when (
            val recovered = ensurePrepared(
                planTask = planTask,
                guidanceState = guidanceState,
                modeVersion = modeVersion,
                persistedResponse = persistedResponse,
            )
        ) {
            is CapturedTutorChoiceLearningMemoryState.Prepared ->
                submitPreparedChoice(
                    planTask = planTask,
                    guidanceState = guidanceState,
                    persistedResponse = persistedResponse,
                    modeVersion = modeVersion,
                )

            else -> recovered
        }
    }

    /**
     * Owns the only authorized ordering for a captured diagnostic choice:
     * prepare durable evidence, persist the response, then settle the exact returned response.
     */
    suspend fun recordPreparedChoice(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        modeVersion: Long,
        command: RecordTutorChoiceCommand,
        isStillCurrent: () -> Boolean,
        persistChoice: suspend (RecordTutorChoiceCommand) -> TutorTurnResponse,
    ): CapturedTutorChoiceLearningMemoryState {
        if (!isStillCurrent()) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.STALE_MODE_EPOCH,
            )
        }
        val context = planContext(planTask, modeVersion)
            ?: return planIneligible(planTask)
        if (!context.matchesCommand(command)) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        val prepared = ensurePrepared(planTask, guidanceState, modeVersion)
        if (prepared !is CapturedTutorChoiceLearningMemoryState.Prepared) return prepared
        if (!isStillCurrent()) {
            return cancelPreparedChoice(
                planTask = planTask,
                evidenceRequestId = context.planRequestId,
                modeVersion = modeVersion,
                reason = TutorLearningEvidenceCancellationReason.TURN_SUPERSEDED,
            )
        }
        val durableCommand = command.copy(
            occurredAtEpochMillis = maxOf(
                command.occurredAtEpochMillis,
                prepared.request.createdAtEpochMillis,
                clock(),
            ),
        )
        val persistedResponse = persistChoice(durableCommand)
        if (!context.matchesPersistedResponse(persistedResponse)) {
            cancelPreparedChoice(
                planTask = planTask,
                evidenceRequestId = context.planRequestId,
                modeVersion = modeVersion,
                reason = TutorLearningEvidenceCancellationReason.TURN_SUPERSEDED,
            )
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        if (!isStillCurrent()) {
            return cancelPreparedChoice(
                planTask = planTask,
                evidenceRequestId = context.planRequestId,
                modeVersion = modeVersion,
                reason = TutorLearningEvidenceCancellationReason.TURN_SUPERSEDED,
            )
        }
        return submitPreparedChoice(
            planTask = planTask,
            guidanceState = guidanceState,
            persistedResponse = persistedResponse,
            modeVersion = modeVersion,
        )
    }

    /**
     * Settles only an already prepared bridge. A legacy response without the deterministic turn
     * receipt predates this bridge and is deliberately never converted into learning evidence.
     */
    suspend fun submitPreparedChoice(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        persistedResponse: TutorTurnResponse,
        modeVersion: Long,
    ): CapturedTutorChoiceLearningMemoryState = guarded {
        val context = planContext(planTask, modeVersion)
            ?: return@guarded planIneligible(planTask)
        val evidenceRequestId = persistedResponse.evidenceRequestId
            ?: return@guarded CapturedTutorChoiceLearningMemoryState.PreBridgeHistory
        if (!context.matchesPersistedResponse(persistedResponse)) {
            return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        val request = when (
            val opened = repository.openEvidenceRequest(learnerScopeId, evidenceRequestId)
        ) {
            is OpenTutorEvidenceResult.Found -> opened.request
            OpenTutorEvidenceResult.NotFound ->
                return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                    CapturedTutorChoiceIneligibleReason.EVIDENCE_NOT_PREPARED,
                )
        }
        val receipt = openExactReceipt(context, request)
            ?: return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        if (request.status.isTerminal) return@guarded request.toControllerState()
        if (request.modeVersion != modeVersion) {
            return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.STALE_MODE_EPOCH,
            )
        }
        submit(context, receipt, request, persistedResponse)
    }

    /**
     * Uses the same terminal compare-and-set as submission. Cancellation never constructs an
     * anchor or source fact, and a terminal replay always returns the durable winner.
     */
    suspend fun cancelPreparedChoice(
        planTask: ModelTaskSnapshot,
        evidenceRequestId: String,
        modeVersion: Long,
        reason: TutorLearningEvidenceCancellationReason,
    ): CapturedTutorChoiceLearningMemoryState = guarded {
        val context = planContext(planTask, modeVersion)
            ?: return@guarded planIneligible(planTask)
        if (evidenceRequestId != context.planRequestId) {
            return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        val request = when (
            val opened = repository.openEvidenceRequest(learnerScopeId, evidenceRequestId)
        ) {
            is OpenTutorEvidenceResult.Found -> opened.request
            OpenTutorEvidenceResult.NotFound ->
                return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                    CapturedTutorChoiceIneligibleReason.EVIDENCE_NOT_PREPARED,
                )
        }
        val receipt = openExactReceipt(context, request)
            ?: return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        if (request.status.isTerminal) return@guarded request.toControllerState()
        if (modeVersion < request.modeVersion) {
            return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.STALE_MODE_EPOCH,
            )
        }
        cancel(context, receipt, request, reason)
    }

    private suspend fun allocateAndPrepare(
        context: EligibleChoiceContext,
    ): CapturedTutorChoiceLearningMemoryState {
        val conversation = when (
            val opened = repository.openConversation(
                OpenTutorConversationCommand(
                    learnerScopeId = learnerScopeId,
                    conversationId = context.conversationId,
                    conversationGeneration = CAPTURED_CONVERSATION_GENERATION,
                ),
            )
        ) {
            is OpenTutorConversationResult.Opened -> opened.conversation
            OpenTutorConversationResult.NotFound -> createConversation(context)
        }
        if (!conversation.isEligibleActiveConversation(context.conversationId)) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }
        if (conversation.stateVersion >= Int.MAX_VALUE.toLong()) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }

        val occurredAt = maxOf(clock(), conversation.createdAtEpochMillis)
        val expectedTurnOrdinal = conversation.stateVersion.toInt() + 1
        val allocationPayload = CanonicalSha256(TURN_ALLOCATION_PAYLOAD_DOMAIN)
            .field("learnerScopeId", learnerScopeId)
            .field("conversationId", conversation.conversationId)
            .field("conversationGeneration", conversation.generation)
            .field("conversationStateVersion", conversation.stateVersion)
            .field("expectedTurnOrdinal", expectedTurnOrdinal)
            .field("turnReceiptId", context.turnReceiptId)
            .field("subject", context.subject.name)
            .field("problemAnchorId", context.problemAnchorId)
            .field("requestVersion", CAPTURED_CHOICE_REQUEST_VERSION)
            .field("modeVersion", context.modeVersion)
            .field("mode", TutorExplanationMode.GUIDED.name)
            .field("directiveFingerprint", context.directiveFingerprint)
            .field("studentMessageFingerprint", context.studentMessageFingerprint)
            .field("studentMessageSummary", CAPTURED_CHOICE_PENDING_SUMMARY)
            .finish()
        val allocation = repository.allocateTurn(
            AllocateTutorTurnCommand(
                learnerScopeId = learnerScopeId,
                conversationId = conversation.conversationId,
                conversationGeneration = conversation.generation,
                expectedConversationStateVersion = conversation.stateVersion,
                expectedTurnOrdinal = expectedTurnOrdinal,
                turnReceiptId = context.turnReceiptId,
                subject = context.subject,
                problemAnchorId = context.problemAnchorId,
                requestVersion = CAPTURED_CHOICE_REQUEST_VERSION,
                modeVersion = context.modeVersion,
                mode = TutorExplanationMode.GUIDED,
                directiveFingerprint = context.directiveFingerprint,
                studentMessageFingerprint = context.studentMessageFingerprint,
                studentMessageSummary = CAPTURED_CHOICE_PENDING_SUMMARY,
                clientIdempotencyKey = opaqueId(
                    TURN_ALLOCATION_IDEMPOTENCY_DOMAIN,
                    context.turnReceiptId,
                ),
                payloadFingerprint = allocationPayload,
                occurredAtEpochMillis = occurredAt,
            ),
        )
        if (
            !allocation.receipt.matches(context) ||
            !allocation.conversation.isEligibleActiveConversation(context.conversationId)
        ) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }
        return prepare(context, allocation.receipt)
    }

    private suspend fun createConversation(context: EligibleChoiceContext): TutorConversation {
        val createPayload = CanonicalSha256(CONVERSATION_CREATE_PAYLOAD_DOMAIN)
            .field("learnerScopeId", learnerScopeId)
            .field("conversationId", context.conversationId)
            .field("conversationGeneration", CAPTURED_CONVERSATION_GENERATION)
            .finish()
        return repository.createConversation(
            CreateTutorConversationCommand(
                learnerScopeId = learnerScopeId,
                conversationId = context.conversationId,
                conversationGeneration = CAPTURED_CONVERSATION_GENERATION,
                clientIdempotencyKey = opaqueId(
                    CONVERSATION_CREATE_IDEMPOTENCY_DOMAIN,
                    learnerScopeId,
                    context.conversationId,
                ),
                payloadFingerprint = createPayload,
                occurredAtEpochMillis = clock(),
            ),
        ).conversation
    }

    private suspend fun prepare(
        context: EligibleChoiceContext,
        receipt: TutorTurnReceipt,
    ): CapturedTutorChoiceLearningMemoryState {
        if (!receipt.matches(context)) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }
        val payload = CanonicalSha256(EVIDENCE_PREPARE_PAYLOAD_DOMAIN)
            .field("learnerScopeId", learnerScopeId)
            .field("conversationId", receipt.conversationId)
            .field("conversationGeneration", receipt.conversationGeneration)
            .field("conversationStateVersion", receipt.conversationStateVersion)
            .field("turnReceiptId", receipt.turnReceiptId)
            .field("turnOrdinal", receipt.turnOrdinal)
            .field("subject", context.subject.name)
            .field("problemAnchorId", context.problemAnchorId)
            .field("evidenceRequestId", context.planRequestId)
            .field("kind", TutorEvidenceRequestKind.CHOICE.name)
            .field("requestVersion", CAPTURED_CHOICE_REQUEST_VERSION)
            .field("modeVersion", context.modeVersion)
            .field("mode", TutorExplanationMode.GUIDED.name)
            .field("directiveFingerprint", context.directiveFingerprint)
            .finish()
        val prepared = repository.prepareEvidenceRequest(
            PrepareTutorEvidenceCommand(
                learnerScopeId = learnerScopeId,
                conversationId = receipt.conversationId,
                conversationGeneration = receipt.conversationGeneration,
                expectedConversationStateVersion = receipt.conversationStateVersion,
                turnReceiptId = receipt.turnReceiptId,
                turnOrdinal = receipt.turnOrdinal,
                subject = context.subject,
                problemAnchorId = context.problemAnchorId,
                evidenceRequestId = context.planRequestId,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = CAPTURED_CHOICE_REQUEST_VERSION,
                modeVersion = context.modeVersion,
                mode = TutorExplanationMode.GUIDED,
                directiveFingerprint = context.directiveFingerprint,
                clientIdempotencyKey = opaqueId(
                    EVIDENCE_PREPARE_IDEMPOTENCY_DOMAIN,
                    context.planRequestId,
                    receipt.turnReceiptId,
                ),
                payloadFingerprint = payload,
                occurredAtEpochMillis = maxOf(clock(), receipt.occurredAtEpochMillis),
            ),
        )
        if (!prepared.request.matches(context, receipt)) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }
        return prepared.request.toControllerState()
    }

    private suspend fun recoverPreparedResponse(
        context: EligibleChoiceContext,
        response: TutorTurnResponse,
    ): CapturedTutorChoiceLearningMemoryState {
        val evidenceRequestId = response.evidenceRequestId
            ?: return CapturedTutorChoiceLearningMemoryState.PreBridgeHistory
        if (!context.matchesPersistedResponse(response)) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        val request = when (
            val opened = repository.openEvidenceRequest(learnerScopeId, evidenceRequestId)
        ) {
            is OpenTutorEvidenceResult.Found -> opened.request
            OpenTutorEvidenceResult.NotFound ->
                return CapturedTutorChoiceLearningMemoryState.Ineligible(
                    CapturedTutorChoiceIneligibleReason.EVIDENCE_NOT_PREPARED,
                )
        }
        val receipt = openExactReceipt(context, request)
            ?: return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        if (!request.matchesPlan(context) || !request.matches(receipt)) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }
        if (request.status.isTerminal) return request.toControllerState()
        return if (request.modeVersion == context.modeVersion) {
            request.toControllerState()
        } else {
            CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.STALE_MODE_EPOCH,
            )
        }
    }

    private suspend fun openExactReceipt(
        context: EligibleChoiceContext,
        request: TutorEvidenceRequest,
    ): TutorTurnReceipt? {
        if (!request.matchesPlan(context)) return null
        return when (
            val opened = repository.openTurn(
                learnerScopeId = learnerScopeId,
                turnReceiptId = request.turnReceiptId,
            )
        ) {
            is OpenTutorTurnResult.Found ->
                opened.receipt.takeIf { receipt -> request.matches(receipt) }

            OpenTutorTurnResult.NotFound -> null
        }
    }

    private suspend fun submit(
        context: EligibleChoiceContext,
        receipt: TutorTurnReceipt,
        request: TutorEvidenceRequest,
        response: TutorTurnResponse,
    ): CapturedTutorChoiceLearningMemoryState {
        val responseOccurredAt = checkNotNull(response.choiceSubmittedAtEpochMillis)
        if (
            responseOccurredAt < receipt.occurredAtEpochMillis ||
            responseOccurredAt < request.createdAtEpochMillis
        ) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_PRECEDES_PREPARATION,
            )
        }
        val responseSummary = response.selectedChoiceMarkdown
            ?.trim()
            ?.take(LearningObservationSourceFact.MAX_SUMMARY_CHARS)
            ?.trim()
            .orEmpty()
        if (
            responseSummary.isBlank() ||
            responseSummary.any { character ->
                character.isISOControl() && character !in "\n\r\t"
            }
        ) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }
        val responseFingerprint = responseFingerprint(response)
        val sourceFactId = opaqueId(
            SOURCE_FACT_ID_DOMAIN,
            learnerScopeId,
            request.evidenceRequestId,
            responseFingerprint,
        )
        val sourceFact = LearningObservationSourceFact(
            sourceFactId = sourceFactId,
            learnerScopeId = learnerScopeId,
            source = LearningObservationSource.TUTOR_CHOICE,
            factKind = when {
                response.selectionWasCorrect != true ->
                    LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE

                // The diagnostic answer itself comes from a model-produced plan. Until an
                // independently trusted answer key is attached, recovery must remain
                // deterministic and fail closed instead of promoting a correct click to
                // independent mastery based on transient guidance counters.
                else -> LearningObservationFactKind.MODEL_EVALUATED_ASSISTED_CORRECT_RESPONSE
            },
            anchorId = context.problemAnchorId,
            subject = context.subject,
            conversationGeneration = receipt.conversationGeneration,
            conversationId = receipt.conversationId,
            turnReceiptId = receipt.turnReceiptId,
            evidenceRequestId = request.evidenceRequestId,
            responseFingerprint = responseFingerprint,
            responseSummary = responseSummary,
            occurredAtEpochMillis = responseOccurredAt,
            sourceVersion = CAPTURED_CHOICE_SOURCE_VERSION,
        )
        val anchors = TutorLearningEvidenceAnchorFingerprints(
            questionFingerprint = context.questionFingerprint,
            problemRevisionFingerprint = context.revisionFingerprint,
            fingerprintVersion = CAPTURED_QUESTION_FINGERPRINT_VERSION,
            turnFingerprint = turnFingerprint(receipt),
            directiveFingerprint = context.directiveFingerprint,
        )
        val payload = submissionPayload(context, request, receipt, sourceFact, anchors)
        return repository.finalizeEvidence(
            FinalizeTutorEvidenceCommand(
                learnerScopeId = learnerScopeId,
                conversationId = receipt.conversationId,
                conversationGeneration = receipt.conversationGeneration,
                conversationStateVersion = receipt.conversationStateVersion,
                turnReceiptId = receipt.turnReceiptId,
                turnOrdinal = receipt.turnOrdinal,
                subject = context.subject,
                problemAnchorId = context.problemAnchorId,
                evidenceRequestId = request.evidenceRequestId,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = CAPTURED_CHOICE_REQUEST_VERSION,
                modeVersion = request.modeVersion,
                mode = TutorExplanationMode.GUIDED,
                directiveFingerprint = context.directiveFingerprint,
                expectedEvidenceStateVersion = request.stateVersion,
                terminal = TutorLearningEvidenceTerminal.Submitted(sourceFact, anchors),
                clientIdempotencyKey = opaqueId(
                    EVIDENCE_SUBMIT_IDEMPOTENCY_DOMAIN,
                    request.evidenceRequestId,
                    responseFingerprint,
                ),
                payloadFingerprint = payload,
                occurredAtEpochMillis = maxOf(
                    clock(),
                    responseOccurredAt,
                    request.createdAtEpochMillis,
                ),
            ),
        ).toControllerState()
    }

    private suspend fun cancel(
        context: EligibleChoiceContext,
        receipt: TutorTurnReceipt,
        request: TutorEvidenceRequest,
        reason: TutorLearningEvidenceCancellationReason,
    ): CapturedTutorChoiceLearningMemoryState {
        val payload = CanonicalSha256(EVIDENCE_CANCEL_PAYLOAD_DOMAIN)
            .field("learnerScopeId", learnerScopeId)
            .field("conversationId", receipt.conversationId)
            .field("conversationGeneration", receipt.conversationGeneration)
            .field("conversationStateVersion", receipt.conversationStateVersion)
            .field("turnReceiptId", receipt.turnReceiptId)
            .field("turnOrdinal", receipt.turnOrdinal)
            .field("subject", context.subject.name)
            .field("problemAnchorId", context.problemAnchorId)
            .field("evidenceRequestId", request.evidenceRequestId)
            .field("kind", TutorEvidenceRequestKind.CHOICE.name)
            .field("requestVersion", CAPTURED_CHOICE_REQUEST_VERSION)
            .field("modeVersion", request.modeVersion)
            .field("mode", TutorExplanationMode.GUIDED.name)
            .field("directiveFingerprint", context.directiveFingerprint)
            .field("terminalStatus", TutorEvidenceRequestStatus.CANCELLED.name)
            .finish()
        return repository.finalizeEvidence(
            FinalizeTutorEvidenceCommand(
                learnerScopeId = learnerScopeId,
                conversationId = receipt.conversationId,
                conversationGeneration = receipt.conversationGeneration,
                conversationStateVersion = receipt.conversationStateVersion,
                turnReceiptId = receipt.turnReceiptId,
                turnOrdinal = receipt.turnOrdinal,
                subject = context.subject,
                problemAnchorId = context.problemAnchorId,
                evidenceRequestId = request.evidenceRequestId,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = CAPTURED_CHOICE_REQUEST_VERSION,
                modeVersion = request.modeVersion,
                mode = TutorExplanationMode.GUIDED,
                directiveFingerprint = context.directiveFingerprint,
                expectedEvidenceStateVersion = request.stateVersion,
                terminal = TutorLearningEvidenceTerminal.Cancelled(reason),
                clientIdempotencyKey = opaqueId(
                    EVIDENCE_CANCEL_IDEMPOTENCY_DOMAIN,
                    request.evidenceRequestId,
                    receipt.turnReceiptId,
                ),
                payloadFingerprint = payload,
                occurredAtEpochMillis = maxOf(clock(), request.createdAtEpochMillis),
            ),
        ).toControllerState()
    }

    private fun submissionPayload(
        context: EligibleChoiceContext,
        request: TutorEvidenceRequest,
        receipt: TutorTurnReceipt,
        sourceFact: LearningObservationSourceFact,
        anchors: TutorLearningEvidenceAnchorFingerprints,
    ): String = CanonicalSha256(EVIDENCE_SUBMIT_PAYLOAD_DOMAIN)
        .field("learnerScopeId", learnerScopeId)
        .field("conversationId", receipt.conversationId)
        .field("conversationGeneration", receipt.conversationGeneration)
        .field("conversationStateVersion", receipt.conversationStateVersion)
        .field("turnReceiptId", receipt.turnReceiptId)
        .field("turnOrdinal", receipt.turnOrdinal)
        .field("subject", context.subject.name)
        .field("problemAnchorId", context.problemAnchorId)
        .field("evidenceRequestId", request.evidenceRequestId)
        .field("kind", TutorEvidenceRequestKind.CHOICE.name)
        .field("requestVersion", CAPTURED_CHOICE_REQUEST_VERSION)
        .field("modeVersion", request.modeVersion)
        .field("mode", TutorExplanationMode.GUIDED.name)
        .field("directiveFingerprint", context.directiveFingerprint)
        .field("expectedEvidenceStateVersion", request.stateVersion)
        .field("terminalStatus", TutorEvidenceRequestStatus.SUBMITTED.name)
        .field("sourceFactId", sourceFact.sourceFactId)
        .field("factKind", sourceFact.factKind.name)
        .field("responseFingerprint", sourceFact.responseFingerprint)
        .field("responseSummary", sourceFact.responseSummary)
        .field("responseOccurredAt", sourceFact.occurredAtEpochMillis)
        .field("sourceVersion", sourceFact.sourceVersion)
        .field("questionFingerprint", anchors.questionFingerprint)
        .field("revisionFingerprint", anchors.problemRevisionFingerprint)
        .field("fingerprintVersion", anchors.fingerprintVersion)
        .field("turnFingerprint", anchors.turnFingerprint)
        .finish()

    private fun liveEligibleContext(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        modeVersion: Long,
    ): EligibleChoiceContext? {
        val context = planContext(planTask, modeVersion) ?: return null
        return context.takeIf {
            guidanceState.mode == TutorExplanationMode.GUIDED &&
                guidanceState.problem.problemId == session.questionDocument.document.id &&
                guidanceState.problem.revisionNumber == session.draftRevisionNumber &&
                guidanceState.pendingEvidenceRequestId == planTask.request.requestId &&
                guidanceState.authorizeEvidence(planTask.request.requestId).mayWriteLearningEvidence
        }
    }

    private fun planContext(
        planTask: ModelTaskSnapshot,
        modeVersion: Long,
    ): EligibleChoiceContext? {
        if (modeVersion < 0) return null
        if (planTask.status != ModelTaskStatus.SUCCEEDED) return null
        val input = planTask.request.input as? TutorPlanInput ?: return null
        val output = planTask.output as? TutorPlanOutput ?: return null
        val item = output.plan.diagnosticItem ?: return null
        if (output.plan.interactionDirective != null) return null
        if (!planTask.request.requestId.isSafeOpaqueId()) return null
        val subject = SubjectKind.entries.singleOrNull { candidate ->
            candidate.name == session.subject
        }?.takeUnless { it == SubjectKind.GENERAL } ?: return null
        if (
            input.sessionId != session.sessionId ||
            input.draftRevisionNumber != session.draftRevisionNumber ||
            input.subject != session.subject ||
            input.questionDocument != session.questionDocument.document ||
            output.sessionId != session.sessionId ||
            output.draftRevisionNumber != session.draftRevisionNumber ||
            output.questionDocumentId != session.questionDocument.document.id ||
            output.cycleOrdinal != input.cycleOrdinal ||
            output.turnOrdinal != input.turnOrdinal
        ) {
            return null
        }
        val revisionFingerprint = CapturedQuestionDocumentFingerprint.of(session.questionDocument)
        val questionFingerprint = CanonicalSha256(CAPTURED_QUESTION_FINGERPRINT_VERSION)
            .field("draftId", session.draftId)
            .finish()
        val directiveFingerprint = directiveFingerprint(
            planTask = planTask,
            input = input,
            output = output,
            item = item,
            revisionFingerprint = revisionFingerprint,
        )
        val problemAnchorId = opaqueId(
            PROBLEM_ANCHOR_ID_DOMAIN,
            learnerScopeId,
            subject.name,
            questionFingerprint,
            revisionFingerprint,
            CAPTURED_QUESTION_FINGERPRINT_VERSION,
        )
        val turnReceiptId = opaqueId(
            TURN_RECEIPT_ID_DOMAIN,
            learnerScopeId,
            session.sessionId,
            session.draftId,
            decimal(session.draftRevisionNumber),
            session.questionDocument.document.id,
            planTask.request.requestId,
            decimal(output.cycleOrdinal),
            decimal(output.turnOrdinal),
            directiveFingerprint,
        )
        val conversationId = opaqueId(
            CONVERSATION_ID_DOMAIN,
            learnerScopeId,
            session.sessionId,
            session.draftId,
            decimal(session.draftRevisionNumber),
            session.questionDocument.document.id,
        )
        return EligibleChoiceContext(
            planRequestId = planTask.request.requestId,
            input = input,
            output = output,
            item = item,
            subject = subject,
            questionFingerprint = questionFingerprint,
            revisionFingerprint = revisionFingerprint,
            directiveFingerprint = directiveFingerprint,
            problemAnchorId = problemAnchorId,
            turnReceiptId = turnReceiptId,
            conversationId = conversationId,
            modeVersion = modeVersion,
            studentMessageFingerprint = CanonicalSha256(TURN_PLACEHOLDER_FINGERPRINT_DOMAIN)
                .field("turnReceiptId", turnReceiptId)
                .field("planRequestId", planTask.request.requestId)
                .finish(),
        )
    }

    private fun planIneligible(
        planTask: ModelTaskSnapshot,
    ): CapturedTutorChoiceLearningMemoryState.Ineligible {
        val reason = when {
            planTask.status != ModelTaskStatus.SUCCEEDED ->
                CapturedTutorChoiceIneligibleReason.TASK_NOT_SUCCEEDED

            planTask.request.input !is TutorPlanInput || planTask.output !is TutorPlanOutput ->
                CapturedTutorChoiceIneligibleReason.NOT_A_PLAN

            (planTask.output as TutorPlanOutput).plan.diagnosticItem == null ->
                CapturedTutorChoiceIneligibleReason.NO_LEGACY_DIAGNOSTIC

            (planTask.output as TutorPlanOutput).plan.interactionDirective != null ->
                CapturedTutorChoiceIneligibleReason.STRUCTURED_DIRECTIVE_PRESENT

            SubjectKind.entries.none { it.name == session.subject && it != SubjectKind.GENERAL } ->
                CapturedTutorChoiceIneligibleReason.INVALID_SUBJECT

            else -> CapturedTutorChoiceIneligibleReason.PLAN_SCOPE_MISMATCH
        }
        return CapturedTutorChoiceLearningMemoryState.Ineligible(reason)
    }

    private fun ineligible(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
    ): CapturedTutorChoiceLearningMemoryState.Ineligible {
        val planFailure = planIneligible(planTask)
        val reason = when {
            planFailure.reason != CapturedTutorChoiceIneligibleReason.PLAN_SCOPE_MISMATCH ->
                planFailure.reason

            guidanceState.mode != TutorExplanationMode.GUIDED ||
                guidanceState.pendingEvidenceRequestId != planTask.request.requestId ->
                CapturedTutorChoiceIneligibleReason.GUIDANCE_NOT_AUTHORIZED

            else -> CapturedTutorChoiceIneligibleReason.PLAN_SCOPE_MISMATCH
        }
        return CapturedTutorChoiceLearningMemoryState.Ineligible(reason)
    }

    private fun directiveFingerprint(
        planTask: ModelTaskSnapshot,
        input: TutorPlanInput,
        output: TutorPlanOutput,
        item: TutorAssessmentItem,
        revisionFingerprint: String,
    ): String {
        val digest = CanonicalSha256(DIRECTIVE_FINGERPRINT_DOMAIN)
            .field("planRequestId", planTask.request.requestId)
            .field("sessionId", session.sessionId)
            .field("draftId", session.draftId)
            .field("draftRevisionNumber", session.draftRevisionNumber)
            .field("questionDocumentId", session.questionDocument.document.id)
            .field("revisionFingerprint", revisionFingerprint)
            .field("cycleOrdinal", input.cycleOrdinal)
            .field("turnOrdinal", input.turnOrdinal)
            .field("diagnosticItemId", item.id)
            .field("stemMarkdown", item.stemMarkdown)
            .nullableField("promptMarkdown", item.promptMarkdown)
            .field("choiceCount", item.choices.size)
        item.choices.forEachIndexed { index, choice ->
            digest.field("choice[$index].id", choice.id)
                .field("choice[$index].markdown", choice.markdown)
                .nullableField("choice[$index].feedbackMarkdown", choice.feedbackMarkdown)
                .field("choice[$index].followUpCount", choice.followUpIds.size)
            choice.followUpIds.forEachIndexed { followUpIndex, followUpId ->
                digest.field("choice[$index].followUp[$followUpIndex]", followUpId)
            }
        }
        digest.field("correctChoiceId", item.correctChoiceId)
            .field("initialFollowUpCount", item.initialFollowUpIds.size)
        item.initialFollowUpIds.forEachIndexed { index, followUpId ->
            digest.field("initialFollowUp[$index]", followUpId)
        }
        val sortedKnowledgeNodes = item.knowledgeNodeIds.sorted()
        digest.field("knowledgeNodeCount", sortedKnowledgeNodes.size)
        sortedKnowledgeNodes.forEachIndexed { index, knowledgeNodeId ->
            digest.field("knowledgeNode[$index]", knowledgeNodeId)
        }
        return digest.field("outputCycleOrdinal", output.cycleOrdinal)
            .field("outputTurnOrdinal", output.turnOrdinal)
            .finish()
    }

    private fun responseFingerprint(response: TutorTurnResponse): String =
        CanonicalSha256(RESPONSE_FINGERPRINT_DOMAIN)
            .field("sessionId", response.sessionId)
            .field("questionDocumentId", response.questionDocumentId)
            .field("revisionNumber", response.revisionNumber)
            .field("cycleOrdinal", response.cycleOrdinal)
            .field("turnOrdinal", response.turnOrdinal)
            .nullableField("diagnosticStemMarkdown", response.diagnosticStemMarkdown)
            .nullableField("selectedChoiceId", response.selectedChoiceId)
            .nullableField("selectedChoiceMarkdown", response.selectedChoiceMarkdown)
            .nullableField(
                "selectionWasCorrect",
                response.selectionWasCorrect?.let(::boolean),
            )
            .nullableField("feedbackMarkdown", response.feedbackMarkdown)
            .nullableField("requestedMove", response.requestedMove?.name)
            .field("solutionRevealed", response.solutionRevealed)
            .field("submittedAtEpochMillis", response.submittedAtEpochMillis)
            .field("updatedAtEpochMillis", response.updatedAtEpochMillis)
            .nullableField(
                "choiceSubmittedAtEpochMillis",
                response.choiceSubmittedAtEpochMillis?.let(::decimal),
            )
            .nullableField("evidenceRequestId", response.evidenceRequestId)
            .finish()

    private fun turnFingerprint(receipt: TutorTurnReceipt): String =
        CanonicalSha256(TURN_FINGERPRINT_DOMAIN)
            .field("learnerScopeId", learnerScopeId)
            .field("conversationId", receipt.conversationId)
            .field("conversationGeneration", receipt.conversationGeneration)
            .field("conversationStateVersion", receipt.conversationStateVersion)
            .field("turnReceiptId", receipt.turnReceiptId)
            .field("turnOrdinal", receipt.turnOrdinal)
            .field("subject", receipt.subject.name)
            .nullableField("problemAnchorId", receipt.problemAnchorId)
            .field("requestVersion", receipt.requestVersion)
            .field("modeVersion", receipt.modeVersion)
            .field("mode", receipt.explanationMode.name)
            .field("directiveFingerprint", receipt.directiveFingerprint)
            .field("studentMessageFingerprint", receipt.studentMessageFingerprint)
            .field("occurredAtEpochMillis", receipt.occurredAtEpochMillis)
            .finish()

    private suspend fun guarded(
        action: suspend () -> CapturedTutorChoiceLearningMemoryState,
    ): CapturedTutorChoiceLearningMemoryState = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (conflict: TutorLearningMemoryConflictException) {
        if (
            conflict.reason == TutorLearningMemoryConflictReason.STATE_VERSION_MISMATCH ||
            conflict.reason == TutorLearningMemoryConflictReason.TURN_ORDINAL_MISMATCH
        ) {
            CapturedTutorChoiceLearningMemoryState.RetryableFailure(conflict)
        } else {
            CapturedTutorChoiceLearningMemoryState.PermanentConflict(conflict)
        }
    } catch (failure: Exception) {
        CapturedTutorChoiceLearningMemoryState.RetryableFailure(failure)
    }

    private fun TutorConversation.isEligibleActiveConversation(
        expectedConversationId: String,
    ): Boolean =
        learnerScopeId == this@CapturedTutorChoiceLearningMemoryController.learnerScopeId &&
            conversationId == expectedConversationId &&
            status == TutorConversationStatus.ACTIVE &&
            generation == CAPTURED_CONVERSATION_GENERATION

    private fun TutorTurnReceipt.matches(context: EligibleChoiceContext): Boolean =
        turnReceiptId == context.turnReceiptId &&
            conversationId == context.conversationId &&
            conversationGeneration == CAPTURED_CONVERSATION_GENERATION &&
            subject == context.subject &&
            problemAnchorId == context.problemAnchorId &&
            requestVersion == CAPTURED_CHOICE_REQUEST_VERSION &&
            modeVersion == context.modeVersion &&
            explanationMode == TutorExplanationMode.GUIDED &&
            directiveFingerprint == context.directiveFingerprint &&
            studentMessageFingerprint == context.studentMessageFingerprint &&
            studentMessageSummary == CAPTURED_CHOICE_PENDING_SUMMARY

    private fun TutorEvidenceRequest.matches(
        context: EligibleChoiceContext,
        receipt: TutorTurnReceipt,
    ): Boolean = evidenceRequestId == context.planRequestId &&
        kind == TutorEvidenceRequestKind.CHOICE &&
        matches(receipt)

    private fun TutorEvidenceRequest.matchesPlan(
        context: EligibleChoiceContext,
    ): Boolean = evidenceRequestId == context.planRequestId &&
        conversationId == context.conversationId &&
        conversationGeneration == CAPTURED_CONVERSATION_GENERATION &&
        turnReceiptId == context.turnReceiptId &&
        subject == context.subject &&
        problemAnchorId == context.problemAnchorId &&
        kind == TutorEvidenceRequestKind.CHOICE &&
        requestVersion == CAPTURED_CHOICE_REQUEST_VERSION &&
        explanationMode == TutorExplanationMode.GUIDED &&
        directiveFingerprint == context.directiveFingerprint

    private fun EligibleChoiceContext.matchesPersistedResponse(
        response: TutorTurnResponse,
    ): Boolean {
        if (
            !response.hasChoicePayload ||
            response.sessionId != session.sessionId ||
            response.questionDocumentId != session.questionDocument.document.id ||
            response.revisionNumber != session.draftRevisionNumber ||
            response.cycleOrdinal != output.cycleOrdinal ||
            response.turnOrdinal != output.turnOrdinal ||
            response.diagnosticStemMarkdown != item.stemMarkdown ||
            response.evidenceRequestId != planRequestId ||
            response.requestedMove != null ||
            response.solutionRevealed
        ) {
            return false
        }
        val selected = item.choices.firstOrNull { choice ->
            choice.id == response.selectedChoiceId
        } ?: return false
        return response.selectedChoiceMarkdown == selected.markdown &&
            response.selectionWasCorrect == (selected.id == item.correctChoiceId) &&
            response.feedbackMarkdown == selected.feedbackMarkdown
    }

    private fun EligibleChoiceContext.matchesTurn(
        response: TutorTurnResponse,
    ): Boolean =
        response.hasChoicePayload &&
            response.sessionId == session.sessionId &&
            response.questionDocumentId == session.questionDocument.document.id &&
            response.revisionNumber == session.draftRevisionNumber &&
            response.cycleOrdinal == output.cycleOrdinal &&
            response.turnOrdinal == output.turnOrdinal

    private fun EligibleChoiceContext.matchesCommand(
        command: RecordTutorChoiceCommand,
    ): Boolean {
        if (
            command.sessionId != session.sessionId ||
            command.questionDocumentId != session.questionDocument.document.id ||
            command.revisionNumber != session.draftRevisionNumber ||
            command.cycleOrdinal != output.cycleOrdinal ||
            command.turnOrdinal != output.turnOrdinal ||
            command.diagnosticStemMarkdown != item.stemMarkdown ||
            command.evidenceRequestId != planRequestId
        ) {
            return false
        }
        val selected = item.choices.firstOrNull { choice ->
            choice.id == command.selectedChoiceId
        } ?: return false
        return command.selectedChoiceMarkdown == selected.markdown &&
            command.selectionWasCorrect == (selected.id == item.correctChoiceId) &&
            command.feedbackMarkdown == selected.feedbackMarkdown
    }

    private data class EligibleChoiceContext(
        val planRequestId: String,
        val input: TutorPlanInput,
        val output: TutorPlanOutput,
        val item: TutorAssessmentItem,
        val subject: SubjectKind,
        val questionFingerprint: String,
        val revisionFingerprint: String,
        val directiveFingerprint: String,
        val problemAnchorId: String,
        val turnReceiptId: String,
        val conversationId: String,
        val modeVersion: Long,
        val studentMessageFingerprint: String,
    )
}

internal sealed interface CapturedTutorChoiceLearningMemoryState {
    data class Ineligible(
        val reason: CapturedTutorChoiceIneligibleReason,
    ) : CapturedTutorChoiceLearningMemoryState

    data class Prepared(
        val request: TutorEvidenceRequest,
    ) : CapturedTutorChoiceLearningMemoryState

    data class Submitted(
        val request: TutorEvidenceRequest,
        val sourceFact: LearningObservationSourceFact?,
    ) : CapturedTutorChoiceLearningMemoryState

    data class Cancelled(
        val request: TutorEvidenceRequest,
    ) : CapturedTutorChoiceLearningMemoryState

    data object PreBridgeHistory : CapturedTutorChoiceLearningMemoryState

    data class RetryableFailure(
        val cause: Exception,
    ) : CapturedTutorChoiceLearningMemoryState

    data class PermanentConflict(
        val cause: TutorLearningMemoryConflictException,
    ) : CapturedTutorChoiceLearningMemoryState
}

internal enum class CapturedTutorChoiceIneligibleReason {
    TASK_NOT_SUCCEEDED,
    NOT_A_PLAN,
    NO_LEGACY_DIAGNOSTIC,
    STRUCTURED_DIRECTIVE_PRESENT,
    INVALID_SUBJECT,
    GUIDANCE_NOT_AUTHORIZED,
    PLAN_SCOPE_MISMATCH,
    RESPONSE_SCOPE_MISMATCH,
    RESPONSE_PRECEDES_PREPARATION,
    TURN_NOT_PREPARED,
    EVIDENCE_NOT_PREPARED,
    STALE_MODE_EPOCH,
    DURABLE_SCOPE_MISMATCH,
}

private fun TutorEvidenceRequest.toControllerState(): CapturedTutorChoiceLearningMemoryState =
    when (status) {
        TutorEvidenceRequestStatus.PENDING ->
            CapturedTutorChoiceLearningMemoryState.Prepared(this)

        TutorEvidenceRequestStatus.SUBMITTED ->
            CapturedTutorChoiceLearningMemoryState.Submitted(this, sourceFact = null)

        TutorEvidenceRequestStatus.CANCELLED ->
            CapturedTutorChoiceLearningMemoryState.Cancelled(this)
    }

private fun FinalizeTutorEvidenceResult.toControllerState():
    CapturedTutorChoiceLearningMemoryState = when (this) {
    is FinalizeTutorEvidenceResult.Submitted ->
        CapturedTutorChoiceLearningMemoryState.Submitted(request, sourceFact)

    is FinalizeTutorEvidenceResult.Cancelled ->
        CapturedTutorChoiceLearningMemoryState.Cancelled(request)

    is FinalizeTutorEvidenceResult.Replayed -> when (request.status) {
        TutorEvidenceRequestStatus.SUBMITTED ->
            CapturedTutorChoiceLearningMemoryState.Submitted(request, sourceFact)

        TutorEvidenceRequestStatus.CANCELLED ->
            CapturedTutorChoiceLearningMemoryState.Cancelled(request)

        TutorEvidenceRequestStatus.PENDING ->
            error("A finalization replay cannot remain pending")
    }
}

private class CanonicalSha256(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        field("domain", domain)
        field("canonicalVersion", "1")
    }

    fun field(name: String, value: String): CanonicalSha256 = apply {
        append(name)
        append("PRESENT")
        append(value)
    }

    fun field(name: String, value: Long): CanonicalSha256 =
        field(name, decimal(value))

    fun field(name: String, value: Int): CanonicalSha256 =
        field(name, decimal(value))

    fun field(name: String, value: Boolean): CanonicalSha256 =
        field(name, boolean(value))

    fun nullableField(name: String, value: String?): CanonicalSha256 = apply {
        append(name)
        if (value == null) {
            append("NULL")
        } else {
            append("PRESENT")
            append(value)
        }
    }

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun append(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
}

private fun opaqueId(domain: String, vararg values: String): String {
    val digest = CanonicalSha256(domain).field("valueCount", values.size)
    values.forEachIndexed { index, value -> digest.field("value[$index]", value) }
    return "$domain:${digest.finish()}"
}

private fun String.isSafeOpaqueId(): Boolean =
    isNotBlank() &&
        this == trim() &&
        length <= 256 &&
        none(Char::isISOControl)

private fun decimal(value: Long): String = java.lang.Long.toString(value)

private fun decimal(value: Int): String = java.lang.Integer.toString(value)

private fun boolean(value: Boolean): String = if (value) "true" else "false"

private const val CAPTURED_CONVERSATION_GENERATION = 1L
private const val CAPTURED_CHOICE_REQUEST_VERSION = 1L
private const val CAPTURED_QUESTION_FINGERPRINT_VERSION = "captured-question-v1"
private const val CAPTURED_CHOICE_SOURCE_VERSION = "captured-choice-source-v1"
private const val CAPTURED_CHOICE_PENDING_SUMMARY = "guided-choice-pending"

// v1 was never wired into a production route. The input tuple changed before first production
// use, so this domain is intentionally versioned instead of silently reinterpreting v1 ids.
private const val CONVERSATION_ID_DOMAIN = "captured-choice-conversation-v2"
private const val CONVERSATION_CREATE_IDEMPOTENCY_DOMAIN = "captured-choice-create-key-v1"
private const val CONVERSATION_CREATE_PAYLOAD_DOMAIN = "captured-choice-create-payload-v1"
private const val PROBLEM_ANCHOR_ID_DOMAIN = "captured-choice-anchor-v1"
private const val TURN_RECEIPT_ID_DOMAIN = "captured-choice-turn-v1"
private const val TURN_ALLOCATION_IDEMPOTENCY_DOMAIN = "captured-choice-turn-key-v1"
private const val TURN_ALLOCATION_PAYLOAD_DOMAIN = "captured-choice-turn-payload-v1"
private const val TURN_PLACEHOLDER_FINGERPRINT_DOMAIN =
    "captured-choice-turn-placeholder-v1"
private const val DIRECTIVE_FINGERPRINT_DOMAIN = "captured-choice-directive-v1"
private const val RESPONSE_FINGERPRINT_DOMAIN = "captured-choice-response-v1"
private const val TURN_FINGERPRINT_DOMAIN = "captured-choice-turn-fingerprint-v1"
private const val EVIDENCE_PREPARE_IDEMPOTENCY_DOMAIN = "captured-choice-prepare-key-v1"
private const val EVIDENCE_PREPARE_PAYLOAD_DOMAIN = "captured-choice-prepare-payload-v1"
private const val EVIDENCE_SUBMIT_IDEMPOTENCY_DOMAIN = "captured-choice-submit-key-v1"
private const val EVIDENCE_SUBMIT_PAYLOAD_DOMAIN = "captured-choice-submit-payload-v1"
private const val EVIDENCE_CANCEL_IDEMPOTENCY_DOMAIN = "captured-choice-cancel-key-v1"
private const val EVIDENCE_CANCEL_PAYLOAD_DOMAIN = "captured-choice-cancel-payload-v1"
private const val SOURCE_FACT_ID_DOMAIN = "captured-choice-fact-v1"
