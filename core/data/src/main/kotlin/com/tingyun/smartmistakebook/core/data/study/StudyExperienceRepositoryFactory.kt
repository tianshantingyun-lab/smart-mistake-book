package com.tingyun.smartmistakebook.core.data.study

import android.content.Context
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.StudyExperienceRepository
import kotlinx.coroutines.CoroutineScope

object StudyExperienceRepositoryFactory {
    fun create(
        context: Context,
        applicationScope: CoroutineScope,
    ): StudyExperienceRepository = RoomBackedStudyExperienceRepository(
        database = StudyDatabaseFactory.open(context),
        applicationScope = applicationScope,
        closeDatabaseOnClose = true,
    )

    fun create(
        database: StudyDatabasePort,
        applicationScope: CoroutineScope,
    ): StudyExperienceRepository = RoomBackedStudyExperienceRepository(
        database = database,
        applicationScope = applicationScope,
        closeDatabaseOnClose = false,
    )
}
