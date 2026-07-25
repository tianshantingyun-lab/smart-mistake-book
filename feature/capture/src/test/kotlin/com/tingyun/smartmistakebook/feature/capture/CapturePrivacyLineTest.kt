package com.tingyun.smartmistakebook.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePrivacyLineTest {
    @Test
    fun emptyCaptureDoesNotClaimThatAnOriginalImageWasAlreadySaved() {
        assertEquals(
            "拍摄后会保存原图",
            capturePrivacyLine(
                snapshot = null,
                parseSnapshot = null,
                sourcePersisted = false,
            ),
        )
    }
}
