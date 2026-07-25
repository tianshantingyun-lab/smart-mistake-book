package com.tingyun.smartmistakebook

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewReminderPlatformInstrumentedTest {
    @Test
    fun firedReminderPostsPrivateGenericNotificationWithContentIntent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
        notificationManager.deleteNotificationChannel(REVIEW_REMINDER_CHANNEL_ID)
        grantNotificationPermission(context.packageName)
        assertTrue(context.canPostReviewNotifications())
        val coordinator = ReviewReminderCoordinator(
            repository = EnabledReminderRepository,
            platform = ReviewReminderPlatform(context),
            pendingReviewCount = { 4 },
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { 1_789_699_800_000L },
            zoneId = { ZoneId.of("Asia/Shanghai") },
        )

        coordinator.handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)

        val notification = waitForReviewNotification(notificationManager)
        assertEquals(
            "有 4 道题待复习，点这里开始。",
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.contentIntent)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        notificationManager.cancelAll()
    }

    private fun grantNotificationPermission(packageName: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun waitForReviewNotification(notificationManager: NotificationManager): Notification {
        repeat(40) {
            notificationManager.activeNotifications
                .map { it.notification }
                .firstOrNull {
                    it.extras.getString(Notification.EXTRA_TITLE) == "今天的复习已准备好"
                }
                ?.let { return it }
            SystemClock.sleep(50)
        }
        error("Review notification was not posted within 2 seconds.")
    }

    private object EnabledReminderRepository : ReviewReminderRepository {
        private val value = ReviewReminderPreferences(enabled = true)
        override val preferences: Flow<ReviewReminderPreferences> = flowOf(value)
        override suspend fun current(): ReviewReminderPreferences = value
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun setReminderTime(minutesAfterMidnight: Int) = Unit
        override suspend fun claimNotificationDelivery(delivery: ReviewReminderDelivery): Boolean = true
    }
}
