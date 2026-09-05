package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Holds the user's global "model agent" consent ("配置模型 = 全局同意"). When enabled,
 * agent-eligible rounds (capture assess/parse/classify and tutor plan/respond/visual) may
 * egress to the configured model provider without a per-item confirmation. Defaults to
 * ON so configuring a model keeps the current behavior; the user may opt out in Settings.
 */
interface ModelAgentConsentStore {
    val consentEnabled: Flow<Boolean>

    suspend fun current(): Boolean

    suspend fun setConsentEnabled(enabled: Boolean)
}
