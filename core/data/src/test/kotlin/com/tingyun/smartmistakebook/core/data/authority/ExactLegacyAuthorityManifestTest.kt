package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSnapshot
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationPage
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationRecord
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationPage
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailSourceAssetRecord
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExactLegacyAuthorityManifestTest {
    @Test
    fun overallManifestDoesNotDependOnLegalPageSplitting() = runBlocking {
        val student = listOf(studentRecord(1), studentRecord(2), studentRecord(3))
        val mastery = listOf(masteryRecord(1), masteryRecord(2), masteryRecord(3))

        val singleRecordPages =
            ExactLegacyAuthorityManifestReader(
                source = FakeLegacySource(student, mastery),
                learnerId = LEARNER_ID,
                pageSize = 1,
            ).readStableManifests()
        val threeRecordPages =
            ExactLegacyAuthorityManifestReader(
                source = FakeLegacySource(student, mastery),
                learnerId = LEARNER_ID,
                pageSize = 3,
            ).readStableManifests()

        assertEquals(
            singleRecordPages.studentDocuments.sourceCanonicalFingerprint,
            threeRecordPages.studentDocuments.sourceCanonicalFingerprint,
        )
        assertEquals(
            singleRecordPages.masteryFacts.sourceCanonicalFingerprint,
            threeRecordPages.masteryFacts.sourceCanonicalFingerprint,
        )
        assertEquals(
            singleRecordPages.studentDocuments.sourceCheckpoint,
            threeRecordPages.studentDocuments.sourceCheckpoint,
        )
        assertEquals(
            singleRecordPages.masteryFacts.sourceCheckpoint,
            threeRecordPages.masteryFacts.sourceCheckpoint,
        )
        assertEquals(
            singleRecordPages.studentDocuments.terminalBoundaryCanonicalFingerprint,
            threeRecordPages.studentDocuments.terminalBoundaryCanonicalFingerprint,
        )
    }

    @Test
    fun everyStudentContentFieldParticipatesInTheExactManifest() = runBlocking {
        val original = studentRecord(1)
        val changedTitle = original.copy(title = "changed title")
        val changedAsset =
            original.copy(
                sourceAssets =
                    original.sourceAssets.mapIndexed { index, linked ->
                        if (index == 0) {
                            linked.copy(
                                sourceAsset =
                                    linked.sourceAsset.copy(
                                        contentSha256 = sha('f'),
                                    ),
                            )
                        } else {
                            linked
                        }
                },
            )
        val changedPageIndex =
            original.copy(
                sourceAssets =
                    original.sourceAssets.map { linked ->
                        linked.copy(pageIndex = checkNotNull(linked.pageIndex) + 1)
                    },
            )

        val baseline = manifests(student = listOf(original)).studentDocuments
        val titleManifest = manifests(student = listOf(changedTitle)).studentDocuments
        val assetManifest = manifests(student = listOf(changedAsset)).studentDocuments
        val pageIndexManifest =
            manifests(student = listOf(changedPageIndex)).studentDocuments

        assertNotEquals(
            baseline.sourceCanonicalFingerprint,
            titleManifest.sourceCanonicalFingerprint,
        )
        assertNotEquals(
            baseline.sourceCanonicalFingerprint,
            assetManifest.sourceCanonicalFingerprint,
        )
        assertNotEquals(
            baseline.sourceCanonicalFingerprint,
            pageIndexManifest.sourceCanonicalFingerprint,
        )
    }

    @Test
    fun masteryProofAndTargetMetadataParticipateInTheExactManifest() = runBlocking {
        val original = masteryRecord(1)
        val changedProof =
            original.copy(sourceProofCanonicalFingerprint = sha('e'))
        val changedTarget =
            original.copy(targetVersion = "target-v2")

        val baseline = manifests(mastery = listOf(original)).masteryFacts
        val proofManifest = manifests(mastery = listOf(changedProof)).masteryFacts
        val targetManifest = manifests(mastery = listOf(changedTarget)).masteryFacts

        assertNotEquals(
            baseline.sourceCanonicalFingerprint,
            proofManifest.sourceCanonicalFingerprint,
        )
        assertNotEquals(
            baseline.sourceCanonicalFingerprint,
            targetManifest.sourceCanonicalFingerprint,
        )
    }

    @Test
    fun repeatedCursorFailsClosedInsteadOfLoopingOrSkipping() {
        val source =
            FakeLegacySource(
                studentRecords = listOf(studentRecord(1), studentRecord(2)),
                masteryRecords = emptyList(),
                repeatStudentFirstPage = true,
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ExactLegacyAuthorityManifestReader(
                    source = source,
                    learnerId = LEARNER_ID,
                    pageSize = 1,
                ).readOnce(legacySchemaVersion = 40)
            }
        }
    }

    @Test
    fun sourceMutationBetweenCompleteScansFailsClosed() {
        val source =
            FakeLegacySource(
                studentRecords = listOf(studentRecord(1)),
                masteryRecords = listOf(masteryRecord(1)),
                mutateStudentAfterFirstScan = true,
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ExactLegacyAuthorityManifestReader(
                    source = source,
                    learnerId = LEARNER_ID,
                    pageSize = 1,
                ).readStableManifests()
            }
        }
    }

    @Test
    fun stableSourceOmissionFailsCountCoverageCheck() {
        val source =
            FakeLegacySource(
                studentRecords = listOf(studentRecord(1), studentRecord(3)),
                masteryRecords = emptyList(),
                reportedStudentRecordCount = 3L,
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ExactLegacyAuthorityManifestReader(
                    source = source,
                    learnerId = LEARNER_ID,
                    pageSize = 2,
                ).readStableManifests()
            }
        }
    }

    @Test
    fun reportedMasteryProofCountMustMatchTheExactProofRows() {
        val source =
            FakeLegacySource(
                studentRecords = emptyList(),
                masteryRecords = listOf(masteryRecord(1)),
                reportedMasteryProvenRecordCount = 0L,
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ExactLegacyAuthorityManifestReader(
                    source = source,
                    learnerId = LEARNER_ID,
                    pageSize = 2,
                ).readStableManifests()
            }
        }
    }

    @Test
    fun reportedBrokenSourceAssetLinkFailsClosedBeforeManifestAcceptance() {
        val source =
            FakeLegacySource(
                studentRecords = listOf(studentRecord(1)),
                masteryRecords = emptyList(),
                reportedBrokenSourceAssetLinkCount = 1L,
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ExactLegacyAuthorityManifestReader(
                    source = source,
                    learnerId = LEARNER_ID,
                    pageSize = 2,
                ).readStableManifests()
            }
        }
    }

    private suspend fun manifests(
        student: List<LegacyStudentDocumentMigrationRecord> =
            listOf(studentRecord(1)),
        mastery: List<LegacyMasteryFactMigrationRecord> =
            listOf(masteryRecord(1)),
    ): ExactLegacyAuthorityManifests =
        ExactLegacyAuthorityManifestReader(
            source = FakeLegacySource(student, mastery),
            learnerId = LEARNER_ID,
            pageSize = 2,
        ).readStableManifests()
}

private class FakeLegacySource(
    studentRecords: List<LegacyStudentDocumentMigrationRecord>,
    private val masteryRecords: List<LegacyMasteryFactMigrationRecord>,
    private val repeatStudentFirstPage: Boolean = false,
    private val mutateStudentAfterFirstScan: Boolean = false,
    private val reportedStudentRecordCount: Long = studentRecords.size.toLong(),
    private val reportedMasteryProvenRecordCount: Long =
        masteryRecords.count { it.sourceProofCanonicalFingerprint != null }.toLong(),
    private val reportedBrokenSourceAssetLinkCount: Long = 0L,
) : LegacyAuthorityMigrationSourcePort {
    private val originalStudentRecords = studentRecords
    private var completedStudentScans = 0

    override suspend fun readLegacyAuthorityMigrationSnapshot(
        learnerId: String,
    ): LegacyAuthorityMigrationSnapshot {
        require(learnerId == LEARNER_ID)
        return LegacyAuthorityMigrationSnapshot(
            schemaVersion = 40,
            studentDocumentRevisionCount = reportedStudentRecordCount,
            studentSourceAssetLinkCount =
                originalStudentRecords.sumOf { it.sourceAssets.size }.toLong(),
            studentBrokenSourceAssetLinkCount =
                reportedBrokenSourceAssetLinkCount,
            studentSourceAssetByteCount =
                originalStudentRecords.sumOf { record ->
                    record.sourceAssets.sumOf { it.sourceAsset.byteSize }
                },
            studentProblemWithMultipleEntriesCount = 0L,
            studentProblemCurrentRevisionNotLatestCount = 0L,
            masterySourceFactCount = masteryRecords.size.toLong(),
            masteryProvenSourceFactCount = reportedMasteryProvenRecordCount,
            latestStudentMutationAtEpochMillis =
                originalStudentRecords.maxOfOrNull { it.updatedAtEpochMillis } ?: 0L,
            latestMasteryFactAtEpochMillis =
                masteryRecords.maxOfOrNull { it.sourceFact.occurredAtEpochMillis } ?: 0L,
        )
    }

    override suspend fun readLegacyStudentDocumentMigrationPage(
        afterExclusive: LegacyStudentDocumentMigrationCursor?,
        limit: Int,
    ): LegacyStudentDocumentMigrationPage {
        val records =
            if (mutateStudentAfterFirstScan && completedStudentScans > 0) {
                originalStudentRecords.mapIndexed { index, record ->
                    if (index == 0) record.copy(title = "${record.title}-late") else record
                }
            } else {
                originalStudentRecords
            }
        val effectiveCursor =
            if (repeatStudentFirstPage && afterExclusive != null) null else afterExclusive
        val available =
            records.filter { record ->
                effectiveCursor == null || record.cursor > effectiveCursor
            }
        val pageRecords = available.take(limit)
        val hasMore = available.size > pageRecords.size
        if (!hasMore) completedStudentScans += 1
        return LegacyStudentDocumentMigrationPage(pageRecords, hasMore)
    }

    override suspend fun readLegacyMasteryFactMigrationPage(
        learnerId: String,
        afterExclusive: LegacyMasteryFactMigrationCursor?,
        limit: Int,
    ): LegacyMasteryFactMigrationPage {
        require(learnerId == LEARNER_ID)
        val available =
            masteryRecords.filter { record ->
                afterExclusive == null || record.cursor > afterExclusive
            }
        val pageRecords = available.take(limit)
        return LegacyMasteryFactMigrationPage(
            records = pageRecords,
            hasMore = available.size > pageRecords.size,
        )
    }
}

private fun studentRecord(index: Int): LegacyStudentDocumentMigrationRecord =
    LegacyStudentDocumentMigrationRecord(
        entryId = "entry-$index",
        problemId = "problem-$index",
        problemCanonicalFingerprint = sha('a'),
        revisionId = "revision-$index",
        revisionNumber = index,
        subject = SubjectKind.MATH.name,
        problemCreatedAtEpochMillis = index * 1_000L,
        problemArchivedAtEpochMillis = null,
        title = "title-$index",
        problemMarkdown = "problem $index",
        questionDocumentSnapshot = """{"index":$index}""",
        answerSpecId = null,
        answerSpecSnapshot = null,
        answerVerificationStatus = "UNVERIFIED",
        revisionSourceType = "LEGACY_IMPORT",
        revisionSourceReference = null,
        contentFingerprint = sha('b'),
        practiceUnitId = "unit-$index",
        practiceUnitKey = "unit-key-$index",
        practiceUnitKind = "SAVED_MISTAKE",
        practiceUnitTitle = "unit title $index",
        practiceUnitPromptMarkdown = "problem $index",
        estimatedSeconds = 120,
        practiceUnitRevisionId = "revision-$index",
        practiceUnitCreatedAtEpochMillis = index * 1_000L,
        entryCurrentRevisionId = "revision-$index",
        sourceKey = "legacy-source-$index",
        status = "ACTIVE",
        acceptedAtEpochMillis = index * 1_000L,
        updatedAtEpochMillis = index * 1_000L + 10L,
        revisionCreatedAtEpochMillis = index * 1_000L,
        sourceAssets =
            listOf(
                MistakeDetailSourceAssetRecord(
                    role = "QUESTION_SOURCE",
                    pageIndex = 0,
                    sourceAsset =
                        CanonicalSourceAssetRecord(
                            sourceAssetId = "asset-$index",
                            contentSha256 = sha('c'),
                            relativePath = "capture/$index.jpg",
                            mimeType = "image/jpeg",
                            byteSize = 1_024L + index,
                            width = 1080,
                            height = 1440,
                            sourceType = "CAMERA",
                            createdAtEpochMillis = index * 1_000L,
                        ),
                ),
            ),
    )

private fun masteryRecord(index: Int): LegacyMasteryFactMigrationRecord =
    LegacyMasteryFactMigrationRecord(
        sourceFact =
            LearningObservationSourceFact(
                sourceFactId = "source-fact-$index",
                learnerScopeId = LEARNER_ID,
                source = LearningObservationSource.IMPORTED_MISTAKE,
                factKind = LearningObservationFactKind.IMPORTED_VISIBLE_ERROR,
                anchorId = "anchor-$index",
                subject = SubjectKind.MATH,
                conversationGeneration = null,
                conversationId = null,
                turnReceiptId = null,
                evidenceRequestId = null,
                responseFingerprint = sha('d'),
                responseSummary = "visible error $index",
                occurredAtEpochMillis = index * 2_000L,
                sourceVersion = "source-v1",
            ),
        sourcePayloadCanonicalFingerprint = sha('a'),
        sourceProofCanonicalFingerprint = sha('b'),
        sourceReferenceId = "reference-$index",
        targetKind = "PROBLEM_REVISION",
        targetDatabase = "student-mistakes.db",
        targetId = "revision-$index",
        targetVersion = "1",
        targetCanonicalFingerprint = sha('c'),
        attestedAtEpochMillis = index * 2_000L + 1L,
    )

private fun sha(character: Char): String =
    character.toString().repeat(64)

private const val LEARNER_ID = "local-learner"
