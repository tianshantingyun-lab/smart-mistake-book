package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorStreamTest {
    @Test
    fun completedPreviewCarriesTheExactOwnerTurnAndModeIdentity() {
        val identity = TutorStreamIdentity(
            requestId = "respond-request",
            ownerVersion = 4,
            turnVersion = 7,
            modeVersion = 2,
        )
        val snapshot = TutorMarkdownSnapshot(
            stableMarkdown = "先看已知条件。\n\n",
            provisionalMarkdown = "",
        )

        val completed = TutorStreamEvent.Completed(identity, snapshot)

        assertEquals(identity, completed.identity)
        assertEquals(snapshot, completed.snapshot)
        assertEquals("先看已知条件。\n\n", completed.snapshot.visibleMarkdown)
    }

    @Test
    fun completedPreviewRejectsContentThatIsStillProvisional() {
        val result = runCatching {
            TutorStreamEvent.Completed(
                identity = TutorStreamIdentity(
                    requestId = "respond-request",
                    ownerVersion = 0,
                    turnVersion = 0,
                    modeVersion = 0,
                ),
                snapshot = TutorMarkdownSnapshot(
                    stableMarkdown = "已稳定。\n\n",
                    provisionalMarkdown = "还在生成",
                ),
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun streamIdentityRejectsNegativeOwnerTurnOrModeVersions() {
        val invalidVersions = listOf(
            Triple(-1L, 0L, 0L),
            Triple(0L, -1L, 0L),
            Triple(0L, 0L, -1L),
        )

        invalidVersions.forEach { (owner, turn, mode) ->
            assertTrue(
                runCatching {
                    TutorStreamIdentity(
                        requestId = "respond-request",
                        ownerVersion = owner,
                        turnVersion = turn,
                        modeVersion = mode,
                    )
                }.isFailure,
            )
        }
    }

    @Test
    fun gatewayPreviewCarriesOnlyTheSafeMarkdownSnapshot() {
        val snapshot = TutorMarkdownSnapshot(
            stableMarkdown = "已完成一段。\n\n",
            provisionalMarkdown = "正在继续",
        )

        val event = ModelGatewayEvent.TutorPreview(snapshot)

        assertEquals(snapshot, event.snapshot)
    }
}
