package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.VisualInteractionAttemptEntity

/**
 * DAO for the visual-interaction attempt audit trail (audit PR-11).
 * Attempts are append-only; the teaching flow queries them back per
 * problem revision to close the interaction loop.
 */
@Dao
internal interface VisualInteractionAttemptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttempt(attempt: VisualInteractionAttemptEntity)

    @Query(
        """
        SELECT * FROM visual_interaction_attempt
        WHERE problem_revision_id = :problemRevisionId
        ORDER BY attempted_at_epoch_millis ASC
        """,
    )
    suspend fun findForProblemRevision(
        problemRevisionId: String,
    ): List<VisualInteractionAttemptEntity>

    @Query("SELECT COUNT(*) FROM visual_interaction_attempt")
    suspend fun countAttempts(): Int
}
