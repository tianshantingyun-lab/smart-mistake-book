package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.dao.ChatEvidenceAggregateRow
import com.tingyun.smartmistakebook.core.database.dao.IndependentCorrectAggregateRow
import com.tingyun.smartmistakebook.core.database.dao.IndependentErrorAggregateRow
import com.tingyun.smartmistakebook.core.database.dao.SubjectMasteryRow
import com.tingyun.smartmistakebook.core.database.port.MasteryAggregateRecord
import com.tingyun.smartmistakebook.core.database.port.SubjectMasteryRecord

/**
 * Knowledge-node-grained mastery reads for the tutor's `MASTERY_READ` tool.
 *
 * Split out of [RoomStudyDatabase] the same way the other multi-step reads are:
 * the facade stays a delegation surface, and the assembly logic (three
 * aggregate queries folded onto one node id set) lives somewhere it can be read
 * on its own.
 */
internal class RoomMasteryOverviewStore(
    private val database: StudyDatabase,
) {
    suspend fun readSubjectMastery(
        learnerId: String,
        subject: String,
    ): List<SubjectMasteryRecord> {
        require(learnerId.isNotBlank()) { "Mastery overview needs a learner id" }
        require(subject.isNotBlank()) { "Mastery overview needs a subject" }
        return database.masteryOverviewDao().readSubjectMastery(learnerId, subject)
            .map(SubjectMasteryRow::toRecord)
    }

    /**
     * Folds the three per-node aggregate queries onto one record per requested
     * node. A node with no rows in a source still gets a record with zeros: the
     * caller asked about that node, and "no independent errors recorded" is an
     * answer, not a missing row.
     */
    suspend fun readMasteryAggregates(
        learnerId: String,
        knowledgeNodeIds: Set<String>,
    ): List<MasteryAggregateRecord> {
        require(learnerId.isNotBlank()) { "Mastery aggregates need a learner id" }
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        require(knowledgeNodeIds.none(String::isBlank)) {
            "Mastery aggregate node ids must not be blank"
        }
        val dao = database.masteryOverviewDao()
        // Sorted so the IN-list bind order is stable across calls: the same set
        // must produce the same SQL, which keeps the query cacheable and the
        // results comparable between runs.
        val ids = knowledgeNodeIds.sorted()
        val independentCorrect = dao.readIndependentCorrectAggregates(learnerId, ids)
            .associateBy(IndependentCorrectAggregateRow::knowledgeNodeId)
        val independentErrors = dao.readIndependentErrorAggregates(learnerId, ids)
            .associateBy(IndependentErrorAggregateRow::knowledgeNodeId)
        val modelEvidence = dao.readChatEvidenceAggregates(learnerId, ids)
            .associateBy(ChatEvidenceAggregateRow::knowledgeNodeId)
        return ids.map { nodeId ->
            val correct = independentCorrect[nodeId]
            val errors = independentErrors[nodeId]
            val evidence = modelEvidence[nodeId]
            MasteryAggregateRecord(
                knowledgeNodeId = nodeId,
                independentCorrectCount = correct?.observationCount ?: 0,
                independentCorrectItemFamilyCount = correct?.itemFamilyCount ?: 0,
                independentCorrectStudyDayCount = correct?.studyDayCount ?: 0,
                lastIndependentCorrectAtEpochMillis = correct?.latestAtEpochMillis,
                independentErrorCount = errors?.errorCount ?: 0,
                lastIndependentErrorAtEpochMillis = errors?.latestAtEpochMillis,
                acceptedModelEvidenceCount = evidence?.acceptedCount ?: 0,
                rejectedModelEvidenceCount = evidence?.rejectedCount ?: 0,
                lastAcceptedModelEvidenceAtEpochMillis = evidence?.latestAcceptedAtEpochMillis,
            )
        }
    }

    suspend fun countReviewableKnowledgeNodes(subject: String): Int {
        require(subject.isNotBlank()) { "Knowledge node count needs a subject" }
        return database.masteryOverviewDao().countReviewableKnowledgeNodes(subject)
    }
}
