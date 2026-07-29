package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class TutorExplanationModeSnapshot(
    val mode: TutorExplanationMode,
    val modeVersion: Long,
) {
    init {
        require(modeVersion >= 0L) { "modeVersion must be non-negative" }
    }
}

interface TutorSettingsRepository {
    val modeSnapshot: Flow<TutorExplanationModeSnapshot>

    val mode: Flow<TutorExplanationMode>
        get() = modeSnapshot
            .map { snapshot -> snapshot.mode }
            .distinctUntilChanged()

    suspend fun currentModeSnapshot(): TutorExplanationModeSnapshot = modeSnapshot.first()

    suspend fun currentMode(): TutorExplanationMode = currentModeSnapshot().mode

    suspend fun setMode(mode: TutorExplanationMode)
}
