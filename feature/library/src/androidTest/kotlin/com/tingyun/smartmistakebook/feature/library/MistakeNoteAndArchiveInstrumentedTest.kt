package com.tingyun.smartmistakebook.feature.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionSummary
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks the post-intake controls on the mistake detail screen: the note card
 * (add/edit/save), the archive action (with confirmation), and the restore
 * banner shown once an entry is archived.
 */
@RunWith(AndroidJUnit4::class)
class MistakeNoteAndArchiveInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun userCanAddANoteAndArchive() {
        val repository = FakeMistakeDetailRepository()
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(archived = false),
                    onBack = {},
                    onExport = {},
                    repository = repository,
                )
            }
        }

        // Note card is present on the current revision.
        composeRule.onNodeWithTag("mistake_detail_note").performScrollTo().assertExists()

        // Add a note and save it.
        composeRule.onNodeWithText("添加").performClick()
        composeRule.onNodeWithTag("mistake_detail_note_input").performTextInput("粗心：符号看错")
        composeRule.onNodeWithTag("mistake_detail_note_save").performClick()
        composeRule.waitForIdle()
        assertEquals("粗心：符号看错", repository.savedNote)

        // Archive with confirmation.
        composeRule.onNodeWithTag("mistake_detail_archive").performScrollTo().performClick()
        composeRule.onNodeWithText("移出").performClick()
        composeRule.waitForIdle()
        assertTrue(repository.archivedCallCount > 0)
    }

    @Test
    fun archivedDetailShowsRestoreBannerAndRestores() {
        val repository = FakeMistakeDetailRepository()
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(archived = true),
                    onBack = {},
                    onExport = {},
                    repository = repository,
                )
            }
        }
        composeRule.onNodeWithTag("mistake_detail_archived_banner").performScrollTo().assertExists()
        composeRule.onNodeWithTag("mistake_detail_restore").performClick()
        composeRule.waitForIdle()
        assertTrue(repository.restoreCallCount > 0)
    }

    private fun readyState(archived: Boolean) = MistakeDetailState.Ready(
        detail = MistakeDetail(
            identity = MistakeDetailIdentity(
                errorBookEntryId = "entry-1",
                problemId = "problem-1",
                problemRevisionId = "revision-1",
                revisionNumber = 1,
                title = "函数最值",
                subject = "MATH",
            ),
            fallbackMarkdown = "求函数最值。",
            source = MistakeSourceSet.Missing,
            archived = archived,
        ),
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "question",
                blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = "求函数最值。")),
            ),
            blockEvidence = emptyList(),
        ),
    )

    private class FakeMistakeDetailRepository : MistakeDetailRepository {
        var savedNote: String? = null
        var archivedCallCount = 0
        var restoreCallCount = 0

        override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> =
            flowOf(
                MistakeDetailState.Ready(
                    detail = MistakeDetail(
                        identity = MistakeDetailIdentity(
                            errorBookEntryId = "entry-1",
                            problemId = "problem-1",
                            problemRevisionId = "revision-1",
                            revisionNumber = 1,
                            title = "函数最值",
                            subject = "MATH",
                        ),
                        fallbackMarkdown = "求函数最值。",
                        source = MistakeSourceSet.Missing,
                    ),
                    questionDocument = CapturedQuestionDocument(
                        document = QuestionDocument(
                            id = "question",
                            blocks = listOf(ContentBlock.Paragraph(id = "stem", markdown = "求函数最值。")),
                        ),
                        blockEvidence = emptyList(),
                    ),
                ),
            )

        override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> =
            flowOf(MistakeDetailState.NotFound)

        override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState =
            MistakeDetailState.NotFound

        override suspend fun updateUserNote(
            entryId: String,
            note: String?,
            updatedAtEpochMillis: Long,
        ): Boolean {
            savedNote = note
            return true
        }

        override suspend fun archiveEntry(entryId: String, at: Long): Boolean {
            archivedCallCount += 1
            return true
        }

        override suspend fun restoreEntry(entryId: String, at: Long): Boolean {
            restoreCallCount += 1
            return true
        }

        override fun observeRevisionHistory(
            errorBookEntryId: String,
        ): Flow<List<MistakeRevisionSummary>> = flowOf(emptyList())
    }

    private companion object {
        val KEY = MistakeRevisionKey(
            entryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "revision-1",
        )
    }
}
