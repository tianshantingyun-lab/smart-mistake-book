package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/**
 * Unified error representation for user-facing errors.
 * Replaces raw Throwable.message, "Unknown error", and "Error: null"
 * with structured, actionable error information.
 */
@Serializable
data class UserFacingFailure(
    val code: ErrorCode,
    val title: String,
    val explanation: String,
    val preservedData: String? = null,
    val recoveryActions: List<UserRecoveryAction> = emptyList(),
    val diagnosticId: String,
    val timestampEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(diagnosticId.isNotBlank()) { "Diagnostic id must not be blank" }
    }
}

/**
 * Error codes for categorizing failures.
 */
@Serializable
enum class ErrorCode {
    // Capture errors
    CAPTURE_PARSE_TIMEOUT,
    CAPTURE_PARSE_FAILED,
    CAPTURE_IMAGE_TOO_LARGE,
    CAPTURE_IMAGE_CORRUPTED,
    CAPTURE_NETWORK_ERROR,

    // Tutor errors
    TUTOR_PROVIDER_UNAVAILABLE,
    TUTOR_PROVIDER_TIMEOUT,
    TUTOR_PROVIDER_RATE_LIMITED,
    TUTOR_PROVIDER_AUTH_FAILED,
    TUTOR_ORDINAL_CONFLICT,
    TUTOR_OPERATION_REPLAY_MISMATCH,
    TUTOR_ANCHOR_NOT_FOUND,
    TUTOR_REVISION_SUPERSEDED,
    TUTOR_PROVIDER_CAPABILITY_CHANGED,

    // Library errors
    LIBRARY_SEARCH_FAILED,
    LIBRARY_PAGING_ERROR,

    // Backup errors
    BACKUP_VALIDATION_FAILED,
    BACKUP_CREATE_FAILED,
    BACKUP_RESTORE_FAILED,
    BACKUP_RESTORE_CORRUPTED,
    BACKUP_INSUFFICIENT_SPACE,

    // Learning errors
    LEARNING_PROJECTION_FAILED,
    LEARNING_MODEL_INCONSISTENT,

    // Knowledge errors
    KNOWLEDGE_RETRIEVAL_FAILED,
    KNOWLEDGE_INDEX_STALE,

    // General errors
    DATABASE_ERROR,
    NETWORK_ERROR,
    AUTHENTICATION_ERROR,
    PERMISSION_DENIED,
    UNKNOWN_ERROR,
}

/**
 * A recovery action the user can take to resolve the error.
 * This is the UI action contract; the action *kind* is [ActionType].
 */
@Serializable
data class UserRecoveryAction(
    val actionId: String,
    val label: String,
    val description: String,
    val isPrimary: Boolean = false,
    val actionType: ActionType,
)

/**
 * Types of recovery actions.
 */
@Serializable
enum class ActionType {
    /** Retry the failed operation. */
    RETRY,
    /** Navigate to a different screen. */
    NAVIGATE,
    /** Open settings. */
    OPEN_SETTINGS,
    /** Contact support. */
    CONTACT_SUPPORT,
    /** Dismiss the error. */
    DISMISS,
    /** Use a fallback approach. */
    USE_FALLBACK,
    /** Manually input data. */
    MANUAL_INPUT,
    /** Change provider. */
    CHANGE_PROVIDER,
}

/**
 * Factory methods for common error types.
 */
object ErrorFactory {
    fun captureParseTimeout(diagnosticId: String): UserFacingFailure = UserFacingFailure(
        code = ErrorCode.CAPTURE_PARSE_TIMEOUT,
        title = "图片解析超时",
        explanation = "图片解析服务响应时间过长，请稍后重试或更换图片。",
        preservedData = "原图已保留，不会丢失。",
        recoveryActions = listOf(
            UserRecoveryAction(
                actionId = "retry",
                label = "重新解析",
                description = "再次尝试解析当前图片",
                isPrimary = true,
                actionType = ActionType.RETRY,
            ),
            UserRecoveryAction(
                actionId = "manual",
                label = "手动填写",
                description = "手动输入题目内容",
                actionType = ActionType.MANUAL_INPUT,
            ),
            UserRecoveryAction(
                actionId = "replace",
                label = "更换图片",
                description = "选择另一张图片重新拍摄",
                actionType = ActionType.NAVIGATE,
            ),
        ),
        diagnosticId = diagnosticId,
    )

    fun providerUnavailable(diagnosticId: String, providerName: String): UserFacingFailure =
        UserFacingFailure(
            code = ErrorCode.TUTOR_PROVIDER_UNAVAILABLE,
            title = "AI 服务暂时不可用",
            explanation = "$providerName 服务暂时不可用，正在尝试其他服务。",
            recoveryActions = listOf(
                UserRecoveryAction(
                    actionId = "retry",
                    label = "重试",
                    description = "稍后重试当前服务",
                    isPrimary = true,
                    actionType = ActionType.RETRY,
                ),
                UserRecoveryAction(
                    actionId = "change_provider",
                    label = "切换服务",
                    description = "使用其他 AI 服务",
                    actionType = ActionType.CHANGE_PROVIDER,
                ),
            ),
            diagnosticId = diagnosticId,
        )

    fun backupRestoreFailed(diagnosticId: String, reason: String): UserFacingFailure =
        UserFacingFailure(
            code = ErrorCode.BACKUP_RESTORE_FAILED,
            title = "恢复备份失败",
            explanation = "恢复备份时出错：$reason。当前数据未受影响。",
            preservedData = "原始数据已保留，可以安全重试。",
            recoveryActions = listOf(
                UserRecoveryAction(
                    actionId = "retry",
                    label = "重试恢复",
                    description = "再次尝试恢复备份",
                    isPrimary = true,
                    actionType = ActionType.RETRY,
                ),
                UserRecoveryAction(
                    actionId = "dismiss",
                    label = "取消",
                    description = "放弃恢复，保持当前数据",
                    actionType = ActionType.DISMISS,
                ),
            ),
            diagnosticId = diagnosticId,
        )

    fun databaseError(diagnosticId: String): UserFacingFailure = UserFacingFailure(
        code = ErrorCode.DATABASE_ERROR,
        title = "数据存储错误",
        explanation = "应用数据存储出现问题。如果问题持续，请联系技术支持。",
        recoveryActions = listOf(
            UserRecoveryAction(
                actionId = "retry",
                label = "重试",
                description = "重新尝试操作",
                isPrimary = true,
                actionType = ActionType.RETRY,
            ),
            UserRecoveryAction(
                actionId = "support",
                label = "联系支持",
                description = "获取技术支持",
                actionType = ActionType.CONTACT_SUPPORT,
            ),
        ),
        diagnosticId = diagnosticId,
    )

    fun networkError(diagnosticId: String): UserFacingFailure = UserFacingFailure(
        code = ErrorCode.NETWORK_ERROR,
        title = "网络连接错误",
        explanation = "无法连接到网络。请检查网络设置后重试。",
        recoveryActions = listOf(
            UserRecoveryAction(
                actionId = "retry",
                label = "重试",
                description = "重新尝试操作",
                isPrimary = true,
                actionType = ActionType.RETRY,
            ),
            UserRecoveryAction(
                actionId = "settings",
                label = "检查网络",
                description = "打开网络设置",
                actionType = ActionType.OPEN_SETTINGS,
            ),
        ),
        diagnosticId = diagnosticId,
    )
}
