package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreModelAgentConsentStoreTest {
    @Test
    fun defaultsOnAndSavedPreferenceSurvivesRepositoryRecreation() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File(baseContext.cacheDir, "consent-store-tests/${UUID.randomUUID()}")
        val context = IsolatedStorageContext(baseContext, root)
        val firstJob = SupervisorJob()
        val firstStore = DataStoreModelAgentConsentStore(
            context = context,
            scope = CoroutineScope(firstJob + Dispatchers.IO),
        )

        // 默认开启：已配置模型即沿用当前"配置即同意"行为，用户可主动关闭
        assertTrue(firstStore.consentEnabled.first())
        firstStore.setConsentEnabled(false)
        assertFalse(firstStore.current())
        firstJob.cancelAndJoin()

        val secondJob = SupervisorJob()
        val secondStore = DataStoreModelAgentConsentStore(
            context = context,
            scope = CoroutineScope(secondJob + Dispatchers.IO),
        )
        try {
            assertFalse(secondStore.current())
            secondStore.setConsentEnabled(true)
            assertTrue(secondStore.current())
        } finally {
            secondJob.cancelAndJoin()
        }
    }
}

/** Isolates the DataStore file so tests never touch real app prefs. */
private class IsolatedStorageContext(
    base: Context,
    private val root: File,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = File(root, "files").also { directory ->
        check(directory.isDirectory || directory.mkdirs())
    }
}
