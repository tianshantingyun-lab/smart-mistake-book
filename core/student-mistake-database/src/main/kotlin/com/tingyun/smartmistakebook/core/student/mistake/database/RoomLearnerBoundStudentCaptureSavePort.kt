package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

internal class RoomLearnerBoundStudentCaptureSavePort(
    private val store: RoomStudentMistakeStore,
    private val dao: StudentMistakeDao,
    override val learnerId: String,
) : LearnerBoundStudentCaptureSavePort {
    init {
        require(learnerId.isNotBlank()) { "Capture-save learner id must not be blank" }
    }

    override suspend fun save(
        command: SaveStudentOwnedCaptureCommand,
    ): StudentOwnedCaptureSaveReceipt {
        val target = command.target
        val problem = target.problem
        require(problem.revision.problem.learnerId == learnerId) {
            "Student capture save belongs to another learner"
        }
        val handoff = command.source.toEntity(target, learnerId)
        val saved =
            dao.saveStudentOwnedCapture(
                SaveStudentOwnedCaptureBundle(
                    confirmedMistake = store.prepareTargetConfirmedMistakeBundle(target),
                    handoff = handoff,
                ),
            )
        return StudentOwnedCaptureSaveReceipt(
            outcome = saved.outcome,
            handoff = saved.handoff.toRecord(),
        )
    }

    override suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery,
    ): List<StudentCaptureSaveHandoffRecord> =
        dao.readPendingCaptureSaveHandoffs(
            learnerId = learnerId,
            afterOccurredAtEpochMillis = query.afterOccurredAtEpochMillis,
            afterIntentId = query.afterIntentId,
            limit = query.limit,
        ).map(StudentCaptureSaveHandoffEntity::toRecord)

    override suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord> {
        require(draftIds.size <= MAX_CAPTURE_SAVE_LOOKUP_IDS) {
            "Capture-save draft lookup is too broad"
        }
        require(draftIds.none(String::isBlank)) {
            "Capture-save draft ids must not be blank"
        }
        if (draftIds.isEmpty()) return emptyList()
        return dao.readCaptureSaveHandoffsByDraftIds(
            learnerId = learnerId,
            draftIds = draftIds.sorted(),
        ).map(StudentCaptureSaveHandoffEntity::toRecord)
    }

    override suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord? {
        require(sessionId.isNotBlank()) { "Capture-save session id must not be blank" }
        return dao.readCaptureSaveHandoffBySessionId(
            learnerId = learnerId,
            sessionId = sessionId,
        )?.toRecord()
    }

    override suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord =
        dao.acknowledgeStudentCaptureSaveHandoff(
            learnerId = learnerId,
            command = command,
        ).toRecord()
}

private fun StudentCaptureSaveSource.toEntity(
    target: SaveTargetConfirmedStudentMistakeCommand,
    learnerId: String,
): StudentCaptureSaveHandoffEntity {
    val revision = target.problem.revision
    val library = this as? LibraryStudentCaptureSaveSource
    val tutor = this as? TutorStudentCaptureSaveSource
    return StudentCaptureSaveHandoffEntity(
        intentId = intentId,
        sourceKind = kind.name,
        sourceCanonicalFingerprint = sourceCanonicalFingerprint,
        learnerId = learnerId,
        draftId = draftId,
        draftRevisionNumber = draftRevisionNumber,
        sessionId = sessionId,
        basisRevisionNumber = library?.basisRevisionNumber,
        workspaceVersion = library?.workspaceVersion,
        workspaceCanonicalFingerprint = library?.workspaceCanonicalFingerprint,
        confirmationRequestId = library?.confirmationRequestId,
        saveRequestId = tutor?.saveRequestId,
        targetSubject = revision.problem.subject.name,
        targetProblemId = revision.problem.problemId,
        targetPracticeUnitId = revision.problem.practiceUnitId,
        targetRevisionId = revision.revisionId,
        targetRevisionNumber = revision.revisionNumber,
        targetDocumentCanonicalFingerprint = revision.documentCanonicalFingerprint,
        errorBookEntryId = checkNotNull(target.problem.errorBookEntryId),
        targetCanonicalFingerprint = target.targetCanonicalFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
        acknowledgedAtEpochMillis = null,
        schemaVersion = STUDENT_CAPTURE_SAVE_HANDOFF_SCHEMA_VERSION,
    )
}

private fun StudentCaptureSaveHandoffEntity.toRecord(): StudentCaptureSaveHandoffRecord {
    check(schemaVersion == STUDENT_CAPTURE_SAVE_HANDOFF_SCHEMA_VERSION) {
        "Unsupported student capture-save handoff schema version"
    }
    val kind =
        runCatching { StudentCaptureSaveSourceKind.valueOf(sourceKind) }
            .getOrElse { throw IllegalStateException("Corrupt student capture source kind", it) }
    val source =
        when (kind) {
            StudentCaptureSaveSourceKind.LIBRARY_CONFIRMATION ->
                LibraryStudentCaptureSaveSource(
                    intentId = intentId,
                    sourceCanonicalFingerprint = sourceCanonicalFingerprint,
                    draftId = draftId,
                    basisRevisionNumber =
                        checkNotNull(basisRevisionNumber) {
                            "Corrupt library capture: missing basis revision"
                        },
                    workspaceVersion =
                        checkNotNull(workspaceVersion) {
                            "Corrupt library capture: missing workspace version"
                        },
                    workspaceCanonicalFingerprint =
                        checkNotNull(workspaceCanonicalFingerprint) {
                            "Corrupt library capture: missing workspace fingerprint"
                        },
                    confirmationRequestId =
                        checkNotNull(confirmationRequestId) {
                            "Corrupt library capture: missing confirmation request"
                        },
                    occurredAtEpochMillis = occurredAtEpochMillis,
                ).also {
                    check(
                        sessionId == null &&
                            saveRequestId == null &&
                            draftRevisionNumber == it.draftRevisionNumber,
                    ) {
                        "Corrupt library capture source shape"
                    }
                }

            StudentCaptureSaveSourceKind.TUTOR_SESSION ->
                TutorStudentCaptureSaveSource(
                    intentId = intentId,
                    sourceCanonicalFingerprint = sourceCanonicalFingerprint,
                    saveRequestId =
                        checkNotNull(saveRequestId) {
                            "Corrupt tutor capture: missing save request"
                        },
                    sessionId =
                        checkNotNull(sessionId) {
                            "Corrupt tutor capture: missing session id"
                        },
                    draftId = draftId,
                    draftRevisionNumber = draftRevisionNumber,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                ).also {
                    check(
                        basisRevisionNumber == null &&
                            workspaceVersion == null &&
                            workspaceCanonicalFingerprint == null &&
                            confirmationRequestId == null,
                    ) {
                        "Corrupt tutor capture source shape"
                    }
                }
        }
    val subject =
        runCatching { SubjectKind.valueOf(targetSubject) }
            .getOrElse { throw IllegalStateException("Corrupt capture target subject", it) }
    val problem =
        StudentProblemRef(
            learnerId = learnerId,
            subject = subject,
            problemId = targetProblemId,
            practiceUnitId = targetPracticeUnitId,
        )
    return StudentCaptureSaveHandoffRecord(
        source = source,
        learnerId = learnerId,
        targetProblem = problem,
        targetRevision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = targetRevisionId,
                revisionNumber = targetRevisionNumber,
                documentCanonicalFingerprint = targetDocumentCanonicalFingerprint,
            ),
        errorBookEntryId = errorBookEntryId,
        targetCanonicalFingerprint = targetCanonicalFingerprint,
        acknowledgedAtEpochMillis = acknowledgedAtEpochMillis,
    )
}

private const val MAX_CAPTURE_SAVE_LOOKUP_IDS = 128
