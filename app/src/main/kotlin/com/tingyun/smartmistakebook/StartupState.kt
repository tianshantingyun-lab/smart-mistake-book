package com.tingyun.smartmistakebook

sealed interface StartupState {
    data object Initializing : StartupState
    data object Ready : StartupState

    data class RecoverableFailure(
        val title: String,
        val message: String,
        val diagnosticId: String,
        val errorCategory: StartupErrorCategory = StartupErrorCategory.UNKNOWN,
    ) : StartupState

    data class FatalFailure(
        val title: String,
        val message: String,
        val diagnosticId: String,
        val errorCategory: StartupErrorCategory = StartupErrorCategory.DATABASE,
    ) : StartupState
}

enum class StartupErrorCategory {
    DATABASE,
    KNOWLEDGE_BASE,
    PROVIDER,
    KEYSTORE,
    UNKNOWN,
}

val StartupState.isRetryable: Boolean
    get() = when (this) {
        is StartupState.RecoverableFailure -> true
        is StartupState.FatalFailure -> false
        StartupState.Initializing,
        StartupState.Ready,
        -> false
    }
