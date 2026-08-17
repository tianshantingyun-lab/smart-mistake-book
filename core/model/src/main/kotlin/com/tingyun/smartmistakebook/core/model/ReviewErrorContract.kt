package com.tingyun.smartmistakebook.core.model

enum class ReviewRetryReason {
    SUBMISSION_RECORDING,
    REVEAL_RECORDING,
}

fun reviewRetryError(reason: ReviewRetryReason): UserRecoverableError = userRecoverableError(
    code = AppErrorCode.DATABASE_WRITE_FAILED,
    title = when (reason) {
        ReviewRetryReason.SUBMISSION_RECORDING -> "这次作答还没确认写入"
        ReviewRetryReason.REVEAL_RECORDING -> "完整讲解还没安全记录"
    },
    message = when (reason) {
        ReviewRetryReason.SUBMISSION_RECORDING ->
            "作答尚未确认写入。为保证安全重试，当前选择已锁定；请重新提交答案。"
        ReviewRetryReason.REVEAL_RECORDING ->
            "讲解尚未安全记录，因此暂未显示。请重试打开讲解。"
    },
    dataSafe = true,
    primaryAction = RecoveryAction.RETRY,
)
