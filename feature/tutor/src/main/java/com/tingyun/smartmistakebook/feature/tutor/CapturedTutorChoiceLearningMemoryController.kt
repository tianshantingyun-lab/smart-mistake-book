package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAnchorFingerprints
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCancellationReason
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
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
        persistedResponse: TutorTurnResponse? = null,
    ): CapturedTutorChoiceLearningMemoryState = guarded {
        val context = eligibleContext(planTask, guidanceState)
            ?: return@guarded ineligible(planTask, guidanceState)
        if (
            persistedResponse != null &&
            !context.matchesPersistedResponse(persistedResponse)
        ) {
            return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }

        when (
            val opened = repository.openTurn(
                learnerScopeId = learnerScopeId,
                turnReceiptId = context.turnReceiptId,
            )
        ) {
            is OpenTutorTurnResult.Found -> prepare(context, opened.receipt)
            OpenTutorTurnResult.NotFound -> {
                if (persistedResponse != null) {
                    CapturedTutorChoiceLearningMemoryState.PreBridgeHistory
                } else {
                    allocateAndPrepare(context)
                }
            }
        }
    }

    /**
     * Settles only an already prepared bridge. A legacy response without the deterministic turn
     * receipt predates this bridge and is deliberately never converted into learning evidence.
     */
    suspend fun submitPreparedChoice(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        persistedResponse: TutorTurnResponse,
    ): CapturedTutorChoiceLearningMemoryState = guarded {
        val context = eligibleContext(planTask, guidanceState)
            ?: return@guarded ineligible(planTask, guidanceState)
        if (!context.matchesPersistedResponse(persistedResponse)) {
            return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.RESPONSE_SCOPE_MISMATCH,
            )
        }

        val receipt = when (
            val opened = repository.openTurn(
                learnerScopeId = learnerScopeId,
                turnReceiptId = context.turnReceiptId,
            )
        ) {
            is OpenTutorTurnResult.Found -> opened.receipt
            OpenTutorTurnResult.NotFound ->
                return@guarded CapturedTutorChoiceLearningMemoryState.PreBridgeHistory
        }
        when (val prepared = prepare(context, receipt)) {
            is CapturedTutorChoiceLearningMemoryState.Prepared ->
                submit(context, receipt, prepared.request, persistedResponse)

            else -> prepared
        }
    }

    /**
     * Uses the same terminal compare-and-set as submission. Cancellation never constructs an
     * anchor or source fact, and a terminal replay always returns the durable winner.
     */
    suspend fun cancelPreparedChoice(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
        reason: TutorLearningEvidenceCancellationReason,
    ): CapturedTutorChoiceLearningMemoryState = guarded {
        val context = eligibleContext(planTask, guidanceState)
            ?: return@guarded ineligible(planTask, guidanceState)
        val receipt = when (
            val opened = repository.openTurn(
                learnerScopeId = learnerScopeId,
                turnReceiptId = context.turnReceiptId,
            )
        ) {
            is OpenTutorTurnResult.Found -> opened.receipt
            OpenTutorTurnResult.NotFound ->
                return@guarded CapturedTutorChoiceLearningMemoryState.Ineligible(
                    CapturedTutorChoiceIneligibleReason.TURN_NOT_PREPARED,
                )
        }
        when (val prepared = prepare(context, receipt)) {
            is CapturedTutorChoiceLearningMemoryState.Prepared ->
                cancel(context, receipt, prepared.request, reason)

            else -> prepared
        }
    }

    private suspend fun allocateAndPrepare(
        context: EligibleChoiceContext,
    ): CapturedTutorChoiceLearningMemoryState {
        val conversation = repository.latestActiveConversation(learnerScopeId)
            ?: createConversation(context)
        if (!conversation.isEligibleActiveConversation()) {
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
            .field("modeVersion", CAPTURED_CHOICE_MODE_VERSION)
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
                modeVersion = CAPTURED_CHOICE_MODE_VERSION,
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
        if (!allocation.receipt.matches(context) || !allocation.conversation.isEligibleActiveConversation()) {
            return CapturedTutorChoiceLearningMemoryState.Ineligible(
                CapturedTutorChoiceIneligibleReason.DURABLE_SCOPE_MISMATCH,
            )
        }
        return prepare(context, allocation.receipt)
    }

    private suspend fun createConversation(context: EligibleChoiceContext): TutorConversation {
        val conversationId = opaqueId(
            CONVERSATION_ID_DOMAIN,
            learnerScopeId,
            session.sessionId,
            session.draftId,
            decimal(session.draftRevisionNumber),
            context.planRequestId,
        )
        val createPayload = CanonicalSha256(CONVERSATION_CREATE_PAYLOAD_DOMAIN)
            .field("learnerScopeId", learnerScopeId)
            .field("conversationId", conversationId)
            .field("conversationGeneration", CAPTURED_CONVERSATION_GENERATION)
            .finish()
        return repository.createConversation(
            CreateTutorConversationCommand(
                learnerScopeId = learnerScopeId,
                conversationId = conversationId,
                conversationGeneration = CAPTURED_CONVERSATION_GENERATION,
                clientIdempotencyKey = opaqueId(
                    CONVERSATION_CREATE_IDEMPOTENCY_DOMAIN,
                    learnerScopeId,
                    conversationId,
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
            .field("modeVersion", CAPTURED_CHOICE_MODE_VERSION)
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
                modeVersion = CAPTURED_CHOICE_MODE_VERSION,
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
            context.planRequestId,
            responseFingerprint,
        )
        val sourceFact = LearningObservationSourceFact(
            sourceFactId = sourceFactId,
            learnerScopeId = learnerScopeId,
            source = LearningObservationSource.TUTOR_CHOICE,
            factKind = if (response.selectionWasCorrect == true) {
                LearningObservationFactKind.VERIFIED_CORRECT_RESPONSE
            } else {
                LearningObservationFactKind.VERIFIED_INCORRECT_RESPONSE
            },
            anchorId = context.problemAnchorId,
            subject = context.subject,
            conversationGeneration = receipt.conversationGeneration,
            conversationId = receipt.conversationId,
            turnReceiptId = receipt.turnReceiptId,
            evidenceRequestId = context.planRequestId,
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
                evidenceRequestId = context.planRequestId,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = CAPTURED_CHOICE_REQUEST_VERSION,
                modeVersion = CAPTURED_CHOICE_MODE_VERSION,
                mode = TutorExplanationMode.GUIDED,
                directiveFingerprint = context.directiveFingerprint,
                expectedEvidenceStateVersion = request.stateVersion,
                terminal = TutorLearningEvidenceTerminal.Submitted(sourceFact, anchors),
                clientIdempotencyKey = opaqueId(
                    EVIDENCE_SUBMIT_IDEMPOTENCY_DOMAIN,
                    context.planRequestId,
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
            .field("evidenceRequestId", context.planRequestId)
            .field("kind", TutorEvidenceRequestKind.CHOICE.name)
            .field("requestVersion", CAPTURED_CHOICE_REQUEST_VERSION)
            .field("modeVersion", CAPTURED_CHOICE_MODE_VERSION)
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
                evidenceRequestId = context.planRequestId,
                kind = TutorEvidenceRequestKind.CHOICE,
                requestVersion = CAPTURED_CHOICE_REQUEST_VERSION,
                modeVersion = CAPTURED_CHOICE_MODE_VERSION,
                mode = TutorExplanationMode.GUIDED,
                directiveFingerprint = context.directiveFingerprint,
                expectedEvidenceStateVersion = request.stateVersion,
                terminal = TutorLearningEvidenceTerminal.Cancelled(reason),
                clientIdempotencyKey = opaqueId(
                    EVIDENCE_CANCEL_IDEMPOTENCY_DOMAIN,
                    context.planRequestId,
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
        .field("evidenceRequestId", context.planRequestId)
        .field("kind", TutorEvidenceRequestKind.CHOICE.name)
        .field("requestVersion", CAPTURED_CHOICE_REQUEST_VERSION)
        .field("modeVersion", CAPTURED_CHOICE_MODE_VERSION)
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

    private fun eligibleContext(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
    ): EligibleChoiceContext? {
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
        if (
            guidanceState.mode != TutorExplanationMode.GUIDED ||
            guidanceState.problem.problemId != session.questionDocument.document.id ||
            guidanceState.problem.revisionNumber != session.draftRevisionNumber ||
            guidanceState.pendingEvidenceRequestId != planTask.request.requestId ||
            !guidanceState.authorizeEvidence(planTask.request.requestId).mayWriteLearningEvidence
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
            studentMessageFingerprint = CanonicalSha256(TURN_PLACEHOLDER_FINGERPRINT_DOMAIN)
                .field("turnReceiptId", turnReceiptId)
                .field("planRequestId", planTask.request.requestId)
                .finish(),
        )
    }

    private fun ineligible(
        planTask: ModelTaskSnapshot,
        guidanceState: TutorGuidanceState,
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
    } catch (failure: Exception) {
        CapturedTutorChoiceLearningMemoryState.RetryableFailure(failure)
    }

    private fun TutorConversation.isEligibleActiveConversation(): Boolean =
        learnerScopeId == this@CapturedTutorChoiceLearningMemoryController.learnerScopeId &&
            status == TutorConversationStatus.ACTIVE &&
            generation > 0

    private fun TutorTurnReceipt.matches(context: EligibleChoiceContext): Boolean =
        turnReceiptId == context.turnReceiptId &&
            subject == context.subject &&
            problemAnchorId == context.problemAnchorId &&
            requestVersion == CAPTURED_CHOICE_REQUEST_VERSION &&
            modeVersion == CAPTURED_CHOICE_MODE_VERSION &&
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
private const val CAPTURED_CHOICE_MODE_VERSION = 0L
private const val CAPTURED_QUESTION_FINGERPRINT_VERSION = "captured-question-v1"
private const val CAPTURED_CHOICE_SOURCE_VERSION = "captured-choice-source-v1"
private const val CAPTURED_CHOICE_PENDING_SUMMARY = "guided-choice-pending"

private const val CONVERSATION_ID_DOMAIN = "captured-choice-conversation-v1"
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
