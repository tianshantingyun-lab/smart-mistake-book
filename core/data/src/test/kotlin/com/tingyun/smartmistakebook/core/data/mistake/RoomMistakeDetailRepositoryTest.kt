package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.MistakeRevisionSummaryRecord
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMistakeDetailRepositoryTest {
    @Test
    fun revisionHistoryPreservesImmutableIdentityAndCurrentHead() = runBlocking {
        val repository = RoomMistakeDetailRepository(
            recordReader = MistakeDetailRecordReader { null },
            exactRecordReader = ExactMistakeDetailRecordReader { null },
            revisionHistoryReader = MistakeRevisionHistoryReader {
                listOf(
                    MistakeRevisionSummaryRecord(
                        entryId = ENTRY_ID,
                        problemId = "problem-1",
                        problemRevisionId = "revision-2",
                        revisionNumber = 2,
                        title = "第二版",
                        createdAtEpochMillis = 2_000,
                        isCurrent = true,
                    ),
                    MistakeRevisionSummaryRecord(
                        entryId = ENTRY_ID,
                        problemId = "problem-1",
                        problemRevisionId = "revision-1",
                        revisionNumber = 1,
                        title = "第一版",
                        createdAtEpochMillis = 1_000,
                        isCurrent = false,
                    ),
                )
            },
            assetUriResolver = CanonicalAssetUriResolver {
                "file:/private/source-assets/question.jpg"
            },
        )

        val history = repository.observeRevisionHistory(ENTRY_ID).first()

        assertEquals(listOf(2, 1), history.map { it.revisionNumber })
        assertEquals("revision-2", history.single { it.isCurrent }.problemRevisionId)
        assertEquals(
            MistakeRevisionKey(ENTRY_ID, "problem-1", "revision-1"),
            history.last().toKey(),
        )
    }

    @Test
    fun readyPreservesMultiBlockDocumentAndAvailableSource() = runBlocking {
        val document = committedDocument()
        val repository = repository(
            record = detailRecord(
                snapshot = CapturedQuestionDocumentCodec.encode(document),
                sourceAssets = listOf(sourceRecord()),
                tutorSessionId = "tutor-session-captured",
                tutorQuestionRevisionNumber = 2,
            ),
            resolver = CanonicalAssetUriResolver { "file:/private/source-assets/question.jpg" },
        )

        val states = repository.observe(ENTRY_ID).toList()

        assertSame(MistakeDetailState.Loading, states.first())
        val ready = states.last() as MistakeDetailState.Ready
        assertEquals(document, ready.questionDocument)
        assertEquals(2, ready.questionDocument.document.blocks.size)
        assertEquals("数学", ready.detail.identity.subject)
        assertEquals(3, ready.detail.identity.revisionNumber)
        assertEquals("tutor-session-captured", ready.detail.tutorConversation?.sessionId)
        assertEquals(2, ready.detail.tutorConversation?.questionRevisionNumber)
        val source = (ready.detail.source as MistakeSourceSet.Present).assets.single()
        assertEquals(ASSET_ID, source.sourceAssetId)
        assertEquals(
            "file:/private/source-assets/question.jpg",
            (source.location as MistakeSourceLocation.Available).localUri,
        )
    }

    @Test
    fun legacyUsesMarkdownOnlyWhenStructuredSnapshotIsAbsent() = runBlocking {
        val repository = repository(detailRecord(snapshot = null))

        val legacy = repository.observe(ENTRY_ID).toList().last() as MistakeDetailState.Legacy

        assertEquals("旧题面 Markdown", legacy.detail.fallbackMarkdown)
        assertSame(MistakeSourceSet.Missing, legacy.detail.source)
    }

    @Test
    fun missingCanonicalFileDoesNotHideStructuredQuestion() = runBlocking {
        val document = committedDocument()
        val repository = repository(
            record = detailRecord(
                snapshot = CapturedQuestionDocumentCodec.encode(document),
                sourceAssets = listOf(sourceRecord()),
            ),
            resolver = CanonicalAssetUriResolver { error("Canonical file is missing") },
        )

        val ready = repository.observe(ENTRY_ID).toList().last() as MistakeDetailState.Ready

        assertEquals(document, ready.questionDocument)
        val source = (ready.detail.source as MistakeSourceSet.Present).assets.single()
        assertSame(MistakeSourceLocation.Unavailable, source.location)
    }

    @Test
    fun corruptStructuredSnapshotNeverMasqueradesAsLegacyMarkdown() = runBlocking {
        val repository = repository(detailRecord(snapshot = "{not-valid-json}"))

        val terminal = repository.observe(ENTRY_ID).toList().last()

        assertTrue(terminal is MistakeDetailState.CorruptSnapshot)
    }

    @Test
    fun validButDifferentConfirmedSnapshotFailsClosedForCurrentAndExactReads() = runBlocking {
        val committed = committedDocument()
        val tampered = committedDocument(stemMarkdown = "已知函数如下，但题面已被替换：")
        val repository = repository(
            detailRecord(
                snapshot = CapturedQuestionDocumentCodec.encode(tampered),
                contentFingerprint = CapturedQuestionDocumentFingerprint.of(committed),
            ),
        )
        val key = MistakeRevisionKey(ENTRY_ID, "problem-1", "revision-3")

        assertTrue(
            repository.observe(ENTRY_ID).toList().last() is MistakeDetailState.CorruptSnapshot,
        )
        assertTrue(repository.readExact(key) is MistakeDetailState.CorruptSnapshot)
    }

    @Test
    fun exactReadRemainsPinnedWhenCurrentRevisionChanges() = runBlocking {
        var current = detailRecord(
            snapshot = CapturedQuestionDocumentCodec.encode(committedDocument()),
            revisionId = "revision-1",
            revisionNumber = 1,
        )
        val pinned = current
        val repository = RoomMistakeDetailRepository(
            recordReader = MistakeDetailRecordReader { current },
            exactRecordReader = ExactMistakeDetailRecordReader { key ->
                pinned.takeIf {
                    key == MistakeRevisionKey(ENTRY_ID, "problem-1", "revision-1")
                }
            },
            assetUriResolver = CanonicalAssetUriResolver {
                "file:/private/source-assets/question.jpg"
            },
        )
        val key = MistakeRevisionKey(ENTRY_ID, "problem-1", "revision-1")

        val before = repository.readExact(key)
        current = detailRecord(
            snapshot = CapturedQuestionDocumentCodec.encode(committedDocument()),
            revisionId = "revision-2",
            revisionNumber = 2,
        )
        val after = repository.readExact(key)

        assertEquals(before, after)
        val currentReady = repository.observe(ENTRY_ID).toList().last() as MistakeDetailState.Ready
        assertEquals("revision-2", currentReady.detail.identity.problemRevisionId)
        val pinnedReady = after as MistakeDetailState.Ready
        assertEquals("revision-1", pinnedReady.detail.identity.problemRevisionId)
    }

    @Test
    fun exactReadRejectsCrossProblemKeyAndKeepsCorruptState() = runBlocking {
        val corrupt = detailRecord(
            snapshot = "{not-valid-json}",
            revisionId = "revision-1",
            revisionNumber = 1,
        )
        val expectedKey = MistakeRevisionKey(ENTRY_ID, "problem-1", "revision-1")
        val repository = RoomMistakeDetailRepository(
            recordReader = MistakeDetailRecordReader { corrupt },
            exactRecordReader = ExactMistakeDetailRecordReader { key ->
                corrupt.takeIf { key == expectedKey }
            },
            assetUriResolver = CanonicalAssetUriResolver {
                "file:/private/source-assets/question.jpg"
            },
        )

        assertTrue(repository.readExact(expectedKey) is MistakeDetailState.CorruptSnapshot)
        assertSame(
            MistakeDetailState.NotFound,
            repository.readExact(MistakeRevisionKey(ENTRY_ID, "problem-2", "revision-1")),
        )
    }

    @Test
    fun exactReadKeepsUnavailableCanonicalSourceVisible() = runBlocking {
        val document = committedDocument()
        val repository = repository(
            record = detailRecord(
                snapshot = CapturedQuestionDocumentCodec.encode(document),
                sourceAssets = listOf(sourceRecord()),
                revisionId = "revision-1",
                revisionNumber = 1,
            ),
            resolver = CanonicalAssetUriResolver { error("Canonical file is missing") },
        )

        val ready = repository.readExact(
            MistakeRevisionKey(ENTRY_ID, "problem-1", "revision-1"),
        ) as MistakeDetailState.Ready

        val source = (ready.detail.source as MistakeSourceSet.Present).assets.single()
        assertSame(MistakeSourceLocation.Unavailable, source.location)
    }

    @Test
    fun batchReadUsesOneSnapshotAndRestoresRequestedOrderFailClosed() = runBlocking {
        val snapshot = CapturedQuestionDocumentCodec.encode(committedDocument())
        val first = detailRecord(
            snapshot = snapshot,
            revisionId = "revision-a",
            revisionNumber = 1,
        ).copy(entryId = "entry-a", problemId = "problem-a")
        val second = detailRecord(
            snapshot = snapshot,
            revisionId = "revision-b",
            revisionNumber = 2,
        ).copy(entryId = "entry-b", problemId = "problem-b")
        var batchCalls = 0
        var requestedEntryIds = emptyList<String>()
        val repository = RoomMistakeDetailRepository(
            recordReader = MistakeDetailRecordReader { null },
            exactRecordReader = ExactMistakeDetailRecordReader {
                error("Batch reads must not fall back to one query per entry")
            },
            batchRecordReader = CurrentMistakeDetailBatchRecordReader { entryIds ->
                batchCalls += 1
                requestedEntryIds = entryIds
                listOf(second, first)
            },
            assetUriResolver = CanonicalAssetUriResolver {
                "file:/private/source-assets/question.jpg"
            },
        )
        val keys = listOf(
            MistakeRevisionKey("entry-a", "problem-a", "revision-a"),
            MistakeRevisionKey("entry-b", "problem-b", "revision-b"),
            MistakeRevisionKey("entry-a", "problem-a", "stale-revision"),
        )

        val states = repository.readExact(keys)

        assertEquals(1, batchCalls)
        assertEquals(listOf("entry-a", "entry-b"), requestedEntryIds)
        assertEquals(
            listOf("entry-a", "entry-b"),
            states.filterIsInstance<MistakeDetailState.Ready>()
                .map { state -> state.detail.identity.errorBookEntryId },
        )
        assertSame(MistakeDetailState.NotFound, states.last())
    }

    private fun repository(
        record: MistakeDetailRecord?,
        resolver: CanonicalAssetUriResolver = CanonicalAssetUriResolver {
            "file:/private/source-assets/question.jpg"
        },
    ) = RoomMistakeDetailRepository(
        recordReader = MistakeDetailRecordReader { record },
        exactRecordReader = ExactMistakeDetailRecordReader { key ->
            record?.takeIf {
                it.entryId == key.entryId &&
                    it.problemId == key.problemId &&
                    it.problemRevisionId == key.problemRevisionId
            }
        },
        assetUriResolver = resolver,
    )

    private fun detailRecord(
        snapshot: String?,
        sourceAssets: List<MistakeDetailSourceAssetRecord> = emptyList(),
        revisionId: String = "revision-3",
        revisionNumber: Int = 3,
        contentFingerprint: String = CapturedQuestionDocumentFingerprint.of(committedDocument()),
        tutorSessionId: String? = null,
        tutorQuestionRevisionNumber: Int? = null,
    ) = MistakeDetailRecord(
        entryId = ENTRY_ID,
        problemId = "problem-1",
        problemRevisionId = revisionId,
        revisionNumber = revisionNumber,
        subject = "数学",
        title = "二次函数单调性",
        problemMarkdown = "旧题面 Markdown",
        questionDocumentSnapshot = snapshot,
        contentFingerprint = contentFingerprint,
        sourceAssets = sourceAssets,
        tutorSessionId = tutorSessionId,
        tutorQuestionRevisionNumber = tutorQuestionRevisionNumber,
    )

    private fun sourceRecord() = MistakeDetailSourceAssetRecord(
        role = "QUESTION_SOURCE",
        sourceAsset = CanonicalSourceAssetRecord(
            sourceAssetId = ASSET_ID,
            contentSha256 = "a".repeat(64),
            relativePath = "source-assets/${"a".repeat(64)}.jpg",
            mimeType = "image/jpeg",
            byteSize = 4_096,
            width = 1_200,
            height = 1_600,
            sourceType = "PHOTO_PICKER",
            createdAtEpochMillis = 1_000,
        ),
    )

    private fun committedDocument(stemMarkdown: String = "已知函数如下：") = CapturedQuestionDocument(
        document = QuestionDocument(
            id = "document-draft-1",
            title = "二次函数单调性",
            blocks = listOf(
                ContentBlock.Paragraph("stem", stemMarkdown),
                ContentBlock.Formula(
                    id = "formula",
                    latex = "f(x)=x^2-2x",
                    alternativeText = "f(x) 等于 x 平方减 2x",
                ),
            ),
        ),
        blockEvidence = listOf(
            evidence("stem", WritingLayer.PRINTED),
            evidence("formula", WritingLayer.HANDWRITTEN),
        ),
    )

    private fun evidence(blockId: String, writingLayer: WritingLayer) = QuestionBlockEvidence(
        blockId = blockId,
        sourceAssetId = ASSET_ID,
        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
        writingLayer = writingLayer,
        provenance = QuestionBlockProvenance.USER_CORRECTION,
        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
    )

    private companion object {
        const val ENTRY_ID = "entry-1"
        const val ASSET_ID = "asset-1"
    }
}
