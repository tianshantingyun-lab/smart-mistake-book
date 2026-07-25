package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchReviewFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchSourceFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeResearchReviewContractTest {
    @Test
    fun validReviewBundlePasses() {
        KnowledgeResearchReviewContract.validate(bundle())
    }

    @Test
    fun bundleIdentityMustMatchSources() {
        val changed = bundle().let { bundle ->
            bundle.copy(
                sources = bundle.sources.map { source ->
                    source.copy(contentFingerprint = "c".repeat(64))
                },
            )
        }

        assertTrue(runCatching { KnowledgeResearchReviewContract.validate(changed) }.isFailure)
    }

    @Test
    fun groundingIdentityMustMatchItsNormalizedKnowledgeGap() {
        val valid = bundle()

        assertTrue(
            runCatching {
                KnowledgeResearchReviewContract.validate(
                    valid.copy(groundingKey = "a".repeat(64)),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                KnowledgeResearchReviewContract.validate(
                    valid.copy(query = "不相同的待校准知识"),
                )
            }.isFailure,
        )
    }

    @Test
    fun sourceOrderAndNewStatusAreClosedAtTheDatabaseBoundary() {
        val bundle = bundle()
        assertTrue(
            runCatching {
                KnowledgeResearchReviewContract.validate(
                    bundle.copy(
                        sources = bundle.sources.map { it.copy(sourceOrdinal = 1) },
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                KnowledgeResearchReviewContract.validate(
                    bundle.copy(status = StudyDbValue.KnowledgeResearchReviewStatus.APPROVED),
                )
            }.isFailure,
        )
    }

    @Test
    fun reviewDecisionMustBeAuditableAndMonotonic() {
        val bundle = bundle()
        KnowledgeResearchReviewContract.validateDecision(
            DecideKnowledgeResearchReviewBundleCommand(
                bundleId = bundle.bundleId,
                decisionStatus = StudyDbValue.KnowledgeResearchReviewStatus.APPROVED,
                reviewerReference = "reviewer:curriculum-team",
                decisionNote = "来源与知识边界已复核。",
                decidedAtEpochMillis = 30L,
            ),
            bundle,
        )

        assertTrue(
            runCatching {
                KnowledgeResearchReviewContract.validateDecision(
                    DecideKnowledgeResearchReviewBundleCommand(
                        bundleId = bundle.bundleId,
                        decisionStatus = StudyDbValue.KnowledgeResearchReviewStatus.PENDING_REVIEW,
                        reviewerReference = "reviewer:curriculum-team",
                        decisionNote = "无效回退",
                        decidedAtEpochMillis = 10L,
                    ),
                    bundle,
                )
            }.isFailure,
        )
    }

    private fun bundle(): KnowledgeResearchReviewBundleRecord {
        val query = "导数与单调性的局部判定"
        val expectedParent = "导数"
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = expectedParent,
            query = query,
        )
        val source = KnowledgeResearchReviewSourceRecord(
            sourceOrdinal = 0,
            canonicalSourceUri = "https://www.moe.gov.cn/source.pdf",
            title = "课程标准",
            publisher = "教育部",
            sourceType = "OFFICIAL_CURRICULUM_STANDARD",
            licenseStatus = "PUBLIC_OFFICIAL",
            searchRank = 0,
            contentType = "application/pdf",
            contentLengthBytes = 1_024L,
            contentFingerprint = "b".repeat(64),
            verifiedAtEpochMillis = 10L,
        )
        val bundleId = KnowledgeResearchReviewFingerprint.of(
            groundingKey = groundingKey,
            subject = SubjectKind.MATH.name,
            sources = listOf(
                KnowledgeResearchSourceFingerprint(
                    canonicalSourceUri = source.canonicalSourceUri,
                    contentFingerprint = source.contentFingerprint,
                ),
            ),
        )
        return KnowledgeResearchReviewBundleRecord(
            bundleId = bundleId,
            groundingKey = groundingKey,
            subject = "MATH",
            query = query,
            expectedParentKnowledgeDisplayName = expectedParent,
            relatedQuestionCount = 3,
            workflowVersion = "knowledge-research-v1",
            createdAtEpochMillis = 20L,
            updatedAtEpochMillis = 20L,
            sources = listOf(source),
        )
    }

}
