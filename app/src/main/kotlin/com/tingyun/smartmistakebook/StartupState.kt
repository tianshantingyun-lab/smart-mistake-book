package com.tingyun.smartmistakebook

sealed interface StartupState {
    data object Initializing : StartupState
    data object Ready : StartupState

    data class RecoverableFailure(
        val title: String,
        val message: String,
        val diagnosticId: String,
    ) : StartupState

    data class FatalFailure(
        val title: String,
        val message: String,
        val diagnosticId: String,
    ) : StartupState
}
