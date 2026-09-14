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
        // 只有知识包失败有本轮内的重试动作（重新安装）；恢复回滚/隔离的
        // 出路是用户到存储页主动重新恢复备份，横幅上的"重试"只会空转。
        is StartupState.RecoverableFailure -> errorCategory == StartupErrorCategory.KNOWLEDGE_BASE
        is StartupState.FatalFailure -> false
        StartupState.Initializing,
        StartupState.Ready,
        -> false
    }
