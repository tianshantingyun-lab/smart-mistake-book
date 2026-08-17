package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewErrorContractTest {
    @Test
    fun reviewRetryErrorsAreSafeAndRetryable() {
        val submission = reviewRetryError(ReviewRetryReason.SUBMISSION_RECORDING)
        val reveal = reviewRetryError(ReviewRetryReason.REVEAL_RECORDING)

        assertEquals(AppErrorCode.DATABASE_WRITE_FAILED, submission.code)
        assertEquals(RecoveryAction.RETRY, submission.primaryAction)
        assertEquals(AppErrorCode.DATABASE_WRITE_FAILED, reveal.code)
        assertTrue(submission.dataSafe)
        assertTrue(reveal.dataSafe)
        assertTrue(submission.message.contains("重新提交答案"))
        assertTrue(reveal.message.contains("重试打开讲解"))
    }
}
