package com.tingyun.smartmistakebook.core.data.session

import android.content.Context
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseContextRequest
import com.tingyun.smartmistakebook.core.data.tutor.TutorLearningEvidenceCandidate
import com.tingyun.smartmistakebook.core.database.AppendCurrentTutorInteractionCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionBundle
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionEventKind
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionEventRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionScopeRecord
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAnchorFingerprints
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceAssistance
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCurrentSessionReference
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceSubmission
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceTerminal
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerEvaluationReceipt
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

internal fun CurrentTutorInteractionBundle.toContext(scope: SessionScope) =
    TutorCurrentInteractionContext(
        scope = scope,
        conversationId = this.scope.conversationId,
        conversationGeneration = this.scope.conversationGeneration,
        conversationStateVersion = this.scope.conversationStateVersion,
        questionDocumentId = this.scope.questionDocumentId,
        revisionNumber = this.scope.questionRevisionNumber,
        questionFingerprint = this.scope.questionFingerprint,
        subject = this.scope.subject,
        problemAnchorId = this.scope.problemAnchorId,
        explanationMode = this.scope.explanationMode,
        modeVersion = this.scope.modeVersion,
        learningWritePermissionVersion = this.scope.learningWritePermissionVersion,
        turnReferenceId = this.scope.turnReferenceId,
        turnGeneration = this.scope.turnGeneration,
        cycleOrdinal = this.scope.cycleOrdinal,
        turnOrdinal = this.scope.turnOrdinal,
        attemptOrdinal = this.scope.attemptOrdinal.coerceAtLeast(1),
        hintCount = this.scope.hintCount,
        answerWasRevealed = this.scope.answerWasRevealed,
        requestVersion = this.scope.requestVersion,
        version = SessionVersion(head.stateVersion, head.stateFingerprint),
    )

internal fun CurrentTutorInteractionBundle.matches(
    query: TutorCurrentInteractionAuthorizationQuery,
): Boolean {
    val session = scope
    if (session.learnerId != query.scope.learnerId || session.conversationId != query.conversationId) {
        return false
    }
    if (query.purpose == TutorCurrentInteractionAuthorizationPurpose.ANCHOR_SESSION) return true
    if (
        session.questionDocumentId != query.questionDocumentId ||
        session.questionRevisionNumber != query.revisionNumber
    ) return false
    return query.cycleOrdinal == null ||
        session.cycleOrdinal == query.cycleOrdinal && session.turnOrdinal == query.turnOrdinal
}

internal fun TutorEvidenceRequest.matchesAuthority(
    session: CurrentTutorInteractionScopeRecord,
): Boolean =
    conversationId == session.authorityConversationId &&
        conversationGeneration == session.authorityConversationGeneration &&
        conversationStateVersion == session.authorityConversationStateVersion &&
        turnReceiptId == session.authorityTurnReceiptId &&
        turnOrdinal == session.authorityTurnOrdinal &&
        subject == session.subject &&
        problemAnchorId == session.problemAnchorId &&
        requestVersion == session.authorityRequestVersion &&
        explanationMode == session.explanationMode &&
        modeVersion == session.modeVersion

internal fun TutorEvidenceRequest.matchesGuidedOpenResponse(
    session: CurrentTutorInteractionScopeRecord,
): Boolean =
    status == TutorEvidenceRequestStatus.PENDING &&
        kind == TutorEvidenceRequestKind.FREE_RESPONSE &&
        matchesAuthority(session)

internal fun CurrentTutorInteractionScopeRecord.matches(
    request: CoreDataTutorOpenResponseContextRequest,
    document: QuestionDocument,
): Boolean =
    learnerId.isNotBlank() &&
        conversationId == request.conversationId &&
        conversationGeneration == request.conversationGeneration &&
        conversationStateVersion == request.conversationStateVersion &&
        questionDocumentId == request.questionDocumentId &&
        questionRevisionNumber == request.questionRevisionNumber &&
        subject == request.subject &&
        questionFingerprint == request.questionFingerprint &&
        document == request.questionDocument &&
        explanationMode == request.explanationMode &&
        modeVersion == request.modeVersion &&
        turnReferenceId == request.turnReferenceId &&
        turnOrdinal == request.turnOrdinal &&
        turnGeneration == request.turnGeneration &&
        attemptOrdinal == request.attemptOrdinal &&
        hintCount == request.hintCount &&
        answerWasRevealed == request.answerWasRevealed &&
        requestVersion == request.requestVersion

internal fun CurrentTutorInteractionScopeRecord.matches(
    candidate: TutorLearningEvidenceCandidate,
): Boolean =
    learnerId == candidate.learnerId &&
        authorityConversationId == candidate.conversationId &&
        authorityConversationGeneration == candidate.conversationGeneration &&
        authorityConversationStateVersion == candidate.conversationStateVersion &&
        authorityTurnReceiptId == candidate.turnReceiptId &&
        authorityTurnOrdinal == candidate.turnOrdinal &&
        authorityRequestVersion == candidate.requestVersion &&
        questionFingerprint == candidate.questionFingerprint &&
        subject == candidate.subject &&
        problemAnchorId == candidate.sessionAnchorId &&
        modeVersion == candidate.modeVersion &&
        attemptOrdinal == candidate.attemptOrdinal &&
        hintCount == candidate.hintCount &&
        answerWasRevealed == candidate.answerWasRevealed &&
        learningWritePermissionVersion ==
            candidate.currentSessionReference.learningWritePermissionVersion &&
        learningEvidenceAuthorizationFingerprint() ==
            candidate.currentSessionReference.authorizationFingerprint

internal fun CurrentTutorInteractionScopeRecord.matchesRecordedChoice(
    response: TutorTurnResponse,
): Boolean =
    learnerId.isNotBlank() &&
        conversationId == response.sessionId &&
        questionDocumentId == response.questionDocumentId &&
        questionRevisionNumber == response.revisionNumber &&
        cycleOrdinal == response.cycleOrdinal &&
        turnOrdinal == response.turnOrdinal &&
        response.evidenceRequestId != null

internal fun CurrentTutorInteractionEventRecord.matchesRecordedChoice(
    response: TutorTurnResponse,
    session: CurrentTutorInteractionScopeRecord,
): Boolean =
    scopeId == session.scopeId &&
        learnerId == session.learnerId &&
        conversationId == session.conversationId &&
        conversationGeneration == session.conversationGeneration &&
        conversationStateVersion == session.conversationStateVersion &&
        questionDocumentId == session.questionDocumentId &&
        questionRevisionNumber == session.questionRevisionNumber &&
        questionFingerprint == session.questionFingerprint &&
        subject == session.subject &&
        problemAnchorId == session.problemAnchorId &&
        explanationMode == session.explanationMode &&
        modeVersion == session.modeVersion &&
        learningWritePermissionVersion == session.learningWritePermissionVersion &&
        turnReferenceId == session.turnReferenceId &&
        turnGeneration == session.turnGeneration &&
        cycleOrdinal == response.cycleOrdinal &&
        turnOrdinal == response.turnOrdinal &&
        attemptOrdinal == session.attemptOrdinal &&
        hintCount == session.hintCount &&
        answerWasRevealed == session.answerWasRevealed &&
        diagnosticStemMarkdown == response.diagnosticStemMarkdown &&
        selectedChoiceId == response.selectedChoiceId &&
        selectedChoiceMarkdown == response.selectedChoiceMarkdown &&
        selectionWasCorrect == response.selectionWasCorrect &&
        feedbackMarkdown == response.feedbackMarkdown &&
        evidenceRequestId == response.evidenceRequestId &&
        occurredAtEpochMillis == response.choiceSubmittedAtEpochMillis

internal fun CurrentTutorTrustedAnswerEvent.evidenceKind(): TutorEvidenceRequestKind = when (this) {
    is CurrentTutorTrustedAnswerEvent.Choice -> TutorEvidenceRequestKind.CHOICE
    is CurrentTutorTrustedAnswerEvent.VisualTarget -> TutorEvidenceRequestKind.VISUAL_TARGET
}

internal fun CurrentTutorInteractionScopeRecord.toTrustedSavedAnswerAppendCommand(
    command: CurrentTutorTrustedAnswerCommitCommand,
    head: com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionHeadRecord,
): AppendCurrentTutorInteractionCommand {
    val eventKey = CanonicalSha256("current-tutor-trusted-saved-answer-event-key-v1")
        .field("scopeId", scopeId)
        .field("evidenceRequestId", command.evidenceRequestId)
        .field("exactContentBinding", command.exactContentBinding)
        .field("responseBinding", command.evaluation.responseBinding)
        .finish()
    val payloadBuilder = CanonicalSha256("current-tutor-trusted-saved-answer-event-v1")
        .field("scopeId", scopeId)
        .field("presentationToken", command.presentationToken)
        .field("exactContentBinding", command.exactContentBinding)
        .field("evidenceRequestId", command.evidenceRequestId)
        .field("responseForm", command.event.responseForm.name)
        .field("responseBinding", command.evaluation.responseBinding)
        .field("evaluationFingerprint", command.evaluation.canonicalFingerprint)
        .field("selectionWasCorrect", command.evaluation.selectionWasCorrect)
    when (val event = command.event) {
        is CurrentTutorTrustedAnswerEvent.Choice -> payloadBuilder
            .field("diagnosticStemMarkdown", event.diagnosticStemMarkdown)
            .field("selectedChoiceId", event.selectedChoiceId)
            .field("selectedChoiceMarkdown", event.selectedChoiceMarkdown)
            .field("feedbackMarkdown", event.feedbackMarkdown)
        is CurrentTutorTrustedAnswerEvent.VisualTarget -> payloadBuilder
            .field("ownerModelTaskRequestId", event.hitProof.presentation.ownerModelTaskRequestId)
            .field("sceneSourceKind", event.hitProof.presentation.sourceKind.name)
            .field("sceneTaskRequestId", event.hitProof.presentation.sceneTaskRequestId)
            .field("sceneId", event.hitProof.presentation.sceneId)
            .field("sceneFingerprint", event.hitProof.presentation.sceneFingerprint)
            .field("hitProofId", event.hitProof.proofId)
            .field("panelId", event.hitProof.panelId)
            .field("frameFingerprint", event.hitProof.frameFingerprint)
            .field("stepIndex", event.hitProof.stepIndex)
            .field("selectedTargetId", event.hitProof.selectedTargetId)
    }
    val event = command.event
    val choice = event as? CurrentTutorTrustedAnswerEvent.Choice
    val visual = (event as? CurrentTutorTrustedAnswerEvent.VisualTarget)?.hitProof
    return AppendCurrentTutorInteractionCommand(
        scopeId = scopeId,
        learnerId = learnerId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        questionDocumentId = questionDocumentId,
        questionRevisionNumber = questionRevisionNumber,
        questionFingerprint = questionFingerprint,
        subject = subject,
        problemAnchorId = problemAnchorId,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritePermissionVersion = learningWritePermissionVersion,
        turnReferenceId = turnReferenceId,
        turnOrdinal = turnOrdinal,
        turnGeneration = turnGeneration,
        cycleOrdinal = cycleOrdinal,
        attemptOrdinal = attemptOrdinal.coerceAtLeast(1),
        hintCount = hintCount,
        answerWasRevealed = false,
        expectedStateVersion = head.stateVersion,
        expectedStateFingerprint = head.stateFingerprint,
        eventId = "trusted-answer-$eventKey",
        eventKind = if (choice != null) {
            CurrentTutorInteractionEventKind.CHOICE
        } else {
            CurrentTutorInteractionEventKind.VISUAL_SELECTION
        },
        authorizationPurpose = if (choice != null) {
            "RECORD_CHOICE"
        } else {
            CURRENT_TUTOR_TRUSTED_SAVED_VISUAL_PURPOSE
        },
        authorizationRequestId = command.evidenceRequestId,
        idempotencyKey = "trusted-answer-$eventKey",
        requestVersion = requestVersion,
        payloadFingerprint = payloadBuilder.finish(),
        occurredAtEpochMillis = command.occurredAtEpochMillis,
        diagnosticStemMarkdown = choice?.diagnosticStemMarkdown,
        selectedChoiceId = choice?.selectedChoiceId,
        selectedChoiceMarkdown = choice?.selectedChoiceMarkdown,
        selectionWasCorrect = command.evaluation.selectionWasCorrect,
        feedbackMarkdown = choice?.feedbackMarkdown,
        evidenceRequestId = command.evidenceRequestId,
        surfaceKind = visual?.let { TutorVisualTurnSurface.PLAN.name },
        modelTaskRequestId = visual?.presentation?.ownerModelTaskRequestId,
        responseOrdinal = null,
        sceneSourceKind = visual?.presentation?.sourceKind?.name,
        sceneTaskRequestId = visual?.presentation?.sceneTaskRequestId,
        sceneId = visual?.presentation?.sceneId,
        sceneFingerprint = visual?.presentation?.sceneFingerprint,
        hitProofId = visual?.proofId,
        panelId = visual?.panelId,
        frameFingerprint = visual?.frameFingerprint,
        stepIndex = visual?.stepIndex,
        selectedTargetId = visual?.selectedTargetId,
    )
}

internal fun CurrentTutorInteractionEventRecord.matchesTrustedSavedAnswer(
    command: AppendCurrentTutorInteractionCommand,
    evaluation: StudentTrustedSavedAnswerEvaluationReceipt,
): Boolean =
    eventId == command.eventId &&
        scopeId == command.scopeId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        eventKind == command.eventKind &&
        authorizationPurpose == command.authorizationPurpose &&
        authorizationRequestId == command.authorizationRequestId &&
        evidenceRequestId == command.evidenceRequestId &&
        idempotencyKey == command.idempotencyKey &&
        requestVersion == command.requestVersion &&
        payloadFingerprint == command.payloadFingerprint &&
        selectionWasCorrect == evaluation.selectionWasCorrect &&
        when (command.eventKind) {
            CurrentTutorInteractionEventKind.CHOICE ->
                diagnosticStemMarkdown == command.diagnosticStemMarkdown &&
                    selectedChoiceId == command.selectedChoiceId &&
                    selectedChoiceMarkdown == command.selectedChoiceMarkdown &&
                    feedbackMarkdown == command.feedbackMarkdown
            CurrentTutorInteractionEventKind.VISUAL_SELECTION ->
                surfaceKind == command.surfaceKind &&
                    modelTaskRequestId == command.modelTaskRequestId &&
                    responseOrdinal == command.responseOrdinal &&
                    sceneSourceKind == command.sceneSourceKind &&
                    sceneTaskRequestId == command.sceneTaskRequestId &&
                    sceneId == command.sceneId &&
                    sceneFingerprint == command.sceneFingerprint &&
                    hitProofId == command.hitProofId &&
                    panelId == command.panelId &&
                    frameFingerprint == command.frameFingerprint &&
                    stepIndex == command.stepIndex &&
                    selectedTargetId == command.selectedTargetId
            else -> false
        }

internal fun CurrentTutorSessionEvidenceState.isTrustedAnswerSuccessorOf(
    before: CurrentTutorSessionEvidenceState,
): Boolean =
    scopeId == before.scopeId &&
        activationFingerprint == before.activationFingerprint &&
        presentationFingerprint == before.presentationFingerprint &&
        stateVersion == before.stateVersion + 1L &&
        stateFingerprint != before.stateFingerprint &&
        attemptOrdinal == before.attemptOrdinal + 1 &&
        hintCount == before.hintCount &&
        answerWasRevealed == before.answerWasRevealed

internal fun CurrentTutorInteractionScopeRecord.toTrustedAnswerFinalizationCommand(
    response: CurrentTutorInteractionEventRecord,
    request: TutorEvidenceRequest,
): FinalizeTutorEvidenceCommand = when (response.eventKind) {
    CurrentTutorInteractionEventKind.CHOICE -> toChoiceFinalizationCommand(response, request)
    CurrentTutorInteractionEventKind.VISUAL_SELECTION ->
        toVisualFinalizationCommand(response, request)
    else -> error("Only a trusted choice or visual target can become answer evidence")
}

internal fun CurrentTutorInteractionScopeRecord.toChoiceFinalizationCommand(
    choice: CurrentTutorInteractionEventRecord,
    request: TutorEvidenceRequest,
): FinalizeTutorEvidenceCommand {
    val responseFingerprint = choice.choiceResponseFingerprint()
    val currentSessionReference =
        TutorLearningEvidenceCurrentSessionReference(
            authorizationFingerprint = learningEvidenceAuthorizationFingerprint(),
            learningWritePermissionVersion = learningWritePermissionVersion,
        )
    val assistance = when {
        choice.answerWasRevealed -> TutorLearningEvidenceAssistance.ANSWER_REVEALED
        choice.hintCount == 1 -> TutorLearningEvidenceAssistance.ONE_HINT
        choice.hintCount > 1 -> TutorLearningEvidenceAssistance.MULTIPLE_HINTS
        else -> TutorLearningEvidenceAssistance.UNKNOWN
    }
    val evidence =
        TutorLearningEvidenceSubmission(
            anchors =
                TutorLearningEvidenceAnchorFingerprints(
                    questionFingerprint = questionFingerprint,
                    problemRevisionFingerprint =
                        CanonicalSha256(CURRENT_CHOICE_PROBLEM_REVISION_DOMAIN)
                            .field("problemFingerprint", problemFingerprint)
                            .field("questionFingerprint", questionFingerprint)
                            .field("questionRevisionNumber", questionRevisionNumber)
                            .finish(),
                    fingerprintVersion = CURRENT_CHOICE_FINGERPRINT_VERSION,
                    turnFingerprint = choice.choiceTurnFingerprint(this),
                    directiveFingerprint = request.directiveFingerprint,
                ),
            responseFingerprint = responseFingerprint,
            outcome =
                if (choice.selectionWasCorrect == true) {
                    TutorLearningEvidenceOutcome.ASSISTED_CORRECT
                } else {
                    TutorLearningEvidenceOutcome.INCORRECT
                },
            occurredAtEpochMillis = choice.occurredAtEpochMillis,
            producerVersion = CURRENT_CHOICE_PRODUCER_VERSION,
            currentSessionReference = currentSessionReference,
            attemptOrdinal = choice.attemptOrdinal,
            retryCount = choice.attemptOrdinal - 1,
            hintCount = choice.hintCount,
            answerWasRevealed = choice.answerWasRevealed,
            // A guided choice is prompted by the tutor and is never independent evidence.
            independentlyAnswered = false,
            assistance = assistance,
        )
    val payloadFingerprint =
        CanonicalSha256(CURRENT_CHOICE_FINALIZATION_PAYLOAD_DOMAIN)
            .field("learnerId", learnerId)
            .field("evidenceRequestId", request.evidenceRequestId)
            .field("conversationId", request.conversationId)
            .field("conversationGeneration", request.conversationGeneration)
            .field("conversationStateVersion", request.conversationStateVersion)
            .field("turnReceiptId", request.turnReceiptId)
            .field("turnOrdinal", request.turnOrdinal)
            .field("subject", request.subject.name)
            .field("problemAnchorId", request.problemAnchorId)
            .field("kind", request.kind.name)
            .field("requestVersion", request.requestVersion)
            .field("modeVersion", request.modeVersion)
            .field("directiveFingerprint", request.directiveFingerprint)
            .field("responseFingerprint", responseFingerprint)
            .field("outcome", evidence.outcome.name)
            .field("attemptOrdinal", evidence.attemptOrdinal)
            .field("retryCount", evidence.retryCount)
            .field("hintCount", evidence.hintCount)
            .field("answerWasRevealed", evidence.answerWasRevealed)
            .field("independentlyAnswered", evidence.independentlyAnswered)
            .field("assistance", evidence.assistance.name)
            .field("authorizationFingerprint", currentSessionReference.authorizationFingerprint)
            .field("learningWritePermissionVersion", learningWritePermissionVersion)
            .finish()
    val expectedStateVersion = when (request.status) {
        TutorEvidenceRequestStatus.PENDING -> request.stateVersion
        TutorEvidenceRequestStatus.SUBMITTED -> {
            check(request.stateVersion > 0) {
                "Submitted tutor choice evidence has no preceding pending version"
            }
            request.stateVersion - 1
        }
        TutorEvidenceRequestStatus.CANCELLED ->
            error("Cancelled tutor choice cannot become learning evidence")
    }
    return FinalizeTutorEvidenceCommand(
        learnerScopeId = learnerId,
        conversationId = request.conversationId,
        conversationGeneration = request.conversationGeneration,
        conversationStateVersion = request.conversationStateVersion,
        turnReceiptId = request.turnReceiptId,
        turnOrdinal = request.turnOrdinal,
        subject = request.subject,
        problemAnchorId = request.problemAnchorId,
        evidenceRequestId = request.evidenceRequestId,
        kind = request.kind,
        requestVersion = request.requestVersion,
        modeVersion = request.modeVersion,
        mode = request.explanationMode,
        directiveFingerprint = request.directiveFingerprint,
        expectedEvidenceStateVersion = expectedStateVersion,
        terminal = TutorLearningEvidenceTerminal.Submitted(evidence),
        clientIdempotencyKey = "tutor-choice-$responseFingerprint",
        payloadFingerprint = payloadFingerprint,
        occurredAtEpochMillis = maxOf(
            choice.occurredAtEpochMillis,
            choice.recordedAtEpochMillis,
            request.createdAtEpochMillis,
        ),
    )
}

internal fun CurrentTutorInteractionScopeRecord.toVisualFinalizationCommand(
    visual: CurrentTutorInteractionEventRecord,
    request: TutorEvidenceRequest,
): FinalizeTutorEvidenceCommand {
    check(request.kind == TutorEvidenceRequestKind.VISUAL_TARGET)
    val responseFingerprint = visual.visualResponseFingerprint()
    val currentSessionReference = TutorLearningEvidenceCurrentSessionReference(
        authorizationFingerprint = learningEvidenceAuthorizationFingerprint(),
        learningWritePermissionVersion = learningWritePermissionVersion,
    )
    val assistance = when {
        visual.answerWasRevealed -> TutorLearningEvidenceAssistance.ANSWER_REVEALED
        visual.hintCount == 1 -> TutorLearningEvidenceAssistance.ONE_HINT
        visual.hintCount > 1 -> TutorLearningEvidenceAssistance.MULTIPLE_HINTS
        else -> TutorLearningEvidenceAssistance.UNKNOWN
    }
    val evidence = TutorLearningEvidenceSubmission(
        anchors = TutorLearningEvidenceAnchorFingerprints(
            questionFingerprint = questionFingerprint,
            problemRevisionFingerprint = CanonicalSha256(
                CURRENT_VISUAL_PROBLEM_REVISION_DOMAIN,
            )
                .field("problemFingerprint", problemFingerprint)
                .field("questionFingerprint", questionFingerprint)
                .field("questionRevisionNumber", questionRevisionNumber)
                .finish(),
            fingerprintVersion = CURRENT_VISUAL_FINGERPRINT_VERSION,
            turnFingerprint = visual.choiceTurnFingerprint(this),
            directiveFingerprint = request.directiveFingerprint,
        ),
        responseFingerprint = responseFingerprint,
        outcome = if (visual.selectionWasCorrect == true) {
            TutorLearningEvidenceOutcome.ASSISTED_CORRECT
        } else {
            TutorLearningEvidenceOutcome.INCORRECT
        },
        occurredAtEpochMillis = visual.occurredAtEpochMillis,
        producerVersion = CURRENT_VISUAL_PRODUCER_VERSION,
        currentSessionReference = currentSessionReference,
        attemptOrdinal = visual.attemptOrdinal,
        retryCount = visual.attemptOrdinal - 1,
        hintCount = visual.hintCount,
        answerWasRevealed = visual.answerWasRevealed,
        independentlyAnswered = false,
        assistance = assistance,
    )
    val payloadFingerprint = CanonicalSha256(CURRENT_VISUAL_FINALIZATION_PAYLOAD_DOMAIN)
        .field("learnerId", learnerId)
        .field("evidenceRequestId", request.evidenceRequestId)
        .field("conversationId", request.conversationId)
        .field("conversationGeneration", request.conversationGeneration)
        .field("conversationStateVersion", request.conversationStateVersion)
        .field("turnReceiptId", request.turnReceiptId)
        .field("turnOrdinal", request.turnOrdinal)
        .field("subject", request.subject.name)
        .field("problemAnchorId", request.problemAnchorId)
        .field("kind", request.kind.name)
        .field("requestVersion", request.requestVersion)
        .field("modeVersion", request.modeVersion)
        .field("directiveFingerprint", request.directiveFingerprint)
        .field("responseFingerprint", responseFingerprint)
        .field("outcome", evidence.outcome.name)
        .field("attemptOrdinal", evidence.attemptOrdinal)
        .field("retryCount", evidence.retryCount)
        .field("hintCount", evidence.hintCount)
        .field("answerWasRevealed", evidence.answerWasRevealed)
        .field("independentlyAnswered", evidence.independentlyAnswered)
        .field("assistance", evidence.assistance.name)
        .field("authorizationFingerprint", currentSessionReference.authorizationFingerprint)
        .field("learningWritePermissionVersion", learningWritePermissionVersion)
        .finish()
    val expectedStateVersion = when (request.status) {
        TutorEvidenceRequestStatus.PENDING -> request.stateVersion
        TutorEvidenceRequestStatus.SUBMITTED -> {
            check(request.stateVersion > 0)
            request.stateVersion - 1
        }
        TutorEvidenceRequestStatus.CANCELLED ->
            error("Cancelled tutor visual evidence cannot be finalized")
    }
    return FinalizeTutorEvidenceCommand(
        learnerScopeId = learnerId,
        conversationId = request.conversationId,
        conversationGeneration = request.conversationGeneration,
        conversationStateVersion = request.conversationStateVersion,
        turnReceiptId = request.turnReceiptId,
        turnOrdinal = request.turnOrdinal,
        subject = request.subject,
        problemAnchorId = request.problemAnchorId,
        evidenceRequestId = request.evidenceRequestId,
        kind = request.kind,
        requestVersion = request.requestVersion,
        modeVersion = request.modeVersion,
        mode = request.explanationMode,
        directiveFingerprint = request.directiveFingerprint,
        expectedEvidenceStateVersion = expectedStateVersion,
        terminal = TutorLearningEvidenceTerminal.Submitted(evidence),
        clientIdempotencyKey = "tutor-visual-$responseFingerprint",
        payloadFingerprint = payloadFingerprint,
        occurredAtEpochMillis = maxOf(
            visual.occurredAtEpochMillis,
            visual.recordedAtEpochMillis,
            request.createdAtEpochMillis,
        ),
    )
}

internal fun CurrentTutorInteractionEventRecord.visualResponseFingerprint(): String =
    CanonicalSha256(CURRENT_VISUAL_RESPONSE_DOMAIN)
        .field("eventId", eventId)
        .field("scopeId", scopeId)
        .field("payloadFingerprint", payloadFingerprint)
        .field("surfaceKind", checkNotNull(surfaceKind))
        .field("modelTaskRequestId", checkNotNull(modelTaskRequestId))
        .field("sceneSourceKind", checkNotNull(sceneSourceKind))
        .field("sceneTaskRequestId", checkNotNull(sceneTaskRequestId))
        .field("sceneId", checkNotNull(sceneId))
        .field("sceneFingerprint", checkNotNull(sceneFingerprint))
        .field("hitProofId", checkNotNull(hitProofId))
        .field("panelId", checkNotNull(panelId))
        .field("frameFingerprint", checkNotNull(frameFingerprint))
        .field("stepIndex", checkNotNull(stepIndex))
        .field("selectedTargetId", checkNotNull(selectedTargetId))
        .field("selectionWasCorrect", checkNotNull(selectionWasCorrect))
        .field("evidenceRequestId", checkNotNull(evidenceRequestId))
        .finish()

internal fun CurrentTutorInteractionEventRecord.choiceResponseFingerprint(): String =
    CanonicalSha256(CURRENT_CHOICE_RESPONSE_DOMAIN)
        .field("eventId", eventId)
        .field("scopeId", scopeId)
        .field("payloadFingerprint", payloadFingerprint)
        .field("diagnosticStemMarkdown", checkNotNull(diagnosticStemMarkdown))
        .field("selectedChoiceId", checkNotNull(selectedChoiceId))
        .field("selectedChoiceMarkdown", checkNotNull(selectedChoiceMarkdown))
        .field("selectionWasCorrect", checkNotNull(selectionWasCorrect))
        .field("feedbackMarkdown", checkNotNull(feedbackMarkdown))
        .field("evidenceRequestId", checkNotNull(evidenceRequestId))
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .finish()

internal fun CurrentTutorInteractionEventRecord.choiceTurnFingerprint(
    session: CurrentTutorInteractionScopeRecord,
): String =
    CanonicalSha256(CURRENT_CHOICE_TURN_DOMAIN)
        .field("authorityConversationId", session.authorityConversationId)
        .field("authorityConversationGeneration", session.authorityConversationGeneration)
        .field("authorityConversationStateVersion", session.authorityConversationStateVersion)
        .field("authorityTurnReceiptId", session.authorityTurnReceiptId)
        .field("authorityTurnOrdinal", session.authorityTurnOrdinal)
        .field("conversationId", conversationId)
        .field("conversationGeneration", conversationGeneration)
        .field("conversationStateVersion", conversationStateVersion)
        .field("turnReferenceId", turnReferenceId)
        .field("turnGeneration", turnGeneration)
        .field("cycleOrdinal", cycleOrdinal)
        .field("turnOrdinal", turnOrdinal)
        .finish()

internal fun CurrentTutorInteractionScopeRecord.learningEvidenceAuthorizationFingerprint(): String =
    CanonicalSha256("current-tutor-learning-evidence-authorization-v1")
        .field("activationFingerprint", activationFingerprint)
        .field("learningWritePermissionVersion", learningWritePermissionVersion)
        .finish()

internal const val CURRENT_CHOICE_PROBLEM_REVISION_DOMAIN =
    "current-tutor-choice-problem-revision-v1"
internal const val CURRENT_CHOICE_FINALIZATION_PAYLOAD_DOMAIN =
    "current-tutor-choice-finalization-payload-v1"
internal const val CURRENT_CHOICE_RESPONSE_DOMAIN = "current-tutor-choice-response-v1"
internal const val CURRENT_CHOICE_TURN_DOMAIN = "current-tutor-choice-turn-v1"
internal const val CURRENT_CHOICE_FINGERPRINT_VERSION = "current-tutor-choice-v1"
internal const val CURRENT_CHOICE_PRODUCER_VERSION = "current-tutor-choice-owner-v1"
internal const val CURRENT_VISUAL_PROBLEM_REVISION_DOMAIN =
    "current-tutor-visual-problem-revision-v1"
internal const val CURRENT_VISUAL_FINALIZATION_PAYLOAD_DOMAIN =
    "current-tutor-visual-finalization-payload-v1"
internal const val CURRENT_VISUAL_RESPONSE_DOMAIN = "current-tutor-visual-response-v1"
internal const val CURRENT_VISUAL_FINGERPRINT_VERSION = "current-tutor-visual-v1"
internal const val CURRENT_VISUAL_PRODUCER_VERSION = "current-tutor-visual-owner-v1"
internal const val CURRENT_TUTOR_TRUSTED_SAVED_VISUAL_PURPOSE =
    "RECORD_TRUSTED_SAVED_VISUAL_SELECTION"
internal const val CURRENT_TUTOR_HINT_SHOWN_PURPOSE = "RECORD_HINT_SHOWN"
internal const val CURRENT_TUTOR_HINT_SHOWN_VALUE = "HINT_SHOWN"

internal fun toCurrentSnapshot(bundle: CurrentTutorInteractionBundle) =
    TutorCurrentInteractionSessionSnapshot(
        current = bundle.toContext(SessionScope(bundle.scope.learnerId)),
        responses = foldResponseRecords(bundle),
        visualSelections = emptyList(),
        exposures = bundle.events
            .filterKind(CurrentTutorInteractionEventKind.ANSWER_EXPOSURE)
            .map { event -> TutorCurrentInteractionRecord(event.toContext(), toExposure(event)) },
    )

internal fun foldResponses(bundle: CurrentTutorInteractionBundle): List<TutorTurnResponseSessionSnapshot> =
    foldResponseRecords(bundle).map { it.value }

internal fun foldResponses(events: List<CurrentTutorInteractionEventRecord>): List<TutorTurnResponseSessionSnapshot> =
    events.groupBy { it.scopeId }.values.flatMap { foldResponseEvents(it).map { record -> record.value } }

internal fun foldResponseRecords(
    bundle: CurrentTutorInteractionBundle,
): List<TutorCurrentInteractionRecord<TutorTurnResponseSessionSnapshot>> =
    foldResponseEvents(bundle.events)

internal fun foldResponseEvents(
    events: List<CurrentTutorInteractionEventRecord>,
): List<TutorCurrentInteractionRecord<TutorTurnResponseSessionSnapshot>> {
    val responseEvents = events.filter {
        it.eventKind in RESPONSE_EVENT_KINDS && it.eventKind != CurrentTutorInteractionEventKind.CHOICE
    }
    return responseEvents.groupBy { listOf(it.cycleOrdinal, it.turnOrdinal) }
        .values
        .map { group ->
            val ordered = group.sortedBy { it.eventSequence }
            val move = ordered.lastOrNull { it.eventKind == CurrentTutorInteractionEventKind.MOVE }
            val reveal = ordered.lastOrNull { it.eventKind == CurrentTutorInteractionEventKind.SOLUTION_REVEAL }
            val latest = ordered.last()
            TutorCurrentInteractionRecord(
                context = latest.toContext(),
                value = TutorTurnResponseSessionSnapshot(
                    scope = SessionScope(latest.learnerId),
                    key = latest.toKey(),
                    version = latest.toVersion(),
                    diagnosticStemMarkdown = null,
                    selectedChoiceId = null,
                    selectedChoiceMarkdown = null,
                    selectionWasCorrect = null,
                    feedbackMarkdown = null,
                    requestedMove = move?.requestedMove,
                    solutionRevealed = reveal != null,
                    choiceSubmittedAtEpochMillis = null,
                    submittedAtEpochMillis = ordered.minOf { it.occurredAtEpochMillis },
                    updatedAtEpochMillis = ordered.maxOf { it.occurredAtEpochMillis },
                    evidenceRequestId = null,
                ),
            )
        }
}

internal fun CurrentTutorInteractionEventRecord.toContext() = TutorCurrentInteractionContext(
    scope = SessionScope(learnerId),
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    questionDocumentId = questionDocumentId,
    revisionNumber = questionRevisionNumber,
    questionFingerprint = questionFingerprint,
    subject = subject,
    problemAnchorId = problemAnchorId,
    explanationMode = explanationMode,
    modeVersion = modeVersion,
    learningWritePermissionVersion = learningWritePermissionVersion,
    turnReferenceId = turnReferenceId,
    turnGeneration = turnGeneration,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    attemptOrdinal = attemptOrdinal.coerceAtLeast(1),
    hintCount = hintCount,
    answerWasRevealed = answerWasRevealed,
    requestVersion = requestVersion,
    version = toVersion(),
)

internal fun CurrentTutorInteractionEventRecord.toKey() = TutorTurnSessionKey(
    sessionId = conversationId,
    questionDocumentId = questionDocumentId,
    revisionNumber = questionRevisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

internal fun TutorCurrentInteractionContext.toKey() = TutorTurnSessionKey(
    sessionId = conversationId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

internal fun CurrentTutorInteractionEventRecord.toVersion() =
    SessionVersion(eventSequence, committedStateFingerprint)

internal fun toVisual(event: CurrentTutorInteractionEventRecord) =
    TutorVisualSelectionSessionSnapshot(
        scope = SessionScope(event.learnerId),
        key = event.toKey(),
        version = event.toVersion(),
        surfaceKind = checkNotNull(event.surfaceKind),
        modelTaskRequestId = checkNotNull(event.modelTaskRequestId),
        responseOrdinal = event.responseOrdinal,
        sceneSourceKind = checkNotNull(event.sceneSourceKind),
        sceneTaskRequestId = checkNotNull(event.sceneTaskRequestId),
        sceneId = checkNotNull(event.sceneId),
        sceneFingerprint = checkNotNull(event.sceneFingerprint),
        hitProofId = checkNotNull(event.hitProofId),
        panelId = checkNotNull(event.panelId),
        frameFingerprint = checkNotNull(event.frameFingerprint),
        stepIndex = checkNotNull(event.stepIndex),
        selectedTargetId = checkNotNull(event.selectedTargetId),
        selectionWasCorrect = checkNotNull(event.selectionWasCorrect),
        submittedAtEpochMillis = event.occurredAtEpochMillis,
    )

internal fun toExposure(event: CurrentTutorInteractionEventRecord) =
    TutorAnswerExposureSessionSnapshot(
        scope = SessionScope(event.learnerId),
        exposureId = event.eventId,
        key = event.toKey(),
        version = event.toVersion(),
        surfaceKind = checkNotNull(event.surfaceKind),
        modelTaskRequestId = event.modelTaskRequestId,
        responseOrdinal = event.responseOrdinal,
        exposedAtEpochMillis = event.occurredAtEpochMillis,
        outcomeReceiptId = null,
    )

internal fun toAnchor(event: CurrentTutorInteractionEventRecord) = TutorSessionAnchorReceipt(
    scope = SessionScope(event.learnerId),
    sessionId = event.conversationId,
    targetRevisionRef = checkNotNull(event.targetRevisionRef),
    targetPracticeRef = checkNotNull(event.targetPracticeRef),
    sourceKind = checkNotNull(event.sourceKind),
    anchoredAtEpochMillis = event.occurredAtEpochMillis,
)

internal fun List<CurrentTutorInteractionEventRecord>.filterKind(
    kind: CurrentTutorInteractionEventKind,
): List<CurrentTutorInteractionEventRecord> = filter { it.eventKind == kind }

internal fun TutorInteractionSessionMutation.isResponseMutation(): Boolean =
    this is TutorInteractionSessionMutation.RecordMove ||
        this is TutorInteractionSessionMutation.RevealSolution

internal fun TutorInteractionSessionMutation.revokesLearningEvidence(): Boolean =
    this is TutorInteractionSessionMutation.RevealSolution ||
        this is TutorInteractionSessionMutation.RecordExposure

internal fun TutorInteractionSessionMutation.requiresStudentAnswerOwner(): Boolean =
    this is TutorInteractionSessionMutation.RecordChoice ||
        this is TutorInteractionSessionMutation.RecordVisualSelection

internal fun TutorCurrentInteractionAuthorizationPurpose.requiresStudentAnswerOwner(): Boolean =
    this == TutorCurrentInteractionAuthorizationPurpose.RECORD_CHOICE ||
        this == TutorCurrentInteractionAuthorizationPurpose.RECORD_VISUAL_SELECTION

internal val SessionMutationDisposition.isAccepted: Boolean
    get() = this == SessionMutationDisposition.APPLIED || this == SessionMutationDisposition.DUPLICATE

internal fun CurrentTutorInteractionBundle.hasAnswerExposure(): Boolean =
    scope.answerWasRevealed || events.any { event ->
        event.answerWasRevealed ||
            event.solutionRevealed ||
            event.eventKind == CurrentTutorInteractionEventKind.SOLUTION_REVEAL ||
            event.eventKind == CurrentTutorInteractionEventKind.ANSWER_EXPOSURE
    }

internal fun CurrentTutorInteractionBundle.toEvidenceState(): CurrentTutorSessionEvidenceState =
    CurrentTutorSessionEvidenceState(
        scopeId = scope.scopeId,
        activationFingerprint = scope.activationFingerprint,
        presentationFingerprint = scope.presentationFingerprint,
        stateVersion = head.stateVersion,
        stateFingerprint = head.stateFingerprint,
        attemptOrdinal = maxOf(
            scope.attemptOrdinal,
            events.maxOfOrNull(CurrentTutorInteractionEventRecord::attemptOrdinal) ?: 0,
        ),
        hintCount = maxOf(
            scope.hintCount,
            events.maxOfOrNull(CurrentTutorInteractionEventRecord::hintCount) ?: 0,
        ),
        answerWasRevealed = hasAnswerExposure(),
    )

internal fun decodeQuestionDocument(snapshot: String): QuestionDocument? =
    runCatching { QUESTION_DOCUMENT_JSON.decodeFromString<QuestionDocument>(snapshot) }.getOrNull()

internal val QUESTION_DOCUMENT_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
    classDiscriminator = "type"
}

internal val RESPONSE_EVENT_KINDS = setOf(
    CurrentTutorInteractionEventKind.CHOICE,
    CurrentTutorInteractionEventKind.MOVE,
    CurrentTutorInteractionEventKind.SOLUTION_REVEAL,
)

internal val TRUSTED_ANSWER_EVENT_KINDS = setOf(
    CurrentTutorInteractionEventKind.CHOICE,
    CurrentTutorInteractionEventKind.VISUAL_SELECTION,
)
