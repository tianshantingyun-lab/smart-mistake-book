package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchIndexStateEntity

/**
 * 别名倒排索引版本锚点的读写面。见 [KnowledgeSearchIndexStateEntity] 的语义说明。
 */
@Dao
internal interface KnowledgeSearchIndexStateDao {
    /** 返回 null = 该科从未按任何已知版本锚定过（legacy 或全新库）。 */
    @Query("SELECT index_version FROM knowledge_search_index_state WHERE subject = :subject")
    suspend fun readVersion(subject: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(state: KnowledgeSearchIndexStateEntity)
}
