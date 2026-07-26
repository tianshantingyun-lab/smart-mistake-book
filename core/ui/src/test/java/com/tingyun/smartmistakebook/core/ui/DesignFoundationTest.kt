package com.tingyun.smartmistakebook.core.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DesignFoundationTest {
    @Test
    fun `spacing scale stays restrained`() {
        assertEquals(listOf(8.dp, 12.dp, 16.dp, 24.dp), SmartDimens.SpacingScale)
    }

    @Test
    fun `controls and icons use shared accessible dimensions`() {
        assertEquals(52.dp, SmartDimens.PrimaryControlHeight)
        assertEquals(48.dp, SmartDimens.MinimumTouchTarget)
        assertEquals(22.dp, SmartDimens.SmallIconSize)
        assertEquals(24.dp, SmartDimens.IconSize)
        assertEquals(52.dp, SmartDimens.ComposerHeight)
        assertEquals(64.dp, SmartDimens.BottomBarHeight)
    }

    @Test
    fun `page margins adapt at the large screen breakpoint`() {
        assertEquals(16.dp, SmartDimens.contentHorizontalPadding(599.dp))
        assertEquals(24.dp, SmartDimens.contentHorizontalPadding(600.dp))
    }
}
