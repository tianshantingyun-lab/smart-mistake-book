package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPreparedQuestion
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionSavedMistakeReference
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.util.concurrent.ConcurrentHashMap

internal fun interface CurrentTutorSavedMistakeQuestionPreparer {
    suspend fun prepare(
        reference: TutorCurrentSessionSavedMistakeReference,
    ): TutorCurrentSessionQuestionPreparationResult
}

/**
 * Host-owned bridge from an immutable saved-mistake locator to a confirmed tutor question.
 *
 * Only locators are retained in memory. Every read goes back through [MistakeDetailRepository] and
 * rechecks the full identity, so neither Compose state nor an earlier snapshot becomes question
 * authority. After process recreation the route can submit the same locator and rebuild the exact
 * deterministic session without copying question content into another store.
 */
internal class CurrentTutorSavedMistakeQuestionRegistry(
    private val mistakes: MistakeDetailRepository,
) : CurrentTutorSavedMistakeQuestionPreparer, CurrentTutorQuestionSource {
    private val references = ConcurrentHashMap<String, TutorCurrentSessionSavedMistakeReference>()

    override suspend fun prepare(
        reference: TutorCurrentSessionSavedMistakeReference,
    ): TutorCurrentSessionQuestionPreparationResult {
        val ready = mistakes.readExact(reference.toRevisionKey()) as? MistakeDetailState.Ready
            ?: return TutorCurrentSessionQuestionPreparationResult.Unavailable(
                TutorCurrentSessionQuestionPreparationBlocker.NOT_FOUND,
            )
        if (!ready.matches(reference)) {
            return TutorCurrentSessionQuestionPreparationResult.Unavailable(
                TutorCurrentSessionQuestionPreparationBlocker.REVISION_MISMATCH,
            )
        }
        val sessionId = reference.stableTutorSessionId()
        val existing = references.putIfAbsent(sessionId, reference)
        if (existing != null && existing != reference) {
            return TutorCurrentSessionQuestionPreparationResult.Unavailable(
                TutorCurrentSessionQuestionPreparationBlocker.REVISION_MISMATCH,
            )
        }
        return TutorCurrentSessionQuestionPreparationResult.Ready(
            TutorCurrentSessionPreparedQuestion(
                sessionId = sessionId,
                questionRevisionNumber = ready.detail.identity.revisionNumber,
            ),
        )
    }

    override suspend fun read(sessionId: String): ConfirmedTutorSession? {
        val reference = references[sessionId] ?: return null
        if (reference.stableTutorSessionId() != sessionId) return null
        val ready = mistakes.readExact(reference.toRevisionKey()) as? MistakeDetailState.Ready
            ?: return null
        if (!ready.matches(reference)) return null
        val identity = ready.detail.identity
        val source = ready.detail.source
        return ConfirmedTutorSession(
            sessionId = sessionId,
            draftId = "saved-mistake:" + CanonicalSha256("saved-mistake-draft-v1")
                .field("sessionId", sessionId)
                .finish(),
            draftRevisionNumber = identity.revisionNumber,
            subject = identity.subject,
            title = identity.title,
            questionDocument = ready.questionDocument,
            sourceImageUri = source.firstAvailableUri()
                ?: "mistake://source-unavailable/$sessionId",
            createdAtEpochMillis = source.earliestCreatedAtEpochMillis(),
            isSaved = true,
            isEndedWithoutSave = false,
            errorBookEntryId = identity.errorBookEntryId,
            savedProblemRevisionId = identity.problemRevisionId,
        )
    }
}

private fun TutorCurrentSessionSavedMistakeReference.toRevisionKey(): MistakeRevisionKey =
    MistakeRevisionKey(
        entryId = errorBookEntryId,
        problemId = problemId,
        problemRevisionId = problemRevisionId,
    )

private fun TutorCurrentSessionSavedMistakeReference.stableTutorSessionId(): String =
    "mistake-tutor:" + CanonicalSha256("saved-mistake-tutor-session-v1")
        .field("errorBookEntryId", errorBookEntryId)
        .field("problemId", problemId)
        .field("problemRevisionId", problemRevisionId)
        .field("revisionNumber", expectedRevisionNumber)
        .finish()

private fun MistakeDetailState.Ready.matches(
    reference: TutorCurrentSessionSavedMistakeReference,
): Boolean = detail.identity.let { identity ->
    identity.errorBookEntryId == reference.errorBookEntryId &&
        identity.problemId == reference.problemId &&
        identity.problemRevisionId == reference.problemRevisionId &&
        identity.revisionNumber == reference.expectedRevisionNumber
}

private fun MistakeSourceSet.firstAvailableUri(): String? =
    (this as? MistakeSourceSet.Present)?.assets?.firstNotNullOfOrNull { asset ->
        (asset.location as? MistakeSourceLocation.Available)?.localUri
    }

private fun MistakeSourceSet.earliestCreatedAtEpochMillis(): Long =
    (this as? MistakeSourceSet.Present)?.assets
        ?.minOfOrNull { asset -> asset.createdAtEpochMillis }
        ?: 0L
