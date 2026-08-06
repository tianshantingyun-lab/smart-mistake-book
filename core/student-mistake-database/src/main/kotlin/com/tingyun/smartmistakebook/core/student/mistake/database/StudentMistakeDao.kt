package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class StudentMistakeDao : StudentAtomicCaptureWriteDao() {

    @Query(
        """
        SELECT COALESCE(MAX(change_version), 0)
        FROM student_learner_change
        WHERE learner_id = :learnerId
        """,
    )
    abstract fun observeChangeVersion(learnerId: String): Flow<Long>

}
