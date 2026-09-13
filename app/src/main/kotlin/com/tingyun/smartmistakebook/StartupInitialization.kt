package com.tingyun.smartmistakebook

import kotlinx.coroutines.CancellationException

/**
 * 启动期两段后台初始化的**归属**：哪一段失败，就说哪一段的话（审计 N-02）。
 *
 * 消灭的失败：`BundledKnowledgeBaseInstaller.install` 与 `studyRepository.initialize()`
 * 原先合用一个 `try`／`catch`，于是任何投影或账本失败都会被说成「本地知识包尚未准备好」，
 * 并附上一句「错题和复习可以继续使用」——而投影失败的含义恰恰是学习进度没有更新。
 *
 * **为什么抽成一个函数**：`StartupFailureMessages` 里的两个纯函数只钉住「每类失败说什么」，
 * 钉不住「哪个 catch 用哪句」。把两段拆开写在 `Application.onCreate` 里的话，谁把它们合回去，
 * 五条文案断言会全绿——因为文案本身没变。归属正是 N-02 的实质，所以它必须自己可测。
 *
 * **行为**
 * - 知识包失败后**仍然**尝试投影初始化。这是**本次唯一的刻意行为改动**：HEAD 上两句共用一个
 *   `try`，`install()` 一抛就永远走不到 `initialize()`——知识包安装失败会连带冻住学习进度，
 *   而界面只说「自动分类会暂缓」，把严重性说反了。这正是 N-02 要消除的假耦合。
 *   代价写在这里：两段都失败时只有投影那条会显示（横幅一次只显示一条）。
 * - 两段都失败时以投影失败为准——学习进度停滞比自动分类暂缓更严重。
 *   这条在 HEAD 上无从发生（知识包一失败就没有第二段），是本次新增的组合。
 * - 取消永远向上抛，绝不被转换成一条"失败态"。
 * - 全部成功时返回 `null`，**不写**任何状态（启动态此时已是 `Ready`，
 *   重写一次 `Ready` 是空操作；而投影成功也不得抹掉先前那条知识包失败）。
 *
 * @param logFailure 记录一次失败。生产里写 logcat；测试里用来确认两个失败没有被合并成一条。
 * @return 需要发布的失败态；两段都成功时为 `null`。
 */
internal suspend fun runStartupInitialization(
    installKnowledgeBase: suspend () -> Unit,
    initializeProjection: suspend () -> Unit,
    logFailure: (String, Throwable) -> Unit = { _, _ -> },
): StartupState.RecoverableFailure? {
    val knowledgeBaseState = attempt(LOG_KNOWLEDGE_INSTALL, logFailure, installKnowledgeBase)
        ?.let(::knowledgeBaseFailure)
    val projectionState = attempt(LOG_PROJECTION_INITIALIZE, logFailure, initializeProjection)
        ?.let(::projectionFailure)

    return projectionState ?: knowledgeBaseState
}

/** 跑一段；失败时记一条日志并把异常交回调用方归类，取消原样上抛。 */
private suspend fun attempt(
    description: String,
    logFailure: (String, Throwable) -> Unit,
    step: suspend () -> Unit,
): Throwable? = try {
    step()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    logFailure(description, failure)
    failure
}

/**
 * **重试**：与启动**同一条**初始化，但这一次的结论**无论如何都要发布**（审计 N-21）。
 *
 * 与 [runStartupInitialization] 的差别只有这一条，而它正是那条缺陷的实质：
 * 启动路径成功时不写状态（那时启动态本来就是 `Ready`，而"投影成功不得抹掉知识包失败"），
 * 但用户**按了重试**之后再成功，那条失败就是真的过去了——不收回横幅，等于给了他一个
 * 永远不可能消失的承诺。旧实现还有另一半错：重试调的 `refresh()` 只等于 `initialize()`，
 * **失败的那一步（知识包安装）压根没重跑**。
 *
 * [publish] 收到的一定是"这一次的结论"：一个失败态或 [StartupState.Ready]，没有第三种。
 * 取消向上抛且**不发布**——被取消的尝试不是一次结论。
 */
internal suspend fun runStartupRetry(
    installKnowledgeBase: suspend () -> Unit,
    initializeProjection: suspend () -> Unit,
    publish: (StartupState) -> Unit,
    logFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    val failure = runStartupInitialization(
        installKnowledgeBase = installKnowledgeBase,
        initializeProjection = initializeProjection,
        logFailure = logFailure,
    )
    publish(failure ?: StartupState.Ready)
}

private const val LOG_KNOWLEDGE_INSTALL = "Bundled knowledge install failed"
private const val LOG_PROJECTION_INITIALIZE = "Study projection initialize failed"
