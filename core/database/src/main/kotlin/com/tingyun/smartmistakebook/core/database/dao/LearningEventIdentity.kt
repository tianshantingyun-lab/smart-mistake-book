package com.tingyun.smartmistakebook.core.database.dao

import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.entity.LearningEventIdentityEntity

internal suspend fun claimLearningEventIdentity(
    eventId: String,
    eventKind: String,
    insert: suspend (LearningEventIdentityEntity) -> Long,
) {
    val inserted = insert(
        LearningEventIdentityEntity(
            eventId = eventId,
            eventKind = eventKind,
        ),
    )
    if (inserted == -1L) {
        throw ImmutablePayloadConflictException("learning_event_identity", eventId)
    }
}
