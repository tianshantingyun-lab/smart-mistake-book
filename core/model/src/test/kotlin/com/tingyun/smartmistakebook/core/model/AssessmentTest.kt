package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentTest {
    private val item = TutorAssessmentItem(
        id = "item-1",
        stemMarkdown = "请选择。",
        choices = listOf(
            TutorChoice("a", "选项 A"),
            TutorChoice("b", "选项 B"),
        ),
        correctChoiceId = "b",
    )

    @Test
    fun `choice evaluation uses the assessment answer key`() {
        val incorrect = item.evaluateChoice("a")
        val correct = item.evaluateChoice("b")

        assertEquals("a", incorrect.choice.id)
        assertFalse(incorrect.isCorrect)
        assertEquals("b", correct.choice.id)
        assertTrue(correct.isCorrect)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `choice evaluation rejects a choice from another assessment`() {
        item.evaluateChoice("outside")
    }
}
