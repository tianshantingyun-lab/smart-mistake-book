package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeResearchReviewBundleEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeResearchReviewSourceEntity

internal class RoomKnowledgeResearchReviewStore(
    private val database: StudyDatabase,
) {
    suspend fun enqueue(bundle: KnowledgeResearchReviewBundleRecord) {
        KnowledgeResearchReviewContract.validate(bundle)
        database.knowledgeResearchReviewDao().enqueue(
            bundle = bundle.toEntity(),
            sources = bundle.sources.map { source -> source.toEntity(bundle.bundleId) },
        )
    }

    suspend fun readPending(limit: Int): List<KnowledgeResearchReviewBundleRecord> {
        require(limit in 1..256) { "Knowledge research review limit must be in 1..256" }
        return database.withReadTransaction {
            val bundles = database.knowledgeResearchReviewDao().readPendingBundles(limit)
            if (bundles.isEmpty()) return@withReadTransaction emptyList()
            val sourcesByBundleId = database.knowledgeResearchReviewDao()
                .readSourcesForBundles(bundles.mapTo(mutableSetOf()) { it.bundleId })
                .groupBy(KnowledgeResearchReviewSourceEntity::bundleId)
            bundles.map { bundle ->
                bundle.toRecord(sources = sourcesByBundleId[bundle.bundleId].orEmpty())
            }
        }
    }

    suspend fun decide(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord = database.withWriteTransaction {
        val dao = database.knowledgeResearchReviewDao()
        val entity = dao.readBundle(command.bundleId)
            ?: throw DatabaseContractViolationException(
                "Knowledge research review bundle does not exist",
            )
        val current = entity.toRecord(dao.readSources(command.bundleId))
        KnowledgeResearchReviewContract.validateDecision(command, current)
        if (current.matches(command)) return@withWriteTransaction current
        if (current.status != StudyDbValue.KnowledgeResearchReviewStatus.PENDING_REVIEW) {
            throw ImmutablePayloadConflictException(
                entityType = "knowledge research review decision",
                entityId = command.bundleId,
            )
        }
        val changed = dao.decidePending(
            bundleId = command.bundleId,
            decisionStatus = command.decisionStatus,
            reviewerReference = command.reviewerReference,
            decisionNote = command.decisionNote,
            decidedAtEpochMillis = command.decidedAtEpochMillis,
        )
        if (changed != 1) {
            throw ImmutablePayloadConflictException(
                entityType = "knowledge research review decision",
                entityId = command.bundleId,
            )
        }
        checkNotNull(dao.readBundle(command.bundleId))
            .toRecord(dao.readSources(command.bundleId))
    }

    suspend fun read(bundleId: String): KnowledgeResearchReviewBundleRecord? {
        val dao = database.knowledgeResearchReviewDao()
        val entity = dao.readBundle(bundleId) ?: return null
        return entity.toRecord(dao.readSources(bundleId))
    }

    suspend fun markApplied(
        bundleId: String,
        packFingerprint: String,
        appliedAtEpochMillis: Long,
    ): KnowledgeResearchReviewBundleRecord {
        val changed = database.knowledgeResearchReviewDao().markApprovedAsApplied(
            bundleId = bundleId,
            packFingerprint = packFingerprint,
            appliedAtEpochMillis = appliedAtEpochMillis,
        )
        if (changed != 1) {
            throw ImmutablePayloadConflictException(
                entityType = "approved knowledge research pack",
                entityId = bundleId,
            )
        }
        return checkNotNull(read(bundleId))
    }
}

private fun KnowledgeResearchReviewBundleRecord.toEntity() =
    KnowledgeResearchReviewBundleEntity(
        bundleId = bundleId,
        groundingKey = groundingKey,
        subject = subject,
        query = query,
        expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
        relatedQuestionCount = relatedQuestionCount,
        workflowVersion = workflowVersion,
        status = status,
        reviewerReference = reviewerReference,
        decisionNote = decisionNote,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
        appliedPackFingerprint = appliedPackFingerprint,
        appliedAtEpochMillis = appliedAtEpochMillis,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

private fun KnowledgeResearchReviewSourceRecord.toEntity(
    bundleId: String,
) = KnowledgeResearchReviewSourceEntity(
    bundleId = bundleId,
    sourceOrdinal = sourceOrdinal,
    canonicalSourceUri = canonicalSourceUri,
    title = title,
    publisher = publisher,
    sourceType = sourceType,
    licenseStatus = licenseStatus,
    searchRank = searchRank,
    contentType = contentType,
    contentLengthBytes = contentLengthBytes,
    contentFingerprint = contentFingerprint,
    verifiedAtEpochMillis = verifiedAtEpochMillis,
)

private fun KnowledgeResearchReviewBundleEntity.toRecord(
    sources: List<KnowledgeResearchReviewSourceEntity>,
) = KnowledgeResearchReviewBundleRecord(
    bundleId = bundleId,
    groundingKey = groundingKey,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    relatedQuestionCount = relatedQuestionCount,
    workflowVersion = workflowVersion,
    status = status,
    reviewerReference = reviewerReference,
    decisionNote = decisionNote,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
    appliedPackFingerprint = appliedPackFingerprint,
    appliedAtEpochMillis = appliedAtEpochMillis,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    sources = sources.map(KnowledgeResearchReviewSourceEntity::toRecord),
)

private fun KnowledgeResearchReviewSourceEntity.toRecord() =
    KnowledgeResearchReviewSourceRecord(
        sourceOrdinal = sourceOrdinal,
        canonicalSourceUri = canonicalSourceUri,
        title = title,
        publisher = publisher,
        sourceType = sourceType,
        licenseStatus = licenseStatus,
        searchRank = searchRank,
        contentType = contentType,
        contentLengthBytes = contentLengthBytes,
        contentFingerprint = contentFingerprint,
        verifiedAtEpochMillis = verifiedAtEpochMillis,
    )

private fun KnowledgeResearchReviewBundleRecord.matches(
    command: DecideKnowledgeResearchReviewBundleCommand,
): Boolean =
    (
        status == command.decisionStatus ||
            (
                status == StudyDbValue.KnowledgeResearchReviewStatus.APPLIED &&
                    command.decisionStatus ==
                    StudyDbValue.KnowledgeResearchReviewStatus.APPROVED
                )
        ) &&
        reviewerReference == command.reviewerReference &&
        decisionNote == command.decisionNote &&
        reviewedAtEpochMillis == command.decidedAtEpochMillis
