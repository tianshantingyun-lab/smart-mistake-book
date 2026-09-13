package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.domain.ModelAgentConsentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 可变的同意开关替身，供图片出网关与两条图片通道的测试共用。
 *
 * [granted] 可写，是为了让「先同意、后撤销」这个顺序能被测到——而它正是审计 S-2 的失败形状：
 * 用户在设置里关掉开关，配图与去手写却仍然出网。
 */
internal class FakeModelAgentConsentStore(
    granted: Boolean = true,
) : ModelAgentConsentStore {
    private val state = MutableStateFlow(granted)

    var granted: Boolean
        get() = state.value
        set(value) {
            state.value = value
        }

    override val consentEnabled: Flow<Boolean> = state

    override suspend fun current(): Boolean = state.value

    override suspend fun setConsentEnabled(enabled: Boolean) {
        state.value = enabled
    }
}
