package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Shadow presentation/problem-family budget fingerprint page primitives.
 */
@Dao
internal abstract class LearnerMasteryShadowBudgetDao : LearnerMasteryProjectionGenerationDao() {
    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
        ORDER BY learner_id, presentation_id, subject, knowledge_node_id,
                 taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readFirstShadowPresentationBudgetFingerprintPage(
        generationId: Long,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction > :direction
        ORDER BY direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowPresentationBudgetsAfterDirection(
        generationId: Long,
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version > :taxonomyVersion
        ORDER BY taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowPresentationBudgetsAfterTaxonomy(
        generationId: Long,
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject = :subject
          AND knowledge_node_id > :knowledgeNodeId
        ORDER BY knowledge_node_id, taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowPresentationBudgetsAfterKnowledgeNode(
        generationId: Long,
        learnerId: String,
        presentationId: String,
        subject: String,
        knowledgeNodeId: String,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND presentation_id = :presentationId
          AND subject > :subject
        ORDER BY subject, knowledge_node_id, taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowPresentationBudgetsAfterSubject(
        generationId: Long,
        learnerId: String,
        presentationId: String,
        subject: String,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND presentation_id > :presentationId
        ORDER BY presentation_id, subject, knowledge_node_id, taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowPresentationBudgetsAfterPresentation(
        generationId: Long,
        learnerId: String,
        presentationId: String,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id > :learnerId
        ORDER BY learner_id, presentation_id, subject, knowledge_node_id,
                 taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowPresentationBudgetsAfterLearner(
        generationId: Long,
        learnerId: String,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity>

    protected open suspend fun readShadowPresentationBudgetFingerprintPage(
        generationId: Long,
        afterExclusive: String?,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetShadowEntity> {
        require(limit > 0) { "Directional budget fingerprint page limit must be positive" }
        val cursor = afterExclusive?.decodeDirectionalBudgetCursor()
            ?: return readFirstShadowPresentationBudgetFingerprintPage(generationId, limit)
        val rows = ArrayList<MasteryPresentationNodeBudgetShadowEntity>(limit)
        fun append(page: List<MasteryPresentationNodeBudgetShadowEntity>) {
            rows.addAll(page.take(limit - rows.size))
        }
        append(
            readShadowPresentationBudgetsAfterDirection(
                generationId,
                cursor.learnerId,
                cursor.secondaryId,
                cursor.subject,
                cursor.knowledgeNodeId,
                cursor.taxonomyVersion,
                cursor.direction,
                limit,
            ),
        )
        if (rows.size < limit) {
            append(
                readShadowPresentationBudgetsAfterTaxonomy(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    cursor.subject,
                    cursor.knowledgeNodeId,
                    cursor.taxonomyVersion,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowPresentationBudgetsAfterKnowledgeNode(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    cursor.subject,
                    cursor.knowledgeNodeId,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowPresentationBudgetsAfterSubject(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    cursor.subject,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowPresentationBudgetsAfterPresentation(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowPresentationBudgetsAfterLearner(
                    generationId,
                    cursor.learnerId,
                    limit - rows.size,
                ),
            )
        }
        return rows
    }

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
        ORDER BY learner_id, problem_family_fingerprint, subject, knowledge_node_id,
                 taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readFirstShadowProblemFamilyBudgetFingerprintPage(
        generationId: Long,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version = :taxonomyVersion
          AND direction > :direction
        ORDER BY direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowProblemFamilyBudgetsAfterDirection(
        generationId: Long,
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        direction: String,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject = :subject
          AND knowledge_node_id = :knowledgeNodeId
          AND taxonomy_version > :taxonomyVersion
        ORDER BY taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowProblemFamilyBudgetsAfterTaxonomy(
        generationId: Long,
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject = :subject
          AND knowledge_node_id > :knowledgeNodeId
        ORDER BY knowledge_node_id, taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowProblemFamilyBudgetsAfterKnowledgeNode(
        generationId: Long,
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        knowledgeNodeId: String,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND problem_family_fingerprint = :problemFamilyFingerprint
          AND subject > :subject
        ORDER BY subject, knowledge_node_id, taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowProblemFamilyBudgetsAfterSubject(
        generationId: Long,
        learnerId: String,
        problemFamilyFingerprint: String,
        subject: String,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id = :learnerId
          AND problem_family_fingerprint > :problemFamilyFingerprint
        ORDER BY problem_family_fingerprint, subject, knowledge_node_id,
                 taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowProblemFamilyBudgetsAfterFamily(
        generationId: Long,
        learnerId: String,
        problemFamilyFingerprint: String,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    @Query(
        """
        SELECT * FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
          AND learner_id > :learnerId
        ORDER BY learner_id, problem_family_fingerprint, subject, knowledge_node_id,
                 taxonomy_version, direction
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readShadowProblemFamilyBudgetsAfterLearner(
        generationId: Long,
        learnerId: String,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity>

    protected open suspend fun readShadowProblemFamilyBudgetFingerprintPage(
        generationId: Long,
        afterExclusive: String?,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetShadowEntity> {
        require(limit > 0) { "Directional budget fingerprint page limit must be positive" }
        val cursor = afterExclusive?.decodeDirectionalBudgetCursor()
            ?: return readFirstShadowProblemFamilyBudgetFingerprintPage(generationId, limit)
        val rows = ArrayList<MasteryProblemFamilyNodeBudgetShadowEntity>(limit)
        fun append(page: List<MasteryProblemFamilyNodeBudgetShadowEntity>) {
            rows.addAll(page.take(limit - rows.size))
        }
        append(
            readShadowProblemFamilyBudgetsAfterDirection(
                generationId,
                cursor.learnerId,
                cursor.secondaryId,
                cursor.subject,
                cursor.knowledgeNodeId,
                cursor.taxonomyVersion,
                cursor.direction,
                limit,
            ),
        )
        if (rows.size < limit) {
            append(
                readShadowProblemFamilyBudgetsAfterTaxonomy(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    cursor.subject,
                    cursor.knowledgeNodeId,
                    cursor.taxonomyVersion,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowProblemFamilyBudgetsAfterKnowledgeNode(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    cursor.subject,
                    cursor.knowledgeNodeId,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowProblemFamilyBudgetsAfterSubject(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    cursor.subject,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowProblemFamilyBudgetsAfterFamily(
                    generationId,
                    cursor.learnerId,
                    cursor.secondaryId,
                    limit - rows.size,
                ),
            )
        }
        if (rows.size < limit) {
            append(
                readShadowProblemFamilyBudgetsAfterLearner(
                    generationId,
                    cursor.learnerId,
                    limit - rows.size,
                ),
            )
        }
        return rows
    }

}
