package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession

internal sealed interface TutorSessionUiState {
    data object Loading : TutorSessionUiState
    data class Ready(val session: ConfirmedTutorSession) : TutorSessionUiState
    data object Missing : TutorSessionUiState
    data object Unavailable : TutorSessionUiState
}
