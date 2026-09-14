package com.tingyun.smartmistakebook.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * 消息附图的披露说明状态：首次发送带图消息前，学生需要看到一次
 * “图片会随消息交给已配置模型”的说明并确认；确认后长期记住。
 */
interface LobbyImageDisclosureStore {
    val acknowledged: Flow<Boolean>

    suspend fun isAcknowledged(): Boolean

    suspend fun acknowledge()
}
