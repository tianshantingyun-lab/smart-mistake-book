package com.tingyun.smartmistakebook.feature.tutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorReadingAnchorPolicyTest {
    @Test
    fun activeReplyGrowthFollowsOnlyWhenTheReaderWasNearTheBottom() {
        assertTrue(
            shouldFollowTutorConversationTail(
                wasNearBottom = true,
                mutation = TutorConversationMutation.ACTIVE_REPLY_GROWTH,
            ),
        )
        assertFalse(
            shouldFollowTutorConversationTail(
                wasNearBottom = false,
                mutation = TutorConversationMutation.ACTIVE_REPLY_GROWTH,
            ),
        )
    }

    @Test
    fun validatedVisualInsertionNeverMovesTheReadingAnchor() {
        assertFalse(
            shouldFollowTutorConversationTail(
                wasNearBottom = true,
                mutation = TutorConversationMutation.VISUAL_INSERTION,
            ),
        )
    }

    @Test
    fun aStudentSendExplicitlyMovesToTheNewTail() {
        assertTrue(
            shouldFollowTutorConversationTail(
                wasNearBottom = false,
                mutation = TutorConversationMutation.STUDENT_SEND,
            ),
        )
    }

    @Test
    fun asynchronouslyRenderedTailGrowthFollowsOnlyWhileTheReaderStillFollowsTheTail() {
        val beforeRender = TutorConversationTailLayout(
            totalItemsCount = 4,
            tailItemSizePx = 120,
        )
        val afterRender = beforeRender.copy(tailItemSizePx = 420)

        assertTrue(
            shouldFollowTutorConversationLayoutGrowth(
                followsTail = true,
                previous = beforeRender,
                current = afterRender,
            ),
        )
        assertFalse(
            shouldFollowTutorConversationLayoutGrowth(
                followsTail = false,
                previous = beforeRender,
                current = afterRender,
            ),
        )
    }

    @Test
    fun nearBottomIncludesASmallTrailingGapButRejectsTallActiveContent() {
        assertTrue(
            isTutorConversationNearBottom(
                totalItemsCount = 4,
                lastVisibleItemIndex = 3,
                lastVisibleItemBottomPx = 1_040,
                viewportEndPx = 1_000,
                thresholdPx = 48,
            ),
        )
        assertFalse(
            isTutorConversationNearBottom(
                totalItemsCount = 4,
                lastVisibleItemIndex = 3,
                lastVisibleItemBottomPx = 1_300,
                viewportEndPx = 1_000,
                thresholdPx = 48,
            ),
        )
        assertFalse(
            isTutorConversationNearBottom(
                totalItemsCount = 4,
                lastVisibleItemIndex = 2,
                lastVisibleItemBottomPx = 980,
                viewportEndPx = 1_000,
                thresholdPx = 48,
            ),
        )
    }
}
