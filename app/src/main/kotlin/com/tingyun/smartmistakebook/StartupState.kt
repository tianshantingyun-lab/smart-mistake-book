package com.tingyun.smartmistakebook

sealed interface StartupState {
    data object Initializing : StartupState
    data object Ready : StartupState

    data class RecoverableFailure(
        val title: String,
        val message: String,
        val diagnosticId: String,
        val errorCategory: StartupErrorCategory = StartupErrorCategory.UNKNOWN,
        /**
         * 界面是否提供「重试」。默认 `true` = 拆分前的行为。
         *
         * 消灭的失败：确定性失败（账本有缺口、账本超出重放上界）也带一个「重试」按钮，
         * 按下去只是把同一条错误再显示一次——而文案正在说「重试不会改变结果」。
         * 这两类失败的输入在同一版本内不会变（账本只追加、不裁剪），所以重试无意义。
         */
        val retryable: Boolean = true,
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
        is StartupState.RecoverableFailure -> retryable
        is StartupState.FatalFailure -> false
        StartupState.Initializing,
        StartupState.Ready,
        -> false
    }
