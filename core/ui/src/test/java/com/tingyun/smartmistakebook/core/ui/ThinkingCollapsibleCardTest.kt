package com.tingyun.smartmistakebook.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCollapsibleCardTest {
    @Test
    fun hiddenWhenNoThinkingAndNotThinking() {
        assertFalse(shouldShowThinkingCard(null, thinking = false))
        assertFalse(shouldShowThinkingCard("   ", thinking = false))
    }

    @Test
    fun visibleWhenThinkingIsPresent() {
        assertTrue(shouldShowThinkingCard("先判断符号区间。", thinking = false))
    }

    @Test
    fun visibleWhileThinkingEvenWithoutContent() {
        assertTrue(shouldShowThinkingCard(null, thinking = true))
        assertTrue(shouldShowThinkingCard("", thinking = true))
    }
}
