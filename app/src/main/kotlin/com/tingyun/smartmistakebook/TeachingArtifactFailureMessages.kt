package com.tingyun.smartmistakebook

/**
 * 题干读取失败时给用户的说明（审计 N-12）。
 *
 * 为什么单独是一个纯函数：它是这条失败**唯一**面向用户的解释。此前这条路径与另外两种
 * 完全不同的处境共用一个出口——
 *
 * | 处境 | 该说的话 |
 * |---|---|
 * | 还在读 | 「正在读取题目…」 |
 * | **读的时候出错了** | **本函数** |
 * | 读完了，但这道题没有会话/条目（实拍题的正常情形之一） | 「当前题目暂时不可用，未记录本次作答。」 |
 *
 * 前两者原先都落进第一行：`produceState` 里 `repository.teachingArtifact(...)` 一旦抛异常，
 * `isLoaded` 就永远停在 `false`，界面**永远**停在「正在读取题目…」——它既不说出错，也不让用户
 * 做任何事。说错原因比不说更糟（模式 E：缺省值冒充真实信号），而"一直在读"是最像'没事'的那种错法。
 *
 * 文案里给出**下一步**：这条通道没有重试按钮（[ReviewSessionGateMessage] 只渲染一句话），
 * 所以必须用文字说清"返回再进来就是重试"。
 */
internal fun teachingArtifactFailureMessage(diagnosticId: String): String =
    "这道题的题干没能读出来，这次没有记录作答。返回后重新进入这道题再试一次。（$diagnosticId）"

/** 题干读取失败的诊断编号。前缀与启动期那几条（`startup:*`）不同类，便于一眼区分出处。 */
internal fun teachingArtifactFailureDiagnosticId(failure: Throwable): String =
    "review:artifact:${failure.hashCode().toUInt()}"
