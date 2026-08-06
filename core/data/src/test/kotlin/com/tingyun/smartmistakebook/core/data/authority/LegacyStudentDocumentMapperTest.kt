package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailSourceAssetRecord
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentPracticeUnitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStudentDocumentMapperTest {
    @Test
    fun migrationAndLiveMirrorShareOneExactDocumentMapping() {
        val source = sourceRecord()
        val mapper =
            LegacyStudentDocumentMapper(
                learnerId = LEARNER_ID,
                assetUriResolver = LegacyStudentAssetUriResolver { "file:///vault/source-1.jpg" },
            )

        val mapped = mapper.map(source)

        assertEquals(LEARNER_ID, mapped.problem.revision.problem.learnerId)
        assertEquals(SubjectKind.MATH, mapped.problem.revision.problem.subject)
        assertEquals(source.problemId, mapped.problem.revision.problem.problemId)
        assertEquals(source.practiceUnitId, mapped.problem.revision.problem.practiceUnitId)
        assertEquals(source.revisionId, mapped.problem.revision.revisionId)
        assertEquals(source.revisionNumber, mapped.problem.revision.revisionNumber)
        assertEquals(source.contentFingerprint, mapped.problem.revision.documentCanonicalFingerprint)
        assertEquals(StudentPracticeUnitKind.WHOLE_PROBLEM, mapped.problem.practiceUnitKind)
        assertEquals(source.entryId, mapped.problem.errorBookEntryId)
        assertEquals(source.committedAtEpochMillis, mapped.problem.committedAtEpochMillis)
        assertEquals(StudentMistakeEntryState.ACTIVE, mapped.collection.mistakeState)
        assertEquals("file:///vault/source-1.jpg", mapped.problem.originalImages.single().localContentUri)
        assertEquals(null, mapped.problem.sourceBundleId)
        val semantics = checkNotNull(mapped.importSemanticSnapshot)
        assertEquals(source.problemCanonicalFingerprint, semantics.problemCanonicalFingerprint)
        assertEquals(source.revisionSourceType, semantics.revisionSourceType)
        assertEquals(source.revisionSourceReference, semantics.revisionSourceReference)
        assertEquals(source.answerSpecId, semantics.answerSpecId)
        assertEquals(source.answerSpecSnapshot, semantics.answerSpecSnapshot)
        assertEquals(source.answerVerificationStatus, semantics.answerVerificationStatus)
        assertEquals(source.sourceKey, semantics.errorBookSourceKey)
        assertEquals(source.practiceUnitKey, semantics.practiceUnitKey)
        assertEquals(
            source.practiceUnitPromptMarkdown,
            semantics.practiceUnitPromptMarkdown,
        )
    }

    @Test
    fun invalidLegacyDocumentIsRejectedWithItsExactSourceReference() {
        val mapper =
            LegacyStudentDocumentMapper(
                learnerId = LEARNER_ID,
                assetUriResolver = LegacyStudentAssetUriResolver { "file:///vault/source-1.jpg" },
            )

        val failure =
            runCatching {
                mapper.map(sourceRecord().copy(subject = SubjectKind.GENERAL.name))
            }.exceptionOrNull()

        assertTrue(failure is LegacyStudentRecordRejected)
        assertSame(
            LegacyAuthorityPreparationBlockerCode.STUDENT_DOCUMENT_INVALID,
            (failure as LegacyStudentRecordRejected).code,
        )
        assertEquals(REVISION_ID, failure.sourceReferenceId)
    }

    @Test
    fun legacyTimestampsThatCannotBeRepresentedLosslesslyAreRejected() {
        val mapper =
            LegacyStudentDocumentMapper(
                learnerId = LEARNER_ID,
                assetUriResolver = LegacyStudentAssetUriResolver { "file:///vault/source-1.jpg" },
            )

        val failure =
            runCatching {
                mapper.map(
                    sourceRecord().copy(problemCreatedAtEpochMillis = 900L),
                )
            }.exceptionOrNull()

        assertTrue(failure is LegacyStudentRecordRejected)
        assertEquals(
            LegacyAuthorityPreparationBlockerCode.STUDENT_DOCUMENT_INVALID,
            (failure as LegacyStudentRecordRejected).code,
        )
    }

    private fun sourceRecord() =
        LegacyStudentDocumentMigrationRecord(
            entryId = "entry-1",
            problemId = "problem-1",
            problemCanonicalFingerprint = "b".repeat(64),
            revisionId = REVISION_ID,
            revisionNumber = 1,
            subject = SubjectKind.MATH.name,
            problemCreatedAtEpochMillis = 1_000L,
            problemArchivedAtEpochMillis = null,
            title = "Quadratic equation",
            problemMarkdown = "Solve x² - 5x + 6 = 0.",
            questionDocumentSnapshot = null,
            answerSpecId = "answer-spec-1",
            answerSpecSnapshot = """{"kind":"numeric"}""",
            answerVerificationStatus = "VERIFIED",
            revisionSourceType = "CAMERA",
            revisionSourceReference = "file:///vault/source-1.jpg",
            contentFingerprint = "d".repeat(64),
            practiceUnitId = "practice-1",
            practiceUnitKey = "whole-problem",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM.name,
            practiceUnitTitle = "Quadratic equation",
            practiceUnitPromptMarkdown = "Solve x² - 5x + 6 = 0.",
            estimatedSeconds = 180,
            practiceUnitRevisionId = REVISION_ID,
            practiceUnitCreatedAtEpochMillis = 1_000L,
            entryCurrentRevisionId = REVISION_ID,
            sourceKey = "legacy-source-key",
            status = StudentMistakeEntryState.ACTIVE.name,
            acceptedAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 1_000L,
            revisionCreatedAtEpochMillis = 1_000L,
            sourceAssets =
                listOf(
                    MistakeDetailSourceAssetRecord(
                        role = "QUESTION_SOURCE",
                        sourceAsset =
                            CanonicalSourceAssetRecord(
                                sourceAssetId = "source-1",
                                contentSha256 = "c".repeat(64),
                                relativePath = "source-1.jpg",
                                mimeType = "image/jpeg",
                                byteSize = 1_024L,
                                width = 800,
                                height = 600,
                                sourceType = "CAMERA",
                                createdAtEpochMillis = 900L,
                            ),
                    ),
                ),
        )

    private companion object {
        const val LEARNER_ID = "learner:local"
        const val REVISION_ID = "revision-1"
    }
}
