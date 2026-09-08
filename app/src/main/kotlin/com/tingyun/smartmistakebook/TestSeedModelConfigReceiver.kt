package com.tingyun.smartmistakebook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate

/**
 * Debug-only test harness: lets QA seed a model configuration from `adb`
 * without fighting the on-screen keyboard.
 *
 *     adb shell am broadcast -a com.tingyun.smartmistakebook.TEST_SEED_MODEL_CONFIG \
 *       --es provider commandcode \
 *       --es base_url https://api.commandcode.ai/provider \
 *       --es model_id deepseek/deepseek-v4-flash-vision-exp \
 *       --es api_key <key>
 *
 * Registered dynamically by the Application under BuildConfig.DEBUG only; never
 * exposed in a release build. The key goes through the normal
 * ModelConfigurationStore.save path into the Keystore-backed secret vault.
 */
class TestSeedModelConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val provider = intent.getStringExtra("provider")
        val baseUrl = intent.getStringExtra("base_url")
        val modelId = intent.getStringExtra("model_id")
        val apiKey = intent.getStringExtra("api_key")
        if (provider.isNullOrBlank() || baseUrl.isNullOrBlank() || modelId.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return
        }
        val app = context.applicationContext as SmartMistakeBookApplication
        val store = app.modelConfigurationStore ?: return
        val original = goAsync()
        // Save off the main thread; the store persists to DataStore + Keystore.
        Thread {
            try {
                val key = ModelApiKey.from(apiKey.toCharArray())
                key.use {
                    kotlinx.coroutines.runBlocking {
                        store.save(
                            ModelConfigurationUpdate(
                                provider = provider,
                                baseUrl = baseUrl,
                                modelId = modelId,
                            ),
                            key,
                        )
                    }
                }
            } catch (_: Throwable) {
                // Best-effort seed; the settings screen remains authoritative.
            } finally {
                original.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_TEST_SEED_MODEL_CONFIG =
            "com.tingyun.smartmistakebook.TEST_SEED_MODEL_CONFIG"
    }
}
