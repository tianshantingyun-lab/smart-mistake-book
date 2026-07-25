package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import java.io.File
import java.util.Arrays
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreModelConfigurationStoreTest {
    @Test
    fun realPreferenceDataStoreRoundTripsAndCorruptionFailsClosed() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File(baseContext.cacheDir, "model-store-tests/${UUID.randomUUID()}")
        val context = IsolatedStorageContext(baseContext, root)
        val firstJob = SupervisorJob()
        val firstStore = DataStoreModelConfigurationStore(
            context = context,
            scope = CoroutineScope(firstJob + Dispatchers.IO),
            clock = { 123_456L },
        )

        try {
            val save = firstStore.saveWithKey("instrumented-secret")
            assertTrue(save is ModelConfigurationMutationResult.Success)
            assertTrue(firstStore.configuration.first().isConfigured)
            assertCredential(firstStore.readCredential(), "instrumented-secret")

            firstJob.cancelAndJoin()
            val persistedJob = SupervisorJob()
            val persistedStore = DataStoreModelConfigurationStore(
                context = context,
                scope = CoroutineScope(persistedJob + Dispatchers.IO),
                clock = { 200_000L },
            )
            try {
                assertTrue(persistedStore.configuration.first().isConfigured)
                assertCredential(persistedStore.readCredential(), "instrumented-secret")
            } finally {
                persistedJob.cancelAndJoin()
            }

            val preferencesFile = context.preferencesDataStoreFile(
                "model_configuration.preferences_pb",
            )
            assertTrue(preferencesFile.isFile)
            val corruptBytes = ByteArray(64) { 0x7f.toByte() }
            try {
                preferencesFile.writeBytes(corruptBytes)
            } finally {
                Arrays.fill(corruptBytes, 0.toByte())
            }

            val restoredJob = SupervisorJob()
            val restoredStore = DataStoreModelConfigurationStore(
                context = context,
                scope = CoroutineScope(restoredJob + Dispatchers.IO),
                clock = { 234_567L },
            )
            try {
                val snapshot = restoredStore.configuration.first()
                assertFalse(snapshot.isConfigured)
                assertEquals(ModelCredentialReadResult.Missing, restoredStore.readCredential())
                assertTrue(restoredStore.clear() is ModelConfigurationMutationResult.Success)
            } finally {
                restoredJob.cancelAndJoin()
            }
        } finally {
            if (firstJob.isActive) firstJob.cancelAndJoin()
            AndroidKeystoreModelSecretVault(context).clear()
            root.deleteRecursively()
        }
    }

    private suspend fun DataStoreModelConfigurationStore.saveWithKey(
        value: String,
    ): ModelConfigurationMutationResult {
        val source = value.toCharArray()
        val key = ModelApiKey.from(source)
        Arrays.fill(source, '\u0000')
        return try {
            save(
                update = ModelConfigurationUpdate(
                    provider = "openai-compatible",
                    baseUrl = "https://api.example.com/v1",
                    modelId = "model-1",
                ),
                apiKey = key,
            )
        } finally {
            key.close()
        }
    }

    private fun assertCredential(
        result: ModelCredentialReadResult,
        expected: String,
    ) {
        val available = result as ModelCredentialReadResult.Available
        val copied = available.apiKey.copyChars()
        try {
            assertEquals("openai-compatible", available.configuration.provider)
            assertArrayEquals(expected.toCharArray(), copied)
        } finally {
            Arrays.fill(copied, '\u0000')
            available.apiKey.close()
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

        override fun getNoBackupFilesDir(): File = File(root, "no-backup").also { directory ->
            check(directory.isDirectory || directory.mkdirs())
        }
    }
}
