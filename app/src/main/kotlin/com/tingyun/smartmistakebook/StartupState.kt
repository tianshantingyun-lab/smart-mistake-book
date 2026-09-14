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
        // 两条互相独立的判断，合并时必须都在（这是两条线各自裁定的，不是同一件事）：
        //
        // ① 恢复回滚/隔离（`DATABASE`）**没有**本轮内的重试动作：它的出路是用户到存储页
        //    主动重新恢复备份，横幅上的"重试"只会空转（main 那一线的裁定）。
        //    这条必须按**类别**排除，不能只读下面的标志——那两处的 `retryable` 是默认值。
        // ② 其余读**归类处写下的那个标志**：投影失败三类各有各的实话——账本损坏与超出重放
        //    上限重试无用（`retryable = false`），而排空预算用尽**重试就是通路**
        //    （已完成的推进已落库，从断点继续；审计 N-02／N-06）。
        //    所以它挂在调用点上，不挂在类型或类别上（`StartupFailureMessages` 的类注释）。
        is StartupState.RecoverableFailure ->
            errorCategory != StartupErrorCategory.DATABASE && retryable
        is StartupState.FatalFailure -> false
        StartupState.Initializing,
        StartupState.Ready,
        -> false
    }
