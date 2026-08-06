package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverTutorInteractionSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.PersistTutorAnswerExposureCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorEvidenceCancellationCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorMoveCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorRevealCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionProblemAnchorRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.TutorVisualTargetEvidenceRecord
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal class LegacyTutorInteractionSessionAdapter(
    private val boundScope: SessionScope,
    private val legacy: LegacyPreCutoverTutorInteractionSessionDatabasePort,
) : TutorInteractionSessionPort {
    override val scope: SessionScope
        get() = boundScope

    override fun observeCurrent(
        conversationId: String,
    ): Flow<TutorCurrentInteractionSessionSnapshot> =
        throw UnsupportedOperationException("Frozen tutor interactions have no current session")

    override suspend fun authorizeCurrent(
        query: TutorCurrentInteractionAuthorizationQuery,
    ): TutorCurrentInteractionAuthorization? =
        throw UnsupportedOperationException("Frozen tutor interactions cannot authorize appends")

    override suspend fun mutateCurrent(
        command: TutorAuthorizedInteractionSessionMutation,
    ): TutorInteractionSessionMutationResult =
        throw UnsupportedOperationException("Frozen tutor interactions reject current appends")

    override fun observeResponses(
        scope: SessionScope,
        sessionId: String,
    ): Flow<List<TutorTurnResponseSessionSnapshot>> {
        scope.requireBoundTo(boundScope)
        sessionId.requireSessionIdentifier("Tutor interaction session id")
        return legacy.observeTutorTurnResponses(sessionId).map { records ->
            records.map { it.toSession(boundScope) }
        }
    }

    override fun observeVisualSelections(
        scope: SessionScope,
        sessionId: String,
    ): Flow<List<TutorVisualSelectionSessionSnapshot>> {
        scope.requireBoundTo(boundScope)
        sessionId.requireSessionIdentifier("Tutor interaction session id")
        return legacy.observeTutorVisualTargetEvidence(sessionId).map { records ->
            records.map { it.toSession(boundScope) }
        }
    }

    override suspend fun isEvidenceCancelled(
        query: TutorEvidenceCancellationSessionQuery,
    ): Boolean {
        query.scope.requireBoundTo(boundScope)
        return legacy.isTutorEvidenceCancelled(
            query.toLegacyCancellation(
                boundScope = boundScope,
                cancelledAtEpochMillis = 0,
            ),
        )
    }

    override suspend fun readExposures(
        query: TutorAnswerExposureSessionQuery,
    ): List<TutorAnswerExposureSessionSnapshot> {
        query.scope.requireBoundTo(boundScope)
        return legacy.readTutorAnswerExposures(query.modelTaskRequestIds)
            .onEach {
                require(it.learnerId == boundScope.learnerId) {
                    "Legacy tutor exposure belongs to another learner"
                }
            }.map { it.toSession(boundScope) }
    }

    override suspend fun mutate(
        command: TutorInteractionSessionMutation,
    ): TutorInteractionSessionMutationResult {
        command.scope.requireBoundTo(boundScope)
        require(command.occurredAtEpochMillis >= 0) {
            "Tutor interaction time must not be negative"
        }
        return when (command) {
            is TutorInteractionSessionMutation.RecordChoice -> recordChoice(command)
            is TutorInteractionSessionMutation.RecordVisualSelection ->
                recordVisualSelection(command)
            is TutorInteractionSessionMutation.CancelEvidence -> cancelEvidence(command)
            is TutorInteractionSessionMutation.RecordMove -> recordMove(command)
            is TutorInteractionSessionMutation.RevealSolution -> revealSolution(command)
            is TutorInteractionSessionMutation.RecordExposure -> recordExposure(command)
            is TutorInteractionSessionMutation.AnchorSession -> anchorSession(command)
        }
    }

    private suspend fun recordChoice(
        command: TutorInteractionSessionMutation.RecordChoice,
    ): TutorInteractionSessionMutationResult {
        command.requireValid()
        val before = readResponse(command.key)
        command.expectedVersion?.let { expected ->
            if (before?.version != expected) {
                return responseResult(
                    command.operation,
                    SessionMutationDisposition.RELOAD_REQUIRED,
                    before,
                    command.occurredAtEpochMillis,
                )
            }
        }
        val persisted =
            PersistTutorChoiceCommand(
                sessionId = command.key.sessionId,
                questionDocumentId = command.key.questionDocumentId,
                revisionNumber = command.key.revisionNumber,
                cycleOrdinal = command.key.cycleOrdinal,
                turnOrdinal = command.key.turnOrdinal,
                diagnosticStemMarkdown = command.diagnosticStemMarkdown,
                selectedChoiceId = command.selectedChoiceId,
                selectedChoiceMarkdown = command.selectedChoiceMarkdown,
                selectionWasCorrect = command.selectionWasCorrect,
                feedbackMarkdown = command.feedbackMarkdown,
                choiceSubmittedAtEpochMillis = command.occurredAtEpochMillis,
                evidenceRequestId = command.evidenceRequestId,
            )
        val record =
            if (command.evidenceRequestId == null) {
                legacy.recordTutorChoice(persisted)
            } else {
                legacy.recordTutorChoiceUnlessCancelled(
                    persisted,
                    TutorEvidenceCancellationSessionQuery(
                        scope = boundScope,
                        key = command.key,
                        evidenceRequestId = command.evidenceRequestId,
                    ).toLegacyCancellation(
                        boundScope = boundScope,
                        cancelledAtEpochMillis = command.occurredAtEpochMillis,
                    ),
                ) ?: return responseResult(
                    command.operation,
                    SessionMutationDisposition.REJECTED,
                    before,
                    command.occurredAtEpochMillis,
                )
            }
        val after = record.toSession(boundScope)
        return responseResult(
            command.operation,
            if (after == before) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.APPLIED
            },
            after,
            command.occurredAtEpochMillis,
        )
    }

    private suspend fun recordVisualSelection(
        command: TutorInteractionSessionMutation.RecordVisualSelection,
    ): TutorInteractionSessionMutationResult {
        command.requireValid()
        val before = readVisualSelection(command.key, command.modelTaskRequestId)
        command.expectedVersion?.let { expected ->
            if (before?.version != expected) {
                return visualResult(
                    command.operation,
                    SessionMutationDisposition.RELOAD_REQUIRED,
                    before,
                    command.occurredAtEpochMillis,
                )
            }
        }
        val record =
            legacy.recordTutorVisualTargetEvidenceUnlessCancelled(
                PersistTutorVisualTargetEvidenceCommand(
                    sessionId = command.key.sessionId,
                    questionDocumentId = command.key.questionDocumentId,
                    revisionNumber = command.key.revisionNumber,
                    cycleOrdinal = command.key.cycleOrdinal,
                    turnOrdinal = command.key.turnOrdinal,
                    surfaceKind = command.surfaceKind,
                    modelTaskRequestId = command.modelTaskRequestId,
                    responseOrdinal = command.responseOrdinal,
                    sceneSourceKind = command.sceneSourceKind,
                    sceneTaskRequestId = command.sceneTaskRequestId,
                    sceneId = command.sceneId,
                    sceneFingerprint = command.sceneFingerprint,
                    hitProofId = command.hitProofId,
                    panelId = command.panelId,
                    frameFingerprint = command.frameFingerprint,
                    stepIndex = command.stepIndex,
                    selectedTargetId = command.selectedTargetId,
                    submittedAtEpochMillis = command.occurredAtEpochMillis,
                ),
                TutorEvidenceCancellationSessionQuery(
                    scope = boundScope,
                    key = command.key,
                    evidenceRequestId = command.evidenceRequestId,
                ).toLegacyCancellation(
                    boundScope = boundScope,
                    cancelledAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ) ?: return visualResult(
                command.operation,
                SessionMutationDisposition.REJECTED,
                before,
                command.occurredAtEpochMillis,
            )
        val after = record.toSession(boundScope)
        return visualResult(
            command.operation,
            if (after == before) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.APPLIED
            },
            after,
            command.occurredAtEpochMillis,
        )
    }

    private suspend fun cancelEvidence(
        command: TutorInteractionSessionMutation.CancelEvidence,
    ): TutorInteractionSessionMutationResult {
        command.evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
        val query =
            TutorEvidenceCancellationSessionQuery(
                scope = boundScope,
                key = command.key,
                evidenceRequestId = command.evidenceRequestId,
            )
        val persisted =
            query.toLegacyCancellation(
                boundScope = boundScope,
                cancelledAtEpochMillis = command.occurredAtEpochMillis,
            )
        val existing = legacy.isTutorEvidenceCancelled(persisted)
        legacy.recordTutorEvidenceCancellation(persisted)
        val version = command.syntheticVersion("tutor-evidence-cancellation-v1")
        return TutorInteractionSessionMutationResult(
            receipt =
                receipt(
                    command.operation,
                    if (existing) {
                        SessionMutationDisposition.DUPLICATE
                    } else {
                        SessionMutationDisposition.APPLIED
                    },
                    version,
                    command.occurredAtEpochMillis,
                ),
        )
    }

    private suspend fun recordMove(
        command: TutorInteractionSessionMutation.RecordMove,
    ): TutorInteractionSessionMutationResult {
        command.requestedMove.requireSessionIdentifier("Tutor requested move")
        val before = readResponse(command.key)
        if (before?.version != command.expectedVersion) {
            return responseResult(
                command.operation,
                if (before == null) {
                    SessionMutationDisposition.NOT_FOUND
                } else {
                    SessionMutationDisposition.RELOAD_REQUIRED
                },
                before,
                command.occurredAtEpochMillis,
            )
        }
        val after =
            legacy.recordTutorMove(
                PersistTutorMoveCommand(
                    sessionId = command.key.sessionId,
                    questionDocumentId = command.key.questionDocumentId,
                    revisionNumber = command.key.revisionNumber,
                    cycleOrdinal = command.key.cycleOrdinal,
                    turnOrdinal = command.key.turnOrdinal,
                    requestedMove = command.requestedMove,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toSession(boundScope)
        return responseResult(
            command.operation,
            if (after == before) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.APPLIED
            },
            after,
            command.occurredAtEpochMillis,
        )
    }

    private suspend fun revealSolution(
        command: TutorInteractionSessionMutation.RevealSolution,
    ): TutorInteractionSessionMutationResult {
        val before = readResponse(command.key)
        if (before?.version != command.expectedVersion) {
            return responseResult(
                command.operation,
                if (before == null) {
                    SessionMutationDisposition.NOT_FOUND
                } else {
                    SessionMutationDisposition.RELOAD_REQUIRED
                },
                before,
                command.occurredAtEpochMillis,
            )
        }
        val after =
            legacy.revealTutorSolution(
                PersistTutorRevealCommand(
                    learnerId = boundScope.learnerId,
                    sessionId = command.key.sessionId,
                    questionDocumentId = command.key.questionDocumentId,
                    revisionNumber = command.key.revisionNumber,
                    cycleOrdinal = command.key.cycleOrdinal,
                    turnOrdinal = command.key.turnOrdinal,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toSession(boundScope)
        return responseResult(
            command.operation,
            if (checkNotNull(before).solutionRevealed) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.APPLIED
            },
            after,
            command.occurredAtEpochMillis,
        )
    }

    private suspend fun recordExposure(
        command: TutorInteractionSessionMutation.RecordExposure,
    ): TutorInteractionSessionMutationResult {
        command.surfaceKind.requireSessionIdentifier("Tutor exposure surface kind")
        command.modelTaskRequestId.requireSessionIdentifier("Tutor exposure model request id")
        val before =
            legacy.readTutorAnswerExposure(command.modelTaskRequestId)
                ?.takeIf { it.learnerId == boundScope.learnerId }
                ?.toSession(boundScope)
        command.expectedVersion?.let { expected ->
            if (before?.version != expected) {
                return exposureResult(
                    command.operation,
                    SessionMutationDisposition.RELOAD_REQUIRED,
                    before,
                    command.occurredAtEpochMillis,
                )
            }
        }
        val after =
            legacy.recordTutorSolutionExposure(
                PersistTutorAnswerExposureCommand(
                    learnerId = boundScope.learnerId,
                    sessionId = command.key.sessionId,
                    questionDocumentId = command.key.questionDocumentId,
                    revisionNumber = command.key.revisionNumber,
                    cycleOrdinal = command.key.cycleOrdinal,
                    turnOrdinal = command.key.turnOrdinal,
                    surfaceKind = command.surfaceKind,
                    modelTaskRequestId = command.modelTaskRequestId,
                    responseOrdinal = command.responseOrdinal,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toSession(boundScope)
        return exposureResult(
            command.operation,
            if (after == before) {
                SessionMutationDisposition.DUPLICATE
            } else {
                SessionMutationDisposition.APPLIED
            },
            after,
            command.occurredAtEpochMillis,
        )
    }

    private suspend fun anchorSession(
        command: TutorInteractionSessionMutation.AnchorSession,
    ): TutorInteractionSessionMutationResult {
        command.sessionId.requireSessionIdentifier("Tutor anchor session id")
        command.targetRevisionRef.requireSessionIdentifier("Tutor anchor revision reference")
        command.targetPracticeRef.requireSessionIdentifier("Tutor anchor practice reference")
        command.sourceKind.requireSessionIdentifier("Tutor anchor source kind")
        val anchor =
            legacy.bindTutorSessionProblemAnchor(
                PersistTutorSessionAnchorCommand(
                    learnerId = boundScope.learnerId,
                    sessionId = command.sessionId,
                    problemRevisionId = command.targetRevisionRef,
                    practiceUnitId = command.targetPracticeRef,
                    source = command.sourceKind,
                    anchoredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toSession(boundScope)
        val version = command.syntheticVersion("tutor-session-anchor-v1")
        return TutorInteractionSessionMutationResult(
            receipt =
                receipt(
                    command.operation,
                    SessionMutationDisposition.APPLIED,
                    version,
                    command.occurredAtEpochMillis,
                ),
            anchor = anchor,
        )
    }

    private suspend fun readResponse(
        key: TutorTurnSessionKey,
    ): TutorTurnResponseSessionSnapshot? =
        legacy.observeTutorTurnResponses(key.sessionId).first()
            .firstOrNull { it.matches(key) }
            ?.toSession(boundScope)

    private suspend fun readVisualSelection(
        key: TutorTurnSessionKey,
        modelTaskRequestId: String,
    ): TutorVisualSelectionSessionSnapshot? =
        legacy.observeTutorVisualTargetEvidence(key.sessionId).first()
            .firstOrNull { it.matches(key) && it.modelTaskRequestId == modelTaskRequestId }
            ?.toSession(boundScope)
}

private fun TutorInteractionSessionMutation.RecordChoice.requireValid() {
    require(
        diagnosticStemMarkdown.isNotBlank() &&
            selectedChoiceId.isNotBlank() &&
            selectedChoiceMarkdown.isNotBlank() &&
            feedbackMarkdown.isNotBlank(),
    ) { "Tutor choice session payload is incomplete" }
    listOf(diagnosticStemMarkdown, selectedChoiceMarkdown, feedbackMarkdown).forEach {
        require(it.length <= 8_000) { "Tutor choice text exceeds its session budget" }
    }
    evidenceRequestId?.requireSessionIdentifier("Tutor evidence request id")
}

private fun TutorInteractionSessionMutation.RecordVisualSelection.requireValid() {
    evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
    surfaceKind.requireSessionIdentifier("Tutor visual surface kind")
    modelTaskRequestId.requireSessionIdentifier("Tutor model request id")
    require(responseOrdinal == null || responseOrdinal > 0) {
        "Tutor visual response ordinal must be positive"
    }
    sceneSourceKind.requireSessionIdentifier("Tutor visual source kind")
    sceneTaskRequestId.requireSessionIdentifier("Tutor visual scene request id")
    sceneId.requireSessionIdentifier("Tutor visual scene id")
    sceneFingerprint.requireSessionFingerprint("Tutor visual scene fingerprint")
    hitProofId.requireSessionIdentifier("Tutor visual hit-proof id")
    panelId.requireSessionIdentifier("Tutor visual panel id")
    frameFingerprint.requireSessionFingerprint("Tutor visual frame fingerprint")
    require(stepIndex >= 0) { "Tutor visual step index must not be negative" }
    selectedTargetId.requireSessionIdentifier("Tutor visual selected target id")
}

private fun TutorEvidenceCancellationSessionQuery.toLegacyCancellation(
    boundScope: SessionScope,
    cancelledAtEpochMillis: Long,
): PersistTutorEvidenceCancellationCommand {
    scope.requireBoundTo(boundScope)
    require(cancelledAtEpochMillis >= 0) {
        "Tutor evidence cancellation time must not be negative"
    }
    return PersistTutorEvidenceCancellationCommand(
        learnerId = boundScope.learnerId,
        sessionId = key.sessionId,
        questionDocumentId = key.questionDocumentId,
        revisionNumber = key.revisionNumber,
        evidenceRequestId = evidenceRequestId,
        cancelledAtEpochMillis = cancelledAtEpochMillis,
    )
}

private fun TutorTurnResponseRecord.matches(key: TutorTurnSessionKey): Boolean =
    sessionId == key.sessionId &&
        questionDocumentId == key.questionDocumentId &&
        revisionNumber == key.revisionNumber &&
        cycleOrdinal == key.cycleOrdinal &&
        turnOrdinal == key.turnOrdinal

private fun TutorVisualTargetEvidenceRecord.matches(key: TutorTurnSessionKey): Boolean =
    sessionId == key.sessionId &&
        questionDocumentId == key.questionDocumentId &&
        revisionNumber == key.revisionNumber &&
        cycleOrdinal == key.cycleOrdinal &&
        turnOrdinal == key.turnOrdinal

private fun TutorTurnResponseRecord.toSession(scope: SessionScope):
    TutorTurnResponseSessionSnapshot {
    val fingerprint =
        CanonicalSha256("tutor-response-session-state-v1")
            .field("sessionId", sessionId)
            .field("questionDocumentId", questionDocumentId)
            .field("revisionNumber", revisionNumber)
            .field("cycleOrdinal", cycleOrdinal)
            .field("turnOrdinal", turnOrdinal)
            .nullableField("selectedChoiceId", selectedChoiceId)
            .nullableField("requestedMove", requestedMove)
            .field("solutionRevealed", solutionRevealed)
            .field("updatedAtEpochMillis", updatedAtEpochMillis)
            .nullableField("evidenceRequestId", evidenceRequestId)
            .finish()
    return TutorTurnResponseSessionSnapshot(
        scope = scope,
        key =
            TutorTurnSessionKey(
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = revisionNumber,
                cycleOrdinal = cycleOrdinal,
                turnOrdinal = turnOrdinal,
            ),
        version = SessionVersion(updatedAtEpochMillis, fingerprint),
        diagnosticStemMarkdown = diagnosticStemMarkdown,
        selectedChoiceId = selectedChoiceId,
        selectedChoiceMarkdown = selectedChoiceMarkdown,
        selectionWasCorrect = selectionWasCorrect,
        feedbackMarkdown = feedbackMarkdown,
        requestedMove = requestedMove,
        solutionRevealed = solutionRevealed,
        choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
        submittedAtEpochMillis = submittedAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        evidenceRequestId = evidenceRequestId,
    )
}

private fun TutorVisualTargetEvidenceRecord.toSession(
    scope: SessionScope,
): TutorVisualSelectionSessionSnapshot {
    val fingerprint =
        CanonicalSha256("tutor-visual-selection-session-state-v1")
            .field("sessionId", sessionId)
            .field("questionDocumentId", questionDocumentId)
            .field("revisionNumber", revisionNumber)
            .field("cycleOrdinal", cycleOrdinal)
            .field("turnOrdinal", turnOrdinal)
            .field("modelTaskRequestId", modelTaskRequestId)
            .field("sceneFingerprint", sceneFingerprint)
            .field("hitProofId", hitProofId)
            .field("frameFingerprint", frameFingerprint)
            .field("selectedTargetId", selectedTargetId)
            .field("submittedAtEpochMillis", submittedAtEpochMillis)
            .finish()
    return TutorVisualSelectionSessionSnapshot(
        scope = scope,
        key =
            TutorTurnSessionKey(
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = revisionNumber,
                cycleOrdinal = cycleOrdinal,
                turnOrdinal = turnOrdinal,
            ),
        version = SessionVersion(submittedAtEpochMillis, fingerprint),
        surfaceKind = surfaceKind,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
        sceneSourceKind = sceneSourceKind,
        sceneTaskRequestId = sceneTaskRequestId,
        sceneId = sceneId,
        sceneFingerprint = sceneFingerprint,
        hitProofId = hitProofId,
        panelId = panelId,
        frameFingerprint = frameFingerprint,
        stepIndex = stepIndex,
        selectedTargetId = selectedTargetId,
        selectionWasCorrect = selectionWasCorrect,
        submittedAtEpochMillis = submittedAtEpochMillis,
    )
}

private fun TutorAnswerExposureRecord.toSession(
    scope: SessionScope,
): TutorAnswerExposureSessionSnapshot {
    val fingerprint =
        CanonicalSha256("tutor-answer-exposure-session-state-v1")
            .field("exposureId", exposureId)
            .field("learnerId", learnerId)
            .field("sessionId", sessionId)
            .field("questionDocumentId", questionDocumentId)
            .field("questionRevisionNumber", questionRevisionNumber)
            .field("cycleOrdinal", cycleOrdinal)
            .field("turnOrdinal", turnOrdinal)
            .field("surfaceKind", surfaceKind)
            .nullableField("modelTaskRequestId", modelTaskRequestId)
            .nullableField("responseOrdinal", responseOrdinal?.toString())
            .field("exposedAtEpochMillis", exposedAtEpochMillis)
            .finish()
    return TutorAnswerExposureSessionSnapshot(
        scope = scope,
        exposureId = exposureId,
        key =
            TutorTurnSessionKey(
                sessionId = sessionId,
                questionDocumentId = questionDocumentId,
                revisionNumber = questionRevisionNumber,
                cycleOrdinal = cycleOrdinal,
                turnOrdinal = turnOrdinal,
            ),
        version = SessionVersion(exposedAtEpochMillis, fingerprint),
        surfaceKind = surfaceKind,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
        exposedAtEpochMillis = exposedAtEpochMillis,
        outcomeReceiptId = outcomeId,
    )
}

private fun TutorSessionProblemAnchorRecord.toSession(
    scope: SessionScope,
) = TutorSessionAnchorReceipt(
    scope = scope,
    sessionId = sessionId,
    targetRevisionRef = problemRevisionId,
    targetPracticeRef = practiceUnitId,
    sourceKind = source,
    anchoredAtEpochMillis = anchoredAtEpochMillis,
)

private fun TutorInteractionSessionMutation.syntheticVersion(domain: String): SessionVersion =
    SessionVersion(
        sequence = occurredAtEpochMillis,
        fingerprint =
            CanonicalSha256(domain)
                .field("learnerId", scope.learnerId)
                .field("requestId", operation.requestId)
                .field("idempotencyKey", operation.idempotencyKey)
                .field("payloadFingerprint", operation.payloadFingerprint)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .finish(),
    )

private fun receipt(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    version: SessionVersion?,
    occurredAtEpochMillis: Long,
) = SessionMutationReceipt(
    operation = operation,
    disposition = disposition,
    currentVersion = version,
    recordedAtEpochMillis = occurredAtEpochMillis,
)

private fun responseResult(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    response: TutorTurnResponseSessionSnapshot?,
    occurredAtEpochMillis: Long,
) = TutorInteractionSessionMutationResult(
    receipt = receipt(operation, disposition, response?.version, occurredAtEpochMillis),
    response = response,
)

private fun visualResult(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    visual: TutorVisualSelectionSessionSnapshot?,
    occurredAtEpochMillis: Long,
) = TutorInteractionSessionMutationResult(
    receipt = receipt(operation, disposition, visual?.version, occurredAtEpochMillis),
    visualSelection = visual,
)

private fun exposureResult(
    operation: SessionOperationIdentity,
    disposition: SessionMutationDisposition,
    exposure: TutorAnswerExposureSessionSnapshot?,
    occurredAtEpochMillis: Long,
) = TutorInteractionSessionMutationResult(
    receipt = receipt(operation, disposition, exposure?.version, occurredAtEpochMillis),
    exposure = exposure,
)
