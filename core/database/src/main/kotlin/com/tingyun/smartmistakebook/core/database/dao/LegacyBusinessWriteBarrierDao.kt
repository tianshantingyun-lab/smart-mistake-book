package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import com.tingyun.smartmistakebook.core.database.entity.LegacyBusinessWriteBarrierEntity

@Dao
internal interface LegacyBusinessWriteBarrierDao {
    @Query(
        """
        SELECT *
        FROM legacy_business_write_barrier
        WHERE barrier_key = :barrierKey
        """,
    )
    suspend fun read(barrierKey: String): LegacyBusinessWriteBarrierEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LegacyBusinessWriteBarrierEntity): Long
}
