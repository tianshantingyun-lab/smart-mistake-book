package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatEvidenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<LearnerChatEvidenceEntity>)

    @Query("SELECT * FROM learner_chat_evidence WHERE conversation_id = :conversationId ORDER BY created_at_epoch_millis")
    suspend fun readByConversation(conversationId: String): List<LearnerChatEvidenceEntity>

    @Query("SELECT * FROM learner_chat_evidence WHERE learner_id = :learnerId ORDER BY created_at_epoch_millis DESC LIMIT :limit")
    fun observeRecent(learnerId: String, limit: Int): Flow<List<LearnerChatEvidenceEntity>>

    @Query("DELETE FROM learner_chat_evidence WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: Int): Int
}
