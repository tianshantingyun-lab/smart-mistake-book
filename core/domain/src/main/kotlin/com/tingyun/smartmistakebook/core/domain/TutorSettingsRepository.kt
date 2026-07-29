package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val mode: Flow<TutorExplanationMode>

    val modeSnapshot: Flow<TutorExplanationModeSnapshot>
        get() = mode
            .map { mode -> TutorExplanationModeSnapshot(mode = mode, modeVersion = 0L) }
            .distinctUntilChanged()

    suspend fun currentMode(): TutorExplanationMode

    suspend fun currentModeSnapshot(): TutorExplanationModeSnapshot =
        TutorExplanationModeSnapshot(mode = currentMode(), modeVersion = 0L)

    suspend fun setMode(mode: TutorExplanationMode)
}
