package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.flow.Flow

interface TutorSettingsRepository {
    val mode: Flow<TutorExplanationMode>

    suspend fun currentMode(): TutorExplanationMode

    suspend fun setMode(mode: TutorExplanationMode)
}
