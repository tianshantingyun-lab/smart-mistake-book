package com.tingyun.smartmistakebook

import android.net.Uri
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey

/**
 * 路由表与它唯一的本地形态转换（路径参数的编码/解码）。
 *
 * 从 `SmartMistakeBookRoot.kt` 拆出（2026-09-14）：那个文件因并行会话的改动超过了 1000 行
 * 硬限，规模门按处方要求拆块。同一包内移动，**所有调用点无需改 import**；
 * `RootDestination` 与 `rootDestinations` 留在 Root——它们是 file-private，移出来会破坏可见性。
 *
 * 讲题只有**一个页面**（智能体），`Tutor` / `CapturedTutorSession` / `MistakeTutor` /
 * `TutorTextConversation` 是它的四种"这一轮带什么"的形态：不带附件（无题轮/大厅）、一次已
 * 拍好的会话、一道错题（错题详情 / 复习判题 / 判题复核 / 页内选题共用）、一条历史文字会话。
 * 四条都渲染同一个页面（同一个标题栏），底栏都映射到 `Tutor`（见 `bottomBarRouteFor`）。
 */
internal object Routes {
    const val Review = "review"
    const val Tutor = "tutor"
    const val Library = "library"
    const val Profile = "profile"
    const val ReviewSession = "review/session"
    const val KnowledgeReviewSession = "review/knowledge-session"
    const val CaptureTutor = "capture/tutor"
    const val CaptureLibrary = "capture/library"
    const val BatchImport = "capture/batch"
    const val LibraryBatchExport = "library/export"
    const val CaptureResume = "capture/resume/{draftId}"
    const val SplitReview = "capture/split-review?jobId={jobId}"
    const val CapturedTutorSession = "tutor/captured/{sessionId}"
    const val TutorHistory = "tutor/history"
    const val TutorTextConversation = "tutor/lobby/{conversationId}"
    const val MistakeDetail = "mistake/{itemId}"
    const val MistakeTutor = "mistake/tutor/{entryId}/{problemId}/{problemRevisionId}"
    const val MistakeExport =
        "mistake/export/{entryId}/{problemId}/{problemRevisionId}"
    const val Capability = "settings/capability"
    const val LearningMastery = "profile/learning-mastery"
    const val Privacy = "settings/privacy"
    const val Reminder = "settings/reminder"
    const val Scheduling = "settings/scheduling"
    const val Storage = "settings/storage"

    fun mistakeDetail(itemId: String): String = "mistake/${Uri.encode(itemId)}"

    fun mistakeExport(key: MistakeRevisionKey): String = listOf(
        "mistake",
        "export",
        encodeRevisionArgument(key.entryId),
        encodeRevisionArgument(key.problemId),
        encodeRevisionArgument(key.problemRevisionId),
    ).joinToString("/")

    fun mistakeTutor(key: MistakeRevisionKey): String = listOf(
        "mistake",
        "tutor",
        encodeRevisionArgument(key.entryId),
        encodeRevisionArgument(key.problemId),
        encodeRevisionArgument(key.problemRevisionId),
    ).joinToString("/")

    fun decodeMistakeExportKey(
        entryId: String?,
        problemId: String?,
        problemRevisionId: String?,
    ): MistakeRevisionKey? = runCatching {
        MistakeRevisionKey(
            entryId = Uri.decode(entryId.orEmpty()),
            problemId = Uri.decode(problemId.orEmpty()),
            problemRevisionId = Uri.decode(problemRevisionId.orEmpty()),
        )
    }.getOrNull()

    // Navigation decodes a path argument once; keep one encoded layer for the explicit boundary decode.
    private fun encodeRevisionArgument(value: String): String = Uri.encode(Uri.encode(value))

    fun capturedTutorSession(sessionId: String): String = "tutor/captured/${Uri.encode(sessionId)}"

    fun tutorTextConversation(conversationId: String): String =
        "tutor/lobby/${Uri.encode(conversationId)}"

    fun captureResume(draftId: String): String = "capture/resume/${Uri.encode(draftId)}"

    fun splitReview(jobId: String?): String =
        if (jobId.isNullOrBlank()) {
            "capture/split-review"
        } else {
            "capture/split-review?jobId=${Uri.encode(jobId)}"
        }
}
