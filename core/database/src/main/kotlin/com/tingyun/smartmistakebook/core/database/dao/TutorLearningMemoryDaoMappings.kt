package com.tingyun.smartmistakebook.core.database.dao

import com.tingyun.smartmistakebook.core.database.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.database.FinalizeTutorEvidenceRequestCommand
import com.tingyun.smartmistakebook.core.database.PrepareTutorEvidenceRequestCommand
import com.tingyun.smartmistakebook.core.database.TutorEvidenceConflictException
import com.tingyun.smartmistakebook.core.database.TutorMemoryScopeConflictException
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningProblemAnchorEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorConversationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceCancellationEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorEvidenceRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorTurnResponseEntity
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.LearningProblemAnchor
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

internal fun TutorConversationEntity.matchesCreate(command: CreateTutorConversationCommand): Boolean =
    conversationId == command.conversationId &&
        learnerId == command.learnerId &&
        generation == command.generation &&
        createIdempotencyKey == command.idempotencyKey &&
        createPayloadFingerprint == command.payloadFingerprint

internal fun LearningProblemAnchorEntity.matchesIdentity(
    candidate: LearningProblemAnchorEntity,
): Boolean =
    anchorId == candidate.anchorId &&
        learnerId == candidate.learnerId &&
        subject == candidate.subject &&
        questionFingerprint == candidate.questionFingerprint &&
        revisionFingerprint == candidate.revisionFingerprint &&
        fingerprintVersion == candidate.fingerprintVersion

internal fun TutorConversationEntity.requireScope(learnerId: String, generation: Long) {
    if (this.learnerId != learnerId || this.generation != generation) {
        throw TutorMemoryScopeConflictException("Tutor conversation scope does not match")
    }
}

internal fun TutorTurnReceiptEntity.matches(command: AllocateTutorTurnCommand): Boolean =
    turnReceiptId == command.turnReceiptId &&
        conversationId == command.conversationId &&
        learnerId == command.learnerId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.expectedConversationStateVersion + 1 &&
        turnOrdinal == command.expectedTurnOrdinal &&
        clientTurnId == command.clientTurnId &&
        payloadFingerprint == command.payloadFingerprint &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint &&
        studentMessageFingerprint == command.studentMessageFingerprint &&
        studentMessageSummary == command.studentMessageSummary &&
        occurredAtEpochMillis == command.occurredAtEpochMillis

internal fun TutorTurnReceiptEntity.matches(
    command: PrepareTutorEvidenceRequestCommand,
): Boolean =
    conversationId == command.conversationId &&
        learnerId == command.learnerId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        turnReceiptId == command.turnReceiptId &&
        turnOrdinal == command.turnOrdinal &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint

internal fun TutorTurnReceiptEntity.matches(request: TutorEvidenceRequestEntity): Boolean =
    conversationId == request.conversationId &&
        learnerId == request.learnerId &&
        conversationGeneration == request.conversationGeneration &&
        conversationStateVersion == request.conversationStateVersion &&
        turnReceiptId == request.turnReceiptId &&
        turnOrdinal == request.turnOrdinal &&
        subject == request.subject &&
        problemAnchorId == request.problemAnchorId &&
        requestVersion == request.requestVersion &&
        explanationMode == request.explanationMode &&
        modeVersion == request.modeVersion &&
        directiveFingerprint == request.directiveFingerprint

internal fun TutorEvidenceRequestEntity.matches(
    command: PrepareTutorEvidenceRequestCommand,
): Boolean =
    evidenceRequestId == command.evidenceRequestId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        turnReceiptId == command.turnReceiptId &&
        turnOrdinal == command.turnOrdinal &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        kind == command.kind.name &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint &&
        prepareIdempotencyKey == command.idempotencyKey &&
        preparePayloadFingerprint == command.payloadFingerprint

internal fun TutorEvidenceRequestEntity.matches(
    command: FinalizeTutorEvidenceRequestCommand,
): Boolean =
    evidenceRequestId == command.evidenceRequestId &&
        learnerId == command.learnerId &&
        conversationId == command.conversationId &&
        conversationGeneration == command.conversationGeneration &&
        conversationStateVersion == command.conversationStateVersion &&
        turnReceiptId == command.turnReceiptId &&
        turnOrdinal == command.turnOrdinal &&
        subject == command.subject.name &&
        problemAnchorId == command.problemAnchorId &&
        kind == command.kind.name &&
        requestVersion == command.requestVersion &&
        explanationMode == command.explanationMode.name &&
        modeVersion == command.modeVersion &&
        directiveFingerprint == command.directiveFingerprint

internal fun AllocateTutorTurnCommand.toEntity(
    conversationStateVersion: Long,
    turnOrdinal: Int,
    allocatedAtEpochMillis: Long,
) = TutorTurnReceiptEntity(
    turnReceiptId = turnReceiptId,
    conversationId = conversationId,
    learnerId = learnerId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnOrdinal = turnOrdinal,
    clientTurnId = clientTurnId,
    payloadFingerprint = payloadFingerprint,
    subject = subject.name,
    problemAnchorId = problemAnchorId,
    requestVersion = requestVersion,
    explanationMode = explanationMode.name,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    studentMessageFingerprint = studentMessageFingerprint,
    studentMessageSummary = studentMessageSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
    allocatedAtEpochMillis = allocatedAtEpochMillis,
)

internal fun PrepareTutorEvidenceRequestCommand.toEntity(
    createdAtEpochMillis: Long,
) = TutorEvidenceRequestEntity(
    evidenceRequestId = evidenceRequestId,
    learnerId = learnerId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = subject.name,
    problemAnchorId = problemAnchorId,
    kind = kind.name,
    requestVersion = requestVersion,
    explanationMode = explanationMode.name,
    modeVersion = modeVersion,
    directiveFingerprint = directiveFingerprint,
    status = TutorEvidenceRequestStatus.PENDING.name,
    stateVersion = 0,
    prepareIdempotencyKey = idempotencyKey,
    preparePayloadFingerprint = payloadFingerprint,
    terminalIdempotencyKey = null,
    terminalPayloadFingerprint = null,
    terminalSourceFactId = null,
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = null,
)

internal fun FinalizeTutorEvidenceRequestCommand.toAnchorEntity(
    submission: com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission,
    createdAtEpochMillis: Long,
) = LearningProblemAnchorEntity(
    anchorId = problemAnchorId,
    learnerId = learnerId,
    subject = subject.name,
    questionFingerprint = submission.questionFingerprint,
    revisionFingerprint = submission.revisionFingerprint,
    fingerprintVersion = submission.fingerprintVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun FinalizeTutorEvidenceRequestCommand.toSourceFactEntity(
    submission: com.tingyun.smartmistakebook.core.database.TutorEvidenceSubmission,
) = LearningObservationSourceFactEntity(
    sourceFactId = submission.sourceFactId,
    learnerId = learnerId,
    source = submission.source.name,
    factKind = submission.factKind.name,
    anchorId = problemAnchorId,
    subject = subject.name,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    turnReceiptId = turnReceiptId,
    evidenceRequestId = evidenceRequestId,
    responseFingerprint = submission.responseFingerprint,
    responseSummary = submission.responseSummary,
    payloadFingerprint = payloadFingerprint,
    occurredAtEpochMillis = submission.occurredAtEpochMillis,
    sourceVersion = submission.sourceVersion,
)

internal fun requireSourceMatchesKind(
    source: LearningObservationSource,
    kind: TutorEvidenceRequestKind,
) {
    val expected = when (kind) {
        TutorEvidenceRequestKind.CHOICE -> LearningObservationSource.TUTOR_CHOICE
        TutorEvidenceRequestKind.FREE_RESPONSE -> LearningObservationSource.TUTOR_FREE_RESPONSE
        TutorEvidenceRequestKind.VISUAL_TARGET -> LearningObservationSource.TUTOR_VISUAL_TARGET
        TutorEvidenceRequestKind.SPECIFIC_STUCK -> LearningObservationSource.TUTOR_SPECIFIC_STUCK
    }
    if (source != expected) {
        throw TutorMemoryScopeConflictException("Tutor source kind does not match the request")
    }
}

internal fun TutorTurnResponseEntity.canonicalChoiceSummary(): String {
    if (
        evidenceRequestId.isNullOrBlank() ||
        diagnosticStemMarkdown == null ||
        selectedChoiceId == null ||
        selectedChoiceMarkdown == null ||
        selectionWasCorrect == null ||
        feedbackMarkdown == null ||
        choiceSubmittedAtEpochMillis == null ||
        requestedMove != null ||
        solutionRevealed
    ) {
        throw TutorEvidenceConflictException(evidenceRequestId.orEmpty())
    }
    val summary = selectedChoiceMarkdown
        .trim()
        .take(LearningObservationSourceFact.MAX_SUMMARY_CHARS)
        .trim()
    if (
        summary.isBlank() ||
        summary.any { character ->
            character.isISOControl() && character !in "\n\r\t"
        }
    ) {
        throw TutorEvidenceConflictException(evidenceRequestId)
    }
    return summary
}

internal fun TutorTurnResponseEntity.canonicalChoiceFingerprint(): String {
    canonicalChoiceSummary()
    return CanonicalSha256(CAPTURED_CHOICE_RESPONSE_FINGERPRINT_DOMAIN)
        .field("sessionId", sessionId)
        .field("questionDocumentId", questionDocumentId)
        .field("revisionNumber", revisionNumber)
        .field("cycleOrdinal", cycleOrdinal)
        .field("turnOrdinal", turnOrdinal)
        .nullableField("diagnosticStemMarkdown", diagnosticStemMarkdown)
        .nullableField("selectedChoiceId", selectedChoiceId)
        .nullableField("selectedChoiceMarkdown", selectedChoiceMarkdown)
        .nullableField("selectionWasCorrect", selectionWasCorrect?.toString())
        .nullableField("feedbackMarkdown", feedbackMarkdown)
        .nullableField("requestedMove", requestedMove)
        .field("solutionRevealed", solutionRevealed)
        .field("submittedAtEpochMillis", submittedAtEpochMillis)
        .field("updatedAtEpochMillis", updatedAtEpochMillis)
        .nullableField(
            "choiceSubmittedAtEpochMillis",
            choiceSubmittedAtEpochMillis?.toString(),
        )
        .nullableField("evidenceRequestId", evidenceRequestId)
        .finish()
}

internal fun canonicalChoiceDirectiveFingerprint(
    evidenceRequestId: String,
    session: TutorSessionEntity,
    questionDocumentId: String,
    revisionFingerprint: String,
    input: TutorPlanInput,
    output: TutorPlanOutput,
    item: TutorAssessmentItem,
): String {
    val digest = CanonicalSha256(CAPTURED_CHOICE_DIRECTIVE_FINGERPRINT_DOMAIN)
        .field("planRequestId", evidenceRequestId)
        .field("sessionId", session.sessionId)
        .field("draftId", session.draftId)
        .field("draftRevisionNumber", session.draftRevisionNumber)
        .field("questionDocumentId", questionDocumentId)
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

internal fun canonicalChoiceSourceFactId(
    learnerId: String,
    evidenceRequestId: String,
    responseFingerprint: String,
): String = opaqueId(
    CAPTURED_CHOICE_SOURCE_FACT_ID_DOMAIN,
    learnerId,
    evidenceRequestId,
    responseFingerprint,
)

internal fun opaqueId(domain: String, vararg values: String): String {
    val digest = CanonicalSha256(domain)
        .field("valueCount", values.size)
    values.forEachIndexed { index, value ->
        digest.field("value[$index]", value)
    }
    return "$domain:${digest.finish()}"
}

internal fun TutorEvidenceCancellationEntity.canonicalFingerprint(): String =
    CanonicalSha256(PERSISTENT_CANCELLATION_FINGERPRINT_DOMAIN)
        .field("learnerId", learnerId)
        .field("sessionId", sessionId)
        .field("questionDocumentId", questionDocumentId)
        .field("revisionNumber", revisionNumber)
        .field("evidenceRequestId", evidenceRequestId)
        .field("cancelledAtEpochMillis", cancelledAtEpochMillis)
        .finish()

internal data class TrustedChoiceScope(
    val questionFingerprint: String,
    val revisionFingerprint: String,
    val fingerprintVersion: String,
)

internal fun TutorConversationEntity.toModel() = TutorConversation(
    conversationId = conversationId,
    learnerScopeId = learnerId,
    generation = generation,
    status = TutorConversationStatus.valueOf(status),
    createdAtEpochMillis = createdAtEpochMillis,
    archivedAtEpochMillis = archivedAtEpochMillis,
    stateVersion = stateVersion,
)

internal fun TutorTurnReceiptEntity.toModel() = TutorTurnReceipt(
    turnReceiptId = turnReceiptId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnOrdinal = turnOrdinal,
    subject = SubjectKind.valueOf(subject),
    problemAnchorId = problemAnchorId,
    requestVersion = requestVersion,
    modeVersion = modeVersion,
    explanationMode = TutorExplanationMode.valueOf(explanationMode),
    directiveFingerprint = directiveFingerprint,
    studentMessageFingerprint = studentMessageFingerprint,
    studentMessageSummary = studentMessageSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun TutorEvidenceRequestEntity.toModel() = TutorEvidenceRequest(
    evidenceRequestId = evidenceRequestId,
    conversationId = conversationId,
    conversationGeneration = conversationGeneration,
    conversationStateVersion = conversationStateVersion,
    turnReceiptId = turnReceiptId,
    turnOrdinal = turnOrdinal,
    subject = SubjectKind.valueOf(subject),
    problemAnchorId = problemAnchorId,
    kind = TutorEvidenceRequestKind.valueOf(kind),
    requestVersion = requestVersion,
    modeVersion = modeVersion,
    explanationMode = TutorExplanationMode.valueOf(explanationMode),
    directiveFingerprint = directiveFingerprint,
    status = TutorEvidenceRequestStatus.valueOf(status),
    stateVersion = stateVersion,
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
    // A legacy source-fact id is migration/read compatibility only. It must never masquerade as
    // an owner-issued learner-mastery receipt in the production domain ABI.
    terminalReceiptId = null,
)

internal fun LearningProblemAnchorEntity.toModel() = LearningProblemAnchor(
    anchorId = anchorId,
    learnerScopeId = learnerId,
    subject = SubjectKind.valueOf(subject),
    questionFingerprint = questionFingerprint,
    revisionFingerprint = revisionFingerprint,
    fingerprintVersion = fingerprintVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

internal fun LearningObservationSourceFactEntity.toModel() = LearningObservationSourceFact(
    sourceFactId = sourceFactId,
    learnerScopeId = learnerId,
    source = LearningObservationSource.valueOf(source),
    factKind = com.tingyun.smartmistakebook.core.model.LearningObservationFactKind.valueOf(factKind),
    anchorId = anchorId,
    subject = SubjectKind.valueOf(subject),
    conversationGeneration = conversationGeneration,
    conversationId = conversationId,
    turnReceiptId = turnReceiptId,
    evidenceRequestId = evidenceRequestId,
    responseFingerprint = responseFingerprint,
    responseSummary = responseSummary,
    occurredAtEpochMillis = occurredAtEpochMillis,
    sourceVersion = sourceVersion,
)

internal const val CAPTURED_CHOICE_SOURCE_VERSION = "captured-choice-source-v1"
internal const val CAPTURED_CHOICE_DIRECTIVE_FINGERPRINT_DOMAIN =
    "captured-choice-directive-v1"
internal const val CAPTURED_CHOICE_RESPONSE_FINGERPRINT_DOMAIN = "captured-choice-response-v1"
internal const val CAPTURED_CHOICE_SOURCE_FACT_ID_DOMAIN = "captured-choice-fact-v1"
internal const val CAPTURED_CHOICE_CONVERSATION_ID_DOMAIN = "captured-choice-conversation-v2"
internal const val CAPTURED_CHOICE_TURN_RECEIPT_ID_DOMAIN = "captured-choice-turn-v1"
internal const val CAPTURED_CHOICE_PROBLEM_ANCHOR_ID_DOMAIN = "captured-choice-anchor-v1"
internal const val PERSISTENT_CANCELLATION_FINGERPRINT_DOMAIN =
    "durable-tutor-evidence-cancellation-v1"
internal const val PERSISTENT_CANCELLATION_ID_PREFIX = "durable-tutor-evidence-cancellation"
