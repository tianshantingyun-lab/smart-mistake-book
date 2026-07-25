package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeGroundingResolutionInstrumentedTest {
    @Test
    fun reviewedAtomicImportClosesGapAndExactReplayStaysIdempotent() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.seedFixture(baseSeed())
            val request = request()
            val repeatedRequest = request(
                organizationRequestId = "organization-request:resolution-repeat",
                createdAtEpochMillis = 1_100,
            )
            val command = resolutionCommand(ATOMIC_ID)
            store.recordKnowledgeGroundingRequests(listOf(request, repeatedRequest))

            val prematureFailure = runCatching {
                store.resolveKnowledgeGrounding(command)
            }.exceptionOrNull()
            assertTrue(prematureFailure is DatabaseContractViolationException)
            assertEquals(2, store.observePendingKnowledgeGroundingRequests().first().size)
            assertNull(store.readKnowledgeGroundingResolution(request.groundingKey))

            store.importKnowledgeBase(
                sources = listOf(source()),
                nodes = listOf(topic(), atomic(ATOMIC_ID, "判断函数单调性")),
                bindings = listOf(binding(TOPIC_ID), binding(ATOMIC_ID)),
            )
            store.importKnowledgeBase(
                sources = listOf(source(LATER_SOURCE_ID, 2_500)),
                nodes = listOf(atomic(ATOMIC_ID, "判断函数单调性")),
                bindings = listOf(binding(ATOMIC_ID, LATER_SOURCE_ID, 2_500)),
            )

            val topicFailure = runCatching {
                store.resolveKnowledgeGrounding(resolutionCommand(TOPIC_ID))
            }.exceptionOrNull()
            assertTrue(topicFailure is DatabaseContractViolationException)
            val crossSubjectFailure = runCatching {
                store.resolveKnowledgeGrounding(
                    command.copy(subject = SubjectKind.PHYSICS.name),
                )
            }.exceptionOrNull()
            assertTrue(crossSubjectFailure is DatabaseContractViolationException)

            store.importKnowledgeBase(
                sources = emptyList(),
                nodes = listOf(atomic(LATE_EVIDENCE_ATOMIC_ID, "依据导数符号判断驻点")),
                bindings = listOf(
                    binding(
                        nodeId = LATE_EVIDENCE_ATOMIC_ID,
                        sourceId = LATER_SOURCE_ID,
                        reviewedAtEpochMillis = 1_500,
                    ),
                ),
            )
            val lateEvidenceFailure = runCatching {
                store.resolveKnowledgeGrounding(resolutionCommand(LATE_EVIDENCE_ATOMIC_ID))
            }.exceptionOrNull()
            assertTrue(lateEvidenceFailure is DatabaseContractViolationException)

            val futureAtomic = atomic(FUTURE_ATOMIC_ID, "依据导数符号判断极值")
                .copy(createdAtEpochMillis = 2_500)
            store.importKnowledgeBase(
                sources = emptyList(),
                nodes = listOf(futureAtomic),
                bindings = listOf(binding(FUTURE_ATOMIC_ID)),
            )
            val futureNodeFailure = runCatching {
                store.resolveKnowledgeGrounding(resolutionCommand(FUTURE_ATOMIC_ID))
            }.exceptionOrNull()
            assertTrue(futureNodeFailure is DatabaseContractViolationException)

            val timeTravelFailure = runCatching {
                store.resolveKnowledgeGrounding(command.copy(resolvedAtEpochMillis = 1_400))
            }.exceptionOrNull()
            assertTrue(timeTravelFailure is DatabaseContractViolationException)
            assertEquals(2, store.observePendingKnowledgeGroundingRequests().first().size)

            val resolved = store.resolveKnowledgeGrounding(command)
            assertEquals(
                KnowledgeGroundingFingerprint.resolutionId(request.groundingKey, ATOMIC_ID),
                resolved.resolutionId,
            )
            assertEquals(2, resolved.resolvedOccurrenceCount)
            assertEquals(1, resolved.linkedPracticeUnitCount)
            assertEquals(resolved, store.readKnowledgeGroundingResolution(request.groundingKey))
            assertTrue(store.observePendingKnowledgeGroundingRequests().first().isEmpty())
            assertTrue(store.observePendingKnowledgeGroundingSummaries().first().isEmpty())

            assertEquals(resolved, store.resolveKnowledgeGrounding(command))
            store.recordKnowledgeGroundingRequests(listOf(request, repeatedRequest))
            assertTrue(store.observePendingKnowledgeGroundingRequests().first().isEmpty())

            val secondAtomic = atomic(RANGE_ID, "求函数值域")
            store.importKnowledgeBase(
                sources = emptyList(),
                nodes = listOf(secondAtomic),
                bindings = listOf(binding(RANGE_ID)),
            )
            val conflict = runCatching {
                store.resolveKnowledgeGrounding(resolutionCommand(RANGE_ID))
            }.exceptionOrNull()
            assertTrue(conflict is ImmutablePayloadConflictException)
            assertEquals(resolved, store.readKnowledgeGroundingResolution(request.groundingKey))
        } finally {
            store.close()
        }
    }

    private fun baseSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = PROBLEM_ID,
                canonicalFingerprint = "a".repeat(64),
                subject = SubjectKind.MATH.name,
                createdAtEpochMillis = 1_000,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = REVISION_ID,
                problemId = PROBLEM_ID,
                revisionNumber = 1,
                title = "函数单调性",
                problemMarkdown = "判断函数在给定区间上的单调性。",
                questionDocumentSnapshot = null,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "UNKNOWN",
                sourceType = "TEST",
                sourceReference = null,
                contentFingerprint = "b".repeat(64),
                createdAtEpochMillis = 1_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = PRACTICE_UNIT_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                unitKey = "unit:$PRACTICE_UNIT_ID",
                unitKind = "PROBLEM",
                title = "函数单调性",
                promptMarkdown = "判断函数在给定区间上的单调性。",
                estimatedSeconds = 180,
                createdAtEpochMillis = 1_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-1",
                practiceUnitId = PRACTICE_UNIT_ID,
                problemId = PROBLEM_ID,
                currentRevisionId = REVISION_ID,
                sourceKey = null,
                status = StudyDbValue.ErrorBookStatus.ACTIVE,
                acceptedAtEpochMillis = 1_000,
                updatedAtEpochMillis = 1_000,
            ),
        ),
    )

    private fun request(
        organizationRequestId: String = "organization-request:resolution",
        createdAtEpochMillis: Long = 1_000,
    ): KnowledgeGroundingRequestRecord {
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "函数性质",
            query = "导数符号与单调性",
        )
        return KnowledgeGroundingRequestRecord(
            groundingRequestId = KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId = organizationRequestId,
                requestOrdinal = 0,
                groundingKey = groundingKey,
            ),
            groundingKey = groundingKey,
            organizationRequestId = organizationRequestId,
            organizationRequestFingerprint = "c".repeat(64),
            requestOrdinal = 0,
            problemId = PROBLEM_ID,
            problemRevisionId = REVISION_ID,
            practiceUnitId = PRACTICE_UNIT_ID,
            subject = SubjectKind.MATH.name,
            query = "导数符号与单调性",
            expectedParentKnowledgeDisplayName = "函数性质",
            reasonMarkdown = "现有本体无法可靠归因到原子知识。",
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
        )
    }

    private fun resolutionCommand(knowledgeNodeId: String) = ResolveKnowledgeGroundingCommand(
        groundingKey = request().groundingKey,
        subject = SubjectKind.MATH.name,
        knowledgeNodeId = knowledgeNodeId,
        resolvedAtEpochMillis = 2_000,
    )

    private fun source(
        sourceId: String = SOURCE_ID,
        importedAtEpochMillis: Long = 1_500,
    ) = KnowledgeSourceSeedRecord(
        sourceId = sourceId,
        subject = SubjectKind.MATH.name,
        sourceType = KnowledgeSourceType.MANUAL_RESEARCH.name,
        title = "经审校的函数知识目录",
        publisher = "授权教研机构",
        edition = null,
        sourceUri = "https://example.edu/math/function-index",
        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
        contentFingerprint = if (sourceId == SOURCE_ID) "D".repeat(64) else "E".repeat(64),
        importedAtEpochMillis = importedAtEpochMillis,
    )

    private fun topic() = KnowledgeNodeSeedRecord(
        knowledgeNodeId = TOPIC_ID,
        stableCode = "research-v1:math:topic:function",
        subject = SubjectKind.MATH.name,
        displayName = "函数性质",
        parentKnowledgeNodeId = null,
        taxonomyVersion = "research-v1",
        createdAtEpochMillis = 1_500,
        canonicalName = "函数性质",
        nodeKind = KnowledgeNodeKind.TOPIC.name,
        granularity = KnowledgeNodeGranularity.TOPIC.name,
        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
    )

    private fun atomic(id: String, displayName: String) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        stableCode = id.removePrefix("kb:"),
        subject = SubjectKind.MATH.name,
        displayName = displayName,
        parentKnowledgeNodeId = TOPIC_ID,
        taxonomyVersion = "research-v1",
        createdAtEpochMillis = 1_500,
        canonicalName = displayName,
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        boundaryMarkdown = "只处理当前原子能力，不扩展到其他题型。",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    )

    private fun binding(
        nodeId: String,
        sourceId: String = SOURCE_ID,
        reviewedAtEpochMillis: Long = 1_500,
    ) = KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = nodeId,
        sourceId = sourceId,
        sourceLocator = "函数目录",
        derivationNote = "人工核对原始材料后拆分。",
        reviewedAtEpochMillis = reviewedAtEpochMillis,
    )

    private companion object {
        const val PROBLEM_ID = "problem-1"
        const val REVISION_ID = "revision-1"
        const val PRACTICE_UNIT_ID = "unit-1"
        const val SOURCE_ID = "source:research:math"
        const val LATER_SOURCE_ID = "source:research:math:later"
        const val TOPIC_ID = "kb:research-v1:math:topic:function"
        const val ATOMIC_ID = "kb:research-v1:math:atomic:monotonicity"
        const val RANGE_ID = "kb:research-v1:math:atomic:range"
        const val FUTURE_ATOMIC_ID = "kb:research-v1:math:atomic:future-extrema"
        const val LATE_EVIDENCE_ATOMIC_ID = "kb:research-v1:math:atomic:late-evidence"
    }
}
