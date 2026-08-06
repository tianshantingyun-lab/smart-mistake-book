package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.model.CaptureDraftEditedField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAutoCommitPolicyTest {
    private fun commit(
        draftId: String? = "draft-1",
        candidateUsable: Boolean = true,
        workflowInProgress: Boolean = false,
        workspaceSaving: Boolean = false,
        committedEntryId: String? = null,
        commitOutcomeUnknown: Boolean = false,
        autoCommittedDraftId: String? = null,
        modelStructuredCandidate: Boolean = true,
        userEditedFields: Set<CaptureDraftEditedField> = emptySet(),
        transcriptionEditedByUser: Boolean = false,
        titleEditedByUser: Boolean = false,
    ) =
        shouldAutoCommitCaptureCandidate(
            draftId = draftId,
            candidateUsable = candidateUsable,
            workflowInProgress = workflowInProgress,
            workspaceSaving = workspaceSaving,
            committedEntryId = committedEntryId,
            commitOutcomeUnknown = commitOutcomeUnknown,
            autoCommittedDraftId = autoCommittedDraftId,
            modelStructuredCandidate = modelStructuredCandidate,
            userEditedFields = userEditedFields,
            transcriptionEditedByUser = transcriptionEditedByUser,
            titleEditedByUser = titleEditedByUser,
        )

    @Test
    fun modelCandidateWithoutUserEditsAutoCommits() {
        assertTrue(commit())
    }

    @Test
    fun localTransitionalCandidateDoesNotAutoCommit() {
        assertFalse(commit(modelStructuredCandidate = false))
    }

    @Test
    fun editedFieldsBlockAutoCommit() {
        assertFalse(commit(userEditedFields = setOf(CaptureDraftEditedField.TRANSCRIPTION)))
    }

    @Test
    fun userTranscriptionEditBlocksAutoCommit() {
        assertFalse(commit(transcriptionEditedByUser = true))
    }

    @Test
    fun userTitleEditBlocksAutoCommit() {
        assertFalse(commit(titleEditedByUser = true))
    }

    @Test
    fun committedEntryBlocksAutoCommit() {
        assertFalse(commit(committedEntryId = "entry-1"))
    }

    @Test
    fun workflowOrSavingBlocksAutoCommit() {
        assertFalse(commit(workflowInProgress = true))
        assertFalse(commit(workspaceSaving = true))
    }

    @Test
    fun unknownOutcomeBlocksAutoCommit() {
        assertFalse(commit(commitOutcomeUnknown = true))
    }

    @Test
    fun sameDraftIsAutoCommittedOnlyOnce() {
        assertFalse(commit(autoCommittedDraftId = "draft-1"))
    }

    @Test
    fun nextDraftCanAutoCommitAfterPreviousOne() {
        assertTrue(commit(draftId = "draft-2", autoCommittedDraftId = "draft-1"))
    }
}
