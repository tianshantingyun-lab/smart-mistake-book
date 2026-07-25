package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeResearchReviewBundleRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeResearchReviewSourceRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.KnowledgeResearchReviewBundle
import com.tingyun.smartmistakebook.core.domain.KnowledgeResearchReviewQueue

fun createRoomKnowledgeResearchReviewQueue(
    database: StudyDatabasePort,
): KnowledgeResearchReviewQueue = RoomKnowledgeResearchReviewQueue(database)

internal class RoomKnowledgeResearchReviewQueue(
    private val database: StudyDatabasePort,
) : KnowledgeResearchReviewQueue {
    override suspend fun enqueue(bundle: KnowledgeResearchReviewBundle) {
        database.enqueueKnowledgeResearchReviewBundle(bundle.toRecord())
    }
}

private fun KnowledgeResearchReviewBundle.toRecord() = KnowledgeResearchReviewBundleRecord(
    bundleId = bundleId,
    groundingKey = query.groundingKey,
    subject = query.subject.name,
    query = query.query,
    expectedParentKnowledgeDisplayName = query.expectedParentKnowledgeDisplayName,
    relatedQuestionCount = query.relatedQuestionCount,
    workflowVersion = workflowVersion,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = createdAtEpochMillis,
    sources = sources.mapIndexed { index, source ->
        KnowledgeResearchReviewSourceRecord(
            sourceOrdinal = index,
            canonicalSourceUri = source.candidate.canonicalSourceUri,
            title = source.candidate.title,
            publisher = source.candidate.publisher,
            sourceType = source.candidate.sourceType.name,
            licenseStatus = source.candidate.licenseStatus.name,
            searchRank = source.candidate.searchRank,
            contentType = source.contentType,
            contentLengthBytes = source.contentLengthBytes,
            contentFingerprint = source.contentFingerprint,
            verifiedAtEpochMillis = source.verifiedAtEpochMillis,
        )
    },
)
