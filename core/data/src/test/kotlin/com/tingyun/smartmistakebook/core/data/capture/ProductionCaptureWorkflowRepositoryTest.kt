package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceIdentity
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult
import com.tingyun.smartmistakebook.core.domain.PendingCaptureItem
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.model.CaptureFinalConfirmationRequestIdentity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.AcknowledgeStudentCaptureSaveHandoffCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.AppendStudentProblemErrorOccurrenceCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.CommitStudentProblemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.LibraryStudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.ReadPendingStudentCaptureSaveHandoffsQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.SaveStudentOwnedCaptureCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.SaveTargetConfirmedStudentMistakeCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveHandoffRecord
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOwnedCaptureSaveReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentPracticeUnitKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemCanonicalIdentityKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemErrorOccurrence
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference
import com.tingyun.smartmistakebook.core.student.mistake.database.TargetConfirmedStudentMistakeSaveOutcome
import com.tingyun.smartmistakebook.core.student.mistake.database.TutorStudentCaptureSaveSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCaptureWorkflowRepositoryTest {
    @Test
    fun tutorCaptureEndedWithoutSaveNeverEntersTheStudentMistakeLibrary() = runBlocking {
        val session = FakeProductionCaptureSessionPort()
        val transition = RecordingCaptureOccurrenceCommitPort()
        val repository = repository(session, transition)
        val request = confirmation("draft-unsaved")

        val tutorSession = repository.confirmForTutoring(request)
        val ended =
            repository.endTutorSessionWithoutSaving(
                EndTutorSessionWithoutSaveRequest(
                    sessionId = tutorSession.sessionId,
                    occurredAtEpochMillis = 2_000L,
                ),
            )

        assertEquals(request.draftId, ended.draftId)
        assertTrue(transition.saveInvocations.isEmpty())
        assertTrue(transition.records.isEmpty())
    }

    @Test
    fun repeatedLibrarySaveCommitsOneStudentOwnedProblem() = runBlocking {
        val session = FakeProductionCaptureSessionPort()
        val transition = RecordingCaptureOccurrenceCommitPort()
        val repository = repository(session, transition)
        val request = confirmation("draft-idempotent")

        val created = repository.confirmAndCommit(request)
        val replayed = repository.confirmAndCommit(request)

        assertTrue(created.created)
        assertFalse(replayed.created)
        assertEquals(created.copy(created = false), replayed)
        assertEquals(1, transition.saveInvocations.size)
        assertEquals(1, transition.records.size)
    }

    @Test
    fun commitSummaryUsesTheStudentAuthorityResolvedTargetIds() = runBlocking {
        val session = FakeProductionCaptureSessionPort()
        val authority =
            RecordingCaptureOccurrenceCommitPort(resolveDifferentTargetIds = true)
        val result =
            repository(session, authority)
                .confirmAndCommit(confirmation("draft-resolved-target"))

        assertEquals("resolved-problem-draft-resolved-target", result.problemId)
        assertEquals("resolved-revision-draft-resolved-target", result.problemRevisionId)
        assertEquals("resolved-practice-draft-resolved-target", result.practiceUnitId)
        assertEquals("resolved-error-draft-resolved-target", result.errorBookEntryId)
        assertNotEquals(
            authority.saveInvocations.single().target.problem.revision.problem.problemId,
            result.problemId,
        )
    }

    @Test
    fun everyCanonicalImageReachesTheStudentOwnedCommandInOrder() = runBlocking {
        val session =
            FakeProductionCaptureSessionPort(
                imageCounts = mapOf("draft-images" to 3),
            )
        val transition = RecordingCaptureOccurrenceCommitPort()
        val repository = repository(session, transition)

        repository.confirmAndCommit(confirmation("draft-images"))

        val images =
            transition.saveInvocations.single()
                .target.problem.originalImages
        assertEquals(listOf(0, 1, 2), images.map(StudentProblemImageReference::ordinal))
        assertEquals(
            listOf(
                "content://student-capture/draft-images/0",
                "content://student-capture/draft-images/1",
                "content://student-capture/draft-images/2",
            ),
            images.map(StudentProblemImageReference::localContentUri),
        )
    }

    @Test
    fun processRestartReplaysOnlyThePendingSessionAcknowledgement() = runBlocking {
        val session = FakeProductionCaptureSessionPort(failAcknowledgement = true)
        val transition = RecordingCaptureOccurrenceCommitPort()
        val request = confirmation("draft-recovery")

        repository(session, transition).confirmAndCommit(request)

        assertEquals(1, transition.saveInvocations.size)
        assertEquals(1, transition.pending.size)
        session.failAcknowledgement = false

        repository(session, transition).readPendingCapture("another-draft")

        assertEquals(1, transition.saveInvocations.size)
        assertTrue(transition.pending.isEmpty())
        assertEquals(2, session.acknowledgementAttempts)
    }

    @Test
    fun failedFirstPageDoesNotStarveLaterPendingAcknowledgements() = runBlocking {
        val session = FakeProductionCaptureSessionPort(failAcknowledgement = true)
        val authority = RecordingCaptureOccurrenceCommitPort()
        repeat(129) { index ->
            val draftId = "draft-pending-${index.toString().padStart(3, '0')}"
            val source =
                LibraryStudentCaptureSaveSource(
                    intentId = "intent-$draftId",
                    sourceCanonicalFingerprint = sourceFingerprint(draftId),
                    draftId = draftId,
                    basisRevisionNumber = 1,
                    workspaceVersion = 1,
                    workspaceCanonicalFingerprint = "b".repeat(64),
                    confirmationRequestId = "confirm-$draftId",
                    occurredAtEpochMillis = 1_000L + index,
                )
            authority.save(
                preparedCapture(source, learnerId = session.learnerId, imageCount = 1),
                ProductionStudentProblemIdentityEvidenceRequest
                    .ExactAssetSelectionOrUnresolved,
            )
        }

        repository(session, authority).readPendingCapture("another-draft")

        assertEquals(129, session.acknowledgementAttempts)
    }

    @Test
    fun mismatchedStudentAcknowledgementIsNotSwallowed() = runBlocking {
        val session = FakeProductionCaptureSessionPort()
        val authority =
            RecordingCaptureOccurrenceCommitPort(
                mismatchAcknowledgement = true,
            )

        val result =
            runCatching {
                repository(session, authority)
                    .confirmAndCommit(confirmation("draft-ack-mismatch"))
            }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(1, session.acknowledgementAttempts)
    }

    @Test
    fun lateSessionResultCannotMutateAnAlreadyStudentOwnedCapture() = runBlocking {
        val session = FakeProductionCaptureSessionPort()
        val transition = RecordingCaptureOccurrenceCommitPort()
        val repository = repository(session, transition)
        val request = confirmation("draft-late")
        repository.confirmAndCommit(request)

        val lateResult = runCatching { repository.confirmForTutoring(request) }

        assertTrue(lateResult.exceptionOrNull() is IllegalStateException)
        assertEquals(0, session.tutorConfirmationCalls)
        assertEquals(1, transition.saveInvocations.size)
    }

    @Test
    fun sameDraftCommitAndLateSessionWriteShareOneTerminalLock() = runBlocking {
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val session = FakeProductionCaptureSessionPort()
        val authority =
            RecordingCaptureOccurrenceCommitPort(
                saveEntered = saveEntered,
                releaseSave = releaseSave,
            )
        val repository = repository(session, authority)
        val request = confirmation("draft-concurrent-terminal")

        coroutineScope {
            val commit = async { repository.confirmAndCommit(request) }
            saveEntered.await()
            val lateSession =
                async {
                    runCatching { repository.confirmForTutoring(request) }
                }
            yield()
            assertEquals(0, session.tutorConfirmationCalls)
            releaseSave.complete(Unit)
            commit.await()
            assertTrue(lateSession.await().exceptionOrNull() is IllegalStateException)
        }
        assertEquals(0, session.tutorConfirmationCalls)
    }

    @Test
    fun preparedCaptureForAnotherLearnerIsRejectedBeforeStudentWrite() = runBlocking {
        val session =
            FakeProductionCaptureSessionPort(
                preparedLearnerId = "learner-other",
            )
        val transition = RecordingCaptureOccurrenceCommitPort()

        val result =
            runCatching {
                repository(session, transition)
                    .confirmAndCommit(confirmation("draft-cross-learner"))
            }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(transition.saveInvocations.isEmpty())
        assertTrue(transition.records.isEmpty())
    }

    @Test
    fun anotherLearnersCommitPortIsRejectedBeforeRecoveryReads() = runBlocking {
        val session = FakeProductionCaptureSessionPort()
        val foreignPort =
            RecordingCaptureOccurrenceCommitPort(
                learnerId = "learner-other",
            )

        val result =
            runCatching {
                repository(session, foreignPort)
                    .readPendingCapture("draft-cross-owner")
            }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(foreignPort.saveInvocations.isEmpty())
        assertTrue(foreignPort.records.isEmpty())
    }

    @Test
    fun disjointCapturesRemainIndependentRevisionsAndAttachmentGroups() = runBlocking {
        val session =
            FakeProductionCaptureSessionPort(
                imageCounts =
                    mapOf(
                        "draft-group-a" to 2,
                        "draft-group-b" to 2,
                    ),
            )
        val transition = RecordingCaptureOccurrenceCommitPort()
        val repository = repository(session, transition)

        val first = repository.confirmAndCommit(confirmation("draft-group-a"))
        val second = repository.confirmAndCommit(confirmation("draft-group-b"))

        assertNotEquals(first.problemRevisionId, second.problemRevisionId)
        val imageGroups =
            transition.saveInvocations.map { command ->
                command.target.problem.originalImages
            }
        assertEquals(listOf(0, 1), imageGroups[0].map(StudentProblemImageReference::ordinal))
        assertEquals(listOf(0, 1), imageGroups[1].map(StudentProblemImageReference::ordinal))
        assertTrue(
            imageGroups[0].map(StudentProblemImageReference::localContentUri).toSet()
                .intersect(
                    imageGroups[1].map(StudentProblemImageReference::localContentUri).toSet(),
                ).isEmpty(),
        )
    }

    private fun repository(
        session: FakeProductionCaptureSessionPort,
        commitPort: RecordingCaptureOccurrenceCommitPort,
    ) = ProductionCaptureWorkflowRepositoryFactory.create(
        session = session,
        commitPreparation = session,
        commitPortProvider = { commitPort },
    )

    private fun confirmation(draftId: String): ConfirmCapturedProblemRequest =
        ConfirmCapturedProblemRequest(
            draftId = draftId,
            workspaceIdentity =
                CaptureDraftWorkspaceIdentity(
                    draftId = draftId,
                    basisRevisionNumber = 1,
                    workspaceVersion = 1,
                    workspaceFingerprint = "b".repeat(64),
                    finalConfirmationRequest =
                        CaptureFinalConfirmationRequestIdentity(
                            requestId = "confirm-$draftId",
                            occurredAtEpochMillis = 1_000L,
                        ),
                ),
        )
}

private class FakeProductionCaptureSessionPort(
    override val learnerId: String = "learner-local",
    private val imageCounts: Map<String, Int> = emptyMap(),
    private val preparedLearnerId: String = learnerId,
    var failAcknowledgement: Boolean = false,
) : ProductionCaptureSessionPort, ProductionCaptureCommitPreparationPort {
    var tutorConfirmationCalls: Int = 0
    var acknowledgementAttempts: Int = 0
    private val tutorSessions = linkedMapOf<String, ConfirmedTutorSession>()

    override fun observePendingCaptures(): Flow<List<PendingCaptureItem>> = emptyFlow()

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? = null

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): CaptureDraftWorkspaceSnapshot? = null

    override suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot = error("Not used by this contract")

    override suspend fun consumeDraftWorkspace(
        request: ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = error("Not used by this contract")

    override suspend fun importDraft(
        command: ImportCaptureDraftSessionCommand,
    ): CaptureDraftSessionId = error("Not used by this contract")

    override suspend fun readDraftSummary(
        draftSessionId: CaptureDraftSessionId,
    ): CaptureDraftSummary? = error("Not used by this contract")

    override suspend fun readCanonicalSourceAssets(
        query: ReadCaptureDraftCanonicalAssetsQuery,
    ): CaptureDraftCanonicalAssetBundle? = error("Not used by this contract")

    override suspend fun mergeAdjacentDrafts(
        command: MergeAdjacentCaptureDraftsCommand,
    ): CaptureDraftMergeReceipt = error("Not used by this contract")

    override suspend fun readMergeReceipt(
        query: ReadCaptureDraftMergeReceiptQuery,
    ): CaptureDraftMergeReceipt? = error("Not used by this contract")

    override suspend fun appendDraftPage(
        request: AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = error("Not used by this contract")

    override suspend fun replaceDraft(
        request: ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = error("Not used by this contract")

    override suspend fun splitDraft(
        request: SplitCaptureDraftRequest,
    ): CaptureDraftSplitResult = error("Not used by this contract")

    override suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession {
        tutorConfirmationCalls += 1
        val created =
            ConfirmedTutorSession(
                sessionId = "session-${request.draftId}",
                draftId = request.draftId,
                draftRevisionNumber = request.workspaceIdentity.basisRevisionNumber + 1,
                subject = "MATH",
                title = "待讲解题目",
                questionDocument = confirmedDocument(request.draftId),
                sourceImageUri = "content://capture/${request.draftId}",
                createdAtEpochMillis =
                    checkNotNull(request.workspaceIdentity.finalConfirmationRequest)
                        .occurredAtEpochMillis,
                isSaved = false,
                errorBookEntryId = null,
            )
        tutorSessions[created.sessionId] = created
        return created
    }

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? =
        tutorSessions[sessionId]

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<TutorVisualSourceAssetScope> = emptyList()

    override suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult {
        val session = checkNotNull(tutorSessions[request.sessionId])
        tutorSessions[request.sessionId] = session.copy(isEndedWithoutSave = true)
        return EndTutorSessionWithoutSaveResult(
            sessionId = session.sessionId,
            draftId = session.draftId,
            endedAtEpochMillis = request.occurredAtEpochMillis,
            created = true,
        )
    }

    override fun studentOwnedLibraryCaptureSource(
        request: ConfirmCapturedProblemRequest,
    ): LibraryStudentCaptureSaveSource {
        val identity = request.workspaceIdentity
        val confirmation = checkNotNull(identity.finalConfirmationRequest)
        return LibraryStudentCaptureSaveSource(
            intentId = confirmation.requestId,
            sourceCanonicalFingerprint = sourceFingerprint(request.draftId),
            draftId = request.draftId,
            basisRevisionNumber = identity.basisRevisionNumber,
            workspaceVersion = identity.workspaceVersion,
            workspaceCanonicalFingerprint = identity.workspaceFingerprint,
            confirmationRequestId = confirmation.requestId,
            occurredAtEpochMillis = confirmation.occurredAtEpochMillis,
        )
    }

    override suspend fun prepareStudentOwnedLibraryCapture(
        request: ConfirmCapturedProblemRequest,
        source: LibraryStudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit =
        preparedCapture(
            source = source,
            learnerId = preparedLearnerId,
            imageCount = imageCounts[request.draftId] ?: 1,
        )

    override suspend fun studentOwnedTutorCaptureSource(
        request: SaveTutorSessionRequest,
    ): TutorStudentCaptureSaveSource = error("Not used by this contract")

    override suspend fun prepareStudentOwnedTutorCapture(
        request: SaveTutorSessionRequest,
        source: TutorStudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit = error("Not used by this contract")

    override suspend fun acknowledgeStudentOwnedCaptureSession(
        receipt: CaptureStudentSaveSessionReceipt,
    ) {
        acknowledgementAttempts += 1
        check(receipt.learnerId == learnerId)
        if (failAcknowledgement) error("Temporary session store is unavailable")
    }
}

private class RecordingCaptureOccurrenceCommitPort(
    override val learnerId: String = "learner-local",
    private val resolveDifferentTargetIds: Boolean = false,
    private val saveEntered: CompletableDeferred<Unit>? = null,
    private val releaseSave: CompletableDeferred<Unit>? = null,
    private val mismatchAcknowledgement: Boolean = false,
) : ProductionCaptureOccurrenceCommitPort {
    val saveInvocations = mutableListOf<SaveStudentOwnedCaptureCommand>()
    val records = linkedMapOf<String, StudentCaptureSaveHandoffRecord>()
    val pending = linkedMapOf<String, StudentCaptureSaveHandoffRecord>()

    override suspend fun save(
        prepared: PreparedStudentOwnedCaptureCommit,
        identityEvidenceRequest: ProductionStudentProblemIdentityEvidenceRequest,
    ): ProductionCaptureOccurrenceCommitReceipt {
        check(
            identityEvidenceRequest ==
                ProductionStudentProblemIdentityEvidenceRequest.ExactAssetSelectionOrUnresolved,
        )
        saveEntered?.complete(Unit)
        releaseSave?.await()
        val command = prepared.command
        saveInvocations += command
        val existing = records[command.source.intentId]
        existing?.let {
            check(it.source == command.source)
            return prepared.toReceipt(
                handoff = it,
                outcome = TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE,
            )
        }
        val target = command.target
        val proposedRevision = target.problem.revision
        val resolvedProblem =
            if (resolveDifferentTargetIds) {
                StudentProblemRef(
                    learnerId = proposedRevision.problem.learnerId,
                    subject = proposedRevision.problem.subject,
                    problemId = "resolved-problem-${command.source.draftId}",
                    practiceUnitId = "resolved-practice-${command.source.draftId}",
                )
            } else {
                proposedRevision.problem
            }
        val resolvedRevision =
            if (resolveDifferentTargetIds) {
                StudentProblemRevisionRef(
                    problem = resolvedProblem,
                    revisionId = "resolved-revision-${command.source.draftId}",
                    revisionNumber = proposedRevision.revisionNumber,
                    documentCanonicalFingerprint =
                        proposedRevision.documentCanonicalFingerprint,
                )
            } else {
                proposedRevision
            }
        val created =
            StudentCaptureSaveHandoffRecord(
                source = command.source,
                learnerId = resolvedProblem.learnerId,
                targetProblem = resolvedProblem,
                targetRevision = resolvedRevision,
                errorBookEntryId =
                    if (resolveDifferentTargetIds) {
                        "resolved-error-${command.source.draftId}"
                    } else {
                        checkNotNull(target.problem.errorBookEntryId)
                    },
                targetCanonicalFingerprint =
                    if (resolveDifferentTargetIds) {
                        "c".repeat(64)
                    } else {
                        target.targetCanonicalFingerprint
                    },
                acknowledgedAtEpochMillis = null,
            )
        records[command.source.intentId] = created
        pending[command.source.intentId] = created
        return prepared.toReceipt(
            handoff = created,
            outcome = TargetConfirmedStudentMistakeSaveOutcome.CREATED,
        )
    }

    override suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery,
    ): List<StudentCaptureSaveHandoffRecord> {
        val afterOccurredAtEpochMillis = query.afterOccurredAtEpochMillis
        val afterIntentId = query.afterIntentId
        return pending.values
            .sortedWith(
                compareBy<StudentCaptureSaveHandoffRecord>(
                    { it.source.occurredAtEpochMillis },
                    { it.source.intentId },
                ),
            )
            .asSequence()
            .filter { handoff ->
                afterOccurredAtEpochMillis == null ||
                    handoff.source.occurredAtEpochMillis >
                    afterOccurredAtEpochMillis ||
                    (
                        handoff.source.occurredAtEpochMillis ==
                            afterOccurredAtEpochMillis &&
                            handoff.source.intentId > checkNotNull(afterIntentId)
                    )
            }
            .take(query.limit)
            .toList()
    }

    override suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord> =
        records.values.filter { it.source.draftId in draftIds }

    override suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord? =
        records.values.singleOrNull { it.source.sessionId == sessionId }

    override suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord {
        val current = checkNotNull(records[command.intentId])
        check(current.source.sourceCanonicalFingerprint == command.sourceCanonicalFingerprint)
        check(current.targetCanonicalFingerprint == command.targetCanonicalFingerprint)
        val acknowledged =
            current.copy(
                acknowledgedAtEpochMillis = command.acknowledgedAtEpochMillis,
            )
        val returned =
            if (mismatchAcknowledgement) {
                val mismatchedProblem =
                    acknowledged.targetProblem.copy(problemId = "mismatched-problem")
                acknowledged.copy(
                    targetProblem = mismatchedProblem,
                    targetRevision =
                        acknowledged.targetRevision.copy(problem = mismatchedProblem),
                )
            } else {
                acknowledged
            }
        records[command.intentId] = returned
        pending.remove(command.intentId)
        return returned
    }
}

private fun PreparedStudentOwnedCaptureCommit.toReceipt(
    handoff: StudentCaptureSaveHandoffRecord,
    outcome: TargetConfirmedStudentMistakeSaveOutcome,
): ProductionCaptureOccurrenceCommitReceipt {
    val source = command.source
    val occurrenceCommand =
        AppendStudentProblemErrorOccurrenceCommand(
            occurrenceId = "occurrence-${source.intentId}",
            idempotencyKey = source.intentId,
            problemRevision = handoff.targetRevision,
            batchCanonicalFingerprint = "7".repeat(64),
            importSourceCanonicalFingerprint = "8".repeat(64),
            occurredAtEpochMillis = source.occurredAtEpochMillis,
            importedAtEpochMillis = source.occurredAtEpochMillis,
            attributionStatus = ProblemErrorAttributionResolutionStatus.UNRESOLVED,
            evidenceRefs = emptyList(),
        )
    val occurrence =
        StudentProblemErrorOccurrence(
            ref = occurrenceCommand.ref,
            idempotencyKey = occurrenceCommand.idempotencyKey,
            batchCanonicalFingerprint = occurrenceCommand.batchCanonicalFingerprint,
            importSourceCanonicalFingerprint =
                occurrenceCommand.importSourceCanonicalFingerprint,
            occurredAtEpochMillis = occurrenceCommand.occurredAtEpochMillis,
            importedAtEpochMillis = occurrenceCommand.importedAtEpochMillis,
            attributionStatus = occurrenceCommand.attributionStatus,
            evidenceRefs = occurrenceCommand.evidenceRefs,
        )
    return ProductionCaptureOccurrenceCommitReceipt(
        transactionId = "transaction-${source.intentId}",
        transactionCanonicalFingerprint = "9".repeat(64),
        assetManifestCanonicalFingerprint = "a".repeat(64),
        selectedRegionCanonicalFingerprint = "b".repeat(64),
        resolvedIdentity = testCanonicalIdentity(source.intentId),
        save = StudentOwnedCaptureSaveReceipt(outcome = outcome, handoff = handoff),
        occurrence = occurrence,
    )
}

private fun testCanonicalIdentity(stableKey: String): StudentProblemCanonicalIdentityKey {
    val constructor =
        StudentProblemCanonicalIdentityKey::class.java.declaredConstructors.single {
            it.parameterCount == 3
        }
    constructor.isAccessible = true
    return constructor.newInstance(
        "test-student-owner",
        "v1",
        stableKey,
    ) as StudentProblemCanonicalIdentityKey
}

internal fun preparedCapture(
    source: LibraryStudentCaptureSaveSource,
    learnerId: String,
    imageCount: Int,
): PreparedStudentOwnedCaptureCommit {
    val problemId = "problem-${source.draftId}"
    val revisionId = "revision-${source.draftId}"
    val practiceUnitId = "practice-${source.draftId}"
    val entryId = "entry-${source.draftId}"
    val problem =
        StudentProblemRef(
            learnerId = learnerId,
            subject = SubjectKind.MATH,
            problemId = problemId,
            practiceUnitId = practiceUnitId,
        )
    val capturedDocument = confirmedDocument(source.draftId)
    val revision =
        StudentProblemRevisionRef(
            problem = problem,
            revisionId = revisionId,
            revisionNumber = 1,
            documentCanonicalFingerprint =
                CapturedQuestionDocumentFingerprint.of(capturedDocument),
        )
    val images =
        List(imageCount) { index ->
            StudentProblemImageReference(
                imageReferenceId = "image-${source.draftId}-$index",
                localContentUri = "content://student-capture/${source.draftId}/$index",
                contentCanonicalFingerprint = "d".repeat(64),
                mediaType = "image/png",
                ordinal = index,
                widthPixels = 1_080,
                heightPixels = 1_440,
                byteSize = 64_000L + index,
            )
        }
    return PreparedStudentOwnedCaptureCommit(
        command =
            SaveStudentOwnedCaptureCommand(
                source = source,
                target =
                    SaveTargetConfirmedStudentMistakeCommand(
                        problem =
                            CommitStudentProblemCommand(
                                revision = revision,
                                title = capturedDocument.document.title,
                                stemMarkdown =
                                    QuestionDocumentMarkdownProjection.project(
                                        capturedDocument.document,
                                    ),
                                practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
                                practiceUnitTitle = "函数",
                                itemFamilyId = problemId,
                                estimatedDurationSeconds = 180,
                                sourceBundleId = null,
                                partIds = emptyList(),
                                originalImages = images,
                                committedAtEpochMillis = source.occurredAtEpochMillis,
                                errorBookEntryId = entryId,
                                capturedQuestionDocument = capturedDocument,
                            ),
                        confirmedAtEpochMillis = source.occurredAtEpochMillis,
                    ),
            ),
        summary =
            com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary(
                draftId = source.draftId,
                problemId = problemId,
                problemRevisionId = revisionId,
                practiceUnitId = practiceUnitId,
                errorBookEntryId = entryId,
                created = true,
            ),
    )
}

private fun confirmedDocument(draftId: String): CapturedQuestionDocument =
    CapturedQuestionDocument(
        document =
            QuestionDocument(
                id = "document-$draftId",
                title = "待讲解题目",
                blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = "求函数值。")),
            ),
        blockEvidence =
            listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "image-$draftId-0",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
    )

private fun sourceFingerprint(draftId: String): String =
    if (draftId.endsWith("b")) "a".repeat(64) else "e".repeat(64)
