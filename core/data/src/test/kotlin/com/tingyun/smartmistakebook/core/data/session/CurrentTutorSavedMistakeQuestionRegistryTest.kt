package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionSavedMistakeReference
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTutorSavedMistakeQuestionRegistryTest {
    @Test
    fun `prepared saved question is reread from the exact immutable revision`() = runBlocking {
        val exactKey = MistakeRevisionKey(SAVED_ENTRY_ID, SAVED_PROBLEM_ID, SAVED_REVISION_ID)
        val repository = FakeMistakeDetailRepository(savedQuestion(revisionNumber = 3))
        val registry = CurrentTutorSavedMistakeQuestionRegistry(repository)
        val reference = TutorCurrentSessionSavedMistakeReference(
            errorBookEntryId = SAVED_ENTRY_ID,
            problemId = SAVED_PROBLEM_ID,
            problemRevisionId = SAVED_REVISION_ID,
            expectedRevisionNumber = 3,
        )

        val prepared = registry.prepare(reference)
        assertTrue(prepared is TutorCurrentSessionQuestionPreparationResult.Ready)
        val sessionId =
            (prepared as TutorCurrentSessionQuestionPreparationResult.Ready).question.sessionId
        val session = registry.read(sessionId)

        assertEquals(listOf(exactKey, exactKey), repository.readKeys)
        assertEquals(SAVED_REVISION_ID, session?.questionDocument?.document?.id)
        assertEquals(3, session?.draftRevisionNumber)
        assertEquals(SAVED_ENTRY_ID, session?.errorBookEntryId)

        repository.state = savedQuestion(revisionNumber = 4)
        assertNull(registry.read(sessionId))
        assertEquals(3, repository.readKeys.size)
    }

    @Test
    fun `new registry restores the same session only after exact locator is resubmitted`() =
        runBlocking {
            val exactKey = MistakeRevisionKey(SAVED_ENTRY_ID, SAVED_PROBLEM_ID, SAVED_REVISION_ID)
            val repository = FakeMistakeDetailRepository(savedQuestion(revisionNumber = 3))
            val reference = TutorCurrentSessionSavedMistakeReference(
                errorBookEntryId = SAVED_ENTRY_ID,
                problemId = SAVED_PROBLEM_ID,
                problemRevisionId = SAVED_REVISION_ID,
                expectedRevisionNumber = 3,
            )
            val original = CurrentTutorSavedMistakeQuestionRegistry(repository)
            val firstPreparation = original.prepare(reference)
                as TutorCurrentSessionQuestionPreparationResult.Ready
            val firstSession = checkNotNull(original.read(firstPreparation.question.sessionId))

            val restarted = CurrentTutorSavedMistakeQuestionRegistry(repository)
            assertNull(restarted.read(firstPreparation.question.sessionId))
            val restoredPreparation = restarted.prepare(reference)
                as TutorCurrentSessionQuestionPreparationResult.Ready
            val restoredSession = checkNotNull(restarted.read(restoredPreparation.question.sessionId))

            assertEquals(firstPreparation.question.sessionId, restoredPreparation.question.sessionId)
            assertEquals(firstSession, restoredSession)
            assertEquals(SAVED_REVISION_ID, restoredSession.savedProblemRevisionId)
            assertEquals(listOf(exactKey, exactKey, exactKey, exactKey), repository.readKeys)
        }
}

private const val SAVED_ENTRY_ID = "entry-saved-host"
private const val SAVED_PROBLEM_ID = "problem-saved-host"
private const val SAVED_REVISION_ID = "revision-saved-host"

private class FakeMistakeDetailRepository(
    var state: MistakeDetailState,
) : MistakeDetailRepository {
    val readKeys = mutableListOf<MistakeRevisionKey>()

    override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> = flowOf(state)

    override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> = flowOf(state)

    override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState {
        readKeys += key
        return state
    }
}

private fun savedQuestion(revisionNumber: Int): MistakeDetailState.Ready {
    val document = QuestionDocument(
        id = SAVED_REVISION_ID,
        blocks = listOf(ContentBlock.Paragraph("stem", "线框转动时感应电动势如何变化？")),
    )
    val captured = CapturedQuestionDocument(
        document = document,
        blockEvidence = listOf(
            QuestionBlockEvidence(
                blockId = "stem",
                sourceAssetId = "source-saved-host",
                sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                writingLayer = WritingLayer.PRINTED,
                provenance = QuestionBlockProvenance.USER_CORRECTION,
                reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
            ),
        ),
    )
    return MistakeDetailState.Ready(
        detail = MistakeDetail(
            identity = MistakeDetailIdentity(
                errorBookEntryId = SAVED_ENTRY_ID,
                problemId = SAVED_PROBLEM_ID,
                problemRevisionId = SAVED_REVISION_ID,
                revisionNumber = revisionNumber,
                title = "保存的错题",
                subject = SubjectKind.PHYSICS.name,
            ),
            fallbackMarkdown = "线框转动时感应电动势如何变化？",
            source = MistakeSourceSet.Present(
                listOf(
                    MistakeSourceAsset(
                        role = "question",
                        sourceAssetId = "source-saved-host",
                        contentSha256 = "a".repeat(64),
                        mimeType = "image/jpeg",
                        byteSize = 1_024,
                        width = 800,
                        height = 600,
                        sourceType = "camera",
                        createdAtEpochMillis = 10_000,
                        location = MistakeSourceLocation.Available(
                            "content://saved-host/question",
                        ),
                    ),
                ),
            ),
        ),
        questionDocument = captured,
    )
}
