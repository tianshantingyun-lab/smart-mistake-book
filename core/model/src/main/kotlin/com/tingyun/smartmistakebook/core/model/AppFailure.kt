package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.Serializable

/**
 * Unified application failure model.
 *
 * Replaces the two previously coexisting duplicate error systems
 * with a single contract that always carries:
 * - a stable machine-readable [code],
 * - safe, leak-free user-facing copy ([safeMessageKey]/[safeMessage], [title]),
 * - an explicit data-preservation guarantee ([dataPreserved]),
 * - retryability semantics ([retryability]),
 * - a [diagnosticId] for log correlation,
 * - typed UI recovery [actions].
 */
@Serializable
data class AppFailure(
    val code: AppFailureCode,
    val title: String,
    /** Safe, user-facing detail message. Never contains stack-trace fragments. */
    val safeMessage: String,
    /** Stable message key for localized lookups; defaults to the code-derived key. */
    val safeMessageKey: String = "error.${code.name.lowercase()}",
    val safeMessageArgs: Map<String, String> = emptyMap(),
    /** True when user data survived the failure and retrying is safe. */
    val dataPreserved: Boolean,
    val retryability: Retryability,
    val diagnosticId: String,
    val actions: List<UserRecoveryAction> = emptyList(),
) {
    /** User-facing detail text shown by the UI. */
    val message: String
        get() = safeMessage

    val retryable: Boolean
        get() = retryability == Retryability.RETRYABLE

    val primaryAction: UserRecoveryAction?
        get() = actions.firstOrNull()

    /** Kind of the primary recovery action, if any. */
    val primaryActionKind: ActionType?
        get() = primaryAction?.actionType

    init {
        require(title.isNotBlank()) { "App failure title must not be blank" }
        require(safeMessage.isNotBlank()) { "App failure message must not be blank" }
        require(diagnosticId.isNotBlank()) { "App failure diagnostic id must not be blank" }
        require(
            !safeMessage.contains("Exception") && !safeMessage.contains(" at "),
        ) { "App failures must not expose raw stack traces" }
        require(
            !title.contains("Exception") && !title.contains(" at "),
        ) { "App failures must not expose raw stack traces" }
    }
}

/**
 * Whether the failed operation can safely be attempted again.
 */
@Serializable
enum class Retryability {
    RETRYABLE,
    NOT_RETRYABLE,
}

/**
 * Stable failure categories. Superset of every category carried by the two
 * removed duplicate error systems, so no existing mapping target is lost.
 */
@Serializable
enum class AppFailureCode {
    // Validation & input
    VALIDATION_FAILED,
    ASSET_UNREADABLE,
    ASSET_TOO_LARGE,
    CAPTURE_PARSE_TIMEOUT,
    CAPTURE_PARSE_FAILED,
    CAPTURE_IMAGE_TOO_LARGE,
    CAPTURE_IMAGE_CORRUPTED,
    CAPTURE_NETWORK_ERROR,

    // Storage
    LOW_STORAGE,
    DATABASE_WRITE_FAILED,
    DATABASE_ERROR,

    // Model provider / dispatch
    PROVIDER_NOT_CONFIGURED,
    PROVIDER_AUTH_FAILED,
    PROVIDER_CAPABILITY_MISMATCH,
    EGRESS_CONSENT_REQUIRED,
    EGRESS_LEASE_EXPIRED,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    MODEL_OUTPUT_INVALID,
    DISPATCH_BUDGET_EXHAUSTED,
    TUTOR_PROVIDER_UNAVAILABLE,
    TUTOR_PROVIDER_TIMEOUT,
    TUTOR_PROVIDER_RATE_LIMITED,
    TUTOR_PROVIDER_AUTH_FAILED,
    TUTOR_ORDINAL_CONFLICT,
    TUTOR_OPERATION_REPLAY_MISMATCH,
    TUTOR_ANCHOR_NOT_FOUND,
    TUTOR_REVISION_SUPERSEDED,
    TUTOR_PROVIDER_CAPABILITY_CHANGED,

    // Feature domains
    LIBRARY_SEARCH_FAILED,
    LIBRARY_PAGING_ERROR,
    BACKUP_CORRUPTED,
    BACKUP_VALIDATION_FAILED,
    BACKUP_CREATE_FAILED,
    BACKUP_RESTORE_FAILED,
    BACKUP_RESTORE_CORRUPTED,
    BACKUP_INSUFFICIENT_SPACE,
    LEARNING_PROJECTION_FAILED,
    LEARNING_MODEL_INCONSISTENT,
    KNOWLEDGE_RETRIEVAL_FAILED,
    KNOWLEDGE_INDEX_STALE,

    // General
    AUTHENTICATION_ERROR,
    PERMISSION_DENIED,
    UNKNOWN,
}

/**
 * The kind of recovery action, decoupled from its presentation.
 */
@Serializable
enum class ActionType {
    /** Retry the failed operation. */
    RETRY,
    /** Continue without retrying. */
    CONTINUE,
    /** Navigate to a different screen. */
    NAVIGATE,
    /** Open settings. */
    OPEN_SETTINGS,
    /** Restore from a backup. */
    BACKUP_RESTORE,
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
 * A recovery action the user can take to resolve the failure.
 * This is the UI action contract; the action *kind* is [ActionType].
 */
@Serializable
data class UserRecoveryAction(
    val actionId: String,
    val label: String,
    val description: String = "",
    val isPrimary: Boolean = false,
    val actionType: ActionType,
)

/**
 * Builds an [AppFailure] with an auto-generated [AppFailure.diagnosticId].
 */
fun appFailure(
    code: AppFailureCode,
    title: String,
    message: String,
    dataPreserved: Boolean,
    retryability: Retryability = Retryability.NOT_RETRYABLE,
    safeMessageKey: String = "error.${code.name.lowercase()}",
    safeMessageArgs: Map<String, String> = emptyMap(),
    primaryAction: ActionType? = null,
    secondaryAction: ActionType? = null,
): AppFailure = AppFailure(
    code = code,
    title = title,
    safeMessage = message,
    safeMessageKey = safeMessageKey,
    safeMessageArgs = safeMessageArgs,
    dataPreserved = dataPreserved,
    retryability = retryability,
    diagnosticId =
        "error:${code.name.lowercase()}:${System.currentTimeMillis().hashCode().toUInt()}",
    actions = buildList {
        primaryAction?.let { kind ->
            add(kind.toRecoveryAction(isPrimary = true))
        }
        secondaryAction?.let { kind ->
            add(kind.toRecoveryAction(isPrimary = false))
        }
    },
)

/**
 * Maps an action kind to a presentable [UserRecoveryAction] with stable ids and
 * default zh-CN copy.
 */
fun ActionType.toRecoveryAction(isPrimary: Boolean = false): UserRecoveryAction =
    UserRecoveryAction(
        actionId = name.lowercase(),
        label = when (this) {
            ActionType.RETRY -> "重试"
            ActionType.CONTINUE -> "继续"
            ActionType.NAVIGATE -> "前往其他页面"
            ActionType.OPEN_SETTINGS -> "打开设置"
            ActionType.BACKUP_RESTORE -> "恢复备份"
            ActionType.CONTACT_SUPPORT -> "联系支持"
            ActionType.DISMISS -> "知道了"
            ActionType.USE_FALLBACK -> "使用替代方式"
            ActionType.MANUAL_INPUT -> "手动填写"
            ActionType.CHANGE_PROVIDER -> "切换服务"
        },
        isPrimary = isPrimary,
        actionType = this,
    )

/**
 * Maps model-execution failures onto the unified application failure contract.
 */
fun ModelFailureCode.toAppFailureCode(): AppFailureCode = when (this) {
    ModelFailureCode.MODEL_NOT_CONFIGURED -> AppFailureCode.PROVIDER_NOT_CONFIGURED
    ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED -> AppFailureCode.EGRESS_CONSENT_REQUIRED
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID -> AppFailureCode.EGRESS_LEASE_EXPIRED
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING -> AppFailureCode.PROVIDER_CAPABILITY_MISMATCH
    ModelFailureCode.NETWORK_UNAVAILABLE -> AppFailureCode.NETWORK_UNAVAILABLE
    ModelFailureCode.AUTHENTICATION_FAILED -> AppFailureCode.PROVIDER_AUTH_FAILED
    ModelFailureCode.RATE_LIMITED -> AppFailureCode.RATE_LIMITED
    ModelFailureCode.TIMEOUT -> AppFailureCode.NETWORK_UNAVAILABLE
    ModelFailureCode.INVALID_RESPONSE -> AppFailureCode.MODEL_OUTPUT_INVALID
    ModelFailureCode.PROVIDER_REJECTED_INPUT -> AppFailureCode.VALIDATION_FAILED
    ModelFailureCode.UNKNOWN -> AppFailureCode.UNKNOWN
}

fun ModelTaskFailure.toAppFailure(): AppFailure = appFailure(
    code = code.toAppFailureCode(),
    title = when (code) {
        ModelFailureCode.MODEL_NOT_CONFIGURED -> "模型还没有配置"
        ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED -> "需要你确认这次发送"
        ModelFailureCode.EGRESS_AUTHORIZATION_INVALID -> "这次授权已经失效"
        ModelFailureCode.PROVIDER_CAPABILITY_MISSING -> "当前模型能力不匹配"
        ModelFailureCode.NETWORK_UNAVAILABLE -> "暂时连不上模型"
        ModelFailureCode.AUTHENTICATION_FAILED -> "模型认证失败"
        ModelFailureCode.RATE_LIMITED -> "模型请求较多"
        ModelFailureCode.TIMEOUT -> "模型响应超时"
        ModelFailureCode.INVALID_RESPONSE -> "模型返回的内容无法使用"
        ModelFailureCode.PROVIDER_REJECTED_INPUT -> "这次请求被模型服务拒绝"
        ModelFailureCode.UNKNOWN -> "这次操作没有完成"
    },
    message = message,
    dataPreserved = true,
    retryability = if (retryable) Retryability.RETRYABLE else Retryability.NOT_RETRYABLE,
    primaryAction = if (retryable) ActionType.RETRY else null,
)
