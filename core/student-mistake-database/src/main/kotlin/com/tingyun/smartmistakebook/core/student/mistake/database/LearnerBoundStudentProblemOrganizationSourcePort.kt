package com.tingyun.smartmistakebook.core.student.mistake.database

/**
 * Exact student-owned source for one committed capture draft.
 *
 * This is a read-only authority surface. It exposes neither Room/DAO/SQL nor an occurrence append
 * operation, and it never accepts a learner id from its caller.
 */
data class StudentProblemOrganizationSourceSnapshot(
    val source: StudentCaptureSaveSource,
    val problem: StudentProblemDocument,
    val errorOccurrences: List<StudentProblemErrorOccurrenceRef>,
) {
    init {
        require(source.draftId.isNotBlank()) { "Organization source draft id is missing" }
        require(problem.revision.problem.learnerId.isNotBlank()) {
            "Organization source learner is missing"
        }
        require(problem.errorBookEntryId != null) {
            "Organization source is not a saved mistake"
        }
        require(problem.lifecycleState == StudentProblemLifecycleState.ACTIVE) {
            "Organization source problem is no longer active"
        }
        require(problem.mistakeState == StudentMistakeEntryState.ACTIVE) {
            "Organization source mistake is no longer active"
        }
        require(problem.capturedQuestionDocument != null) {
            "Organization source lacks an exact captured document"
        }
        require(errorOccurrences.isNotEmpty()) {
            "Organization source lacks a recorded error occurrence"
        }
        require(
            errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId) ==
                errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId).sorted() &&
                errorOccurrences.map(StudentProblemErrorOccurrenceRef::occurrenceId).distinct().size ==
                errorOccurrences.size &&
                errorOccurrences.all { occurrence ->
                    occurrence.problemRevision == problem.revision
                },
        ) {
            "Organization source occurrences do not match the exact student revision"
        }
    }
}

interface LearnerBoundStudentProblemOrganizationSourcePort {
    val learnerId: String

    suspend fun readByDraftId(draftId: String): StudentProblemOrganizationSourceSnapshot?
}

internal class StudentProblemOrganizationSourceOwner(
    override val learnerId: String,
    private val store: StudentMistakeStore,
    private val captureHandoffs: LearnerBoundStudentCaptureHandoffPort,
    private val errorOccurrences: StudentProblemErrorOccurrencePort,
) : LearnerBoundStudentProblemOrganizationSourcePort {
    init {
        require(learnerId.isNotBlank() && learnerId == captureHandoffs.learnerId) {
            "Organization source capabilities must share one learner"
        }
        require(learnerId == errorOccurrences.learnerId) {
            "Organization occurrence reader belongs to another learner"
        }
    }

    override suspend fun readByDraftId(
        draftId: String,
    ): StudentProblemOrganizationSourceSnapshot? {
        require(
            draftId.isNotBlank() &&
                draftId == draftId.trim() &&
                draftId.length <= 256 &&
                draftId.none(Char::isISOControl),
        ) {
            "Organization source draft id is invalid"
        }
        val handoffs = captureHandoffs.readByDraftIds(setOf(draftId))
        check(handoffs.size <= 1) {
            "One capture draft resolved to multiple student-owned problems"
        }
        val handoff = handoffs.singleOrNull() ?: return null
        check(
            handoff.learnerId == learnerId &&
                handoff.source.draftId == draftId &&
                handoff.targetProblem.learnerId == learnerId &&
                handoff.targetRevision.problem == handoff.targetProblem,
        ) {
            "Capture handoff escaped the learner-bound organization source"
        }
        val problem = store.findProblem(handoff.targetProblem) ?: return null
        if (
            problem.revision != handoff.targetRevision ||
            problem.errorBookEntryId != handoff.errorBookEntryId
        ) {
            return null
        }
        val occurrences =
            errorOccurrences
                .readHistory(problem.revision, MAX_SOURCE_ERROR_OCCURRENCES)
                .map(StudentProblemErrorOccurrence::ref)
                .sortedBy(StudentProblemErrorOccurrenceRef::occurrenceId)
        if (occurrences.isEmpty()) return null
        return StudentProblemOrganizationSourceSnapshot(
            source = handoff.source,
            problem = problem,
            errorOccurrences = occurrences,
        )
    }
}

private const val MAX_SOURCE_ERROR_OCCURRENCES = 100
