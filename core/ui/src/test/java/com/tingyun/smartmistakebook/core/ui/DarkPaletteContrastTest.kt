package com.tingyun.smartmistakebook.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkPaletteContrastTest {
    @Test
    fun darkPaletteKeepsReadableTextContrast() {
        assertContrastAtLeast(SmartDarkColors.Ink, SmartDarkColors.Paper, 7.0)
        assertContrastAtLeast(SmartDarkColors.InkSecondary, SmartDarkColors.Paper, 4.5)
        assertContrastAtLeast(SmartDarkColors.InkMuted, SmartDarkColors.Paper, 3.0)
        assertContrastAtLeast(SmartDarkColors.Ink, SmartDarkColors.JadeSoft, 4.5)
        assertContrastAtLeast(SmartDarkColors.InkSecondary, SmartDarkColors.JadeSoft, 4.5)
        assertContrastAtLeast(SmartDarkColors.OnJade, SmartDarkColors.Jade, 4.5)
        assertContrastAtLeast(SmartDarkColors.OnJade, SmartDarkColors.JadeDark, 4.5)
        assertContrastAtLeast(SmartDarkColors.Jade, SmartDarkColors.Paper, 4.5)
        assertContrastAtLeast(SmartDarkColors.ErrorWarm, SmartDarkColors.Paper, 4.5)
    }

    private fun assertContrastAtLeast(
        foreground: Color,
        background: Color,
        minimum: Double,
    ) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05) / (darker + 0.05)
        assertTrue(
            "Expected contrast >= $minimum, got $ratio for $foreground on $background",
            ratio >= minimum,
        )
    }
}
