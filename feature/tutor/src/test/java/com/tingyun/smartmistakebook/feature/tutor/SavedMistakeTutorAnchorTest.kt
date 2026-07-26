package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedMistakeTutorAnchorTest {
    @Test
    fun savedMistakeUsesItsStableSessionProblemRevisionAndPracticeUnitAnchor() {
        val anchor = savedMistakeTutorAnchor(
            sessionId = "mistake-tutor-session",
            problemRevisionId = "problem-revision-7",
            practiceUnitId = "practice-unit-3",
            anchoredAtEpochMillis = 9_000,
        )

        assertEquals("mistake-tutor-session", anchor.sessionId)
        assertEquals("problem-revision-7", anchor.problemRevisionId)
        assertEquals("practice-unit-3", anchor.practiceUnitId)
        assertEquals(9_000L, anchor.anchoredAtEpochMillis)
    }

    @Test
    fun savedMistakeSourceAssetsFeedVisualWorkAndRetainOriginalAccess() {
        val source = MistakeSourceSet.Present(
            assets = listOf(
                MistakeSourceAsset(
                    role = "question",
                    sourceAssetId = "asset-1",
                    contentSha256 = "a".repeat(64),
                    mimeType = "image/jpeg",
                    byteSize = 2_048,
                    width = 1_200,
                    height = 800,
                    sourceType = "capture",
                    createdAtEpochMillis = 10,
                    location = MistakeSourceLocation.Available("file:///private/question.jpg"),
                ),
            ),
        )

        val visualSources = source.toTutorVisualSourceAssets()

        assertEquals(1, visualSources.size)
        assertEquals("asset-1", visualSources.single().assetId)
        assertEquals("a".repeat(64), visualSources.single().sha256)
        assertEquals("file:///private/question.jpg", source.firstAvailableOriginalUri())
        assertTrue(MistakeSourceSet.Missing.toTutorVisualSourceAssets().isEmpty())
    }
}
