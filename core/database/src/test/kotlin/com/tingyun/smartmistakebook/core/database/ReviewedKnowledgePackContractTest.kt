package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchReviewFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchSourceFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedKnowledgePackContractTest {
    @Test
    fun validPackProducesCanonicalResolution() {
        val command = validCommand()

        val validated = ReviewedKnowledgePackContract.validate(command)

        assertEquals(1, validated.size)
        assertEquals(command.resolutions.single(), validated.single().command)
        assertEquals(
            KnowledgeGroundingFingerprint.resolutionId(GROUNDING_KEY, ATOMIC_ID),
            validated.single().resolutionId,
        )
    }

    @Test
    fun resolutionMustTargetAnAtomicNodeIncludedInThePack() {
        val missingTarget = validCommand().let { command ->
            command.copy(
                resolutions = listOf(command.resolutions.single().copy(knowledgeNodeId = "kb:missing")),
            )
        }
        val topicTarget = validCommand().let { command ->
            command.copy(
                resolutions = listOf(command.resolutions.single().copy(knowledgeNodeId = TOPIC_ID)),
            )
        }

        assertTrue(
            runCatching { ReviewedKnowledgePackContract.validate(missingTarget) }
                .exceptionOrNull() is DatabaseContractViolationException,
        )
        assertTrue(
            runCatching { ReviewedKnowledgePackContract.validate(topicTarget) }
                .exceptionOrNull() is DatabaseContractViolationException,
        )
    }

    @Test
    fun duplicateGapOrResolutionBeforeNodeCreationFailsClosed() {
        val command = validCommand()
        val duplicateGap = command.copy(
            resolutions = command.resolutions + command.resolutions.single(),
        )
        val timeTravel = command.copy(
            resolutions = listOf(
                command.resolutions.single().copy(resolvedAtEpochMillis = 1_400),
            ),
        )

        assertTrue(
            runCatching { ReviewedKnowledgePackContract.validate(duplicateGap) }
                .exceptionOrNull() is DatabaseContractViolationException,
        )
        assertTrue(
            runCatching { ReviewedKnowledgePackContract.validate(timeTravel) }
                .exceptionOrNull() is DatabaseContractViolationException,
        )
    }

    @Test
    fun approvedResearchReviewBindsTheExactVerifiedSourceAndPack() {
        val pack = validCommand()
        val review = approvedResearchReview(pack)
        val command = ApplyApprovedKnowledgeResearchPackCommand(
            reviewBundleId = review.bundleId,
            pack = pack,
            appliedAtEpochMillis = 2_100,
        )

        val fingerprint = ApprovedKnowledgeResearchPackContract.validate(review, command)
        val reordered = pack.copy(
            sources = pack.sources.reversed(),
            nodes = pack.nodes.reversed(),
            bindings = pack.bindings.reversed(),
            relations = pack.relations.reversed(),
            resolutions = pack.resolutions.reversed(),
        )

        assertEquals(64, fingerprint.length)
        assertEquals(fingerprint, ReviewedKnowledgePackFingerprint.of(reordered))
        assertTrue(
            runCatching {
                ApprovedKnowledgeResearchPackContract.validate(
                    review.copy(status = StudyDbValue.KnowledgeResearchReviewStatus.PENDING_REVIEW),
                    command,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ApprovedKnowledgeResearchPackContract.validate(
                    review,
                    command.copy(
                        pack = pack.copy(
                            sources = listOf(
                                pack.sources.single().copy(contentFingerprint = "B".repeat(64)),
                            ),
                        ),
                    ),
                )
            }.isFailure,
        )
    }

    private fun validCommand(): ApplyReviewedKnowledgePackCommand {
        val source = KnowledgeSourceSeedRecord(
            sourceId = SOURCE_ID,
            subject = SubjectKind.MATH.name,
            sourceType = KnowledgeSourceType.MANUAL_RESEARCH.name,
            title = "经审校的函数知识目录",
            publisher = "授权教研机构",
            edition = null,
            sourceUri = "https://example.edu/math/function-index",
            licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
            contentFingerprint = "A".repeat(64),
            importedAtEpochMillis = 1_500,
        )
        val topic = KnowledgeNodeSeedRecord(
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
        val atomic = KnowledgeNodeSeedRecord(
            knowledgeNodeId = ATOMIC_ID,
            stableCode = "research-v1:math:atomic:monotonicity",
            subject = SubjectKind.MATH.name,
            displayName = "依据导数符号判断函数单调性",
            parentKnowledgeNodeId = TOPIC_ID,
            taxonomyVersion = "research-v1",
            createdAtEpochMillis = 1_500,
            canonicalName = "依据导数符号判断函数单调性",
            nodeKind = KnowledgeNodeKind.REASONING.name,
            granularity = KnowledgeNodeGranularity.ATOMIC.name,
            boundaryMarkdown = "只处理导数符号与单调区间的对应关系。",
            verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
        )
        return ApplyReviewedKnowledgePackCommand(
            sources = listOf(source),
            nodes = listOf(atomic, topic),
            bindings = listOf(topic, atomic).map { node ->
                KnowledgeNodeSourceBindingSeedRecord(
                    knowledgeNodeId = node.knowledgeNodeId,
                    sourceId = SOURCE_ID,
                    sourceLocator = "函数性质目录",
                    derivationNote = "人工核对后拆分。",
                    reviewedAtEpochMillis = 1_500,
                )
            },
            resolutions = listOf(
                ResolveKnowledgeGroundingCommand(
                    groundingKey = GROUNDING_KEY,
                    subject = SubjectKind.MATH.name,
                    knowledgeNodeId = ATOMIC_ID,
                    resolvedAtEpochMillis = 2_000,
                ),
            ),
        )
    }

    private fun approvedResearchReview(
        pack: ApplyReviewedKnowledgePackCommand,
    ): KnowledgeResearchReviewBundleRecord {
        val source = pack.sources.single()
        val sourceUri = checkNotNull(source.sourceUri)
        val sourceFingerprint = source.contentFingerprint.lowercase()
        val bundleId = KnowledgeResearchReviewFingerprint.of(
            groundingKey = GROUNDING_KEY,
            subject = SubjectKind.MATH.name,
            sources = listOf(
                KnowledgeResearchSourceFingerprint(sourceUri, sourceFingerprint),
            ),
        )
        return KnowledgeResearchReviewBundleRecord(
            bundleId = bundleId,
            groundingKey = GROUNDING_KEY,
            subject = SubjectKind.MATH.name,
            query = "导数符号与单调性",
            expectedParentKnowledgeDisplayName = "函数性质",
            relatedQuestionCount = 1,
            workflowVersion = "knowledge-research-v1",
            status = StudyDbValue.KnowledgeResearchReviewStatus.APPROVED,
            reviewerReference = "reviewer:curriculum-team",
            decisionNote = "来源与拆分结果已复核。",
            reviewedAtEpochMillis = 1_400,
            createdAtEpochMillis = 1_200,
            updatedAtEpochMillis = 1_400,
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
                    contentFingerprint = sourceFingerprint,
                    verifiedAtEpochMillis = 1_100,
                ),
            ),
        )
    }

    private companion object {
        val GROUNDING_KEY = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = "函数性质",
            query = "导数符号与单调性",
        )
        const val SOURCE_ID = "source:research:math:pack"
        const val TOPIC_ID = "kb:research-v1:math:topic:function-pack"
        const val ATOMIC_ID = "kb:research-v1:math:atomic:monotonicity-pack"
    }
}
