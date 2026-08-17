package com.tingyun.smartmistakebook.core.model

enum class AppErrorCode {
    VALIDATION_FAILED,
    ASSET_UNREADABLE,
    ASSET_TOO_LARGE,
    LOW_STORAGE,
    PROVIDER_NOT_CONFIGURED,
    PROVIDER_AUTH_FAILED,
    PROVIDER_CAPABILITY_MISMATCH,
    EGRESS_CONSENT_REQUIRED,
    EGRESS_LEASE_EXPIRED,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    MODEL_OUTPUT_INVALID,
    DISPATCH_BUDGET_EXHAUSTED,
    DATABASE_WRITE_FAILED,
    BACKUP_CORRUPTED,
    UNKNOWN,
}

enum class RecoveryAction {
    CONTINUE,
    RETRY,
    OPEN_SETTINGS,
    BACKUP_RESTORE,
}

data class UserRecoverableError(
    val code: AppErrorCode,
    val title: String,
    val message: String,
    val dataSafe: Boolean,
    val primaryAction: RecoveryAction? = null,
    val secondaryAction: RecoveryAction? = null,
    val diagnosticId: String,
) {
    init {
        require(title.isNotBlank()) { "User-recoverable error title must not be blank" }
        require(message.isNotBlank()) { "User-recoverable error message must not be blank" }
        require(diagnosticId.isNotBlank()) { "User-recoverable diagnostic id must not be blank" }
        require(
            !message.contains("Exception") && !message.contains(" at "),
        ) { "User-recoverable errors must not expose raw stack traces" }
    }
}

fun ModelFailureCode.toAppErrorCode(): AppErrorCode = when (this) {
    ModelFailureCode.MODEL_NOT_CONFIGURED -> AppErrorCode.PROVIDER_NOT_CONFIGURED
    ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED -> AppErrorCode.EGRESS_CONSENT_REQUIRED
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID -> AppErrorCode.EGRESS_LEASE_EXPIRED
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING -> AppErrorCode.PROVIDER_CAPABILITY_MISMATCH
    ModelFailureCode.NETWORK_UNAVAILABLE -> AppErrorCode.NETWORK_UNAVAILABLE
    ModelFailureCode.AUTHENTICATION_FAILED -> AppErrorCode.PROVIDER_AUTH_FAILED
    ModelFailureCode.RATE_LIMITED -> AppErrorCode.RATE_LIMITED
    ModelFailureCode.TIMEOUT -> AppErrorCode.NETWORK_UNAVAILABLE
    ModelFailureCode.INVALID_RESPONSE -> AppErrorCode.MODEL_OUTPUT_INVALID
    ModelFailureCode.PROVIDER_REJECTED_INPUT -> AppErrorCode.VALIDATION_FAILED
    ModelFailureCode.UNKNOWN -> AppErrorCode.UNKNOWN
}

fun userRecoverableError(
    code: AppErrorCode,
    title: String,
    message: String,
    dataSafe: Boolean,
    primaryAction: RecoveryAction? = null,
    secondaryAction: RecoveryAction? = null,
): UserRecoverableError = UserRecoverableError(
    code = code,
    title = title,
    message = message,
    dataSafe = dataSafe,
    primaryAction = primaryAction,
    secondaryAction = secondaryAction,
    diagnosticId = "error:${code.name.lowercase()}:${System.currentTimeMillis().hashCode().toUInt()}",
)

fun ModelTaskFailure.toUserRecoverableError(): UserRecoverableError = userRecoverableError(
    code = code.toAppErrorCode(),
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
    dataSafe = true,
    primaryAction = if (retryable) RecoveryAction.RETRY else null,
)
