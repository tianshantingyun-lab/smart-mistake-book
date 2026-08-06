package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeAuthorityContractTest {
    @Test
    fun capturedSnapshotMustMatchTheExactCommittedRevision() {
        val document = capturedDocument("one")
        val accepted = command(document = document)
        assertEquals(document, accepted.capturedQuestionDocument)

        assertTrue(
            runCatching {
                accepted.copy(
                    revision =
                        accepted.revision.copy(
                            documentCanonicalFingerprint = "f".repeat(64),
                        ),
                )
            }.isFailure,
        )
    }

    @Test
    fun detailHistoryAndMigrationPagesStayBounded() {
        val ownedProblem = command(capturedDocument("scope")).revision.problem
        assertTrue(
            runCatching {
                StudentMistakeDetailQuery(
                    learnerId = "learner-2",
                    problem = ownedProblem,
                    errorBookEntryId = "entry-1",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                StudentProblemRevisionHistoryQuery(
                    learnerId = "learner-2",
                    problem = ownedProblem,
                    errorBookEntryId = "entry-1",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                StudentProblemRevisionHistoryQuery(
                    learnerId = "learner-1",
                    problem = command(capturedDocument("history")).revision.problem,
                    errorBookEntryId = "entry-1",
                    limit = MAX_PAGE_SIZE + 1,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = "legacy-cutover",
                    sourceDatabaseCanonicalFingerprint = "a".repeat(64),
                    sourcePageCanonicalFingerprint = "b".repeat(64),
                    expectedCheckpointCanonicalFingerprint = null,
                    afterExclusive = null,
                    records =
                        List(MAX_MIGRATION_PAGE_SIZE + 1) { index ->
                            migrationRecord(
                                command(
                                    document = capturedDocument("$index"),
                                    problemSuffix = "$index",
                                    committedAtEpochMillis = index.toLong(),
                                ),
                            )
                        },
                    isLastPage = false,
                    appliedAtEpochMillis = 1,
                )
            }.isFailure,
        )
    }

    @Test
    fun migrationPortIsNarrowAndKeysetOrderIsStrict() {
        assertEquals(
            setOf("applyPage", "readCheckpoint"),
            StudentMistakeMigrationPort::class.java.methods
                .filter { it.declaringClass == StudentMistakeMigrationPort::class.java }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val later =
            migrationRecord(
                command(
                    document = capturedDocument("later"),
                    problemSuffix = "later",
                    committedAtEpochMillis = 2,
                ),
            )
        val earlier =
            migrationRecord(
                command(
                    document = capturedDocument("earlier"),
                    problemSuffix = "earlier",
                    committedAtEpochMillis = 1,
                ),
            )
        assertTrue(
            runCatching {
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = "legacy-cutover",
                    sourceDatabaseCanonicalFingerprint = "a".repeat(64),
                    sourcePageCanonicalFingerprint = "b".repeat(64),
                    expectedCheckpointCanonicalFingerprint = null,
                    afterExclusive = null,
                    records = listOf(later, earlier),
                    isLastPage = true,
                    appliedAtEpochMillis = 3,
                )
            }.isFailure,
        )
    }

    @Test
    fun modelReadPortUsesOwnerBoundLearnerAndBoundedQueryScope() {
        assertEquals(
            setOf("readProblemSummaries"),
            StudentMistakeModelReadPort::class.java.methods
                .filter { it.declaringClass == StudentMistakeModelReadPort::class.java }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            runCatching {
                StudentMistakeModelReadQuery(
                    subject = SubjectKind.GENERAL,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                StudentMistakeModelReadQuery(
                    subject = SubjectKind.MATH,
                    knowledgeNodes =
                        setOf(
                            KnowledgeNodeRef(
                                subject = SubjectKind.PHYSICS,
                                knowledgeNodeId = "physics.motion",
                                taxonomyVersion = "taxonomy-v1",
                                knowledgePackVersion = "pack-v1",
                            ),
                        ),
                )
            }.isFailure,
        )
        assertTrue(
            StudentMistakeModelReadQuery::class.java.declaredFields
                .none { field -> field.name == "learnerId" },
        )
    }

    private fun command(
        document: CapturedQuestionDocument,
        problemSuffix: String = "one",
        committedAtEpochMillis: Long = 1,
    ): CommitStudentProblemCommand {
        val problem =
            StudentProblemRef(
                learnerId = "learner-1",
                subject = SubjectKind.MATH,
                problemId = "problem-$problemSuffix",
                practiceUnitId = "unit-$problemSuffix",
            )
        return CommitStudentProblemCommand(
            revision =
                StudentProblemRevisionRef(
                    problem = problem,
                    revisionId = "revision-$problemSuffix",
                    revisionNumber = 1,
                    documentCanonicalFingerprint =
                        CapturedQuestionDocumentFingerprint.of(document),
                ),
            title = "题目 $problemSuffix",
            stemMarkdown = "求解。",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "题目 $problemSuffix",
            itemFamilyId = "family-$problemSuffix",
            estimatedDurationSeconds = 60,
            sourceBundleId = null,
            partIds = emptyList(),
            originalImages = emptyList(),
            committedAtEpochMillis = committedAtEpochMillis,
            errorBookEntryId = "entry-$problemSuffix",
            capturedQuestionDocument = document,
        )
    }

    private fun migrationRecord(
        command: CommitStudentProblemCommand,
    ): StudentMistakeMigrationRecord =
        StudentMistakeMigrationRecord(
            problem = command,
            collection =
                SetStudentProblemCollectionCommand(
                    problem = command.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = command.committedAtEpochMillis,
                ),
        )

    private fun capturedDocument(suffix: String): CapturedQuestionDocument =
        CapturedQuestionDocument(
            document =
                QuestionDocument(
                    id = "document-$suffix",
                    title = "函数",
                    blocks =
                        listOf(
                            ContentBlock.Paragraph(
                                id = "stem-$suffix",
                                markdown = "求函数值。",
                            ),
                        ),
                ),
            blockEvidence =
                listOf(
                    QuestionBlockEvidence(
                        blockId = "stem-$suffix",
                        sourceAssetId = "asset-$suffix",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
        )
}
