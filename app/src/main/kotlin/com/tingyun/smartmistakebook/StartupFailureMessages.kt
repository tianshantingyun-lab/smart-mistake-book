package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.database.LearningLedgerIntegrityException
import com.tingyun.smartmistakebook.core.database.ProjectionDrainBudgetExhaustedException
import com.tingyun.smartmistakebook.core.database.ProjectionReplayLimitExceededException

/*
 * 启动期后台失败的归类与文案。
 *
 * 为什么是独立纯函数：这张映射是**用户唯一能看到的失败说明**。`StartupStateBanner` 只渲染
 * title／message／诊断编号三样（`StartupStateBanner.kt:34-37`），`errorCategory` 全仓没有读取方；
 * 而它原先长在 `Application.onCreate` 的 catch 里，只能靠读代码确认，没有任何测试能钉住它。
 * 抽成纯函数之后，每一类失败的文案与编号前缀都有断言。
 *
 * 消灭的失败（审计 N-02）：`BundledKnowledgeBaseInstaller.install` 与
 * `studyRepository.initialize()` 原先合用一个 catch，于是**任何**投影或账本失败都会被说成
 * 「本地知识包尚未准备好」，并附上一句「错题和复习可以继续使用」——而投影失败恰恰意味着
 * 学习进度没有更新。说错原因比不说更糟：它让学生和排查者都看向错的方向。
 *
 * 诊断编号前缀是**面向人的归类载体**（横幅会显示它）：`startup:knowledge:` / `startup:ledger:` /
 * `startup:replay-limit:` / `startup:drain-budget:` / `startup:projection:`。五者互不相同，
 * 因此"哪一类数据出了问题"在用户能看到的界面上是可区分的。
 */

/** 知识包安装失败。它确实只影响自动分类，不影响作答与复习。 */
internal fun knowledgeBaseFailure(failure: Throwable): StartupState.RecoverableFailure =
    StartupState.RecoverableFailure(
        title = "本地知识包尚未准备好",
        // 文案与拆分前**逐字相同**：这条本来就在说知识包，"可以继续使用"在这条上成立。
        // 拆分只把它从"所有后台失败的兜底"缩小回"知识包失败"。
        message = "错题和复习可以继续使用，自动分类会暂缓。",
        diagnosticId = "startup:knowledge:${failure.hashCode().toUInt()}",
        errorCategory = StartupErrorCategory.KNOWLEDGE_BASE,
    )

/**
 * 学习进度投影失败：账本、投影器或全量重放出的问题（审计 N-02／N-04／N-06）。
 *
 * 四类各有各的实话，因为**用户能做的事不同**：
 * - 账本完整性坏掉（[LearningLedgerIntegrityException]）：数据本身有缺口或冲突，投影停了。
 * - 账本超出一次重放的上限（[ProjectionReplayLimitExceededException]）：这一次投影版本升级
 *   完成不了，重试也没用（ADR-0003 的重放地平线是出路，不是"再试一次"）。
 * - 一次排空的步数预算用完（[ProjectionDrainBudgetExhaustedException]）：积压还没投影完，
 *   但已完成的推进已落库，**重试就是通路**。
 * - 其余：投影更新失败，原因未知。
 *
 * **四类都不得说「可以继续使用」**：投影失败的含义正是学习进度停在最后一次成功的位置，
 * 说"一切正常"会让用户以为界面上的旧进度是新的。
 *
 * 前两类的 `retryable = false`——它们的输入在同一版本内不会变（账本只追加、从不裁剪），
 * 所以重试只会把同一条错误再显示一次。**这个函数只在投影初始化的 catch 里被调用**，
 * 这一点是前提：[LearningLedgerIntegrityException] 在本仓里还被 `ModelTaskTransactionDao`
 * 与 `RoomPendingCaptureStore` 当作「行内字段互相矛盾」的通用异常抛出，仅凭类型推不出
 * 「学习账本坏了」。同一个类型在不同调用点上要说不同的话，所以归类挂在调用点上，不挂在类型上。
 */
internal fun projectionFailure(failure: Throwable): StartupState.RecoverableFailure = when (failure) {
    is LearningLedgerIntegrityException -> StartupState.RecoverableFailure(
        title = "学习记录出现损坏",
        message = "本机记录没有被删除，但投影无法继续推进；" +
            "请把下面的诊断编号一并反馈，不要手工改动数据。",
        diagnosticId = "startup:ledger:${failure.hashCode().toUInt()}",
        errorCategory = StartupErrorCategory.UNKNOWN,
        retryable = false,
    )

    is ProjectionReplayLimitExceededException -> StartupState.RecoverableFailure(
        title = "学习记录超出本次升级能处理的范围",
        message = "作答与复习记录都还在，但进度重算需要一次完整的全量重放，" +
            "而当前记录条数超过了上限；重试不会改变结果，请把诊断编号反馈。",
        diagnosticId = "startup:replay-limit:${failure.hashCode().toUInt()}",
        errorCategory = StartupErrorCategory.UNKNOWN,
        retryable = false,
    )

    // 与上面两条相反的判断：这一条**真的可以重试**。它的输入是"这一次走了多少步"，
    // 不是数据本身——已完成的推进已经落库，下次从那个检查点接着走（审计 N-06）。
    // `retryable` 是默认值，但这里**显式写出来**：这三条分支的差别正在于此，
    // 任何一次改动默认值的重构都不该悄悄改变这一条的用户可见行为。
    is ProjectionDrainBudgetExhaustedException -> StartupState.RecoverableFailure(
        title = "学习进度正在追赶",
        message = "积压的作答记录一次没有全部投影完；已经处理的部分已经保存，" +
            "重试会从断点继续。",
        diagnosticId = "startup:drain-budget:${failure.hashCode().toUInt()}",
        errorCategory = StartupErrorCategory.UNKNOWN,
        retryable = true,
    )

    else -> StartupState.RecoverableFailure(
        title = "学习进度暂时无法更新",
        message = "本机学习记录会保留；复习进度可能停在最近一次成功的位置。",
        diagnosticId = "startup:projection:${failure.hashCode().toUInt()}",
        errorCategory = StartupErrorCategory.UNKNOWN,
    )
}
