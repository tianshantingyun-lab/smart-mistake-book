package com.tingyun.smartmistakebook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewOpenRequestPolicyTest {
    @Test
    fun `valid notification request is consumed only on a fresh delivery`() {
        assertTrue(
            shouldConsumeReviewOpenRequest(
                action = ReviewReminderContract.ACTION_OPEN_REVIEW,
                requested = true,
                isFreshDelivery = true,
            ),
        )
        assertFalse(
            shouldConsumeReviewOpenRequest(
                action = ReviewReminderContract.ACTION_OPEN_REVIEW,
                requested = true,
                isFreshDelivery = false,
            ),
        )
    }

    @Test
    fun `unrelated or incomplete intents never open review`() {
        assertFalse(
            shouldConsumeReviewOpenRequest(
                action = "other-action",
                requested = true,
                isFreshDelivery = true,
            ),
        )
        assertFalse(
            shouldConsumeReviewOpenRequest(
                action = ReviewReminderContract.ACTION_OPEN_REVIEW,
                requested = false,
                isFreshDelivery = true,
            ),
        )
    }
}
