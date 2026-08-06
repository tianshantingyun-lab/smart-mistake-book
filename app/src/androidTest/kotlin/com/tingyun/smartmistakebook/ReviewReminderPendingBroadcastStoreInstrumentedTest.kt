package com.tingyun.smartmistakebook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewReminderPendingBroadcastStoreInstrumentedTest {
    @Test
    fun pendingBroadcastSurvivesStoreRecreationAndDuplicatesStayCoalesced() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName =
            "review-reminder-handoff-test-${System.nanoTime()}"
        val first = SharedPreferencesReviewReminderPendingBroadcastStore(
            context = context,
            preferencesName = preferencesName,
        )

        try {
            assertTrue(first.enqueue(ReviewReminderContract.ACTION_DAILY_REMINDER))
            assertTrue(first.enqueue(ReviewReminderContract.ACTION_DAILY_REMINDER))

            val recreated = SharedPreferencesReviewReminderPendingBroadcastStore(
                context = context,
                preferencesName = preferencesName,
            )
            assertEquals(
                ReviewReminderContract.ACTION_DAILY_REMINDER,
                recreated.next(),
            )
            assertTrue(
                recreated.acknowledge(ReviewReminderContract.ACTION_DAILY_REMINDER),
            )
            assertNull(recreated.next())
        } finally {
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
