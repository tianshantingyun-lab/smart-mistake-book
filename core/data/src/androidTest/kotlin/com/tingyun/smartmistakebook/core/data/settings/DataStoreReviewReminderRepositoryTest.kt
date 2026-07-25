package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.DEFAULT_REVIEW_REMINDER_MINUTES_AFTER_MIDNIGHT
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreReviewReminderRepositoryTest {
    @Test
    fun defaultsAndSavedPreferenceSurviveRepositoryRecreation() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File(baseContext.cacheDir, "reminder-store-tests/${UUID.randomUUID()}")
        val context = IsolatedStorageContext(baseContext, root)
        val firstJob = SupervisorJob()
        val firstRepository = DataStoreReviewReminderRepository(
            context = context,
            scope = CoroutineScope(firstJob + Dispatchers.IO),
        )

        val defaults = firstRepository.preferences.first()
        assertFalse(defaults.enabled)
        assertEquals(DEFAULT_REVIEW_REMINDER_MINUTES_AFTER_MIDNIGHT, defaults.minutesAfterMidnight)
        firstRepository.setReminderTime(18 * 60 + 30)
        firstRepository.setEnabled(true)
        assertTrue(firstRepository.current().enabled)
        firstJob.cancelAndJoin()

        val secondJob = SupervisorJob()
        val secondRepository = DataStoreReviewReminderRepository(
            context = context,
            scope = CoroutineScope(secondJob + Dispatchers.IO),
        )
        try {
            val restored = secondRepository.current()
            assertTrue(restored.enabled)
            assertEquals(18 * 60 + 30, restored.minutesAfterMidnight)
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    @Test
    fun notificationClaimSurvivesRecreationAndRejectsDuplicateOrClockRollback() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File(baseContext.cacheDir, "reminder-delivery-tests/${UUID.randomUUID()}")
        val context = IsolatedStorageContext(baseContext, root)
        val firstJob = SupervisorJob()
        val firstRepository = DataStoreReviewReminderRepository(
            context = context,
            scope = CoroutineScope(firstJob + Dispatchers.IO),
        )
        val delivered = ReviewReminderDelivery(
            localEpochDay = 20_655,
            zoneId = "Asia/Shanghai",
        )

        assertTrue(firstRepository.claimNotificationDelivery(delivered))
        assertFalse(firstRepository.claimNotificationDelivery(delivered))
        assertTrue(
            firstRepository.claimNotificationDelivery(
                delivered.copy(localEpochDay = delivered.localEpochDay + 1),
            ),
        )
        firstJob.cancelAndJoin()

        val secondJob = SupervisorJob()
        val secondRepository = DataStoreReviewReminderRepository(
            context = context,
            scope = CoroutineScope(secondJob + Dispatchers.IO),
        )
        try {
            assertFalse(secondRepository.claimNotificationDelivery(delivered))
            assertFalse(
                secondRepository.claimNotificationDelivery(
                    delivered.copy(localEpochDay = delivered.localEpochDay + 1),
                ),
            )
            assertTrue(
                secondRepository.claimNotificationDelivery(
                    delivered.copy(zoneId = "America/Adak"),
                ),
            )
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    @Test
    fun concurrentDuplicateClaimsHaveExactlyOneWinner() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File(baseContext.cacheDir, "reminder-concurrency-tests/${UUID.randomUUID()}")
        val context = IsolatedStorageContext(baseContext, root)
        val job = SupervisorJob()
        val repository = DataStoreReviewReminderRepository(
            context = context,
            scope = CoroutineScope(job + Dispatchers.IO),
        )
        val delivery = ReviewReminderDelivery(
            localEpochDay = 20_655,
            zoneId = "Asia/Shanghai",
        )

        try {
            val results = coroutineScope {
                List(16) {
                    async(Dispatchers.Default) {
                        repository.claimNotificationDelivery(delivery)
                    }
                }.awaitAll()
            }
            assertEquals(1, results.count { it })
        } finally {
            job.cancelAndJoin()
        }
    }

    private class IsolatedStorageContext(
        base: Context,
        private val root: File,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(root, "files").also { directory ->
            check(directory.isDirectory || directory.mkdirs())
        }
    }
}
