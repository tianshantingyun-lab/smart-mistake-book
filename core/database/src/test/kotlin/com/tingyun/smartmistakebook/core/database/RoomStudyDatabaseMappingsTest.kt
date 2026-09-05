package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.dao.CanonicalSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanAggregate
import com.tingyun.smartmistakebook.core.database.dao.ReviewQueueAggregate
import com.tingyun.smartmistakebook.core.database.entity.ActiveReviewPlanSlotEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.LlmTeachingAdvisoryEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Field-for-field checks on the shared mappings in RoomStudyDatabaseMappings.kt:
 * a mapping that drops or renames a column must fail here instead of silently
 * nulling data at runtime.
 */
class RoomStudyDatabaseMappingsTest {

    @Test
    fun assessmentItemSnapshotRoundTripsLosslessly() {
        val record = AssessmentItemSnapshotSeedRecord(
            assessmentItemSnapshotId = "item-1",
            itemRevision = 2,
            practiceUnitId = "unit-1",
            problemRevisionId = "rev-1",
            tutorContentSnapshotId = "tutor-1",
            promptMarkdown = "1+1=?",
            optionsSnapshot = "[A,B]",
            answerSpecSnapshot = "{\"answer\":\"2\"}",
            verificationStatus = "VERIFIED",
            assessmentEligibility = "ELIGIBLE",
            scoringMode = "EXACT",
            learnerSnapshotVersion = "v1",
            projectionCheckpoint = 7,
            hintLevelAtPresentation = 0,
            answerRevealState = "HIDDEN",
            createdAtEpochMillis = 1_000L,
        )
        assertEquals(record, record.toEntity().toRecord())
    }

    @Test
    fun reviewSessionRecordDerivesActiveSessionKeyOnlyWhileInProgress() {
        val record = reviewSessionRecord(status = StudyDbValue.ReviewStatus.IN_PROGRESS)
        val entity = record.toEntity()
        assertEquals("plan-1", entity.activeSessionKey)
        assertEquals(record, entity.toRecord())

        val ended = record.copy(status = StudyDbValue.ReviewStatus.COMPLETED)
        assertEquals(null, ended.toEntity().activeSessionKey)
    }

    @Test
    fun reviewQueueChildrenAreSortedByIdentifier() {
        val record = reviewQueueRecord(
            knowledgeNodeIds = setOf("kc-c", "kc-a", "kc-b"),
            reasons = setOf("WEAK_KNOWLEDGE", "AVOIDANCE_SIGNAL", "RECENT_LAPSE"),
        )
        assertEquals(
            listOf("kc-a", "kc-b", "kc-c"),
            record.toKnowledgeNodeEntities().map { it.knowledgeNodeId },
        )
        assertEquals(
            listOf("AVOIDANCE_SIGNAL", "RECENT_LAPSE", "WEAK_KNOWLEDGE"),
            record.toReasonEntities().map { it.reason },
        )
    }

    @Test
    fun reviewPlanAggregateToRecordSortsQueueAndResolvesSessionHeads() {
        val plan = reviewPlanEntity()
        val lateSession = reviewSessionEntity(
            status = StudyDbValue.ReviewStatus.COMPLETED,
            lastActiveAtEpochMillis = 9_000L,
            stateVersion = 1,
        )
        val activeSession = reviewSessionEntity(
            status = StudyDbValue.ReviewStatus.IN_PROGRESS,
            lastActiveAtEpochMillis = 1_000L,
            stateVersion = 2,
        )
        val aggregate = ReviewPlanAggregate(
            plan = plan,
            queue = listOf(
                ReviewQueueAggregate(
                    item = reviewQueueItemEntity(ordinal = 2, itemId = "q-2"),
                    knowledgeNodes = listOf(ReviewQueueKnowledgeNodeEntity("q-2", "kc-2")),
                    reasons = listOf(ReviewQueueReasonEntity("q-2", "WEAK_KNOWLEDGE")),
                ),
                ReviewQueueAggregate(
                    item = reviewQueueItemEntity(ordinal = 1, itemId = "q-1"),
                    knowledgeNodes = listOf(ReviewQueueKnowledgeNodeEntity("q-1", "kc-1")),
                    reasons = listOf(ReviewQueueReasonEntity("q-1", "RECENT_LAPSE")),
                ),
            ),
            sessions = listOf(lateSession, activeSession),
            currentSlots = emptyList(),
        )

        val bundle = aggregate.toRecord()
        assertEquals(listOf("q-1", "q-2"), bundle.queue.map { it.reviewQueueItemId })
        assertEquals(false, bundle.isCurrent)
        // activeSession comes from the IN_PROGRESS session, not from currentSlots.
        assertEquals(activeSession.reviewSessionId, bundle.activeSession?.reviewSessionId)
        // latestSessionHead prefers the in-progress session over recency.
        assertEquals(activeSession.reviewSessionId, bundle.latestSession?.reviewSessionId)

        val withoutActive = aggregate.copy(sessions = listOf(lateSession))
        val endedBundle = withoutActive.toRecord()
        assertEquals(null, endedBundle.activeSession)
        assertEquals(lateSession.reviewSessionId, endedBundle.latestSession?.reviewSessionId)

        val withActiveSlot = aggregate.copy(
            currentSlots = listOf(
                ActiveReviewPlanSlotEntity(
                    learnerId = "learner-1",
                    localDayEpochDay = 20_000L,
                    timeZoneId = "Asia/Shanghai",
                    currentReviewPlanId = plan.reviewPlanId,
                    updatedAtEpochMillis = 1_000L,
                ),
            ),
        )
        val activeBundle = withActiveSlot.toRecord()
        assertEquals(true, activeBundle.isCurrent)
        assertEquals(activeSession.reviewSessionId, activeBundle.activeSession?.reviewSessionId)
        // An in-progress session is also the latest head.
        assertEquals(activeSession.reviewSessionId, activeBundle.latestSession?.reviewSessionId)
    }

    @Test
    fun mistakeRowToRecordDecodesTrimsDeduplicatesAndSortsLabels() {
        val row = MistakeRow(
            entryId = "entry-1",
            problemId = "problem-1",
            problemRevisionId = "rev-1",
            practiceUnitId = "unit-1",
            sourceKey = "source-1",
            subject = "MATH",
            title = "题目",
            problemMarkdown = "# 题目",
            status = "ACTIVE",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 2_000L,
            estimatedSeconds = 180,
            nextReviewAtEpochMillis = 3_000L,
            retrievability = 0.9,
            knowledgeNodeIds = "\u001Fkc-c\u001Fkc-a \u001Fkc-a\u001F",
            chapterLabels = null,
            knowledgeLabels = "\u001F函数\u001F",
            captureOccurrenceCount = 0,
        )
        val record = row.toRecord()
        assertEquals(listOf("kc-a", "kc-c"), record.knowledgeNodeIds.toList())
        assertEquals(emptyList<String>(), record.chapterLabels)
        assertEquals(listOf("函数"), record.knowledgeLabels.toList())
        // Zero/negative occurrence counts clamp to a single capture.
        assertEquals(1, record.captureOccurrenceCount)
    }

    @Test
    fun canonicalSourceAssetRowToRecordCarriesEveryField() {
        val row = CanonicalSourceAssetRow(
            sourceAssetId = "asset-1",
            contentSha256 = "abc",
            relativePath = "assets/asset-1",
            mimeType = "image/jpeg",
            byteSize = 10L,
            width = 100,
            height = 200,
            sourceType = "CAMERA",
            createdAtEpochMillis = 1_000L,
        )
        val record = row.toRecord()
        assertEquals("asset-1", record.sourceAssetId)
        assertEquals("abc", record.contentSha256)
        assertEquals("assets/asset-1", record.relativePath)
        assertEquals("image/jpeg", record.mimeType)
        assertEquals(10L, record.byteSize)
        assertEquals(100, record.width)
        assertEquals(200, record.height)
        assertEquals("CAMERA", record.sourceType)
        assertEquals(1_000L, record.createdAtEpochMillis)
    }

    @Test
    fun teachingAdvisoryRoundTripsLosslessly() {
        val record = TeachingAdvisoryRecord(
            advisoryId = "adv-1",
            learnerId = "learner-1",
            practiceUnitId = "unit-1",
            knowledgeNodeId = "kc-1",
            advisoryKind = "HINT",
            payloadMarkdown = "提示",
            confidence = 0.8,
            sourceId = "source-1",
            createdAtEpochMillis = 1_000L,
        )
        val entity: LlmTeachingAdvisoryEntity = record.toEntity()
        assertEquals(record, entity.toRecord())
    }

    @Test
    fun expectedWorkspaceToConsumeCommandCarriesExactIdentity() {
        val expected = ExpectedProblemDraftEditWorkspace(
            draftId = "draft-1",
            basisRevisionNumber = 3,
            workspaceVersion = 5,
            workspaceFingerprint = "fp-1",
            finalRequestId = "request-1",
            finalOccurredAtEpochMillis = 7_000L,
        )
        val command = expected.toConsumeCommand()
        assertEquals("draft-1", command.draftId)
        assertEquals(3, command.basisRevisionNumber)
        assertEquals(5, command.expectedWorkspaceVersion)
        assertEquals("fp-1", command.expectedWorkspaceFingerprint)
    }

    private fun reviewSessionRecord(
        status: String,
    ) = ReviewSessionRecord(
        reviewSessionId = "session-1",
        reviewPlanId = "plan-1",
        status = status,
        startedAtEpochMillis = 1_000L,
        lastActiveAtEpochMillis = 2_000L,
        completedAtEpochMillis = null,
        currentOrdinal = 3,
        timeBudgetSeconds = 600,
        projectionCheckpoint = 7L,
        stateVersion = 4,
    )

    private fun reviewSessionEntity(
        status: String,
        lastActiveAtEpochMillis: Long,
        stateVersion: Long,
    ) = ReviewSessionEntity(
        reviewSessionId = if (status == StudyDbValue.ReviewStatus.IN_PROGRESS) "session-active" else "session-late",
        reviewPlanId = "plan-1",
        status = status,
        activeSessionKey = null,
        startedAtEpochMillis = 1_000L,
        lastActiveAtEpochMillis = lastActiveAtEpochMillis,
        completedAtEpochMillis = null,
        currentOrdinal = 1,
        timeBudgetSeconds = 600,
        projectionCheckpoint = 7L,
        stateVersion = stateVersion,
    )

    private fun reviewPlanEntity() = ReviewPlanEntity(
        reviewPlanId = "plan-1",
        learnerId = "learner-1",
        localDate = "2026-09-06",
        localDayEpochDay = 20_000L,
        timeZoneId = "Asia/Shanghai",
        timeBudgetSeconds = 600,
        planningAtEpochMillis = 1_000L,
        status = "ACTIVE",
        plannerVersion = "v2",
        projectionCheckpoint = 7L,
        inputFingerprint = "in-fp",
        planFingerprint = "plan-fp",
        planRevision = 1,
        createdAtEpochMillis = 1_000L,
    )

    private fun reviewQueueItemEntity(
        ordinal: Int,
        itemId: String,
    ) = ReviewQueueItemEntity(
        reviewQueueItemId = itemId,
        reviewPlanId = "plan-1",
        practiceUnitId = "unit-$ordinal",
        itemFamilyId = "",
        sourceBundleId = null,
        ordinal = ordinal,
        priorityScore = 0.5,
        difficultyBand = "",
        dueAtEpochMillis = 2_000L,
        estimatedSeconds = 180,
        reasonSnapshot = "",
        status = "PENDING",
    )

    private fun reviewQueueRecord(
        knowledgeNodeIds: Set<String>,
        reasons: Set<String>,
    ) = ReviewQueueItemRecord(
        reviewQueueItemId = "q-1",
        reviewPlanId = "plan-1",
        practiceUnitId = "unit-1",
        knowledgeNodeIds = knowledgeNodeIds,
        itemFamilyId = "",
        sourceBundleId = null,
        reasons = reasons,
        ordinal = 1,
        priorityScore = 0.5,
        difficultyBand = "",
        dueAtEpochMillis = 2_000L,
        estimatedSeconds = 180,
        reasonSnapshot = "",
        status = "PENDING",
    )
}
