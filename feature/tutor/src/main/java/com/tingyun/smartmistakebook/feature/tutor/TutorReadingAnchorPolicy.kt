package com.tingyun.smartmistakebook.feature.tutor

internal enum class TutorConversationMutation {
    ACTIVE_REPLY_GROWTH,
    VISUAL_INSERTION,
    STUDENT_SEND,
}

internal fun shouldFollowTutorConversationTail(
    wasNearBottom: Boolean,
    mutation: TutorConversationMutation,
): Boolean = when (mutation) {
    TutorConversationMutation.ACTIVE_REPLY_GROWTH -> wasNearBottom
    TutorConversationMutation.VISUAL_INSERTION -> false
    TutorConversationMutation.STUDENT_SEND -> true
}

internal fun isTutorConversationNearBottom(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    lastVisibleItemBottomPx: Int?,
    viewportEndPx: Int,
    thresholdPx: Int,
): Boolean {
    require(totalItemsCount >= 0)
    require(thresholdPx >= 0)
    if (totalItemsCount == 0) return true
    if (lastVisibleItemIndex != totalItemsCount - 1 || lastVisibleItemBottomPx == null) {
        return false
    }
    return lastVisibleItemBottomPx - viewportEndPx <= thresholdPx
}
