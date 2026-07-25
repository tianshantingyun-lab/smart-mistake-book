package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchReviewFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchSourceFingerprint
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
class ReviewedKnowledgePackInstrumentedTest {
    @Test
    fun reviewedPackClosesGapAndExactReplayIsIdempotent() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.seedFixture(baseSeed())
            val request = request()
            store.recordKnowledgeGroundingRequests(listOf(request))
            val command = reviewedPack(listOf(resolution(request.groundingKey, ATOMIC_ID)))

            val first = store.applyReviewedKnowledgePack(command)
            val replay = store.applyReviewedKnowledgePack(command)

            assertEquals(first, replay)
            assertEquals(1, first.single().resolvedOccurrenceCount)
            assertEquals(1, first.single().linkedPracticeUnitCount)
            assertTrue(store.observePendingKnowledgeGroundingRequests().first().isEmpty())
            assertEquals(
                setOf(TOPIC_ID, ATOMIC_ID, SECOND_ATOMIC_ID),
                store.readKnowledgeNodesByIds(setOf(TOPIC_ID, ATOMIC_ID, SECOND_ATOMIC_ID))
                    .mapTo(mutableSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId),
            )

            val conflictingSource = command.copy(
                sources = listOf(command.sources.single().copy(title = "冲突后的标题")),
            )
            val conflict = runCatching {
                store.applyReviewedKnowledgePack(conflictingSource)
            }.exceptionOrNull()
            assertTrue(conflict is ImmutablePayloadConflictException)
            assertEquals(first.single(), store.readKnowledgeGroundingResolution(request.groundingKey))
        } finally {
            store.close()
        }
    }

    @Test
    fun laterResolutionFailureRollsBackPackAndEarlierResolution() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.seedFixture(baseSeed())
            val request = request()
            store.recordKnowledgeGroundingRequests(listOf(request))
            val missingGapKey = KnowledgeGroundingFingerprint.of(
                subject = SubjectKind.MATH,
                expectedParentKnowledgeDisplayName = "函数性质",
                query = "函数极值",
            )
            val command = reviewedPack(
                listOf(
                    resolution(request.groundingKey, ATOMIC_ID),
                    resolution(missingGapKey, SECOND_ATOMIC_ID),
                ),
            )

            val failure = runCatching {
                store.applyReviewedKnowledgePack(command)
            }.exceptionOrNull()

            assertTrue(failure is DatabaseContractViolationException)
            assertTrue(
                store.readKnowledgeNodesByIds(setOf(TOPIC_ID, ATOMIC_ID, SECOND_ATOMIC_ID))
                    .isEmpty(),
            )
            assertTrue(store.readKnowledgeSourcesByIds(setOf(SOURCE_ID)).isEmpty())
            assertNull(store.readKnowledgeGroundingResolution(request.groundingKey))
            assertEquals(1, store.observePendingKnowledgeGroundingRequests().first().size)
        } finally {
            store.close()
        }
    }

    @Test
    fun approvedResearchPackAppliesOnceAndCannotBeReplaced() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.seedFixture(baseSeed())
            val request = request()
            store.recordKnowledgeGroundingRequests(listOf(request))
            val pack = reviewedPack(listOf(resolution(request.groundingKey, ATOMIC_ID)))
            val review = researchReviewBundle(request, pack)
            val approval = DecideKnowledgeResearchReviewBundleCommand(
                bundleId = review.bundleId,
                decisionStatus = StudyDbValue.KnowledgeResearchReviewStatus.APPROVED,
                reviewerReference = "reviewer:curriculum-team",
                decisionNote = "来源、知识边界和拆分结果已复核。",
                decidedAtEpochMillis = 1_400,
            )
            store.enqueueKnowledgeResearchReviewBundle(review)
            store.decideKnowledgeResearchReviewBundle(approval)
            val command = ApplyApprovedKnowledgeResearchPackCommand(
                reviewBundleId = review.bundleId,
                pack = pack,
                appliedAtEpochMillis = 2_000,
            )

            val first = store.applyApprovedKnowledgeResearchPack(command)
            val replay = store.applyApprovedKnowledgeResearchPack(command)

            assertEquals(first, replay)
            val applied = checkNotNull(store.readKnowledgeResearchReviewBundle(review.bundleId))
            assertEquals(StudyDbValue.KnowledgeResearchReviewStatus.APPLIED, applied.status)
            assertEquals(2_000L, applied.appliedAtEpochMillis)
            assertTrue(applied.appliedPackFingerprint?.length == 64)
            assertEquals(applied, store.decideKnowledgeResearchReviewBundle(approval))
            val changedPack = pack.copy(
                nodes = pack.nodes.map { node ->
                    if (node.knowledgeNodeId == ATOMIC_ID) {
                        node.copy(displayName = "被替换的知识名称")
                    } else {
                        node
                    }
                },
            )
            assertTrue(
                runCatching {
                    store.applyApprovedKnowledgeResearchPack(
                        command.copy(pack = changedPack),
                    )
                }.exceptionOrNull() is ImmutablePayloadConflictException,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun unapprovedResearchPackCannotChangeKnowledgeOrCloseTheGap() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.seedFixture(baseSeed())
            val request = request()
            store.recordKnowledgeGroundingRequests(listOf(request))
            val pack = reviewedPack(listOf(resolution(request.groundingKey, ATOMIC_ID)))
            val review = researchReviewBundle(request, pack)
            store.enqueueKnowledgeResearchReviewBundle(review)

            val failure = runCatching {
                store.applyApprovedKnowledgeResearchPack(
                    ApplyApprovedKnowledgeResearchPackCommand(
                        reviewBundleId = review.bundleId,
                        pack = pack,
                        appliedAtEpochMillis = 2_000,
                    ),
                )
            }.exceptionOrNull()

            assertTrue(failure is DatabaseContractViolationException)
            assertTrue(store.readKnowledgeNodesByIds(setOf(ATOMIC_ID, TOPIC_ID)).isEmpty())
            assertNull(store.readKnowledgeGroundingResolution(request.groundingKey))
            assertEquals(1, store.observePendingKnowledgeGroundingRequests().first().size)
        } finally {
            store.close()
        }
    }

    private fun baseSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = PROBLEM_ID,
                canonicalFingerprint = "1".repeat(64),
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
                problemMarkdown = "根据导数符号判断函数单调性。",
                questionDocumentSnapshot = null,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "UNKNOWN",
                sourceType = "TEST",
                sourceReference = null,
                contentFingerprint = "2".repeat(64),
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
                promptMarkdown = "根据导数符号判断函数单调性。",
                estimatedSeconds = 180,
                createdAtEpochMillis = 1_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry:reviewed-pack",
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

    private fun request(): KnowledgeGroundingRequestRecord {
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "函数性质",
            query = "导数符号与单调性",
        )
        return KnowledgeGroundingRequestRecord(
            groundingRequestId = KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId = "organization-request:reviewed-pack",
                requestOrdinal = 0,
                groundingKey = groundingKey,
            ),
            groundingKey = groundingKey,
            organizationRequestId = "organization-request:reviewed-pack",
            organizationRequestFingerprint = "3".repeat(64),
            requestOrdinal = 0,
            problemId = PROBLEM_ID,
            problemRevisionId = REVISION_ID,
            practiceUnitId = PRACTICE_UNIT_ID,
            subject = SubjectKind.MATH.name,
            query = "导数符号与单调性",
            expectedParentKnowledgeDisplayName = "函数性质",
            reasonMarkdown = "现有本体缺少对应原子知识。",
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
    }

    private fun reviewedPack(
        resolutions: List<ResolveKnowledgeGroundingCommand>,
    ): ApplyReviewedKnowledgePackCommand {
        val nodes = listOf(
            node(TOPIC_ID, "函数性质", KnowledgeNodeGranularity.TOPIC, KnowledgeNodeKind.TOPIC),
            node(ATOMIC_ID, "依据导数符号判断函数单调性"),
            node(SECOND_ATOMIC_ID, "依据导数符号判断函数极值"),
        )
        return ApplyReviewedKnowledgePackCommand(
            sources = listOf(
                KnowledgeSourceSeedRecord(
                    sourceId = SOURCE_ID,
                    subject = SubjectKind.MATH.name,
                    sourceType = KnowledgeSourceType.MANUAL_RESEARCH.name,
                    title = "经审校的函数知识目录",
                    publisher = "授权教研机构",
                    edition = null,
                    sourceUri = "https://example.edu/math/reviewed-pack",
                    licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
                    contentFingerprint = "A".repeat(64),
                    importedAtEpochMillis = 1_500,
                ),
            ),
            nodes = nodes.reversed(),
            bindings = nodes.map { node ->
                KnowledgeNodeSourceBindingSeedRecord(
                    knowledgeNodeId = node.knowledgeNodeId,
                    sourceId = SOURCE_ID,
                    sourceLocator = "函数目录",
                    derivationNote = "人工审校后拆分。",
                    reviewedAtEpochMillis = 1_500,
                )
            },
            resolutions = resolutions,
        )
    }

    private fun researchReviewBundle(
        request: KnowledgeGroundingRequestRecord,
        pack: ApplyReviewedKnowledgePackCommand,
    ): KnowledgeResearchReviewBundleRecord {
        val source = pack.sources.single()
        val sourceUri = checkNotNull(source.sourceUri)
        val fingerprint = source.contentFingerprint.lowercase()
        return KnowledgeResearchReviewBundleRecord(
            bundleId = KnowledgeResearchReviewFingerprint.of(
                groundingKey = request.groundingKey,
                subject = request.subject,
                sources = listOf(
                    KnowledgeResearchSourceFingerprint(sourceUri, fingerprint),
                ),
            ),
            groundingKey = request.groundingKey,
            subject = request.subject,
            query = request.query,
            expectedParentKnowledgeDisplayName = request.expectedParentKnowledgeDisplayName,
            relatedQuestionCount = 1,
            workflowVersion = "knowledge-research-v1",
            createdAtEpochMillis = 1_200,
            updatedAtEpochMillis = 1_200,
            sources = listOf(
                KnowledgeResearchReviewSourceRecord(
                    sourceOrdinal = 0,
                    canonicalSourceUri = sourceUri,
                    title = source.title,
                    publisher = source.publisher,
                    sourceType = source.sourceType,
                    licenseStatus = source.licenseStatus,
                    searchRank = 0,
                    contentType = "application/pdf",
                    contentLengthBytes = 1_024,
                    contentFingerprint = fingerprint,
                    verifiedAtEpochMillis = 1_100,
                ),
            ),
        )
    }

    private fun node(
        id: String,
        name: String,
        granularity: KnowledgeNodeGranularity = KnowledgeNodeGranularity.ATOMIC,
        kind: KnowledgeNodeKind = KnowledgeNodeKind.REASONING,
    ) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = id,
        stableCode = id.removePrefix("kb:"),
        subject = SubjectKind.MATH.name,
        displayName = name,
        parentKnowledgeNodeId = if (granularity == KnowledgeNodeGranularity.ATOMIC) TOPIC_ID else null,
        taxonomyVersion = "research-v1",
        createdAtEpochMillis = 1_500,
        canonicalName = name,
        nodeKind = kind.name,
        granularity = granularity.name,
        boundaryMarkdown = if (granularity == KnowledgeNodeGranularity.ATOMIC) {
            "只处理当前函数性质对应的原子判断。"
        } else {
            null
        },
        verificationStatus = if (granularity == KnowledgeNodeGranularity.ATOMIC) {
            KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name
        } else {
            KnowledgeNodeVerificationStatus.CURATED.name
        },
    )

    private fun resolution(
        groundingKey: String,
        knowledgeNodeId: String,
    ) = ResolveKnowledgeGroundingCommand(
        groundingKey = groundingKey,
        subject = SubjectKind.MATH.name,
        knowledgeNodeId = knowledgeNodeId,
        resolvedAtEpochMillis = 2_000,
    )

    private companion object {
        const val PROBLEM_ID = "problem:reviewed-pack"
        const val REVISION_ID = "revision:reviewed-pack"
        const val PRACTICE_UNIT_ID = "unit:reviewed-pack"
        const val SOURCE_ID = "source:research:math:reviewed-pack"
        const val TOPIC_ID = "kb:research-v1:math:topic:function-reviewed-pack"
        const val ATOMIC_ID = "kb:research-v1:math:atomic:monotonicity-reviewed-pack"
        const val SECOND_ATOMIC_ID = "kb:research-v1:math:atomic:extrema-reviewed-pack"
    }
}
